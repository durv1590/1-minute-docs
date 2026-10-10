import { Inject, Injectable, Logger, OnModuleDestroy, OnModuleInit } from '@nestjs/common';
import { APP_CONFIG, AppConfig } from '../../config/config';
import { DatabaseService } from '../../database/database.service';
import { JobRow, StoredFileRow } from '../jobs/job.types';
import { JobsRepository } from '../jobs/jobs.repository';
import { STORAGE, StorageDriver } from '../storage/storage.driver';

/**
 * Enforces the retention policy (docs/legal/DATA_RETENTION_POLICY.md):
 * - outputs are deleted when they expire (default 60 minutes after completion);
 * - inputs of finished jobs are deleted (normally already removed by the worker);
 * - jobs stuck in an active state past their deadline are failed and their files removed;
 * - metadata rows are purged after 7 days, AI usage rows after 30 days, audit rows after 90 days.
 */
@Injectable()
export class SweeperService implements OnModuleInit, OnModuleDestroy {
  private readonly logger = new Logger(SweeperService.name);
  private timer?: NodeJS.Timeout;
  private running = false;

  constructor(
    @Inject(APP_CONFIG) private readonly config: AppConfig,
    private readonly db: DatabaseService,
    private readonly repo: JobsRepository,
    @Inject(STORAGE) private readonly storage: StorageDriver,
  ) {}

  onModuleInit(): void {
    if (this.config.ROLE === 'api' || this.config.NODE_ENV === 'test') return;
    this.timer = setInterval(() => void this.sweep(), this.config.SWEEP_INTERVAL_SECONDS * 1000);
  }

  onModuleDestroy(): void {
    if (this.timer) clearInterval(this.timer);
  }

  async sweep(now = new Date()): Promise<{ expired: number; stuck: number; filesDeleted: number }> {
    if (this.running) return { expired: 0, stuck: 0, filesDeleted: 0 };
    this.running = true;
    const client = await this.db.pool.connect();
    try {
      // Only one sweeper across all worker replicas.
      const lock = await client.query<{ ok: boolean }>('SELECT pg_try_advisory_lock(726355) AS ok');
      if (!lock.rows[0].ok) return { expired: 0, stuck: 0, filesDeleted: 0 };
      try {
        return await this.sweepLocked(now);
      } finally {
        await client.query('SELECT pg_advisory_unlock(726355)');
      }
    } catch (e) {
      this.logger.error({ err: (e as Error).message }, 'sweep failed');
      return { expired: 0, stuck: 0, filesDeleted: 0 };
    } finally {
      client.release();
      this.running = false;
    }
  }

  private async sweepLocked(now: Date) {
    const expired = await this.db.query<JobRow>(
      `UPDATE jobs SET status = 'EXPIRED', updated_at = now() WHERE status = 'COMPLETED' AND expires_at <= $1 RETURNING id`,
      [now],
    );
    const stuckCutoff = new Date(now.getTime() - this.config.JOB_TIMEOUT_SECONDS * 3 * 1000);
    const stuck = await this.db.query<JobRow>(
      `UPDATE jobs SET status = 'FAILED', error_code = 'WORKER_LOST', error_message = 'Processing was interrupted. Please try again.',
         completed_at = now(), updated_at = now()
       WHERE status IN ('VALIDATING','PROCESSING','FINALIZING') AND updated_at < $1 RETURNING id`,
      [stuckCutoff],
    );
    const abandoned = await this.db.query<JobRow>(
      `UPDATE jobs SET status = 'EXPIRED', error_code = 'EXPIRED', error_message = 'The job was not processed in time.', updated_at = now()
       WHERE status = 'QUEUED' AND expires_at <= $1 RETURNING id`,
      [now],
    );
    for (const j of expired) await this.repo.audit('job.expired', j.id, null);

    // Delete every live file that belongs to a job which no longer needs it.
    const files = await this.db.query<StoredFileRow>(
      `SELECT f.* FROM stored_files f JOIN jobs j ON j.id = f.job_id
       WHERE f.deleted_at IS NULL AND (
         j.deleted_at IS NOT NULL
         OR j.status IN ('FAILED','CANCELLED','EXPIRED')
         OR (j.status = 'COMPLETED' AND f.role = 'input')
       )`,
    );
    let filesDeleted = 0;
    for (const f of files) {
      try {
        await this.storage.delete(f.storage_key);
        await this.repo.markFileDeleted(f.id);
        filesDeleted++;
      } catch (e) {
        this.logger.warn({ fileId: f.id, err: (e as Error).message }, 'could not delete stored file');
      }
    }

    await this.db.query(
      `DELETE FROM jobs WHERE (status IN ('FAILED','CANCELLED','EXPIRED') OR deleted_at IS NOT NULL)
         AND updated_at < $1 AND NOT EXISTS (SELECT 1 FROM stored_files f WHERE f.job_id = jobs.id AND f.deleted_at IS NULL)`,
      [new Date(now.getTime() - 7 * 86400_000)],
    );
    await this.db.query('DELETE FROM ai_requests WHERE created_at < $1', [new Date(now.getTime() - 30 * 86400_000)]);
    await this.db.query('DELETE FROM audit_events WHERE created_at < $1', [new Date(now.getTime() - 90 * 86400_000)]);

    const result = { expired: expired.length + abandoned.length, stuck: stuck.length, filesDeleted };
    if (result.expired || result.stuck || result.filesDeleted) this.logger.log(result, 'retention sweep');
    return result;
  }
}
