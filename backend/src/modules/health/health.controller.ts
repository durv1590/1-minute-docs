import { Controller, Get, HttpStatus, Inject, Res } from '@nestjs/common';
import type { Response } from 'express';
import { APP_CONFIG, AppConfig } from '../../config/config';
import { DatabaseService } from '../../database/database.service';
import { RedisService } from '../../redis/redis.service';
import { AI_PROVIDER, AiProvider } from '../ai/ai-provider';
import { OCR_LANGUAGES, OPERATIONS } from '../jobs/operations';
import { STORAGE, StorageDriver } from '../storage/storage.driver';

@Controller('api/v1')
export class HealthController {
  constructor(
    private readonly db: DatabaseService,
    private readonly redis: RedisService,
    @Inject(STORAGE) private readonly storage: StorageDriver,
    @Inject(APP_CONFIG) private readonly config: AppConfig,
    @Inject(AI_PROVIDER) private readonly ai: AiProvider | null,
  ) {}

  /** Liveness: the process is up. */
  @Get('health/live')
  live() {
    return { status: 'ok' };
  }

  /** Readiness: dependencies reachable. Returns 503 with per-check status otherwise. */
  @Get('health/ready')
  async ready(@Res({ passthrough: true }) res: Response) {
    const checks: Record<string, 'ok' | 'fail'> = {};
    const probe = async (name: string, fn: () => Promise<unknown>) => {
      try {
        await Promise.race([fn(), new Promise((_, rej) => setTimeout(() => rej(new Error('timeout')), 3000))]);
        checks[name] = 'ok';
      } catch {
        checks[name] = 'fail';
      }
    };
    await Promise.all([
      probe('database', () => this.db.query('SELECT 1')),
      probe('redis', () => this.redis.client.ping()),
      probe('storage', () => this.storage.check()),
    ]);
    const ok = Object.values(checks).every((v) => v === 'ok');
    res.status(ok ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE);
    return { status: ok ? 'ok' : 'degraded', checks };
  }

  /** Public description of what this server can do, so the app labels cloud features honestly. */
  @Get('capabilities')
  capabilities() {
    return {
      apiVersion: 1,
      operations: Object.entries(OPERATIONS).map(([name, spec]) => ({
        name,
        inputs: spec.inputs,
        maxFiles: spec.maxFiles,
        available: name !== 'handwriting_recognize' || this.ai !== null,
      })),
      ai: { configured: this.ai !== null, maxInputChars: this.config.AI_MAX_INPUT_CHARS, requestsPerDay: this.config.AI_REQUESTS_PER_CLIENT_DAY },
      limits: {
        maxUploadBytes: this.config.maxUploadBytes,
        maxFilesPerJob: this.config.MAX_FILES_PER_JOB,
        maxPages: this.config.MAX_PAGES,
        jobsPerHour: this.config.RATE_JOBS_PER_CLIENT_HOUR,
        handwritingPagesPerDay: this.config.HANDWRITING_PAGES_PER_CLIENT_DAY,
        outputRetentionMinutes: this.config.OUTPUT_TTL_MINUTES,
      },
      ocrLanguages: OCR_LANGUAGES,
      pdfToDocxEngine: this.config.PDF_TO_DOCX_ENGINE,
    };
  }
}
