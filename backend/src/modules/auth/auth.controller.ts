import { Controller, HttpCode, Inject, Post, Req } from '@nestjs/common';
import type { Request } from 'express';
import { APP_CONFIG, AppConfig } from '../../config/config';
import { RateLimitService } from '../limits/rate-limit.service';
import { TokenService } from './token.service';

@Controller('api/v1/auth')
export class AuthController {
  constructor(
    private readonly tokens: TokenService,
    private readonly limits: RateLimitService,
    @Inject(APP_CONFIG) private readonly config: AppConfig,
  ) {}

  /** Issues an anonymous client token. No personal data is requested or stored. */
  @Post('anonymous')
  @HttpCode(201)
  async anonymous(@Req() req: Request) {
    await this.limits.consume(`tok:${req.ip ?? 'unknown'}`, this.config.RATE_TOKENS_PER_IP_HOUR, 3600);
    const { token, expiresAt } = this.tokens.issue();
    return { token, expiresAt };
  }
}
