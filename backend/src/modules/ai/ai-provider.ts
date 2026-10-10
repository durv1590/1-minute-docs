/** Provider-independent contract. Prompts are built by the caller; providers only transport them. */
export interface AiCompletion {
  text: string;
  /** True when the provider declined for policy reasons (no usable text). */
  refused: boolean;
  /** True when output hit the token cap and may be incomplete. */
  truncated: boolean;
  model: string;
  inputTokens: number | null;
  outputTokens: number | null;
}

export interface AiTextRequest {
  system: string;
  user: string;
  maxOutputTokens: number;
  signal?: AbortSignal;
}

export interface AiImageRequest {
  system: string;
  instructions: string;
  imageBase64: string;
  mediaType: 'image/jpeg' | 'image/png' | 'image/webp';
  maxOutputTokens: number;
  signal?: AbortSignal;
}

export interface AiProvider {
  readonly name: string;
  readonly model: string;
  complete(req: AiTextRequest): Promise<AiCompletion>;
  transcribeImage(req: AiImageRequest): Promise<AiCompletion>;
}

export const AI_PROVIDER = Symbol('AI_PROVIDER');

/** Normalised provider failure, mapped to HTTP errors by the AI service and to job errors by the worker. */
export class AiProviderError extends Error {
  constructor(
    readonly kind: 'NOT_CONFIGURED' | 'AUTH' | 'RATE_LIMITED' | 'TIMEOUT' | 'UNAVAILABLE' | 'BAD_REQUEST' | 'CANCELLED',
    message: string,
  ) {
    super(message);
  }
}
