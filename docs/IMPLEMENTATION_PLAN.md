# Implementation Plan

Work proceeds in the eleven phases of the brief. Each phase lists its acceptance criteria (AC) and how they are verified.
The *Status* column is updated as work lands; see `README.md` → Implementation report for the latest evidence.

| Phase | Scope | Acceptance criteria | Verification |
|---|---|---|---|
| 1 Foundation | Docs, Gradle (version catalog, composite build), backend skeleton, CI | Android project compiles; backend starts; config documented; no secrets committed | CI `assembleDebug`; `npm run build && npm start` health check; secret scan |
| 2 UI foundation | Design system, home, navigation, settings, EN/HI, components, states | Screens navigable; phone + tablet layouts; a11y basics; language switch | Compose UI tests; lint; manual checklist |
| 3 Local documents | SAF picking, metadata (Room), save/export, share, rename, delete, recents, temp cleanup | Users can manage files; permissions correct; no false success | Unit tests for repository, file sniffing, cleanup policy |
| 4 Core PDF | Create, images→PDF, merge, split, rotate, reorder, delete, annotate, compress | Valid outputs; inputs intact; safe errors | Instrumented PDF tests (CI emulator) + shared unit tests |
| 5 Scan & OCR | CameraX, multi-page, crop, perspective, enhance, ML Kit OCR, searchable PDF (cloud) | Multi-page scan to PDF; OCR EN/HI; permission denial safe | Imaging unit tests; instrumented OCR test; manual device test |
| 6 Handwriting | Workflow, import, recognition, correction, outputs A–D | Each output works; corrections; originals preserved; limits disclosed | Unit tests (structuring, DOCX); backend handwriting tests with fake provider |
| 7 Office conversion | PDF→DOCX, DOCX→PDF, OCR-assisted scans, validation | Real engine; validated outputs; documented gaps | Backend integration tests with LibreOffice/pdf2docx/Tesseract |
| 8 AI creator | Provider adapters, generation, refine, summarize, translate, organize, preview, export | Real results when configured; clear error when not; secrets server-side | Backend tests (provider stub over HTTP); app ViewModel tests |
| 9 Hardening | Upload validation, limits, rate limiting, lifecycle, cleanup, authz, secrets, `npm audit` | Security tests pass; access control works; retention enforced | Backend e2e tests; audit report |
| 10 Performance | Profiling, large files, low memory, cancellation | Results recorded; no known critical crashes | Macrobenchmark module (planned), backend timings |
| 11 Release | Tests, release build, signing docs, store listing, privacy | RC builds; tests pass; config documented | CI `bundleRelease` (unsigned unless secrets provided) |

## Feature acceptance criteria (selected)

**Merge PDFs** — Given N≥2 readable PDFs, output page count = Σ input pages, page i of output has the same media box and
rotation as its source page, inputs unchanged (byte hash), output re-opens with PDFBox. Encrypted input without password →
`ENCRYPTED` error, no output file left behind.

**Compress PDF** — Output re-opens; text extraction of output equals input text; reported sizes come from `File.length()`;
if output ≥ input the UI says so and keeps the original.

**Split PDF** — Range grammar `1-3,5,8-` validated against page count; each output has exactly the requested pages.

**PDF→DOCX (device)** — DOCX opens in the reader and in LibreOffice; text paragraphs equal extracted text; screen labels it
“Text only — layout not preserved”.

**DOCX→PDF (cloud)** — Output starts with `%PDF-`, page count ≥ 1, job not COMPLETED before validation.

**AI generate** — With provider configured returns non-empty text; without returns HTTP 503 `AI_NOT_CONFIGURED`;
input over cap returns 413/400; per-client quota returns 429.

**Handwriting** — Original images retained; recognized text editable; export modes A–D produce valid files; cloud
recognition only after consent toggle.

## Order of work in this repository
1. Docs (this set).
2. `android/shared` modules with unit tests (runs locally).
3. Backend with unit and e2e tests (runs locally against Postgres/Redis/LibreOffice/Tesseract/Ghostscript).
4. Android modules and app; compiled and tested in GitHub Actions.
5. Documentation and report refresh.
