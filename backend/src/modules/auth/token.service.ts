import { Inject, Injectable } from '@nestjs/common';
import { createHmac, randomUUID, timingSafeEqual } from 'node:crypto';
import { APP_CONFIG, AppConfig } from '../../config/config';

export interface ClientTokenPayload {
  /** Anonymous client id (one per app install). */
  sub: string;
  iat: number;
  exp: number;
  v: 1;
}

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

/**
 * Compact HMAC-SHA256 signed tokens: base64url(payload).base64url(signature).
 * They identify an anonymous install so its jobs are isolated from every other install; they carry no personal data.
 */
@Injectable()
export class TokenService {
  constructor(@Inject(APP_CONFIG) private readonly config: AppConfig) {}

  issue(now = Date.now()): { token: string; clientId: string; expiresAt: string } {
    const iat = Math.floor(now / 1000);
    const payload: ClientTokenPayload = { sub: randomUUID(), iat, exp: iat + this.config.TOKEN_TTL_DAYS * 86400, v: 1 };
    const body = Buffer.from(JSON.stringify(payload)).toString('base64url');
    return { token: `${body}.${this.sign(body)}`, clientId: payload.sub, expiresAt: new Date(payload.exp * 1000).toISOString() };
  }

  verify(token: string, now = Date.now()): ClientTokenPayload | null {
    if (token.length > 512) return null;
    const parts = token.split('.');
    if (parts.length !== 2) return null;
    const [body, sig] = parts;
    const expected = Buffer.from(this.sign(body));
    const actual = Buffer.from(sig);
    if (expected.length !== actual.length || !timingSafeEqual(expected, actual)) return null;
    try {
      const payload = JSON.parse(Buffer.from(body, 'base64url').toString('utf8')) as ClientTokenPayload;
      if (payload.v !== 1 || typeof payload.sub !== 'string' || !UUID_RE.test(payload.sub)) return null;
      if (typeof payload.exp !== 'number' || payload.exp * 1000 <= now) return null;
      return payload;
    } catch {
      return null;
    }
  }

  private sign(body: string): string {
    return createHmac('sha256', this.config.TOKEN_SECRET).update(body).digest('base64url');
  }
}
