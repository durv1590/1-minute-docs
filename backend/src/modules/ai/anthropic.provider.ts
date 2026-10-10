import Anthropic from '@anthropic-ai/sdk';
import type { AppConfig } from '../../config/config';
import { AiCompletion, AiImageRequest, AiProvider, AiProviderError, AiTextRequest } from './ai-provider';

/**
 * Claude via the official Anthropic SDK. Credentials come from ANTHROPIC_API_KEY (or ANTHROPIC_AUTH_TOKEN) in the server
 * environment; nothing is ever sent to the Android app.
 */
export class AnthropicProvider implements AiProvider {
  readonly name = 'anthropic';
  readonly model: string;
  private readonly client: Anthropic;

  constructor(private readonly config: AppConfig) {
    this.model = config.AI_MODEL;
    this.client = new Anthropic({
      // The SDK reads ANTHROPIC_API_KEY / ANTHROPIC_AUTH_TOKEN / ANTHROPIC_BASE_URL from the environment.
      timeout: config.AI_TIMEOUT_SECONDS * 1000,
      maxRetries: 1,
    });
  }

  complete(req: AiTextRequest): Promise<AiCompletion> {
    return this.send([{ role: 'user', content: req.user }], req.system, req.maxOutputTokens, req.signal);
  }

  transcribeImage(req: AiImageRequest): Promise<AiCompletion> {
    return this.send(
      [
        {
          role: 'user',
          content: [
            { type: 'image', source: { type: 'base64', media_type: req.mediaType, data: req.imageBase64 } },
            { type: 'text', text: req.instructions },
          ],
        },
      ],
      req.system,
      req.maxOutputTokens,
      req.signal,
    );
  }

  private async send(messages: Anthropic.Beta.BetaMessageParam[], system: string, maxTokens: number, signal?: AbortSignal): Promise<AiCompletion> {
    try {
      const response = await this.client.beta.messages.create(
        {
          model: this.model,
          max_tokens: maxTokens,
          system,
          messages,
          output_config: { effort: this.config.AI_EFFORT },
          // Server-side refusal fallback: on a policy decline the API retries on a suitable model within the same call.
          ...(this.config.AI_REFUSAL_FALLBACK ? { betas: ['server-side-fallback-2026-07-01'], fallbacks: 'default' as const } : {}),
        },
        { signal },
      );
      const text = response.content
        .filter((b): b is Anthropic.Beta.BetaTextBlock => b.type === 'text')
        .map((b) => b.text)
        .join('')
        .trim();
      return {
        text,
        refused: response.stop_reason === 'refusal',
        truncated: response.stop_reason === 'max_tokens',
        model: response.model,
        inputTokens: response.usage?.input_tokens ?? null,
        outputTokens: response.usage?.output_tokens ?? null,
      };
    } catch (e) {
      throw mapError(e);
    }
  }
}

/** Most-specific-first mapping of SDK errors to provider-neutral kinds (APIConnectionError subclasses APIError in TS). */
function mapError(e: unknown): AiProviderError {
  if (e instanceof Anthropic.APIUserAbortError) return new AiProviderError('CANCELLED', 'Request cancelled');
  if (e instanceof Anthropic.AuthenticationError || e instanceof Anthropic.PermissionDeniedError) {
    return new AiProviderError('AUTH', 'AI provider rejected the server credentials');
  }
  if (e instanceof Anthropic.RateLimitError) return new AiProviderError('RATE_LIMITED', 'AI provider is rate limiting requests');
  if (e instanceof Anthropic.BadRequestError || e instanceof Anthropic.NotFoundError || e instanceof Anthropic.UnprocessableEntityError) {
    return new AiProviderError('BAD_REQUEST', `AI provider rejected the request (${(e as InstanceType<typeof Anthropic.APIError>).status})`);
  }
  if (e instanceof Anthropic.APIConnectionTimeoutError) return new AiProviderError('TIMEOUT', 'AI provider timed out');
  if (e instanceof Anthropic.APIConnectionError) return new AiProviderError('UNAVAILABLE', 'AI provider unreachable');
  if (e instanceof Anthropic.APIError) return new AiProviderError('UNAVAILABLE', `AI provider error (${e.status ?? 'unknown'})`);
  return new AiProviderError('UNAVAILABLE', 'AI provider failed');
}
