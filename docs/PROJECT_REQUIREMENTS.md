# One-Minute Docs — Project Requirements

Product: **One-Minute Docs** · Company: **One-Minute Docs Ltd.** · Platform: Android phones and tablets (API 29+)
Languages: English, Hindi · Business model: free in the initial release (no paywalls, ads or payments).

This document turns the master development brief into testable requirements. Each requirement has an ID that
`IMPLEMENTATION_PLAN.md` and `TESTING_PLAN.md` refer to. Statuses are tracked in the implementation report in
`README.md`; this file says *what* must be true, not what has been done.

## 1. Non-negotiable principles

| ID | Principle |
|---|---|
| P-1 | No simulated results. A success screen is shown only after the output file exists and has been re-opened/validated. |
| P-2 | The original input is never modified or deleted. Outputs are written to a temp file and moved into place after validation. |
| P-3 | Progress is shown as a percentage only when it is measured; otherwise an indeterminate indicator is used. |
| P-4 | No secrets in the Android app. AI and conversion credentials live only on the backend. |
| P-5 | Every tool declares whether it is *offline*, *internet required*, or *internet for enhanced processing*. |
| P-6 | Limitations of each conversion/recognition engine are disclosed to the user where the result could mislead. |
| P-7 | Minimum permissions; camera requested only when the user starts a camera action; SAF instead of broad storage. |
| P-8 | No password cracking or bypass of PDF access controls. Passwords are never logged or persisted. |

## 2. Functional requirements

### 2.1 Application shell
- FR-SHELL-1 Splash using the Android 12+ SplashScreen API (no artificial delay).
- FR-SHELL-2 Skippable first-launch introduction (PDF tools, scanning, handwriting, AI, conversion); no permission prompts in it.
- FR-SHELL-3 Home: header (logo text mark, settings, help), search over tools and recent documents, quick actions, categories,
  recent documents (name, type, size, modified, actions), storage panel with *measured* app storage and a cleanup action.
- FR-SHELL-4 Tool catalogue: searchable; each tool shows title, description, icon, input/output formats, size limits, offline label.
- FR-SHELL-5 Settings: language, theme, privacy, storage/cache cleanup, export defaults, OCR language, AI consent, about, help,
  legal drafts, open-source notices.
- FR-SHELL-6 English and Hindi, switchable at runtime (per-app locale API), all UI strings in resources.

### 2.2 Files
- FR-FILE-1 Pick files via Storage Access Framework (single and multiple). Validate by content signature, not extension.
- FR-FILE-2 Handle revoked URI permission, unreadable/corrupt files, insufficient storage with specific messages.
- FR-FILE-3 Managed outputs live in app-specific storage; export to a user-chosen location via SAF `CreateDocument`.
- FR-FILE-4 My Documents: search, sort, type filter, date filter, rename, share, delete, multi-select, details.
- FR-FILE-5 Temporary-file cleanup that never touches outputs or inputs of active jobs and survives restarts.
- FR-FILE-6 Preview: PDF page-by-page rendering (bounded memory), zoom, rotate view, page count, info, share/export/rename/delete.

### 2.3 PDF tools (all on-device unless noted)
- FR-PDF-1 Create PDF from text with page size, orientation, margins, font, page numbers, header/footer; Hindi shaped correctly.
- FR-PDF-2 Templates (letter, application, report, resume, meeting notes, study notes, invoice) editable before export.
- FR-PDF-3 Compress with presets (high quality / balanced / small); report original, output, actual reduction; keep original
  if output is not smaller; preserve text (only images are recompressed).
- FR-PDF-4 Merge with reorder/remove, page counts, output name; preserve order and orientation; reject encrypted inputs without password.
- FR-PDF-5 Split: extract pages, ranges, one-file-per-page, every N pages; validated ranges.
- FR-PDF-6 PDF→DOCX: on-device text reconstruction (clearly labelled) and cloud layout-preserving conversion; scanned PDFs use OCR.
- FR-PDF-7 DOCX→PDF via a real office engine (backend LibreOffice). Internet required.
- FR-PDF-8 Images→PDF with order, crop, rotate, page size, margins, fit/fill, quality; downsampled decoding.
- FR-PDF-9 PDF→images (PNG/JPEG), page range, DPI choice with size warning.
- FR-PDF-10 Annotate: text overlay, highlight, underline, freehand ink, rectangle/ellipse/line, image, signature. Overlays are
  labelled as overlays (not edits of embedded text).
- FR-PDF-11 Page manager: thumbnails, reorder, rotate, delete, extract, with in-memory undo before export.
- FR-PDF-12 Security: encrypt with user/owner password (AES-256), open protected PDFs with a password, remove protection when the
  correct password is supplied.
- FR-PDF-13 Scanner: CameraX capture, auto edge detection, manual 4-corner crop, perspective correction, rotation, flash,
  retake, reorder, enhancement filters, PDF export; gallery import alternative.
- FR-PDF-14 OCR: English + Hindi on device (ML Kit), text preview, copy, search, TXT and DOCX export, searchable PDF via backend.
- FR-PDF-15 Drawn signatures placed on chosen pages with move/resize; explicitly *not* a cryptographic digital signature.

### 2.4 Handwritten notes
- FR-HW-1 Dedicated home entry “Handwritten Notes to PDF”; camera, multi-page, gallery and PDF inputs.
- FR-HW-2 Enhancement (crop, perspective, rotate, contrast, brightness, grayscale, B/W, shadow reduction) with before/after; originals kept.
- FR-HW-3 Recognition per page with language choice, review and manual correction. On-device engine is a printed-text OCR and
  is labelled as such; cloud handwriting recognition (vision model) requires explicit upload consent.
- FR-HW-4 Outputs: (A) scanned-image PDF, (B) text PDF, (C) DOCX, (D) AI-organised notes (headings, bullets, summary,
  revision notes, Q&A, flashcards, translate, grammar) shown as AI-generated with a preview before export.

### 2.5 AI document creator
- FR-AI-1 Document types: letter, leave application, resume, cover letter, business report, minutes, study notes, summary,
  project report, proposal, invoice, formal application, notice, general correspondence; or free prompt.
- FR-AI-2 Language and style selection; generate, edit, refine/regenerate, export PDF/DOCX.
- FR-AI-3 Provider-independent backend adapter; timeouts, input/output size caps, rate limits, validation.
- FR-AI-4 Missing provider credentials yield a clear configuration error, never placeholder text.
- FR-AI-5 Editor: text editing, headings, lists, undo/redo, preview, export. Not tied to the AI provider.
- FR-AI-6 Disclaimer that AI output for legal/medical/financial matters is not professional advice.

### 2.6 Backend
- FR-BE-1 Versioned REST API under `/api/v1`, OpenAPI at `/api/docs`.
- FR-BE-2 Anonymous client tokens (no accounts) binding jobs to a client; jobs invisible to other clients.
- FR-BE-3 Job lifecycle QUEUED→VALIDATING→PROCESSING→FINALIZING→COMPLETED/FAILED/CANCELLED/EXPIRED; COMPLETED only after
  output validation.
- FR-BE-4 Operations: DOCX/DOC/ODT/RTF→PDF, PDF→DOCX, OCR (TXT/searchable PDF), handwriting recognition, PDF compression.
- FR-BE-5 Temporary storage (local disk or S3-compatible), retention sweep, deletion endpoint.
- FR-BE-6 Configurable limits: file size, pages, concurrency, AI requests/day, timeouts.
- FR-BE-7 Health/readiness endpoints, structured logs without document content or secrets.

## 3. Non-functional requirements
- NFR-PERF-1 No CPU-heavy work on the main thread; bounded concurrency for PDF/OCR/image work.
- NFR-PERF-2 PDF pages rendered on demand with a small bitmap cache; images decoded with sampling.
- NFR-PERF-3 Baselines recorded before targets are set (see `TESTING_PLAN.md` §6).
- NFR-SEC-1..n See `SECURITY_PLAN.md`.
- NFR-A11Y-1 Content descriptions, 48dp touch targets, font scaling, TalkBack-navigable headings.
- NFR-L10N-1 No hardcoded UI text in composables; Hindi strings reviewed for clipping and wrapping.
- NFR-MAINT-1 Modular Gradle build; pure-Kotlin logic separated from Android so it is unit-testable on the JVM.

## 4. Out of scope for the first release
Accounts and cloud sync, permanent cloud storage, payments, ads, cryptographic (PAdES) signatures, editing embedded PDF text
in place, iOS/web clients.
