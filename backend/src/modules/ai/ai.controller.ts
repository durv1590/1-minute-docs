import { Body, Controller, HttpCode, Post, UseGuards } from '@nestjs/common';
import { ClientAuthGuard, ClientId } from '../auth/client-auth.guard';
import { AiService } from './ai.service';

@Controller('api/v1/ai')
@UseGuards(ClientAuthGuard)
export class AiController {
  constructor(private readonly ai: AiService) {}

  @Post('generate')
  @HttpCode(200)
  generate(@ClientId() clientId: string, @Body() body: unknown) {
    return this.ai.run(clientId, body);
  }
}
