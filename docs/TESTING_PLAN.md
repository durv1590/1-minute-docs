# Testing Plan

## 1. Test layers

| Layer | Tooling | Location | Runs |
|---|---|---|---|
| Shared Kotlin unit tests | JUnit 5 | `android/shared/*/src/test` | Locally and in CI (`./gradlew -p android/shared test`) |
| Android JVM unit tests | JUnit 4, kotlinx-coroutines-test, Turbine-free StateFlow assertions | `android/**/src/test` | CI (`testDebugUnitTest`) |
| Android instrumented tests | AndroidX Test, Compose UI test | `android/**/src/androidTest` | CI emulator job (API 34, x86_64) |
| Backend unit tests | Jest | `backend/test/unit` | Locally and in CI |
| Backend e2e tests | Jest + supertest against real Postgres, Redis, LibreOffice, Tesseract, poppler, pikepdf and the opt-in Ghostscript/pdf2docx engines | `backend/test/e2e` | Locally and in CI (services) |

## 2. Coverage map (requirement → tests)

| Area | Tests |
|---|---|
| Page range grammar, filenames, signatures, limits | `shared/domain` `PageRangeParserTest`, `FileNameSanitizerTest`, `FileSignatureTest`, `ToolCatalogTest` |
| DOCX writer/reader, note structuring, markdown-lite | `shared/document` `DocxWriterTest` (unzips and validates XML, Hindi text round-trip), `NoteStructurerTest`, `MarkdownLiteTest` |
| Edge detection / homography / Otsu | `shared/imaging` tests with synthetic images |
| PDF ops (merge order, split ranges, rotate/reorder/delete, encrypt/unlock, compress text-preservation, corrupt/encrypted inputs, overlay burn-in) | `:processing:pdf` instrumented `PdfEngineTest` |
| Text→PDF incl. Hindi | `:processing:pdf` instrumented `PdfEngineTest.textPdfRendersHindiAndPaginates` (page count, text extraction) |
| App logic | `:app` JVM `DocumentsFilterTest` (library filter/sort, language detection); `:core:network` `BackendClientTest` (MockWebServer: token, upload, polling, error mapping). ViewModel tests per screen are **not yet written**. |
| UI smoke | `:app` instrumented `UiJourneysTest` (tool search → merge, onboarding skip, Hindi resources) |
| Backend auth, ownership, limits, lifecycle, cancellation, download, deletion, retention, AI error paths, conversions | `backend/test/e2e/*.e2e-spec.ts` |

## 3. Critical user journeys (UI)
J1 Create PDF from text → preview → save. J2 Compress → verify → save. J3 Merge → page count → save. J4 Scan handwriting → scanned
PDF. J5 Scan → recognise → correct → DOCX. J6 PDF→Word. J7 AI prompt → draft → edit → PDF. J8 Switch to Hindi and navigate.
Automated today: navigation to a tool via search, onboarding skip, and Hindi resource rendering (`UiJourneysTest`). The full
journeys J1–J8 involve the SAF picker, camera or network and are in the manual checklist (`RELEASE_CHECKLIST.md`); none has
been run on a physical device yet.

## 4. Handwriting / OCR evaluation
Accuracy is **not claimed** until measured. Protocol: 30-page dataset (10 printed EN, 10 handwritten EN, 5 printed HI,
5 handwritten HI) photographed on a budget and a mid-range device; metric = character error rate (CER) via Levenshtein against
ground truth (`shared/document` provides `TextMetrics.characterErrorRate`). Results recorded in `docs/results/ocr-eval.md`.

## 5. Fixtures
Generated in tests rather than committed binaries where possible (PDFs built with PDFBox/LibreOffice during the test run).

## 6. Performance baselines (to record)
Cold/warm start (Macrobenchmark), merge of 10×20-page PDFs, compress 50-page scanned PDF, OCR per page, peak memory (Android
Studio profiler), backend job durations (from `jobs.started_at/completed_at`). No targets are claimed before baselines exist.

## 7. Release gate
Build passes; shared, backend, Android unit tests pass; instrumented tests pass on CI emulator; critical journeys run manually
on at least one physical device; security tests pass; known limitations documented. Tests that could not run are listed in the
implementation report.
