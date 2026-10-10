import { Inject, Injectable, OnModuleDestroy } from '@nestjs/common';
import { Queue } from 'bullmq';
import { APP_CONFIG, AppConfig } from '../../config/config';
import { RedisService } from '../../redis/redis.service';

export const QUEUE_NAME = 'documents';

export interface QueuePayload {
  jobId: string;
}

@Injectable()
export class QueueService implements OnModuleDestroy {
  readonly queue: Queue<QueuePayload>;

  constructor(redis: RedisService, @Inject(APP_CONFIG) config: AppConfig) {
    this.queue = new Queue<QueuePayload>(QUEUE_NAME, {
      connection: redis.bullConnection(),
      prefix: config.QUEUE_PREFIX,
      defaultJobOptions: {
        // One retry with backoff for transient failures (storage/network, worker crash). Deterministic engine
        // failures are thrown as UnrecoverableError and never retried.
        attempts: 2,
        backoff: { type: 'exponential', delay: 5000 },
        removeOnComplete: { age: 3600, count: 1000 },
        removeOnFail: { age: 24 * 3600 },
      },
    });
  }

  async enqueue(jobId: string): Promise<void> {
    // Using the job id as the BullMQ id makes enqueueing idempotent.
    await this.queue.add('process', { jobId }, { jobId });
  }

  async removeIfWaiting(jobId: string): Promise<void> {
    const job = await this.queue.getJob(jobId);
    if (job && (await job.isWaiting() || await job.isDelayed())) await job.remove().catch(() => undefined);
  }

  async onModuleDestroy(): Promise<void> {
    await this.queue.close();
  }
}
