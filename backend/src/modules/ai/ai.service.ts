import { HttpStatus, Inject, Injectable, Logger } from '@nestjs/common';
import { randomUUID } from 'node:crypto';
import { APP_CONFIG, AppConfig } from '../../config/config';
import { AppError } from '../../common/errors';
import { DatabaseService } from '../../database/database.service';
import { RateLimitService } from '../limits/rate-limit.service';
import { AI_PROVIDER, AiProvider, AiProviderError } from './ai-provider';
import { aiRequestSchema, buildPrompt, inputSize } from './prompts';

export interface AiResult {
  task: string;
  language: string;
  markdown: string;
  aiGenerated: true;
  provider: string;
  model: string;
  warnings: string[];
  usage: { inputTokens: number | null; outputTokens: number | null };
}

@Injectable()
export class AiService {
  private readonly logger = new Logger(AiService.name);

  constructor(
    @Inject(AI_PROVIDER) private readonly provider: AiProvider | null,
    @Inject(APP_CONFIG) private readonly config: AppConfig,
    private readonly limits: RateLimitService,
    private readonly db: DatabaseService,
  ) {}

  async run(clientId: string, body: unknown): Promise<AiResult> {
    if (!this.provider) {
      throw new AppError('AI_NOT_CONFIGURED', HttpStatus.SERVICE_UNAVAILABLE, 'AI features are not configured on this server');
    }
    const parsed = aiRequestSchema.safeParse(body);
    if (!parsed.success) {
      throw AppError.badRequest('Invalid AI request', { issues: parsed.error.issues.map((i) => `${i.path.join('.')}: ${i.message}`) });
    }
    const req = parsed.data;
    const size = inputSize(req);
    if (size > this.config.AI_MAX_INPUT_CHARS) {
      throw new AppError('PAYLOAD_TOO_LARGE', HttpStatus.PAYLOAD_TOO_LARGE, `Input is too long (${size} characters; limit ${this.config.AI_MAX_INPUT_CHARS})`, {
        limit: this.config.AI_MAX_INPUT_CHARS,
      });
    }
    await this.limits.consume(`ai:${clientId}`, this.config.AI_REQUESTS_PER_CLIENT_DAY, 86400, 1, 'QUOTA_EXCEEDED');

    const { system, user } = buildPrompt(req);
    const started = Date.now();
    const id = randomUUID();
    try {
      const result = await this.provider.complete({ system, user, maxOutputTokens: this.config.AI_MAX_OUTPUT_TOKENS });
      if (result.refused || !result.text) {
        await this.record(id, clientId, req.task, size, 'REFUSED', started, result.model, result.text.length, result.inputTokens, result.outputTokens, 'AI_REFUSED');
        throw new AppError('AI_REFUSED', HttpStatus.UNPROCESSABLE_ENTITY, 'The AI declined this request. Try rephrasing it.');
      }
      await this.record(id, clientId, req.task, size, 'SUCCEEDED', started, result.model, result.text.length, result.inputTokens, result.outputTokens, null);
      return {
        task: req.task,
        language: req.task === 'translate' ? req.targetLanguage ?? req.language : req.language,
        markdown: result.text,
        aiGenerated: true,
        provider: this.provider.name,
        model: result.model,
        warnings: result.truncated ? ['TRUNCATED'] : [],
        usage: { inputTokens: result.inputTokens, outputTokens: result.outputTokens },
      };
    } catch (e) {
      if (e instanceof AppError) throw e;
      // Provider-side failures are not the user's fault: give the quota back.
      await this.limits.refund(`ai:${clientId}`, 86400).catch(() => undefined);
      const err = e instanceof AiProviderError ? e : new AiProviderError('UNAVAILABLE', 'AI provider failed');
      await this.record(id, clientId, req.task, size, 'FAILED', started, this.provider.model, null, null, null, err.kind);
      this.logger.warn({ kind: err.kind, msg: err.message }, 'AI request failed');
      throw toHttp(err);
    }
  }

  private async record(
    id: string, clientId: string, task: string, inputChars: number, status: string, started: number,
    model: string, outputChars: number | null, inTok: number | null, outTok: number | null, errorCode: string | null,
  ): Promise<void> {
    await this.db.query(
      `INSERT INTO ai_requests (id, client_id, task, provider, model, input_chars, output_chars, input_tokens, output_tokens, status, error_code, latency_ms)
       VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12)`,
      [id, clientId, task, this.provider?.name ?? 'none', model, inputChars, outputChars, inTok, outTok, status, errorCode, Date.now() - started],
    ).catch((e: Error) => this.logger.error({ err: e.message }, 'failed to record AI usage'));
  }
}

export function toHttp(e: AiProviderError): AppError {
  switch (e.kind) {
    case 'TIMEOUT':
      return new AppError('TIMEOUT', HttpStatus.GATEWAY_TIMEOUT, 'The AI service took too long to respond');
    case 'RATE_LIMITED':
      return new AppError('SERVICE_UNAVAILABLE', HttpStatus.SERVICE_UNAVAILABLE, 'The AI service is busy. Please try again shortly.');
    case 'AUTH':
    case 'NOT_CONFIGURED':
      return new AppError('AI_NOT_CONFIGURED', HttpStatus.SERVICE_UNAVAILABLE, 'AI features are not configured correctly on this server');
    default:
      return new AppError('AI_PROVIDER_ERROR', HttpStatus.BAD_GATEWAY, 'The AI service failed to process the request');
  }
}
