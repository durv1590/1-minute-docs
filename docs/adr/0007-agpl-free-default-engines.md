# ADR-0007: Default server engines contain no AGPL code
Status: Accepted · 2026-10-11 (supersedes the "decision required" note for PyMuPDF/Ghostscript)

Context: The best PDF→Word engine available (pdf2docx) depends on PyMuPDF, and the original compression engine was
Ghostscript. Both are AGPL-3.0 unless a commercial licence is bought from Artifex. The company had not yet decided to meet
AGPL obligations or buy a licence, and the product must not depend on that decision to launch.

Decision:
- `PDF_TO_DOCX_ENGINE` defaults to `text`: poppler `pdftotext -bbox-layout` (GPL, unmodified, separate process) → our
  `scripts/pdf_text_to_docx.py` (python-docx, MIT). It rebuilds headings and paragraphs and keeps Devanagari in the correct
  order. Tables, images and exact positions are not reconstructed, and the job reports `TABLES_AND_IMAGES_NOT_PRESERVED`.
- `COMPRESS_ENGINE` defaults to `pikepdf`: `scripts/compress_pdf.py` (pikepdf MPL-2.0 on qpdf Apache-2.0, Pillow HPND)
  downsamples images to the preset DPI, re-encodes them as JPEG when that is ≥5% smaller, and recompresses streams.
- `pdf2docx` and `ghostscript` stay available as opt-in engines. They are installed in the Docker image only with
  `--build-arg WITH_AGPL_ENGINES=true`. Before enabling either, the company needs AGPL compliance or a commercial licence.
- `libreoffice` remains an option for PDF→Word. LibreOffice's PDF import outputs Devanagari in visual glyph order
  ("नमस्ते" → "नम्सेत", measured in the e2e suite), so it is not advised for a Hindi audience.

Consequences: The default image has no AGPL components. PDF→Word quality is lower than pdf2docx for documents with
tables. Ghostscript also subsets fonts, which pikepdf does not, so text-heavy PDFs shrink less. All four engines are covered
by backend e2e tests (`api.e2e-spec.ts`).
