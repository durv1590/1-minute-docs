# Backend baseline (measured)

Raw data: `backend-baseline.json` (re-run with `cd backend && npm run bench`; needs Postgres, Redis and the engines).
Measured 2026-10-11 on the development container: 4 vCPU Intel Xeon @ 2.8 GHz, 16 GiB RAM, Node 22, worker concurrency 1,
local storage driver. Times run from upload through the API, queue, engine and output validation to `COMPLETED`, plus the
download. Each figure is the median of 3 runs. Polling granularity is 100 ms.

**What these numbers are not:** they are not production numbers (different hardware, S3 storage and network latency) and
not device numbers. The compression input is a synthetic gradient-and-noise image, so its reduction ratios are higher
than real scans would get. Use them to compare engines and to spot regressions.

## Job durations

| Job | Median | Notes |
|---|---|---|
| DOCX → PDF, 10 pages (LibreOffice) | 2.0 s | includes LibreOffice cold start per job |
| PDF → DOCX, 10 pages, `text` engine (default) | 0.46 s | |
| PDF → DOCX, 10 pages, `pdf2docx` (opt-in, AGPL) | 2.3 s | |
| PDF → DOCX, 10 pages, `libreoffice` | 8.0 s | |
| Scanned page → DOCX via OCR (eng+hin, 300 dpi) | 6.7 s / page | dominated by Tesseract with two languages |
| Compress 9.3 MB image page, `pikepdf` balanced / small | 1.1 s / 1.0 s | → 65 KB / 11 KB |
| Compress same, `ghostscript` balanced / small | 1.5 s / 1.3 s | → 72 KB / 36 KB |
| OCR one image page, single language | 0.45–0.56 s (150 dpi), 0.97–1.1 s (300 dpi) | |

## OCR character error rate (Tesseract 5, clean rendered pages)

The text was rendered with LibreOffice and rasterised, not photographed. These are best-case figures.

| Language | 14 px · 150 dpi | 20 px · 150 dpi | 14 px · 300 dpi | 20 px · 300 dpi |
|---|---|---|---|---|
| English | 0.0 % | 0.0 % | 0.0 % | 0.0 % |
| Hindi | 1.3 % | 1.7 % | 2.2 % | 1.7 % |

CER is the edit distance over Unicode code points with whitespace collapsed; matras count as characters.

## Still not measured
- Phone-camera photos, under real lighting, of printed and handwritten pages: the 30-page protocol in `TESTING_PLAN.md` §4.
  Handwriting recognition quality is not measured at all.
- On-device ML Kit OCR accuracy and speed, app start time, memory use and large-file behaviour on real phones.
