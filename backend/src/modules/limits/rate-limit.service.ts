import { HttpStatus, Injectable } from '@nestjs/common';
import { AppError } from '../../common/errors';
import { RedisService } from '../../redis/redis.service';

/** Fixed-window counters in Redis. Used for fair-use limits that keep the free service affordable. */
@Injectable()
export class RateLimitService {
  constructor(private readonly redis: RedisService) {}

  /**
   * Adds [cost] to the counter for [key] in the current window and throws 429 when [limit] would be exceeded.
   * Returns the remaining allowance.
   */
  async consume(key: string, limit: number, windowSeconds: number, cost = 1, kind: 'RATE_LIMITED' | 'QUOTA_EXCEEDED' = 'RATE_LIMITED'): Promise<number> {
    const window = Math.floor(Date.now() / 1000 / windowSeconds);
    const redisKey = `omd:rl:${key}:${window}`;
    const results = await this.redis.client.multi().incrby(redisKey, cost).expire(redisKey, windowSeconds + 5).exec();
    const used = Number(results?.[0]?.[1] ?? 0);
    if (used > limit) {
      // Give back the cost so a rejected request does not eat into the next attempt's allowance.
      await this.redis.client.decrby(redisKey, cost);
      const retryAfter = windowSeconds - (Math.floor(Date.now() / 1000) % windowSeconds);
      throw new AppError(kind, HttpStatus.TOO_MANY_REQUESTS, kind === 'QUOTA_EXCEEDED' ? 'Daily fair-use limit reached' : 'Too many requests', {
        retryAfterSeconds: retryAfter,
        limit,
      });
    }
    return limit - used;
  }

  async refund(key: string, windowSeconds: number, cost = 1): Promise<void> {
    const window = Math.floor(Date.now() / 1000 / windowSeconds);
    await this.redis.client.decrby(`omd:rl:${key}:${window}`, cost);
  }
}
