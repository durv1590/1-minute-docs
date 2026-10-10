import { CanActivate, createParamDecorator, ExecutionContext, Injectable } from '@nestjs/common';
import type { Request } from 'express';
import { AppError } from '../../common/errors';
import { TokenService } from './token.service';

export interface AuthedRequest extends Request {
  clientId?: string;
}

@Injectable()
export class ClientAuthGuard implements CanActivate {
  constructor(private readonly tokens: TokenService) {}

  canActivate(ctx: ExecutionContext): boolean {
    const req = ctx.switchToHttp().getRequest<AuthedRequest>();
    const header = req.headers.authorization ?? '';
    const match = /^Bearer\s+(\S+)$/i.exec(header);
    const payload = match ? this.tokens.verify(match[1]) : null;
    if (!payload) throw AppError.unauthorized();
    req.clientId = payload.sub;
    return true;
  }
}

export const ClientId = createParamDecorator((_: unknown, ctx: ExecutionContext): string => {
  const id = ctx.switchToHttp().getRequest<AuthedRequest>().clientId;
  if (!id) throw AppError.unauthorized();
  return id;
});
