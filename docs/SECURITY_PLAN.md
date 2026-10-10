# Security Plan

## 1. Threat model (summary)

| Asset | Threat | Control |
|---|---|---|
| User documents on device | Other apps reading them | App-specific storage; sharing only through `FileProvider` with temporary grants; no world-readable files |
| Documents in transit | Interception | HTTPS only (`usesCleartextTraffic=false`, network security config; cleartext allowed only for `10.0.2.2` in debug) |
| Documents on server | Cross-client access, retention | Jobs bound to client ID; every query filters by owner; inputs deleted after processing; outputs expire (default 60 min); private storage bucket |
| Server | Malicious uploads (zip bombs, crafted PDFs/DOCX) | Size caps while streaming; magic-byte check; page-count cap; engines run via `execFile` without shell, isolated temp dir, timeout + kill, output size cap; containers non-root, read-only rootfs, dropped capabilities; recommendation to run worker under gVisor |
| Server | Resource abuse / cost | Per-IP and per-client rate limits; daily AI quota; concurrency limit on worker; max input chars for AI |
| Secrets | Leakage | Only in env/secret manager; `.env` git-ignored; no keys in APK; logs redact `authorization`, passwords, file names truncated |
| PDF passwords | Leakage | Held in memory only for the operation, never logged, never persisted, `CharArray` cleared where API allows |
| AI content | Training/retention | Provider calls send only what the user asked to process; no content stored in DB (only counts and status); privacy policy states provider terms |

## 2. Android controls
- Permissions: `CAMERA` (requested at first scan, rationale shown), `INTERNET`, `POST_NOTIFICATIONS` not used. No storage permissions — SAF only.
- `android:allowBackup="false"` and data extraction rules excluding documents (user documents are not backed up to cloud by the app).
- Backend token stored in DataStore within app-private storage; it grants access only to that install's own short-lived jobs.
  (Android Keystore wrapping is used for the token at rest via `TokenCipher`, AES-GCM key in AndroidKeyStore.)
- `FLAG_SECURE` optional setting not enabled by default (users screenshot documents legitimately).
- Intents: exported components limited to the launcher activity; FileProvider not exported.
- Input validation: file signature sniffing, size limits, page-range parsing, filename sanitisation (no path separators,
  reserved names, control chars).
- R8 enabled for release; no `Log` of document contents or tokens (lint check `LogDetector` plus code review).

## 3. Backend controls
- `helmet`, strict CORS (no browser origins by default; `CORS_ORIGINS` allow-list), body size limits, validation pipe with
  whitelist + forbidNonWhitelisted.
- Upload via multipart streamed to disk with `limits.fileSize`; content sniffed from first bytes; mismatched type → 415.
- Storage keys are server-generated UUIDs — user file names are never used in paths (path traversal impossible); download
  `Content-Disposition` uses a sanitised name.
- Errors: global filter returns `{error:{code,message,requestId}}`; stack traces only in logs.
- Secrets: `TOKEN_SECRET` (≥32 bytes, required in production), AI keys, DB/Redis/S3 credentials from env.
- Dependency scanning: `npm audit --omit=dev` in CI; Gradle dependency review before release.
- Database: least-privilege role for the app; migrations run by a separate step.

## 4. Privacy
- No accounts, no analytics SDKs, no ads SDKs.
- Cloud processing only after the user is told what will be uploaded (per-operation notice; persistent consent toggle for AI/handwriting).
- Retention: inputs deleted at job end; outputs deleted on download-confirm `DELETE` or at expiry; AI request metadata (counts, latency, status) kept 30 days.
- Templates: `docs/legal/PRIVACY_POLICY_DRAFT.md`, `TERMS_DRAFT.md`, `DATA_RETENTION_POLICY.md` — **drafts requiring legal review**.

## 5. Backup and recovery (backend)
Postgres holds only job metadata; daily logical backup (`pg_dump`) retained 7 days is sufficient. Object storage holds only
short-lived files and is not backed up by design. Restore: recreate DB from dump, run migrations, start services.

## 6. Security testing
Backend e2e tests cover: missing/invalid token (401), cross-client job access (404), oversized upload (413), wrong
signature (415), rate limiting (429), AI not configured (503), path-like file names, retention sweep deletion.
