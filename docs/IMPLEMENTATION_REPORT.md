# Implementation Report

Latest fully green CI: [run #9](https://github.com/durv1590/1-minute-docs/actions/runs/38105390337) (shared Kotlin, backend, Android build/lint/unit tests, Android instrumented tests on the emulator).

Branch `claude/eloquent-knuth-xz3j16`. This report states only what has been built and what evidence exists. "Compiles" means
the code builds and passes Android lint in GitHub Actions. It does **not** mean the feature was exercised on a device.

## Status vocabulary

| Status | Meaning |
|---|---|
| **Verified** | Implemented. Automated tests run against the real engine and pass (locally and/or in CI). |
| **Implemented, partially verified** | Implemented. Core logic is covered by tests, but the screen/device flow has not been run on a device. |
| **Implemented, not device-tested** | Code compiles and passes lint in CI. No automated test exercises the runtime path. |
| **Not implemented** | Not built. |

## Evidence available

| Suite | Where it runs | Result |
|---|---|---|
| `android/shared` (domain, document, imaging): 97 JUnit 5 tests | Locally and CI job `shared-kotlin` | Pass |
| Backend unit tests (26) and e2e tests (28) against real Postgres, Redis, LibreOffice, Tesseract, Ghostscript, qpdf, pdf2docx | Locally and CI job `backend` | Pass |
| Android `assembleDebug`, `lintDebug`, `testDebugUnitTest` | CI job `android` | Pass ([run #9](https://github.com/durv1590/1-minute-docs/actions/runs/38105390337)) |
| Android instrumented tests: `PdfEngineTest` (8 tests, PdfBox/PdfRenderer/PdfDocument on a real Android runtime) and `UiJourneysTest` (3 Compose tests) | CI job `android-instrumented` (emulator API 34) | Pass: 11/11 on emulator API 34 ([run #9](https://github.com/durv1590/1-minute-docs/actions/runs/38105390337)) |
| Physical-device manual journeys J1–J8 (`RELEASE_CHECKLIST.md`) | — | **Not run** |
| Performance baselines | — | **Not measured** |
| OCR / handwriting accuracy (CER protocol in `TESTING_PLAN.md` §4) | — | **Not measured** |

## Feature table

| Feature | Status | Tests | Known limitations |
|---|---|---|---|
| PDF creation (text, templates, EN/HI) | Implemented, partially verified | `TextPdfWriter` Hindi + pagination (instrumented); templates and markdown-lite (shared unit) | Layout is single-column flowing text. Hindi text renders correctly, but copy/search of Devanagari in the generated PDF can be imperfect for conjunct glyphs (the platform PDF writer maps shaped glyphs to Unicode only partially). The test asserts that Devanagari letters survive extraction, not exact words. Fonts are system Noto. Fonts are not bundled, so output depends on the OEM font set. |
| Images → PDF | Implemented, not device-tested | Page geometry and scaling (shared unit) | JPEG re-encoding. No HEIC input on devices without a platform decoder. |
| PDF compression | Implemented, partially verified | On device: image down-sampling with text preserved (instrumented). Cloud: Ghostscript presets (backend e2e). | Only images are recompressed; images with a transparency mask (SMask) are left untouched. Text-only PDFs rarely get smaller. When the output is not smaller, the UI says so and keeps the original. Ghostscript is AGPL (see Dependency Register). |
| PDF merger | Implemented, partially verified | Order and orientation (instrumented) | Bookmarks and form fields from the inputs are not merged. |
| PDF splitter | Implemented, partially verified | Page-range grammar (shared unit); split groups (instrumented) | — |
| Page manager (rotate, reorder, delete) | Implemented, partially verified | `rebuildReordersRotatesAndDeletes` (instrumented) | — |
| Annotate / sign (ink, text, shapes, highlight, image, drawn signature) | Implemented, partially verified | Overlay burn-in (instrumented) | Annotations are flattened into the page as an image layer. They cannot be edited later. Signatures are visual only, with no cryptographic (PAdES) signing. |
| Protect / unlock PDF | Implemented, partially verified | AES-256 protect then unlock (instrumented) | Unlocking requires the password. No password recovery, by design. |
| PDF → images | Implemented, not device-tested | — | PNG/JPEG via PdfRenderer. |
| PDF → Word | Device: implemented, not device-tested. Cloud: Verified. | DOCX writer/reader (shared unit); backend pdf2docx/LibreOffice and OCR-first scanned PDFs (e2e) | On-device mode rebuilds text only and is labelled as such. Cloud layout quality depends on the engine. pdf2docx depends on PyMuPDF (AGPL). `PDF_TO_DOCX_ENGINE` must be chosen explicitly in production. |
| Word → PDF | Cloud: Verified. App flow: implemented, not device-tested. | Backend LibreOffice DOCX/ODT/RTF → PDF with validation (e2e) | Requires internet and a deployed backend. Fidelity is LibreOffice's (fonts on the server matter). |
| Document scanner (CameraX, edge detection, perspective, filters, multi-page) | Implemented, not device-tested | Edge/quad detection, homography, Otsu threshold (shared unit, synthetic images) | Camera behaviour, auto-detection quality and lighting robustness are unmeasured. There is no auto-capture. |
| OCR | Device (ML Kit Latin + Devanagari): implemented, not device-tested. Cloud (Tesseract eng/hin, searchable PDF): Verified. | Backend OCR text and searchable PDF (e2e) | Accuracy has not been measured. ML Kit is aimed at printed text. |
| Handwritten notes (outputs A–D) | Implemented, partially verified | Note structuring and DOCX outputs (shared unit); backend handwriting job with a stub provider (e2e) | Recognition quality is unmeasured. Cloud recognition needs `ANTHROPIC_API_KEY` and user consent. The device fallback (ML Kit) handles handwriting poorly, and the UI discloses this. |
| AI document creator (generate, refine, summarise, translate, organise) | Backend: Verified with a stub provider. Real provider: not called. App: implemented, not device-tested. | Backend AI endpoints, quota, 503 when not configured (e2e); Anthropic adapter request/error mapping (unit) | No live call to the provider has been made: no API key was available. The output is a draft for the user to review. |
| Documents library (import, rename, delete, export, share, search/sort) | Implemented, partially verified | Filter/sort logic (`DocumentsFilterTest`, JVM) | — |
| Localization (English, Hindi) | Implemented, partially verified | 480 string keys in both locales; `hindiResourcesRender` (instrumented) | The Hindi strings are machine-assisted and need review by a native speaker. |
| Offline support | Implemented (design) | — | All tools marked OFFLINE run without network. This has not been checked in airplane mode on a device. |
| Security | Implemented, partially verified | Backend: 401/404 cross-client, 413, 415, 429, 503, path-like names, retention sweep (e2e). Android: Keystore token cipher compiled, not device-tested. | No external penetration test. The legal documents are drafts. |
| Performance | Not measured | — | Bounded concurrency (Semaphore), sampled bitmap decoding and page-at-a-time rendering are in place. No baselines or Macrobenchmark yet. |
| Release build / signing | Configured, not executed | — | No signing key exists, and none was generated. `bundleRelease` with the upload key is a release-time step (`docs/SIGNING.md`). The app is **not published**. |

## Not tested / open items

1. No physical-device or manual journey testing (J1–J8).
2. No measurement of performance, memory or battery.
3. No OCR or handwriting accuracy evaluation.
4. No live AI provider call (requires `ANTHROPIC_API_KEY`).
5. No deployed backend. The release `API_BASE_URL` is empty until a production URL is supplied.
6. Legal review of the privacy policy, terms and retention policy is still needed. Native-speaker review of the Hindi UI is still needed.
7. Licensing decision on the AGPL components (PyMuPDF via pdf2docx, Ghostscript) before production use.
