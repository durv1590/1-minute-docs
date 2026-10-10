export const JOB_STATUSES = ['QUEUED', 'VALIDATING', 'PROCESSING', 'FINALIZING', 'COMPLETED', 'FAILED', 'CANCELLED', 'EXPIRED'] as const;
export type JobStatus = (typeof JOB_STATUSES)[number];
export const TERMINAL: JobStatus[] = ['COMPLETED', 'FAILED', 'CANCELLED', 'EXPIRED'];
export const ACTIVE: JobStatus[] = ['QUEUED', 'VALIDATING', 'PROCESSING', 'FINALIZING'];

export interface JobRow {
  id: string;
  client_id: string;
  operation: string;
  status: JobStatus;
  progress: number | null;
  options: Record<string, unknown>;
  input_meta: Record<string, unknown>;
  output_meta: Record<string, unknown> | null;
  error_code: string | null;
  error_message: string | null;
  idempotency_key: string | null;
  cancel_requested: boolean;
  attempts: number;
  created_at: Date;
  updated_at: Date;
  started_at: Date | null;
  completed_at: Date | null;
  expires_at: Date;
  deleted_at: Date | null;
}

export interface StoredFileRow {
  id: string;
  job_id: string;
  role: 'input' | 'output';
  position: number;
  storage_key: string;
  display_name: string;
  content_type: string;
  size_bytes: string; // bigint comes back as string
  sha256: string;
  created_at: Date;
  deleted_at: Date | null;
}

/** Public JSON representation. Options are echoed without secrets (no operation accepts secrets). */
export interface JobView {
  id: string;
  operation: string;
  status: JobStatus;
  progress: number | null;
  createdAt: string;
  updatedAt: string;
  expiresAt: string;
  inputs: { fileName: string; contentType: string; sizeBytes: number }[];
  output: { fileName: string; contentType: string; sizeBytes: number; meta: Record<string, unknown> } | null;
  error: { code: string; message: string } | null;
}

export function toView(job: JobRow, files: StoredFileRow[]): JobView {
  const live = files.filter((f) => !f.deleted_at);
  const out = job.status === 'COMPLETED' ? live.find((f) => f.role === 'output') : undefined;
  return {
    id: job.id,
    operation: job.operation,
    status: job.status,
    progress: job.progress,
    createdAt: job.created_at.toISOString(),
    updatedAt: job.updated_at.toISOString(),
    expiresAt: job.expires_at.toISOString(),
    inputs: files
      .filter((f) => f.role === 'input')
      .sort((a, b) => a.position - b.position)
      .map((f) => ({ fileName: f.display_name, contentType: f.content_type, sizeBytes: Number(f.size_bytes) })),
    output: out
      ? { fileName: out.display_name, contentType: out.content_type, sizeBytes: Number(out.size_bytes), meta: job.output_meta ?? {} }
      : null,
    error: job.error_code ? { code: job.error_code, message: job.error_message ?? '' } : null,
  };
}
