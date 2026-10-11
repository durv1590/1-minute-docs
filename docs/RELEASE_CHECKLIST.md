# Release Checklist

Tick each item with a link to evidence (CI run, test report, screenshot). Do not mark the build as a release candidate until every
**gate** item is ticked.

## Build (gate)
- [x] `./gradlew -p android/shared test` green — [CI run #13](https://github.com/durv1590/1-minute-docs/actions/runs/38107570841)
- [x] `./gradlew -p android lintDebug testDebugUnitTest assembleDebug` green in CI — [CI run #13](https://github.com/durv1590/1-minute-docs/actions/runs/38107570841)
- [x] Instrumented tests green on CI emulator (`connectedDebugAndroidTest`) — [CI run #13](https://github.com/durv1590/1-minute-docs/actions/runs/38107570841)
- [ ] `./gradlew -p android bundleRelease` with the production upload key (see `docs/SIGNING.md`)
- [ ] `versionCode` incremented, `versionName` set, `applicationId = com.oneminutedocs.app`
- [ ] targetSdk meets the current Google Play requirement (verify at release time on the Play Console help page)
- [ ] R8 release build smoke-tested on a physical device (PDFBox keep rules, ML Kit, Hilt)

## Backend (gate)
- [x] `npm ci && npm run lint && npm test && npm run test:e2e` green — [CI run #13](https://github.com/durv1590/1-minute-docs/actions/runs/38107570841)
- [x] `npm audit --omit=dev` reviewed; no unaddressed high/critical (CI gate `--audit-level=high`; js-yaml pinned via override)
- [ ] Production env set: `NODE_ENV=production`, `TOKEN_SECRET` (≥32 random bytes), DB, Redis, storage, AI provider, `CORS_ORIGINS`
- [ ] Migrations applied (`npm run migrate`)
- [ ] Health `/api/v1/health/ready` green behind the load balancer; TLS certificate valid
- [ ] Worker isolated (non-root, read-only rootfs, resource limits; gVisor recommended)
- [ ] Backups configured for Postgres; restore rehearsed once
- [ ] `API_BASE_URL` for the release build points at production

## Manual journeys on a physical device (gate)
- [ ] J1 Text → PDF (English and Hindi) → preview → export
- [ ] J2 Compress → sizes shown → export
- [ ] J3 Merge 3 PDFs → page count → export
- [ ] J4 Scan 3 handwritten pages → scanned PDF
- [ ] J5 Scan → recognise → correct → DOCX opens in Word/Docs
- [ ] J6 PDF → Word (device text mode and cloud mode)
- [ ] J7 AI prompt → draft → edit → PDF
- [ ] J8 Switch to Hindi; navigate all main screens; no clipped text
- [ ] Camera permission denied → app explains, gallery import still works
- [ ] Airplane mode: offline tools work; cloud tools show “internet required”

## Store listing & policy
- [ ] App name, short description (≤80 chars), full description (`docs/store/STORE_LISTING.md`)
- [ ] 512×512 icon, feature graphic 1024×500, phone + 7" + 10" screenshots (EN and HI)
- [ ] Privacy policy published at a public URL (legal review complete)
- [ ] Data safety form: files uploaded only for user-requested cloud processing, not shared, deleted after processing, encrypted in transit
- [ ] Content rating questionnaire
- [ ] Permissions declaration: CAMERA (document scanning)
- [ ] Open-source notices screen lists all bundled licences

## Rollback
- Android: halt staged rollout in Play Console; previous release remains for existing users.
- Backend: redeploy previous image tag; migrations are additive (down migrations not used in production).
