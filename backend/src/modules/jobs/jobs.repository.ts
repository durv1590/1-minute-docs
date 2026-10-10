import { Injectable } from '@nestjs/common';
import { PoolClient } from 'pg';
import { DatabaseService } from '../../database/database.service';
import { JobRow, JobStatus, StoredFileRow } from './job.types';

export interface NewFile {
  id: string;
  role: 'input' | 'output';
  position: number;
  storageKey: string;
  displayName: string;
  contentType: string;
  sizeBytes: number;
  sha256: string;
}

@Injectable()
export class JobsRepository {
  constructor(private readonly db: DatabaseService) {}

  async create(
    job: { id: string; clientId: string; operation: string; options: object; inputMeta: object; idempotencyKey: string | null; expiresAt: Date },
    files: NewFile[],
  ): Promise<JobRow> {
    return this.db.transaction(async (c) => {
      const row = (
        await c.query<JobRow>(
          `INSERT INTO jobs (id, client_id, operation, status, options, input_meta, idempotency_key, expires_at)
           VALUES ($1,$2,$3,'QUEUED',$4,$5,$6,$7) RETURNING *`,
          [job.id, job.clientId, job.operation, job.options, job.inputMeta, job.idempotencyKey, job.expiresAt],
        )
      ).rows[0];
      for (const f of files) await this.insertFile(c, job.id, f);
      return row;
    });
  }

  private async insertFile(c: PoolClient, jobId: string, f: NewFile): Promise<void> {
    await c.query(
      `INSERT INTO stored_files (id, job_id, role, position, storage_key, display_name, content_type, size_bytes, sha256)
       VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9)`,
      [f.id, jobId, f.role, f.position, f.storageKey, f.displayName, f.contentType, f.sizeBytes, f.sha256],
    );
  }

  findByIdempotencyKey(clientId: string, key: string): Promise<JobRow | undefined> {
    return this.db.one<JobRow>('SELECT * FROM jobs WHERE client_id = $1 AND idempotency_key = $2 AND deleted_at IS NULL', [clientId, key]);
  }

  /** Ownership is enforced in SQL: another client's job is indistinguishable from a missing one. */
  findOwned(id: string, clientId: string): Promise<JobRow | undefined> {
    return this.db.one<JobRow>('SELECT * FROM jobs WHERE id = $1 AND client_id = $2 AND deleted_at IS NULL', [id, clientId]);
  }

  findById(id: string): Promise<JobRow | undefined> {
    return this.db.one<JobRow>('SELECT * FROM jobs WHERE id = $1', [id]);
  }

  listOwned(clientId: string, limit: number): Promise<JobRow[]> {
    return this.db.query<JobRow>(
      'SELECT * FROM jobs WHERE client_id = $1 AND deleted_at IS NULL ORDER BY created_at DESC LIMIT $2',
      [clientId, limit],
    );
  }

  files(jobId: string): Promise<StoredFileRow[]> {
    return this.db.query<StoredFileRow>('SELECT * FROM stored_files WHERE job_id = $1 ORDER BY role, position', [jobId]);
  }

  filesForJobs(jobIds: string[]): Promise<StoredFileRow[]> {
    if (jobIds.length === 0) return Promise.resolve([]);
    return this.db.query<StoredFileRow>('SELECT * FROM stored_files WHERE job_id = ANY($1::uuid[]) ORDER BY role, position', [jobIds]);
  }

  /** Atomic state transition; returns the updated row, or undefined if the job was not in one of [from]. */
  transition(id: string, from: JobStatus[], to: JobStatus, extra: { progress?: number | null; startedAt?: boolean } = {}): Promise<JobRow | undefined> {
    return this.db.one<JobRow>(
      `UPDATE jobs SET status = $3, updated_at = now(),
         progress = CASE WHEN $4::boolean THEN $5::smallint ELSE progress END,
         started_at = CASE WHEN $6::boolean THEN coalesce(started_at, now()) ELSE started_at END,
         attempts = CASE WHEN $6::boolean THEN attempts + 1 ELSE attempts END
       WHERE id = $1 AND status = ANY($2::text[]) RETURNING *`,
      [id, from, to, extra.progress !== undefined, extra.progress ?? null, extra.startedAt === true],
    );
  }

  async setProgress(id: string, progress: number): Promise<void> {
    await this.db.query(`UPDATE jobs SET progress = $2, updated_at = now() WHERE id = $1 AND status IN ('PROCESSING','FINALIZING')`, [
      id,
      Math.max(0, Math.min(100, Math.round(progress))),
    ]);
  }

  async complete(id: string, outputMeta: object, output: NewFile, expiresAt: Date): Promise<JobRow | undefined> {
    return this.db.transaction(async (c) => {
      const row = (
        await c.query<JobRow>(
          `UPDATE jobs SET status = 'COMPLETED', progress = 100, output_meta = $2, completed_at = now(), updated_at = now(), expires_at = $3
           WHERE id = $1 AND status = 'FINALIZING' AND NOT cancel_requested RETURNING *`,
          [id, outputMeta, expiresAt],
        )
      ).rows[0];
      if (row) await this.insertFile(c, id, output);
      return row;
    });
  }

  fail(id: string, code: string, message: string, status: 'FAILED' | 'CANCELLED' = 'FAILED'): Promise<JobRow | undefined> {
    return this.db.one<JobRow>(
      `UPDATE jobs SET status = $2, error_code = $3, error_message = $4, completed_at = now(), updated_at = now()
       WHERE id = $1 AND status = ANY(ARRAY['QUEUED','VALIDATING','PROCESSING','FINALIZING']) RETURNING *`,
      [id, status, code, message.slice(0, 500)],
    );
  }

  requestCancel(id: string): Promise<JobRow | undefined> {
    return this.db.one<JobRow>(
      `UPDATE jobs SET cancel_requested = true, updated_at = now() WHERE id = $1 AND status = ANY(ARRAY['QUEUED','VALIDATING','PROCESSING','FINALIZING']) RETURNING *`,
      [id],
    );
  }

  async isCancelRequested(id: string): Promise<boolean> {
    const r = await this.db.one<{ cancel_requested: boolean }>('SELECT cancel_requested FROM jobs WHERE id = $1', [id]);
    return r?.cancel_requested ?? true;
  }

  async markDeleted(id: string): Promise<void> {
    await this.db.query('UPDATE jobs SET deleted_at = now(), updated_at = now() WHERE id = $1', [id]);
  }

  async markFileDeleted(fileId: string): Promise<void> {
    await this.db.query('UPDATE stored_files SET deleted_at = now() WHERE id = $1 AND deleted_at IS NULL', [fileId]);
  }

  async audit(eventType: string, jobId: string | null, clientId: string | null, detail: object = {}): Promise<void> {
    await this.db.query('INSERT INTO audit_events (event_type, job_id, client_id, detail) VALUES ($1,$2,$3,$4)', [eventType, jobId, clientId, detail]);
  }
}
