# ADR-0004: Backend stack
Status: Accepted · 2026-10-10

Decision: NestJS (TypeScript) API + BullMQ worker, PostgreSQL via `pg` with plain SQL migrations and a small migrator,
Redis for queue and rate limits, storage driver abstraction (local disk / S3-compatible via AWS SDK v3).
Rationale: Prisma/TypeORM add engine downloads or heavy abstractions; plain SQL keeps migrations reviewable.
Engines run as child processes with `execFile` (no shell), per-job temp directories, timeouts, and output-size caps.
