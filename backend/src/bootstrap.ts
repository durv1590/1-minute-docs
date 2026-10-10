import { INestApplication } from '@nestjs/common';
import { NestFactory } from '@nestjs/core';
import { NestExpressApplication } from '@nestjs/platform-express';
import { SwaggerModule } from '@nestjs/swagger';
import helmet from 'helmet';
import { Logger } from 'nestjs-pino';
import { AppModule } from './app.module';
import { AppConfig } from './config/config';
import { DatabaseService } from './database/database.service';
import { migrate } from './database/migrator';
import { AiProvider } from './modules/ai/ai-provider';
import { openApiDocument } from './openapi';

export async function createApp(config: AppConfig, overrides: { aiProvider?: AiProvider | null } = {}): Promise<INestApplication> {
  const app = await NestFactory.create<NestExpressApplication>(AppModule.forConfig(config, overrides), {
    bufferLogs: true,
    bodyParser: false,
  });
  app.useLogger(app.get(Logger));
  app.useBodyParser('json', { limit: '256kb' });
  if (config.TRUST_PROXY) app.set('trust proxy', 1);
  app.disable('x-powered-by');
  app.use(helmet());
  // Mobile clients do not need CORS; browser origins must be allow-listed explicitly.
  app.enableCors({ origin: config.corsOrigins.length ? config.corsOrigins : false });
  SwaggerModule.setup('api/docs', app, openApiDocument() as never);
  app.enableShutdownHooks();

  if (config.ROLE !== 'api' || config.NODE_ENV !== 'production') {
    // Development and worker processes apply pending migrations; production API replicas rely on the migrate step.
    await migrate(app.get(DatabaseService).pool);
  }
  return app;
}
