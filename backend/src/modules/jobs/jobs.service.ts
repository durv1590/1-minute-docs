import { HttpStatus, Inject, Injectable, Logger } from '@nestjs/common';
import { createHash, randomUUID } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { rm } from 'node:fs/promises';
import { Readable } from 'node:stream';
import { APP_CONFIG, AppConfig } from '../../config/config';
import { AppError } from '../../common/errors';
import { safeFileName } from '../../common/filenames';
import { AI_PROVIDER, AiProvider } from '../ai/ai-provider';
import { CONTENT_TYPES, detectFormat } from '../files/file-inspector';
import { RateLimitService } from '../limits/rate-limit.service';
import { STORAGE, StorageDriver } from '../storage/storage.driver';
import { ACTIVE, JobView, toView } from './job.types';
import { JobsRepository, NewFile } from './jobs.repository';
import { isOperation, Operation, OPERATIONS } from './operations';
import { QueueService } from './queue.service';

export interface UploadedFile {
  path: string;
  originalname: string;
  size: number;
}

@Injectable()
export class JobsService {
  private readonly logger = new Logger(JobsService.name);

  constructor(
    private readonly repo: JobsRepository,
    private readonly queue: QueueService,
    private readonly limits: RateLimitService,
    @Inject(STORAGE) private readonly storage: StorageDriver,
    @Inject(APP_CONFIG) private readonly config: AppConfig,
    @Inject(AI_PROVIDER) private readonly ai: AiProvider | null,
  ) {}

  async create(clientId: string, operationRaw: string | undefined, optionsRaw: string | undefined, files: UploadedFile[], idempotencyKey?: string): Promise<{ job: JobView; created: boolean }> {
    try {
      if (idempotencyKey !== undefined && !/^[A-Za-z0-9_-]{8,64}$/.test(idempotencyKey)) {
        throw AppError.badRequest('Idempotency-Key must be 8-64 characters [A-Za-z0-9_-]');
      }
      if (idempotencyKey) {
        const existing = await this.repo.findByIdempotencyKey(clientId, idempotencyKey);
        if (existing) return { job: toView(existing, await this.repo.files(existing.id)), created: false };
      }
      if (!operationRaw || !isOperation(operationRaw)) {
        throw AppError.badRequest('Unknown operation', { allowed: Object.keys(OPERATIONS) });
      }
      const operation: Operation = operationRaw;
      const spec = OPERATIONS[operation];
      const options = this.parseOptions(operation, optionsRaw);

      if (files.length < spec.minFiles || files.length > spec.maxFiles) {
        throw AppError.badRequest(`Operation ${operation} needs ${spec.minFiles}-${spec.maxFiles} file(s)`);
      }
      const perFileLimit = 'maxFileBytes' in spec ? spec.maxFileBytes : undefined;
      if (perFileLimit && files.some((f) => f.size > perFileLimit)) {
        throw new AppError('PAYLOAD_TOO_LARGE', HttpStatus.PAYLOAD_TOO_LARGE, `Each file must be at most ${Math.round(perFileLimit / 1048576)} MB for ${operation}`);
      }
      if (operation === 'handwriting_recognize' && !this.ai) {
        throw new AppError('AI_NOT_CONFIGURED', HttpStatus.SERVICE_UNAVAILABLE, 'Handwriting recognition is not configured on this server');
      }

      const detected = await Promise.all(files.map((f) => detectFormat(f.path)));
      detected.forEach((fmt, i) => {
        if (fmt === 'unknown' || !spec.inputs.includes(fmt)) {
          throw new AppError('UNSUPPORTED_FORMAT', HttpStatus.UNSUPPORTED_MEDIA_TYPE, `File ${i + 1} is not a supported input for ${operation}`, {
            expected: spec.inputs,
          });
        }
      });

      await this.limits.consume(`jobs:${clientId}`, this.config.RATE_JOBS_PER_CLIENT_HOUR, 3600);
      if (operation === 'handwriting_recognize') {
        await this.limits.consume(`hw:${clientId}`, this.config.HANDWRITING_PAGES_PER_CLIENT_DAY, 86400, files.length, 'QUOTA_EXCEEDED');
      }

      const jobId = randomUUID();
      const newFiles: NewFile[] = [];
      for (const [i, f] of files.entries()) {
        const fmt = detected[i] as keyof typeof CONTENT_TYPES;
        const storageKey = `jobs/${jobId}/input-${i}`;
        const sha256 = await sha256File(f.path);
        await this.storage.put(storageKey, f.path, CONTENT_TYPES[fmt]);
        newFiles.push({
          id: randomUUID(),
          role: 'input',
          position: i,
          storageKey,
          displayName: safeFileName(Buffer.from(f.originalname, 'latin1').toString('utf8'), `input-${i + 1}.${fmt}`),
          contentType: CONTENT_TYPES[fmt],
          sizeBytes: f.size,
          sha256,
        });
      }
      // Inputs are deleted when processing ends; this horizon only bounds abandoned/stuck jobs.
      const expiresAt = new Date(Date.now() + (this.config.JOB_TIMEOUT_SECONDS * 4 + this.config.OUTPUT_TTL_MINUTES * 60) * 1000);
      const row = await this.repo.create(
        { id: jobId, clientId, operation, options, inputMeta: { formats: detected, count: files.length }, idempotencyKey: idempotencyKey ?? null, expiresAt },
        newFiles,
      );
      await this.queue.enqueue(jobId);
      this.logger.log({ jobId, operation, files: files.length }, 'job queued');
      return { job: toView(row, await this.repo.files(jobId)), created: true };
    } finally {
      await Promise.all(files.map((f) => rm(f.path, { force: true })));
    }
  }

  private parseOptions(operation: Operation, raw: string | undefined): Record<string, unknown> {
    let json: unknown = {};
    if (raw !== undefined && raw !== '') {
      try {
        json = JSON.parse(raw);
      } catch {
        throw AppError.badRequest('options must be a JSON object');
      }
    }
    const parsed = OPERATIONS[operation].options.safeParse(json);
    if (!parsed.success) {
      if (operation === 'handwriting_recognize' && parsed.error.issues.some((i) => i.path[0] === 'consent')) {
        throw new AppError('CONSENT_REQUIRED', HttpStatus.BAD_REQUEST, 'Explicit consent is required before notes are sent for cloud recognition');
      }
      throw AppError.badRequest('Invalid options', { issues: parsed.error.issues.map((i) => `${i.path.join('.')}: ${i.message}`) });
    }
    return parsed.data as Record<string, unknown>;
  }

  async get(clientId: string, id: string): Promise<JobView> {
    const job = await this.repo.findOwned(assertUuid(id), clientId);
    if (!job) throw AppError.notFound('Job not found');
    return toView(job, await this.repo.files(job.id));
  }

  async list(clientId: string): Promise<JobView[]> {
    const jobs = await this.repo.listOwned(clientId, 50);
    const files = await this.repo.filesForJobs(jobs.map((j) => j.id));
    return jobs.map((j) => toView(j, files.filter((f) => f.job_id === j.id)));
  }

  async cancel(clientId: string, id: string): Promise<JobView> {
    const job = await this.repo.findOwned(assertUuid(id), clientId);
    if (!job) throw AppError.notFound('Job not found');
    if (ACTIVE.includes(job.status)) {
      await this.repo.requestCancel(job.id);
      if (job.status === 'QUEUED') {
        await this.queue.removeIfWaiting(job.id);
        await this.repo.fail(job.id, 'CANCELLED', 'Cancelled by user', 'CANCELLED');
      }
      // Running jobs are stopped by the worker's cancellation watcher within about a second.
      await this.repo.audit('job.cancel_requested', job.id, clientId);
    }
    return this.get(clientId, id);
  }

  async openDownload(clientId: string, id: string): Promise<{ stream: Readable; fileName: string; contentType: string; size: number }> {
    const job = await this.repo.findOwned(assertUuid(id), clientId);
    if (!job) throw AppError.notFound('Job not found');
    if (job.status === 'EXPIRED' || (job.status === 'COMPLETED' && job.expires_at.getTime() <= Date.now())) {
      throw new AppError('JOB_EXPIRED', HttpStatus.GONE, 'The output has expired and was deleted');
    }
    if (job.status !== 'COMPLETED') throw new AppError('JOB_NOT_READY', HttpStatus.CONFLICT, `Job is ${job.status}`);
    const output = (await this.repo.files(job.id)).find((f) => f.role === 'output' && !f.deleted_at);
    if (!output) throw new AppError('JOB_EXPIRED', HttpStatus.GONE, 'The output is no longer available');
    return { stream: await this.storage.stream(output.storage_key), fileName: output.display_name, contentType: output.content_type, size: Number(output.size_bytes) };
  }

  /** Deletes all stored files for the job immediately and hides it. Running jobs are cancelled first. */
  async delete(clientId: string, id: string): Promise<void> {
    const job = await this.repo.findOwned(assertUuid(id), clientId);
    if (!job) throw AppError.notFound('Job not found');
    if (ACTIVE.includes(job.status)) {
      await this.repo.requestCancel(job.id);
      await this.queue.removeIfWaiting(job.id);
      await this.repo.fail(job.id, 'CANCELLED', 'Deleted by user', 'CANCELLED');
    }
    await this.deleteFiles(job.id);
    await this.repo.markDeleted(job.id);
    await this.repo.audit('job.deleted', job.id, clientId);
  }

  async deleteFiles(jobId: string, role?: 'input' | 'output'): Promise<void> {
    for (const f of await this.repo.files(jobId)) {
      if (f.deleted_at || (role && f.role !== role)) continue;
      await this.storage.delete(f.storage_key);
      await this.repo.markFileDeleted(f.id);
    }
  }
}

function assertUuid(id: string): string {
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(id)) throw AppError.notFound('Job not found');
  return id;
}

export function sha256File(path: string): Promise<string> {
  return new Promise((resolve, reject) => {
    const h = createHash('sha256');
    createReadStream(path)
      .on('data', (c) => h.update(c))
      .on('end', () => resolve(h.digest('hex')))
      .on('error', reject);
  });
}
