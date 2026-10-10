import {
  ArgumentsHost,
  Catch,
  ExceptionFilter,
  HttpException,
  HttpStatus,
  Logger,
} from '@nestjs/common';
import type { Request, Response } from 'express';

/** Stable machine-readable codes shared with the Android client (see docs/API_SPECIFICATION.md). */
export type ErrorCode =
  | 'BAD_REQUEST'
  | 'UNAUTHORIZED'
  | 'NOT_FOUND'
  | 'CONFLICT'
  | 'PAYLOAD_TOO_LARGE'
  | 'UNSUPPORTED_FORMAT'
  | 'TOO_MANY_PAGES'
  | 'ENCRYPTED_DOCUMENT'
  | 'CORRUPT_FILE'
  | 'RATE_LIMITED'
  | 'QUOTA_EXCEEDED'
  | 'AI_NOT_CONFIGURED'
  | 'AI_PROVIDER_ERROR'
  | 'AI_REFUSED'
  | 'CONSENT_REQUIRED'
  | 'JOB_NOT_READY'
  | 'JOB_EXPIRED'
  | 'CONVERSION_FAILED'
  | 'TIMEOUT'
  | 'SERVICE_UNAVAILABLE'
  | 'INTERNAL';

export class AppError extends Error {
  constructor(
    readonly code: ErrorCode,
    readonly status: number,
    message: string,
    readonly details?: Record<string, unknown>,
  ) {
    super(message);
  }

  static badRequest(message: string, details?: Record<string, unknown>) {
    return new AppError('BAD_REQUEST', HttpStatus.BAD_REQUEST, message, details);
  }
  static notFound(message = 'Not found') {
    return new AppError('NOT_FOUND', HttpStatus.NOT_FOUND, message);
  }
  static unauthorized(message = 'Missing or invalid client token') {
    return new AppError('UNAUTHORIZED', HttpStatus.UNAUTHORIZED, message);
  }
}

/** Formats every error as {error:{code,message,requestId}}; stack traces go to logs only. */
@Catch()
export class AllExceptionsFilter implements ExceptionFilter {
  private readonly logger = new Logger('Errors');

  catch(exception: unknown, host: ArgumentsHost): void {
    const ctx = host.switchToHttp();
    const res = ctx.getResponse<Response>();
    const req = ctx.getRequest<Request & { id?: string }>();
    const requestId = String(req.id ?? '');

    let status = HttpStatus.INTERNAL_SERVER_ERROR;
    let code: ErrorCode = 'INTERNAL';
    let message = 'Internal server error';
    let details: Record<string, unknown> | undefined;

    if (exception instanceof AppError) {
      status = exception.status;
      code = exception.code;
      message = exception.message;
      details = exception.details;
    } else if (exception instanceof HttpException) {
      status = exception.getStatus();
      const multerCode = (exception as { code?: string }).code;
      code = status === 413 || multerCode === 'LIMIT_FILE_SIZE' ? 'PAYLOAD_TOO_LARGE'
        : status === 404 ? 'NOT_FOUND'
        : status === 401 ? 'UNAUTHORIZED'
        : status === 429 ? 'RATE_LIMITED'
        : status < 500 ? 'BAD_REQUEST' : 'INTERNAL';
      const body = exception.getResponse();
      message = typeof body === 'string' ? body : String((body as { message?: unknown }).message ?? exception.message);
    } else if (isMulterError(exception)) {
      status = exception.code === 'LIMIT_FILE_SIZE' ? 413 : 400;
      code = status === 413 ? 'PAYLOAD_TOO_LARGE' : 'BAD_REQUEST';
      message = exception.code === 'LIMIT_FILE_SIZE' ? 'File exceeds the maximum upload size' : 'Invalid upload';
    }

    if (status >= 500) {
      this.logger.error({ err: exception, requestId }, 'request failed');
    }
    if (!res.headersSent) {
      res.status(status).json({ error: { code, message, requestId, ...(details ? { details } : {}) } });
    } else {
      res.end();
    }
  }
}

function isMulterError(e: unknown): e is { code: string; name: string } {
  return typeof e === 'object' && e !== null && (e as { name?: string }).name === 'MulterError';
}
