# Dependency Register

Versions are pinned in `android/gradle/libs.versions.toml` and `backend/package.json` (+ lockfile).
**Release action items are marked ⚠.**

## Android (bundled in the APK)
| Dependency | Version | Licence | Purpose |
|---|---|---|---|
| Android Gradle Plugin | 8.10.1 | Apache-2.0 | Build |
| Kotlin / KSP | 2.1.21 / 2.1.21-2.0.2 | Apache-2.0 | Language, annotation processing |
| Compose BOM (UI, Material 3, icons) | 2025.05.01 | Apache-2.0 | UI |
| AndroidX core, appcompat, activity, lifecycle, navigation, splashscreen | see catalog | Apache-2.0 | App shell, per-app locales |
| Room 2.7.1, DataStore 1.1.7, WorkManager 2.10.1 | | Apache-2.0 | Metadata, preferences, cleanup |
| CameraX 1.4.2 | | Apache-2.0 | Scanner |
| Hilt 2.56.2 | | Apache-2.0 | DI |
| PdfBox-Android 2.0.27.0 | | Apache-2.0 | PDF manipulation |
| ML Kit Text Recognition (Latin, Devanagari) 16.0.1 | | ML Kit Terms of Service | On-device OCR (bundled models, ~+8 MB) |
| OkHttp 4.12.0 | | Apache-2.0 | Backend client |
| kotlinx.serialization 1.8.1, coroutines 1.10.2 | | Apache-2.0 | |
| ExifInterface 1.4.1 | | Apache-2.0 | Photo orientation |

Shared Kotlin modules (`android/shared`) have no runtime dependencies beyond the Kotlin stdlib.

## Backend (npm, production)
| Dependency | Version | Licence | Purpose |
|---|---|---|---|
| @nestjs/* | 11.2.7 / swagger 11.4.7 | MIT | HTTP framework, OpenAPI UI |
| @anthropic-ai/sdk | 0.133.0 | MIT | AI provider adapter |
| bullmq 5.81.5, ioredis 5.11.1 | | MIT | Queue |
| pg 8.23.1 | | MIT | PostgreSQL |
| @aws-sdk/client-s3 3.1149.0 | | Apache-2.0 | S3-compatible storage |
| zod 3.25.76, helmet 8.3.0, nestjs-pino 4.6.1, pino 9.14.0, yauzl 3.4.0 | | MIT | Validation, headers, logs, zip sniffing |
| js-yaml (override ^5.4.1 via swagger) | 5.4.3 | MIT | Patched GHSA-r3ph-w7gj-g6xm |

`npm audit --omit=dev` reports 0 vulnerabilities (checked in CI on every push).

## Backend engines (separate processes in the worker image)
| Engine | Licence | Notes |
|---|---|---|
| LibreOffice 24.x | MPL-2.0 | Office → PDF; optional PDF → DOCX fallback |
| Tesseract 5 + eng/hin data | Apache-2.0 | OCR, searchable PDF |
| Poppler utils (pdftoppm, pdftotext) | GPL-2.0/3.0 | Invoked as separate executables, unmodified |
| qpdf 11 | Apache-2.0 | Validation, page assembly |
| Ghostscript 10 | ⚠ AGPL-3.0 (or Artifex commercial) | Compression. Run unmodified as a separate process; review AGPL obligations (offer corresponding source) or buy a commercial licence before launch. |
| pdf2docx 0.5.13 | MIT | PDF → DOCX layout reconstruction |
| PyMuPDF 1.28 (pdf2docx dependency) | ⚠ AGPL-3.0 or Artifex commercial | **Decision required.** Production refuses to start until `PDF_TO_DOCX_ENGINE` is set: `pdf2docx` (needs AGPL compliance or commercial licence) or `libreoffice` (MPL, lower quality — text boxes instead of flowing text). |
| python-docx 1.2.0 | MIT | DOCX from OCR text |

## External services
| Service | Data sent | Configuration |
|---|---|---|
| Anthropic API (`claude-opus-5-5` by default) | Text the user chose to process; handwriting page images | `ANTHROPIC_API_KEY`, `AI_MODEL`, `AI_EFFORT`, `AI_REFUSAL_FALLBACK` (server-side refusal fallback, beta header `server-side-fallback-2026-07-01`) |
| S3-compatible object storage | Temporary job files | `STORAGE_DRIVER=s3`, bucket private + lifecycle rule |
