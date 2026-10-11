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
| Backend unit tests (26) and e2e tests (32) against real Postgres, Redis, LibreOffice, Tesseract, poppler, qpdf, pikepdf and the opt-in Ghostscript/pdf2docx engines | Locally and CI job `backend` | Pass |
| Android `assembleDebug`, `lintDebug`, `testDebugUnitTest` | CI job `android` | Pass ([run #9](https://github.com/durv1590/1-minute-docs/actions/runs/38105390337)) |
| Android instrumented tests: `PdfEngineTest` (8 tests, PdfBox/PdfRenderer/PdfDocument on a real Android runtime) and `UiJourneysTest` (3 Compose tests) | CI job `android-instrumented` (emulator API 34) | Pass: 11/11 on emulator API 34 ([run #9](https://github.com/durv1590/1-minute-docs/actions/runs/38105390337)) |
| Physical-device manual journeys J1–J8 (`RELEASE_CHECKLIST.md`) | — | **Not run** |
| Backend performance baseline (job durations per engine) | Dev container, `npm run bench` → `docs/results/backend-baseline.md` | Measured |
| Device performance baselines | — | **Not measured** |
| Cloud OCR accuracy on clean rendered pages (Tesseract) | `npm run bench` | Measured: English 0 % CER, Hindi 1.3–2.2 % CER (best case, not photos) |
| OCR accuracy on phone photos, handwriting accuracy, ML Kit accuracy (protocol in `TESTING_PLAN.md` §4) | — | **Not measured** |

## Feature table

| Feature | Status | Tests | Known limitations |
|---|---|---|---|
| PDF creation (text, templates, EN/HI) | Implemented, partially verified | `TextPdfWriter` Hindi + pagination (instrumented); templates and markdown-lite (shared unit) | Layout is single-column flowing text. Hindi text renders correctly, but copy/search of Devanagari in the generated PDF can be imperfect for conjunct glyphs (the platform PDF writer maps shaped glyphs to Unicode only partially). The test asserts that Devanagari letters survive extraction, not exact words. Fonts are system Noto. Fonts are not bundled, so output depends on the OEM font set. |
| Images → PDF | Implemented, not device-tested | Page geometry and scaling (shared unit) | JPEG re-encoding. No HEIC input on devices without a platform decoder. |
| PDF compression | Implemented, partially verified | On device: image down-sampling with text preserved (instrumented). Cloud: pikepdf (default) and Ghostscript (opt-in) presets, text preserved (backend e2e). | Only images are recompressed; images with a transparency mask (SMask) are left untouched. Text-only PDFs rarely get smaller. When the output is not smaller, the UI says so and keeps the original. Ghostscript is AGPL and opt-in only (ADR-0007); the default pikepdf engine does not subset fonts. |
| PDF merger | Implemented, partially verified | Order and orientation (instrumented) | Bookmarks and form fields from the inputs are not merged. |
| PDF splitter | Implemented, partially verified | Page-range grammar (shared unit); split groups (instrumented) | — |
| Page manager (rotate, reorder, delete) | Implemented, partially verified | `rebuildReordersRotatesAndDeletes` (instrumented) | — |
| Annotate / sign (ink, text, shapes, highlight, image, drawn signature) | Implemented, partially verified | Overlay burn-in (instrumented) | Annotations are flattened into the page as an image layer. They cannot be edited later. Signatures are visual only, with no cryptographic (PAdES) signing. |
| Protect / unlock PDF | Implemented, partially verified | AES-256 protect then unlock (instrumented) | Unlocking requires the password. No password recovery, by design. |
| PDF → images | Implemented, not device-tested | — | PNG/JPEG via PdfRenderer. |
| PDF → Word | Device: implemented, not device-tested. Cloud: Verified. | DOCX writer/reader (shared unit); backend default text engine (headings, paragraphs, Hindi order), opt-in pdf2docx (tables) and LibreOffice, OCR-first scanned PDFs (e2e) | On-device mode rebuilds text only and is labelled as such. The default cloud engine keeps headings and paragraphs but not tables or images, and says so in its warnings. pdf2docx keeps tables but is AGPL and opt-in (ADR-0007). LibreOffice mis-orders Devanagari. |
| Word → PDF | Cloud: Verified. App flow: implemented, not device-tested. | Backend LibreOffice DOCX/ODT/RTF → PDF with validation (e2e) | Requires internet and a deployed backend. Fidelity is LibreOffice's (fonts on the server matter). |
| Document scanner (CameraX, edge detection, perspective, filters, multi-page) | Implemented, not device-tested | Edge/quad detection, homography, Otsu threshold (shared unit, synthetic images) | Camera behaviour, auto-detection quality and lighting robustness are unmeasured. There is no auto-capture. |
| OCR | Device (ML Kit Latin + Devanagari): implemented, not device-tested. Cloud (Tesseract eng/hin, searchable PDF): Verified. | Backend OCR text and searchable PDF (e2e) | Cloud Tesseract on clean rendered pages: English 0 % CER, Hindi 1.3–2.2 % CER (`docs/results/backend-baseline.md`). Accuracy on phone photos and of ML Kit is not measured. ML Kit is aimed at printed text. |
| Handwritten notes (outputs A–D) | Implemented, partially verified | Note structuring and DOCX outputs (shared unit); backend handwriting job with a stub provider (e2e) | Recognition quality is unmeasured. Cloud recognition needs `ANTHROPIC_API_KEY` and user consent. The device fallback (ML Kit) handles handwriting poorly, and the UI discloses this. |
| AI document creator (generate, refine, summarise, translate, organise) | Backend: Verified with a stub provider. Real provider: not called. App: implemented, not device-tested. | Backend AI endpoints, quota, 503 when not configured (e2e); Anthropic adapter request/error mapping (unit) | No live call to the provider has been made: no API key was available. The output is a draft for the user to review. |
| Documents library (import, rename, delete, export, share, search/sort) | Implemented, partially verified | Filter/sort logic (`DocumentsFilterTest`, JVM) | — |
| Localization (English, Hindi) | Implemented, partially verified | 480 string keys in both locales; `hindiResourcesRender` (instrumented) | Hindi strings were reviewed for grammar and consistent everyday terminology (226 of 479 revised). A native-speaker review and an on-device check for clipped labels are still recommended. |
| Offline support | Implemented (design) | — | All tools marked OFFLINE run without network. This has not been checked in airplane mode on a device. |
| Security | Implemented, partially verified | Backend: 401/404 cross-client, 413, 415, 429, 503, path-like names, retention sweep (e2e). Android: Keystore token cipher compiled, not device-tested. | No external penetration test. The legal documents are drafts. |
| Performance | Backend: baseline measured. Device: not measured. | `npm run bench` (`docs/results/backend-baseline.md`) | Bounded concurrency (Semaphore), sampled bitmap decoding and page-at-a-time rendering are in place. No baselines or Macrobenchmark yet. |
| Release build / signing | Configured, not executed | — | No signing key exists, and none was generated. `bundleRelease` with the upload key is a release-time step (`docs/SIGNING.md`). The app is **not published**. |

## Not tested / open items

1. No physical-device or manual journey testing (J1–J8).
2. No device measurement of performance, memory or battery (backend baseline is in `docs/results/backend-baseline.md`).
3. No OCR evaluation on real phone photos and no handwriting accuracy evaluation (clean-page Tesseract CER is measured).
4. No live AI provider call (requires `ANTHROPIC_API_KEY`).
5. No deployed backend. The release `API_BASE_URL` is empty until a production URL is supplied.
6. Legal review of the privacy policy, terms and retention policy is still needed. Native-speaker review of the Hindi UI is still needed.
7. AGPL engines (pdf2docx/PyMuPDF, Ghostscript) are off by default (ADR-0007). Enabling them needs AGPL compliance or a commercial licence.
