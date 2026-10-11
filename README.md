# One-Minute Docs

AI-assisted document creation and PDF tools for Android (English and Hindi), with a NestJS processing backend.
Product of **One-Minute Docs Ltd.** Free to use; no accounts, ads or paywalls in the first release.

```
android/            Kotlin + Jetpack Compose app (multi-module) and android/shared (pure-Kotlin, JVM-testable)
backend/            NestJS API + BullMQ worker, PostgreSQL, Redis, S3/local storage, document engines
infrastructure/     Docker Compose for local development
docs/               Requirements, architecture, ADRs, plans, API, security, testing, release, legal drafts
.github/workflows/  CI: shared tests, backend (real engines), Android build/lint/unit tests, emulator tests
```

## Quick start

### Backend (local, without Docker)
Requirements: Node 22, PostgreSQL 16, Redis 7, LibreOffice, Tesseract (`eng`, `hin`), poppler-utils, qpdf,
Python 3 with `python-docx`, `pikepdf` and `Pillow`. The opt-in engines also need Ghostscript and `pdf2docx` (AGPL; ADR-0007).
```bash
cd backend
cp .env.example .env            # set TOKEN_SECRET (openssl rand -base64 48); ANTHROPIC_API_KEY to enable AI
npm ci && npm run build
npm start                       # ROLE=all: API on :8080 + worker; migrations run automatically outside production
curl localhost:8080/api/v1/health/ready
```
Swagger UI: http://localhost:8080/api/docs

### Backend (Docker)
```bash
cp backend/.env.example backend/.env   # set TOKEN_SECRET
docker compose -f infrastructure/docker/docker-compose.dev.yml up --build
```

### Android
Requirements: JDK 17+, Android SDK (compileSdk 36). The Gradle wrapper downloads Gradle 8.14.3.
```bash
cd android
./gradlew -p shared test                 # pure-Kotlin tests (no Android SDK needed)
./gradlew assembleDebug testDebugUnitTest lintDebug
./gradlew connectedDebugAndroidTest      # needs an emulator/device
```
Debug builds talk to `http://10.0.2.2:8080` (the host machine from the emulator). Release builds need
`-Pomd.apiBaseUrl.release=https://…`; without it, cloud features show "not available in this build" and everything
offline still works. Signing: see `docs/SIGNING.md`.

## Configuration reference
All backend settings are environment variables documented in `backend/.env.example` (limits, retention, AI model,
engine paths, storage). Notable: `AI_PROVIDER`, `ANTHROPIC_API_KEY`, `AI_MODEL` (default `claude-opus-5-5`),
`PDF_TO_DOCX_ENGINE` (`text` default, `pdf2docx`, `libreoffice`) and `COMPRESS_ENGINE` (`pikepdf` default, `ghostscript`); the
defaults contain no AGPL code (ADR-0007, `docs/DEPENDENCY_REGISTER.md`).

## Documentation
| Document | Contents |
|---|---|
| [PROJECT_REQUIREMENTS](docs/PROJECT_REQUIREMENTS.md) | Testable requirements |
| [ARCHITECTURE](docs/ARCHITECTURE.md) and [ADRs](docs/adr/README.md) | Modules, engines, data flow, decisions |
| [IMPLEMENTATION_PLAN](docs/IMPLEMENTATION_PLAN.md) | Phases and acceptance criteria |
| [API_SPECIFICATION](docs/API_SPECIFICATION.md) | REST contract (OpenAPI served by the backend) |
| [SECURITY_PLAN](docs/SECURITY_PLAN.md) | Threat model and controls |
| [TESTING_PLAN](docs/TESTING_PLAN.md) | Test layers and coverage |
| [DEPENDENCY_REGISTER](docs/DEPENDENCY_REGISTER.md) | Versions, licences, release action items |
| [RELEASE_CHECKLIST](docs/RELEASE_CHECKLIST.md), [SIGNING](docs/SIGNING.md), [STORE_LISTING](docs/store/STORE_LISTING.md) | Release preparation |
| [legal/](docs/legal) | Privacy, terms and retention **drafts — require legal review** |
| [IMPLEMENTATION_REPORT](docs/IMPLEMENTATION_REPORT.md) | Current status, test evidence, known limitations |

## Troubleshooting
- `AI_NOT_CONFIGURED` (503): set `ANTHROPIC_API_KEY` on the backend; the app never contains provider keys.
- Jobs stay `QUEUED`: a worker must run (`ROLE=all` or a separate `ROLE=worker` process) and reach Redis.
- `SERVICE_UNAVAILABLE` job errors: an engine binary is missing; check `*_BIN` variables and installed Tesseract languages (`tesseract --list-langs`).
- Production refuses to start: read the message — usually `TOKEN_SECRET` too short or a placeholder.
- Android emulator cannot reach the backend: use `10.0.2.2`, and check the debug network-security config allows cleartext only for local hosts.
