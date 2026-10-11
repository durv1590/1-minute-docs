# API Specification

The machine-readable OpenAPI 3.1 document is generated from `backend/src/openapi.ts` and served at
`GET /api/docs-json` (Swagger UI at `/api/docs`). Export it with `npm run build && npm run openapi`.
This page summarises the contract the Android client relies on.

Base path: `/api/v1`. All bodies are JSON unless stated. Errors are always:

```json
{ "error": { "code": "UNSUPPORTED_FORMAT", "message": "…", "requestId": "…", "details": { } } }
```

## Authentication
| Method | Path | Notes |
|---|---|---|
| POST | `/auth/anonymous` | Returns `{token, expiresAt}`. HMAC-signed, 30-day lifetime, bound to a random client ID. Rate-limited per IP (`RATE_TOKENS_PER_IP_HOUR`). |

Every other endpoint except health/capabilities requires `Authorization: Bearer <token>`. Jobs are only visible to the
client that created them; another client's job ID returns 404.

## Jobs
| Method | Path | Notes |
|---|---|---|
| POST | `/jobs` | `multipart/form-data`: `operation`, `options` (JSON string), `files[]`. Optional `Idempotency-Key` header (8–64 chars) — repeating it returns the same job (200) instead of creating a new one (202). |
| GET | `/jobs` | The caller's 50 most recent jobs. |
| GET | `/jobs/{id}` | Job status. |
| POST | `/jobs/{id}/cancel` | Queued jobs stop immediately; running jobs within ~1 s. |
| GET | `/jobs/{id}/download` | Streams the validated output. 409 `JOB_NOT_READY`, 410 `JOB_EXPIRED`. |
| DELETE | `/jobs/{id}` | Deletes all stored files now (204). |

### Operations
| `operation` | Inputs (sniffed by content) | `options` | Output |
|---|---|---|---|
| `office_to_pdf` | 1 × DOCX/DOC/ODT/RTF | `{}` | PDF (LibreOffice) |
| `pdf_to_docx` | 1 × PDF | `{ocrLanguage: eng\|hin\|eng+hin}` | DOCX; `meta.mode` = `layout` (`meta.engine` `text` default, `pdf2docx` or `libreoffice`) or `ocr_text` (scanned → Tesseract); `meta.warnings` may include `LAYOUT_APPROXIMATE`, `TABLES_AND_IMAGES_NOT_PRESERVED`, `SCANNED_LAYOUT_NOT_PRESERVED` |
| `ocr` | 1 × PDF/JPEG/PNG | `{language, output: txt\|pdf}` | UTF-8 text (pages separated by `\f`) or searchable PDF |
| `compress_pdf` | 1 × PDF | `{preset: high_quality\|balanced\|small_file}` | PDF; `meta.engine` (`pikepdf` default or `ghostscript`), `originalBytes`, `outputBytes`, `reductionPercent`, `notSmaller` |
| `handwriting_recognize` | 1–20 × JPEG/PNG/WEBP (≤5 MB each) | `{language: en\|hi\|auto, consent: true}` | UTF-8 text, pages separated by `\f`; `meta.aiGenerated = true` |

### Job object
```json
{
  "id": "uuid", "operation": "pdf_to_docx",
  "status": "QUEUED|VALIDATING|PROCESSING|FINALIZING|COMPLETED|FAILED|CANCELLED|EXPIRED",
  "progress": 40,               // null when not measurable
  "createdAt": "…", "updatedAt": "…", "expiresAt": "…",
  "inputs": [{ "fileName": "a.pdf", "contentType": "application/pdf", "sizeBytes": 123 }],
  "output": { "fileName": "a.docx", "contentType": "…", "sizeBytes": 456, "meta": { "pages": 3, "warnings": ["LAYOUT_APPROXIMATE"] } },
  "error": null                 // or { "code": "ENCRYPTED_DOCUMENT", "message": "…" }
}
```
`COMPLETED` is set only after the output was re-opened and validated (PDF page count via qpdf, DOCX package check, UTF-8 check).

Job error codes: `ENCRYPTED_DOCUMENT`, `CORRUPT_FILE`, `TOO_MANY_PAGES`, `CONVERSION_FAILED`, `TIMEOUT`, `CANCELLED`,
`AI_PROVIDER_ERROR`, `AI_REFUSED`, `AI_NOT_CONFIGURED`, `SERVICE_UNAVAILABLE`, `WORKER_LOST`, `EXPIRED`, `INTERNAL`.

## AI
`POST /ai/generate`

```json
{ "task": "generate_document|rewrite|summarize|fix_grammar|translate|change_tone|organize_notes|generate_questions|generate_flashcards|extract_key_points",
  "language": "en|hi", "documentType": "letter|leave_application|resume|…", "tone": "formal|…",
  "instructions": "…", "fields": { "subject": "…" }, "text": "…", "targetLanguage": "en|hi" }
```
Response: `{task, language, markdown, aiGenerated: true, provider, model, warnings: ["TRUNCATED"?], usage}`.
Errors: 400 invalid, 413 input over `AI_MAX_INPUT_CHARS`, 422 `AI_REFUSED`, 429 `QUOTA_EXCEEDED`, 502 `AI_PROVIDER_ERROR`,
503 `AI_NOT_CONFIGURED`/busy, 504 `TIMEOUT`. Prompts and outputs are not stored.

## Public
| GET | `/capabilities` | Operations available, AI configured, limits, OCR languages. |
| GET | `/health/live` | Liveness. |
| GET | `/health/ready` | DB, Redis, storage checks; 503 if any fail. |

## Limits (defaults, configurable)
Upload 50 MB/file, 20 files/job, 300 pages, 180 s per job, 30 jobs/client/hour, 50 AI requests/client/day,
60 handwriting pages/client/day, outputs kept 60 minutes.
