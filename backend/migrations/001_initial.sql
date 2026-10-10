-- One-Minute Docs backend schema. Stores job metadata only — never document contents.

CREATE TABLE jobs (
  id               uuid PRIMARY KEY,
  client_id        uuid NOT NULL,
  operation        text NOT NULL,
  status           text NOT NULL CHECK (status IN ('QUEUED','VALIDATING','PROCESSING','FINALIZING','COMPLETED','FAILED','CANCELLED','EXPIRED')),
  progress         smallint CHECK (progress BETWEEN 0 AND 100),
  options          jsonb NOT NULL DEFAULT '{}'::jsonb,
  input_meta       jsonb NOT NULL DEFAULT '{}'::jsonb,
  output_meta      jsonb,
  error_code       text,
  error_message    text,
  idempotency_key  text,
  cancel_requested boolean NOT NULL DEFAULT false,
  attempts         smallint NOT NULL DEFAULT 0,
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  started_at       timestamptz,
  completed_at     timestamptz,
  expires_at       timestamptz NOT NULL,
  deleted_at       timestamptz
);

CREATE INDEX jobs_client_created_idx ON jobs (client_id, created_at DESC);
CREATE INDEX jobs_status_expires_idx ON jobs (status, expires_at);
CREATE UNIQUE INDEX jobs_idempotency_idx ON jobs (client_id, idempotency_key) WHERE idempotency_key IS NOT NULL;

CREATE TABLE stored_files (
  id            uuid PRIMARY KEY,
  job_id        uuid NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
  role          text NOT NULL CHECK (role IN ('input','output')),
  position      smallint NOT NULL DEFAULT 0,
  storage_key   text NOT NULL UNIQUE,
  display_name  text NOT NULL,
  content_type  text NOT NULL,
  size_bytes    bigint NOT NULL,
  sha256        text NOT NULL,
  created_at    timestamptz NOT NULL DEFAULT now(),
  deleted_at    timestamptz
);

CREATE INDEX stored_files_job_idx ON stored_files (job_id, role, position);
CREATE INDEX stored_files_live_idx ON stored_files (created_at) WHERE deleted_at IS NULL;

-- AI usage accounting: sizes, latency and outcome only; prompts and outputs are not stored.
CREATE TABLE ai_requests (
  id            uuid PRIMARY KEY,
  client_id     uuid NOT NULL,
  task          text NOT NULL,
  provider      text NOT NULL,
  model         text NOT NULL,
  input_chars   integer NOT NULL,
  output_chars  integer,
  input_tokens  integer,
  output_tokens integer,
  status        text NOT NULL CHECK (status IN ('SUCCEEDED','FAILED','REFUSED')),
  error_code    text,
  latency_ms    integer,
  created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ai_requests_created_idx ON ai_requests (created_at);

-- Minimal operational audit trail (deletions, expiries, cancellations). No document data.
CREATE TABLE audit_events (
  id         bigserial PRIMARY KEY,
  event_type text NOT NULL,
  job_id     uuid,
  client_id  uuid,
  detail     jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX audit_events_created_idx ON audit_events (created_at);
