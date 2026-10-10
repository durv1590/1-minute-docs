import { Inject, Injectable, OnApplicationShutdown } from '@nestjs/common';
import IORedis, { Redis } from 'ioredis';
import { APP_CONFIG, AppConfig } from '../config/config';

@Injectable()
export class RedisService implements OnApplicationShutdown {
  /** General-purpose connection (rate limits, health). */
  readonly client: Redis;
  private readonly owned: Redis[] = [];

  constructor(@Inject(APP_CONFIG) private readonly config: AppConfig) {
    this.client = this.track(new IORedis(config.REDIS_URL, { maxRetriesPerRequest: 2 }));
  }

  /** New connection for BullMQ (which requires maxRetriesPerRequest: null and does not close connections it was given). */
  bullConnection(): Redis {
    return this.track(new IORedis(this.config.REDIS_URL, { maxRetriesPerRequest: null }));
  }

  private track(r: Redis): Redis {
    this.owned.push(r);
    return r;
  }

  /** Runs after every module's onModuleDestroy, i.e. after queues and workers have closed. */
  async onApplicationShutdown(): Promise<void> {
    await Promise.all(this.owned.map((r) => r.quit().catch(() => r.disconnect())));
  }
}
