import { DynamicModule, Module } from '@nestjs/common';
import { APP_FILTER } from '@nestjs/core';
import { MulterModule } from '@nestjs/platform-express';
import { randomUUID } from 'node:crypto';
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { LoggerModule } from 'nestjs-pino';
import multer from 'multer';
import { AllExceptionsFilter } from './common/errors';
import { APP_CONFIG, AppConfig } from './config/config';
import { DatabaseService } from './database/database.service';
import { RedisService } from './redis/redis.service';
import { AI_PROVIDER, AiProvider } from './modules/ai/ai-provider';
import { AiController } from './modules/ai/ai.controller';
import { AiService } from './modules/ai/ai.service';
import { AnthropicProvider } from './modules/ai/anthropic.provider';
import { AuthController } from './modules/auth/auth.controller';
import { ClientAuthGuard } from './modules/auth/client-auth.guard';
import { TokenService } from './modules/auth/token.service';
import { HealthController } from './modules/health/health.controller';
import { JobsController } from './modules/jobs/jobs.controller';
import { JobsRepository } from './modules/jobs/jobs.repository';
import { JobsService } from './modules/jobs/jobs.service';
import { QueueService } from './modules/jobs/queue.service';
import { RateLimitService } from './modules/limits/rate-limit.service';
import { SweeperService } from './modules/processing/sweeper.service';
import { WorkerService } from './modules/processing/worker.service';
import { LocalStorageDriver } from './modules/storage/local.driver';
import { S3StorageDriver } from './modules/storage/s3.driver';
import { STORAGE, StorageDriver } from './modules/storage/storage.driver';

@Module({})
export class AppModule {
  /** Builds the module graph for a validated config. `overrides` lets tests inject a fake AI provider. */
  static forConfig(config: AppConfig, overrides: { aiProvider?: AiProvider | null } = {}): DynamicModule {
    const uploadDir = join(config.WORK_DIR, 'uploads');
    mkdirSync(uploadDir, { recursive: true, mode: 0o700 });

    const storage: StorageDriver =
      config.STORAGE_DRIVER === 's3'
        ? new S3StorageDriver(config.S3_BUCKET as string, { region: config.S3_REGION, endpoint: config.S3_ENDPOINT, forcePathStyle: config.S3_FORCE_PATH_STYLE })
        : new LocalStorageDriver(config.STORAGE_LOCAL_DIR);
    const aiProvider = overrides.aiProvider !== undefined ? overrides.aiProvider : config.aiConfigured ? new AnthropicProvider(config) : null;

    return {
      module: AppModule,
      imports: [
        LoggerModule.forRoot({
          pinoHttp: {
            level: config.LOG_LEVEL,
            genReqId: (req, res) => {
              const id = randomUUID();
              res.setHeader('X-Request-Id', id);
              return id;
            },
            // Never log credentials, file names or document content.
            redact: { paths: ['req.headers.authorization', 'req.headers.cookie', 'res.headers["content-disposition"]'], censor: '[redacted]' },
            serializers: {
              req: (req: { id: string; method: string; url: string }) => ({ id: req.id, method: req.method, url: req.url }),
            },
            customLogLevel: (_req, res, err) => (err || res.statusCode >= 500 ? 'error' : res.statusCode >= 400 ? 'warn' : 'info'),
            autoLogging: { ignore: (req) => (req.url ?? '').startsWith('/api/v1/health') },
          },
        }),
        MulterModule.register({
          storage: multer.diskStorage({
            destination: uploadDir,
            filename: (_req, _file, cb) => cb(null, randomUUID()),
          }),
          limits: { fileSize: config.maxUploadBytes, files: config.MAX_FILES_PER_JOB, fields: 10, fieldSize: 16 * 1024, parts: config.MAX_FILES_PER_JOB + 10 },
        }),
      ],
      controllers: [AuthController, JobsController, AiController, HealthController],
      providers: [
        { provide: APP_CONFIG, useValue: config },
        { provide: STORAGE, useValue: storage },
        { provide: AI_PROVIDER, useValue: aiProvider },
        { provide: APP_FILTER, useClass: AllExceptionsFilter },
        DatabaseService,
        RedisService,
        TokenService,
        ClientAuthGuard,
        RateLimitService,
        JobsRepository,
        JobsService,
        QueueService,
        AiService,
        WorkerService,
        SweeperService,
      ],
    };
  }
}
