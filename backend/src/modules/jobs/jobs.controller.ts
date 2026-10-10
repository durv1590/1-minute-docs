import {
  Body,
  Controller,
  Delete,
  Get,
  Headers,
  HttpCode,
  Param,
  Post,
  Res,
  UploadedFiles,
  UseGuards,
  UseInterceptors,
} from '@nestjs/common';
import { FilesInterceptor } from '@nestjs/platform-express';
import type { Response } from 'express';
import { contentDisposition } from '../../common/filenames';
import { ClientAuthGuard, ClientId } from '../auth/client-auth.guard';
import { JobsService } from './jobs.service';

@Controller('api/v1/jobs')
@UseGuards(ClientAuthGuard)
export class JobsController {
  constructor(private readonly jobs: JobsService) {}

  /** multipart/form-data: operation, options (JSON string), files[] */
  @Post()
  @UseInterceptors(FilesInterceptor('files'))
  async create(
    @ClientId() clientId: string,
    @UploadedFiles() files: Express.Multer.File[] | undefined,
    @Body() body: { operation?: string; options?: string },
    @Headers('idempotency-key') idempotencyKey: string | undefined,
    @Res({ passthrough: true }) res: Response,
  ) {
    const { job, created } = await this.jobs.create(clientId, body?.operation, body?.options, files ?? [], idempotencyKey);
    res.status(created ? 202 : 200);
    return job;
  }

  @Get()
  async list(@ClientId() clientId: string) {
    return { jobs: await this.jobs.list(clientId) };
  }

  @Get(':id')
  get(@ClientId() clientId: string, @Param('id') id: string) {
    return this.jobs.get(clientId, id);
  }

  @Post(':id/cancel')
  @HttpCode(200)
  cancel(@ClientId() clientId: string, @Param('id') id: string) {
    return this.jobs.cancel(clientId, id);
  }

  @Get(':id/download')
  async download(@ClientId() clientId: string, @Param('id') id: string, @Res() res: Response) {
    const file = await this.jobs.openDownload(clientId, id);
    res.setHeader('Content-Type', file.contentType);
    res.setHeader('Content-Length', String(file.size));
    res.setHeader('Content-Disposition', contentDisposition(file.fileName));
    res.setHeader('Cache-Control', 'no-store');
    file.stream.on('error', () => res.destroy());
    file.stream.pipe(res);
  }

  @Delete(':id')
  @HttpCode(204)
  async remove(@ClientId() clientId: string, @Param('id') id: string) {
    await this.jobs.delete(clientId, id);
  }
}
