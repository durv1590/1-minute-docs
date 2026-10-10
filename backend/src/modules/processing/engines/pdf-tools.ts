import { readFile } from 'node:fs/promises';
import { ProcessingError, run } from './exec';

export interface PdfTools {
  qpdf: string;
  pdftotext: string;
  pdftoppm: string;
}

/** Page count via qpdf; rejects password-protected and unreadable PDFs with specific codes. */
export async function inspectPdf(bins: PdfTools, path: string, cwd: string, timeoutMs: number, signal?: AbortSignal): Promise<{ pages: number }> {
  const enc = await run(bins.qpdf, ['--requires-password', path], { cwd, timeoutMs, signal });
  // qpdf: 0 = a password is required to open, 2 = not encrypted, 3 = encrypted but opens without password.
  if (enc.code === 0) throw new ProcessingError('ENCRYPTED_DOCUMENT', 'The PDF is password protected. Remove the password on your device first.');
  const res = await run(bins.qpdf, ['--show-npages', path], { cwd, timeoutMs, signal });
  const pages = Number.parseInt(res.stdout.trim(), 10);
  if (res.code !== 0 && res.code !== 3 || !Number.isFinite(pages) || pages < 1) {
    throw new ProcessingError('CORRUPT_FILE', 'The PDF could not be read. It may be damaged.');
  }
  return { pages };
}

/** Average extractable text characters per page over the first [maxPages] pages — distinguishes scans from text PDFs. */
export async function textDensity(bins: PdfTools, path: string, cwd: string, timeoutMs: number, maxPages: number, signal?: AbortSignal): Promise<number> {
  const res = await run(bins.pdftotext, ['-l', String(maxPages), '-enc', 'UTF-8', path, '-'], { cwd, timeoutMs, signal });
  if (res.code !== 0) return 0;
  const pages = res.stdout.split('\f').filter((_, i, arr) => i < arr.length - 1 || arr.length === 1);
  const chars = res.stdout.replace(/\s+/g, '').length;
  return chars / Math.max(1, Math.min(maxPages, pages.length));
}

/** Validates that [path] is a readable PDF with at least one page. */
export async function validatePdf(bins: PdfTools, path: string, cwd: string, timeoutMs: number): Promise<number> {
  const head = (await readFile(path)).subarray(0, 1024).toString('latin1');
  if (!head.includes('%PDF-')) throw new ProcessingError('CONVERSION_FAILED', 'Engine did not produce a PDF');
  const res = await run(bins.qpdf, ['--show-npages', path], { cwd, timeoutMs });
  const pages = Number.parseInt(res.stdout.trim(), 10);
  if (!Number.isFinite(pages) || pages < 1) throw new ProcessingError('CONVERSION_FAILED', 'Output PDF failed validation');
  return pages;
}

export async function mergePdfs(qpdf: string, inputs: string[], output: string, cwd: string, timeoutMs: number, signal?: AbortSignal): Promise<void> {
  const res = await run(qpdf, ['--empty', '--pages', ...inputs, '--', output], { cwd, timeoutMs, signal });
  if (res.code !== 0 && res.code !== 3) throw new ProcessingError('CONVERSION_FAILED', 'Could not assemble PDF pages');
}
