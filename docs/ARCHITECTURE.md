# Architecture

## 1. System overview

```
┌──────────────────────── Android app (Kotlin, Compose) ────────────────────────┐
│ :app  — screens, ViewModels, navigation, Hilt graph                           │
│   ├─ :core:designsystem  — Material 3 theme, tokens, shared components         │
│   ├─ :core:data          — Room (documents, jobs), DataStore, file store,      │
│   │                        temp cleanup worker, repositories                   │
│   ├─ :core:network       — OkHttp client for the backend (/api/v1)             │
│   ├─ :processing:pdf     — PdfBox-Android ops, PdfRenderer, PdfDocument writer │
│   ├─ :processing:image   — bitmap decode/sampling, perspective warp, filters   │
│   └─ :processing:ocr     — ML Kit text recognition (Latin + Devanagari)        │
│                                                                                │
│ shared/ (included build, pure Kotlin/JVM — compiled & unit-tested anywhere)    │
│   ├─ :domain    — tool catalogue, file-type sniffing, validation, page ranges, │
│   │               filenames, limits, job states, Result/error model           │
│   ├─ :document  — rich-text block model, markdown-lite parser, note           │
│   │               structuring, DOCX writer & reader, templates                 │
│   └─ :imaging   — grayscale ops, Otsu threshold, Sobel edges, quad detection,  │
│                   homography solver                                            │
└────────────────────────────────────────────────────────────────────────────────┘
                 │ HTTPS, Bearer anonymous client token
┌──────────────────────── Backend (NestJS, TypeScript) ──────────────────────────┐
│ api process:  auth (anonymous tokens) · jobs · ai · health · OpenAPI           │
│ worker process: BullMQ consumer → conversion / ocr / handwriting / compression │
│ PostgreSQL (jobs, stored_files, ai_requests, client_usage) · Redis (queue,     │
│ rate limits) · storage driver (local disk or S3-compatible)                    │
│ Engines: LibreOffice (office→PDF), pdf2docx (PDF→DOCX), Tesseract (OCR,        │
│ searchable PDF), Ghostscript (compression), AI provider adapter (Anthropic /   │
│ OpenAI-compatible) for generation and handwriting vision                       │
└────────────────────────────────────────────────────────────────────────────────┘
```

## 2. Android layering

| Layer | Where | Rules |
|---|---|---|
| Presentation | `:app` (`feature/*` packages), `:core:designsystem` | Composables are stateless where practical; state is a `StateFlow<UiState>` in a `ViewModel`. Strings from resources only. |
| Domain | `shared/:domain`, `shared/:document` | Pure Kotlin, no Android imports. Use cases and contracts (`PdfEngine`, `OcrEngine`, `CloudJobs`) are interfaces. |
| Data | `:core:data`, `:core:network` | Room for metadata only (never document bytes); DataStore for prefs; repositories return `Outcome<T>`. |
| Processing | `:processing:*`, `shared/:imaging` | Runs on `Dispatchers.Default/IO` behind a `Semaphore` (bounded concurrency). Writes to temp file → validates → moves. |
| Backend integration | `:core:network` | Token acquisition, multipart upload, polling job status, streaming download, deletion after download. |

### Why the shared included build
The container used to start this project could not reach Google's Maven repository, so Android modules could not be
compiled locally. Logic that does not need Android was therefore placed in a separate Gradle build (`android/shared`) that is
consumed by the Android build through Gradle composite-build dependency substitution. It compiles and runs its tests
with only Maven Central, and it is also the right separation for testability. See ADR-0003.

## 3. Key processing decisions

| Concern | Engine | Rationale |
|---|---|---|
| Text→PDF, templates, AI/notes export | `android.graphics.pdf.PdfDocument` + `StaticLayout` | Platform text stack shapes Devanagari (HarfBuzz) and embeds font subsets; PDFBox does not shape complex scripts. |
| Merge, split, rotate, reorder, delete, encrypt/decrypt, annotations, compression | PdfBox-Android (Apache-2.0) | Mature object-level PDF manipulation without rasterising text. |
| Rendering, thumbnails, PDF→images | `android.graphics.pdf.PdfRenderer` | Platform renderer, page-at-a-time memory use. |
| OCR on device | ML Kit Text Recognition v2 (Latin, Devanagari) | Free, offline after model download; printed text. |
| Handwriting | Cloud vision model through backend adapter (consented), on-device OCR as best-effort fallback | Printed-text OCR is weak on handwriting (disclosed). |
| DOCX output | `shared/:document` DocxWriter (WordprocessingML) | Small, dependency-free, testable; Apache POI is heavy on Android. |
| PDF→DOCX | On device: text reconstruction (labelled). Cloud: pdf2docx (layout, tables, images) with Tesseract OCR first for scans. |
| DOCX→PDF | Cloud: LibreOffice headless | Only real office layout engine available under a permissive deployment model. |
| Scanner edges | `shared/:imaging` Sobel + contour quad search on downsampled frame; homography warp via `Matrix.setPolyToPoly` | No OpenCV dependency (size), deterministic tests. |

## 4. Data model (device)

Room database `omd.db`:
- `documents(id, displayName, mimeType, sizeBytes, pageCount?, relativePath, createdAt, modifiedAt, sourceOperation)`
- `cloud_jobs(jobId, operation, status, inputName, createdAt, updatedAt, outputDocumentId?, errorCode?)`

Files: `filesDir/documents/` (managed outputs), `cacheDir/work/<jobId>/` (temporary), `cacheDir/shared/` (FileProvider share
copies). Temp cleanup worker deletes `work/*` older than 24 h unless the job is marked active.

## 5. Backend design

- **Process split:** `api` (HTTP) and `worker` (BullMQ). They share the codebase; `ROLE=api|worker|all`.
- **Auth:** `POST /api/v1/auth/anonymous` issues an HMAC-signed token `{sub: clientId, exp}`. All job/AI calls need it.
  Jobs are filtered by `client_id`, so IDs alone do not grant access.
- **Job pipeline:** upload streamed to temp dir with byte cap → magic-byte check → stored via `StorageDriver` → row in `jobs`
  (QUEUED) → BullMQ job. Worker: VALIDATING (page count/limits) → PROCESSING (engine in an isolated temp dir, timeout, no
  shell) → FINALIZING (validate output signature/size) → COMPLETED. Output stored, download streamed; `DELETE` removes files.
- **Retention:** inputs deleted when job finishes; outputs expire (`OUTPUT_TTL_MINUTES`, default 60). A sweeper marks EXPIRED
  and deletes objects.
- **AI:** `AiProvider` interface; `anthropic` and `openai-compatible` implementations; `none` → `AI_NOT_CONFIGURED` (503).
- **Limits:** env-configurable; per-client daily AI quota and per-IP/client rate limiting (Redis fixed window).

## 6. Error model
Device: `Outcome.Failure(DocError)` with a closed set of codes (`UNSUPPORTED_FORMAT`, `CORRUPT_FILE`, `ENCRYPTED`,
`WRONG_PASSWORD`, `TOO_LARGE`, `NO_SPACE`, `OUT_OF_MEMORY`, `PERMISSION_REVOKED`, `NETWORK`, `SERVER`, `CANCELLED`,
`AI_NOT_CONFIGURED`, `OCR_MODEL_MISSING`, …) mapped to localized strings.
Backend: `{ "error": { "code": "...", "message": "...", "requestId": "..." } }`, no stack traces.

## 7. Decision log
See `docs/adr/`.
