import { Inject, Injectable, Logger, OnModuleDestroy, OnModuleInit } from '@nestjs/common';
import { Job, UnrecoverableError, Worker } from 'bullmq';
import { randomUUID } from 'node:crypto';
import { mkdir, rm, stat } from 'node:fs/promises';
import { join } from 'node:path';
import { APP_CONFIG, AppConfig } from '../../config/config';
import { RedisService } from '../../redis/redis.service';
import { AI_PROVIDER, AiProvider } from '../ai/ai-provider';
import { sha256File } from '../jobs/jobs.service';
import { JobsRepository } from '../jobs/jobs.repository';
import { isOperation, OUTPUT_FORMATS } from '../jobs/operations';
import { QUEUE_NAME, QueuePayload } from '../jobs/queue.service';
import { STORAGE, StorageDriver } from '../storage/storage.driver';
import { EngineContext } from './engines/engines';
import { ProcessingError } from './engines/exec';
import { Processor, processors, ProcessorInput, validateOutput } from './processors';

const USER_MESSAGES: Record<string, string> = {
  TIMEOUT: 'Processing took too long and was stopped. Try a smaller document.',
  CANCELLED: 'Cancelled',
  INTERNAL: 'Processing failed due to a server error. Your original file is safe on your device.',
};

@Injectable()
export class WorkerService implements OnModuleInit, OnModuleDestroy {
  private readonly logger = new Logger(WorkerService.name);
  private worker?: Worker<QueuePayload>;
  private readonly processors: Record<string, Processor>;

  constructor(
    @Inject(APP_CONFIG) private readonly config: AppConfig,
    private readonly redis: RedisService,
    private readonly repo: JobsRepository,
    @Inject(STORAGE) private readonly storage: StorageDriver,
    @Inject(AI_PROVIDER) ai: AiProvider | null,
  ) {
    this.processors = processors(ai);
  }

  onModuleInit(): void {
    if (this.config.ROLE === 'api') return;
    this.worker = new Worker<QueuePayload>(QUEUE_NAME, (job) => this.handle(job), {
      connection: this.redis.bullConnection(),
      prefix: this.config.QUEUE_PREFIX,
      concurrency: this.config.WORKER_CONCURRENCY,
      lockDuration: Math.max(60_000, this.config.JOB_TIMEOUT_SECONDS * 1000),
      maxStalledCount: 1,
    });
    this.worker.on('error', (e) => this.logger.error({ err: e.message }, 'worker error'));
    this.logger.log({ concurrency: this.config.WORKER_CONCURRENCY }, 'document worker started');
  }

  async onModuleDestroy(): Promise<void> {
    await this.worker?.close();
  }

  /** Processes one queued job end to end. Exposed for tests. */
  async handle(bullJob: Job<QueuePayload>): Promise<void> {
    const jobId = bullJob.data.jobId;
    const row = await this.repo.findById(jobId);
    if (!row || row.deleted_at || ['COMPLETED', 'FAILED', 'CANCELLED', 'EXPIRED'].includes(row.status)) return;
    if (!isOperation(row.operation)) {
      await this.repo.fail(jobId, 'INTERNAL', 'Unknown operation');
      throw new UnrecoverableError('unknown operation');
    }
    if (row.cancel_requested) {
      await this.repo.fail(jobId, 'CANCELLED', 'Cancelled', 'CANCELLED');
      return;
    }
    const started = await this.repo.transition(jobId, ['QUEUED', 'VALIDATING', 'PROCESSING', 'FINALIZING'], 'VALIDATING', { progress: null, startedAt: true });
    if (!started) return;

    const workDir = join(this.config.WORK_DIR, 'jobs', `${jobId}-${randomUUID().slice(0, 8)}`);
    const controller = new AbortController();
    const deadline = Date.now() + this.config.JOB_TIMEOUT_SECONDS * 1000;
    const timeout = setTimeout(() => controller.abort(new ProcessingError('TIMEOUT', 'timeout')), this.config.JOB_TIMEOUT_SECONDS * 1000);
    const watcher = setInterval(() => {
      this.repo.isCancelRequested(jobId).then((c) => c && controller.abort(new ProcessingError('CANCELLED', 'cancelled')), () => undefined);
    }, 1000);

    const ctx: EngineContext = {
      config: this.config,
      workDir,
      signal: controller.signal,
      timeLeft: () => Math.max(1000, deadline - Date.now()),
      progress: (p) => this.repo.setProgress(jobId, p),
    };
    const processor = this.processors[row.operation];
    try {
      await mkdir(workDir, { recursive: true, mode: 0o700 });
      const files = (await this.repo.files(jobId)).filter((f) => f.role === 'input' && !f.deleted_at);
      const inputs: ProcessorInput[] = [];
      for (const f of files) {
        const ext = f.content_type.includes('pdf') ? 'pdf' : f.content_type.split('/').pop() ?? 'bin';
        const local = join(workDir, `in-${f.position}.${ext}`);
        await this.storage.download(f.storage_key, local);
        if ((await sha256File(local)) !== f.sha256) throw new ProcessingError('INTERNAL', 'Stored input failed integrity check', true);
        inputs.push({ path: local, displayName: f.display_name, format: formatOf(f.content_type) });
      }
      await processor.validate(ctx, inputs);
      throwIfAborted(controller.signal);
      await this.repo.transition(jobId, ['VALIDATING'], 'PROCESSING', { progress: 0 });

      const result = await processor.run(ctx, inputs, row.options);
      throwIfAborted(controller.signal);
      await this.repo.transition(jobId, ['PROCESSING'], 'FINALIZING');

      const { ext, contentType } = OUTPUT_FORMATS[row.operation](row.options);
      await validateOutput(ctx, ext, result.outputPath);
      const outputKey = `jobs/${jobId}/output`;
      const size = (await stat(result.outputPath)).size;
      await this.storage.put(outputKey, result.outputPath, contentType);
      const completed = await this.repo.complete(
        jobId,
        result.meta,
        {
          id: randomUUID(),
          role: 'output',
          position: 0,
          storageKey: outputKey,
          displayName: result.outputName,
          contentType,
          sizeBytes: size,
          sha256: await sha256File(result.outputPath),
        },
        new Date(Date.now() + this.config.OUTPUT_TTL_MINUTES * 60_000),
      );
      if (!completed) {
        // Cancelled or deleted while finalising: do not leave the output behind.
        await this.storage.delete(outputKey);
        await this.repo.fail(jobId, 'CANCELLED', 'Cancelled', 'CANCELLED');
      }
      await this.deleteInputs(jobId);
      this.logger.log({ jobId, operation: row.operation, ms: Date.now() - (deadline - this.config.JOB_TIMEOUT_SECONDS * 1000) }, 'job completed');
    } catch (raw) {
      const reason = controller.signal.aborted && controller.signal.reason instanceof ProcessingError ? controller.signal.reason : raw;
      const err = reason instanceof ProcessingError ? reason : new ProcessingError('INTERNAL', (reason as Error)?.message ?? 'error', true);
      const willRetry = err.retryable && bullJob.attemptsMade + 1 < (bullJob.opts.attempts ?? 1) && err.code !== 'CANCELLED';
      if (willRetry) {
        await this.repo.transition(jobId, ['VALIDATING', 'PROCESSING', 'FINALIZING'], 'QUEUED', { progress: null });
        this.logger.warn({ jobId, code: err.code }, 'job failed, will retry');
        throw err;
      }
      const status = err.code === 'CANCELLED' ? 'CANCELLED' : 'FAILED';
      await this.repo.fail(jobId, err.code, USER_MESSAGES[err.code] ?? err.message, status);
      await this.deleteInputs(jobId);
      if (status === 'FAILED') this.logger.warn({ jobId, operation: row.operation, code: err.code, detail: err.code === 'INTERNAL' ? err.message : undefined }, 'job failed');
      throw new UnrecoverableError(err.code);
    } finally {
      clearTimeout(timeout);
      clearInterval(watcher);
      await rm(workDir, { recursive: true, force: true }).catch(() => undefined);
    }
  }

  private async deleteInputs(jobId: string): Promise<void> {
    for (const f of await this.repo.files(jobId)) {
      if (f.role !== 'input' || f.deleted_at) continue;
      await this.storage.delete(f.storage_key).catch(() => undefined);
      await this.repo.markFileDeleted(f.id);
    }
  }
}

function throwIfAborted(signal: AbortSignal): void {
  if (signal.aborted) throw signal.reason instanceof ProcessingError ? signal.reason : new ProcessingError('CANCELLED', 'Cancelled');
}

function formatOf(contentType: string): string {
  if (contentType === 'application/pdf') return 'pdf';
  if (contentType === 'image/jpeg') return 'jpeg';
  if (contentType === 'image/png') return 'png';
  if (contentType === 'image/webp') return 'webp';
  if (contentType.includes('wordprocessingml')) return 'docx';
  if (contentType.includes('opendocument.text')) return 'odt';
  if (contentType === 'application/msword') return 'doc';
  if (contentType === 'application/rtf') return 'rtf';
  return 'bin';
}
