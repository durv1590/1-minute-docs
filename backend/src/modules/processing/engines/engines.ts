import { copyFile, mkdir, readdir, readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import type { AppConfig } from '../../../config/config';
import { ProcessingError, run } from './exec';
import { mergePdfs, PdfTools } from './pdf-tools';

export interface EngineContext {
  config: AppConfig;
  workDir: string;
  signal: AbortSignal;
  /** Remaining time budget for this job in ms. */
  timeLeft(): number;
  progress(percent: number): Promise<void>;
}

const SCRIPTS_DIR = join(__dirname, '..', '..', '..', '..', 'scripts');

export function pdfBins(c: AppConfig): PdfTools {
  return { qpdf: c.QPDF_BIN, pdftotext: c.PDFTOTEXT_BIN, pdftoppm: c.PDFTOPPM_BIN };
}

/** Office document → PDF with LibreOffice in an isolated, per-job user profile. */
export async function officeToPdf(ctx: EngineContext, input: string, ext: string): Promise<string> {
  const src = join(ctx.workDir, `source.${ext}`);
  await copyFile(input, src);
  const outDir = join(ctx.workDir, 'lo-out');
  await mkdir(outDir, { recursive: true });
  const res = await run(
    ctx.config.SOFFICE_BIN,
    [
      '--headless', '--norestore', '--nolockcheck', '--nodefault', '--nologo', '--nofirststartwizard',
      `-env:UserInstallation=file://${join(ctx.workDir, 'lo-profile')}`,
      '--convert-to', 'pdf:writer_pdf_Export', '--outdir', outDir, src,
    ],
    { cwd: ctx.workDir, timeoutMs: ctx.timeLeft(), signal: ctx.signal },
  );
  const produced = (await readdir(outDir)).find((f) => f.endsWith('.pdf'));
  if (res.code !== 0 || !produced) {
    throw new ProcessingError('CONVERSION_FAILED', 'The document could not be converted. It may be damaged or use unsupported features.');
  }
  return join(outDir, produced);
}

/** Text-based PDF → DOCX with pdf2docx (layout, tables, images). */
export async function pdfToDocxLayout(ctx: EngineContext, input: string): Promise<{ path: string; warnings: string[] }> {
  const out = join(ctx.workDir, 'converted.docx');
  const res = await run(ctx.config.PYTHON_BIN, [join(SCRIPTS_DIR, 'pdf_to_docx.py'), input, out], {
    cwd: ctx.workDir,
    timeoutMs: ctx.timeLeft(),
    signal: ctx.signal,
  });
  if (res.code !== 0) throw new ProcessingError('CONVERSION_FAILED', 'PDF to Word conversion failed for this document.');
  const report = parseJsonLine(res.stdout);
  return { path: out, warnings: Array.isArray(report?.warnings) ? (report.warnings as string[]) : [] };
}

/** Text-based PDF → DOCX from poppler's text blocks (headings and paragraphs; tables/images not kept). */
export async function pdfToDocxText(ctx: EngineContext, input: string): Promise<{ path: string; warnings: string[] }> {
  const bbox = join(ctx.workDir, 'text-bbox.xhtml');
  const extract = await run(ctx.config.PDFTOTEXT_BIN, ['-bbox-layout', '-enc', 'UTF-8', input, bbox], {
    cwd: ctx.workDir,
    timeoutMs: ctx.timeLeft(),
    signal: ctx.signal,
  });
  if (extract.code !== 0) throw new ProcessingError('CONVERSION_FAILED', 'PDF to Word conversion failed for this document.');
  const out = join(ctx.workDir, 'converted.docx');
  const res = await run(ctx.config.PYTHON_BIN, [join(SCRIPTS_DIR, 'pdf_text_to_docx.py'), bbox, out], {
    cwd: ctx.workDir,
    timeoutMs: ctx.timeLeft(),
    signal: ctx.signal,
  });
  if (res.code !== 0) throw new ProcessingError('CONVERSION_FAILED', 'PDF to Word conversion failed for this document.');
  return { path: out, warnings: ['TABLES_AND_IMAGES_NOT_PRESERVED'] };
}

/** Text-based PDF → DOCX with LibreOffice's PDF import (text is placed in positioned text boxes). */
export async function pdfToDocxLibreOffice(ctx: EngineContext, input: string): Promise<{ path: string; warnings: string[] }> {
  const src = join(ctx.workDir, 'source.pdf');
  await copyFile(input, src);
  const outDir = join(ctx.workDir, 'lo-out');
  await mkdir(outDir, { recursive: true });
  const res = await run(
    ctx.config.SOFFICE_BIN,
    [
      '--headless', '--norestore', '--nolockcheck', '--nodefault', '--nologo', '--nofirststartwizard',
      `-env:UserInstallation=file://${join(ctx.workDir, 'lo-profile')}`,
      '--infilter=writer_pdf_import', '--convert-to', 'docx:MS Word 2007 XML', '--outdir', outDir, src,
    ],
    { cwd: ctx.workDir, timeoutMs: ctx.timeLeft(), signal: ctx.signal },
  );
  const produced = (await readdir(outDir)).find((f) => f.endsWith('.docx'));
  if (res.code !== 0 || !produced) throw new ProcessingError('CONVERSION_FAILED', 'PDF to Word conversion failed for this document.');
  return { path: join(outDir, produced), warnings: ['POSITIONED_TEXT_BOXES'] };
}

/** Renders PDF pages to PNG at [dpi]. */
export async function rasterizePdf(ctx: EngineContext, input: string, dpi = 300): Promise<string[]> {
  const dir = join(ctx.workDir, 'pages');
  await mkdir(dir, { recursive: true });
  const res = await run(ctx.config.PDFTOPPM_BIN, ['-r', String(dpi), '-gray', '-png', input, join(dir, 'p')], {
    cwd: ctx.workDir,
    timeoutMs: ctx.timeLeft(),
    signal: ctx.signal,
  });
  if (res.code !== 0) throw new ProcessingError('CORRUPT_FILE', 'Pages could not be rendered for OCR.');
  return (await readdir(dir)).filter((f) => f.endsWith('.png')).sort(naturalCompare).map((f) => join(dir, f));
}

/**
 * OCR of page images with Tesseract. Returns per-page text and, if requested, a searchable PDF (image + invisible text).
 */
export async function ocrPages(
  ctx: EngineContext,
  images: string[],
  language: string,
  wantPdf: boolean,
  progressRange: [number, number] = [5, 95],
): Promise<{ texts: string[]; pdf?: string }> {
  const texts: string[] = [];
  const pdfs: string[] = [];
  for (const [i, img] of images.entries()) {
    const base = join(ctx.workDir, `ocr-${String(i).padStart(4, '0')}`);
    const configs = wantPdf ? ['txt', 'pdf'] : ['txt'];
    const res = await run(ctx.config.TESSERACT_BIN, [img, base, '-l', language, '--psm', '3', ...configs], {
      cwd: ctx.workDir,
      timeoutMs: ctx.timeLeft(),
      signal: ctx.signal,
    });
    if (res.code !== 0) {
      throw new ProcessingError(/Failed loading language/i.test(res.stderrTail) ? 'SERVICE_UNAVAILABLE' : 'CONVERSION_FAILED', 'Text recognition failed');
    }
    texts.push((await readFile(`${base}.txt`, 'utf8')).replace(/\f/g, '').trimEnd());
    if (wantPdf) pdfs.push(`${base}.pdf`);
    const [from, to] = progressRange;
    await ctx.progress(from + ((to - from) * (i + 1)) / images.length);
  }
  if (!wantPdf) return { texts };
  const merged = join(ctx.workDir, 'searchable.pdf');
  if (pdfs.length === 1) await copyFile(pdfs[0], merged);
  else await mergePdfs(ctx.config.QPDF_BIN, pdfs, merged, ctx.workDir, ctx.timeLeft(), ctx.signal);
  return { texts, pdf: merged };
}

/** Builds a DOCX from OCR'd page texts (paragraphs + page breaks) for scanned PDFs. */
export async function textPagesToDocx(ctx: EngineContext, pages: string[]): Promise<string> {
  const json = join(ctx.workDir, 'pages.json');
  await writeFile(json, JSON.stringify({ pages }), 'utf8');
  const out = join(ctx.workDir, 'ocr.docx');
  const res = await run(ctx.config.PYTHON_BIN, [join(SCRIPTS_DIR, 'text_to_docx.py'), json, out], {
    cwd: ctx.workDir,
    timeoutMs: ctx.timeLeft(),
    signal: ctx.signal,
  });
  if (res.code !== 0) throw new ProcessingError('CONVERSION_FAILED', 'Could not build the Word document');
  return out;
}

const GS_PRESETS: Record<string, string[]> = {
  high_quality: ['-dPDFSETTINGS=/printer', '-dColorImageResolution=200', '-dGrayImageResolution=200'],
  balanced: ['-dPDFSETTINGS=/ebook', '-dColorImageResolution=144', '-dGrayImageResolution=144'],
  small_file: ['-dPDFSETTINGS=/screen', '-dColorImageResolution=96', '-dGrayImageResolution=96'],
};

/** Compresses with the configured engine; returns the output path and the engine used. */
export async function compressPdf(ctx: EngineContext, input: string, preset: string): Promise<{ path: string; engine: string }> {
  return ctx.config.COMPRESS_ENGINE === 'ghostscript'
    ? { path: await compressPdfGhostscript(ctx, input, preset), engine: 'ghostscript' }
    : { path: await compressPdfPikepdf(ctx, input, preset), engine: 'pikepdf' };
}

/** Image downsampling/re-encoding plus stream recompression with pikepdf; no AGPL code. */
async function compressPdfPikepdf(ctx: EngineContext, input: string, preset: string): Promise<string> {
  const out = join(ctx.workDir, 'compressed.pdf');
  const res = await run(ctx.config.PYTHON_BIN, [join(SCRIPTS_DIR, 'compress_pdf.py'), input, out, preset], {
    cwd: ctx.workDir,
    timeoutMs: ctx.timeLeft(),
    signal: ctx.signal,
  });
  if (res.code !== 0) throw new ProcessingError('CONVERSION_FAILED', 'This PDF could not be compressed.');
  return out;
}

/** Ghostscript re-distillation: recompresses/downsamples images, subsets fonts; text stays text. */
async function compressPdfGhostscript(ctx: EngineContext, input: string, preset: string): Promise<string> {
  const out = join(ctx.workDir, 'compressed.pdf');
  const res = await run(
    ctx.config.GS_BIN,
    [
      '-dSAFER', '-dBATCH', '-dNOPAUSE', '-dQUIET', '-sDEVICE=pdfwrite', '-dCompatibilityLevel=1.6',
      '-dDetectDuplicateImages=true', '-dCompressFonts=true', '-dSubsetFonts=true',
      '-dDownsampleColorImages=true', '-dDownsampleGrayImages=true', '-dColorImageDownsampleThreshold=1.1',
      '-dGrayImageDownsampleThreshold=1.1',
      ...(GS_PRESETS[preset] ?? GS_PRESETS.balanced),
      `-sOutputFile=${out}`, input,
    ],
    { cwd: ctx.workDir, timeoutMs: ctx.timeLeft(), signal: ctx.signal },
  );
  if (res.code !== 0) throw new ProcessingError('CONVERSION_FAILED', 'This PDF could not be compressed.');
  return out;
}

function parseJsonLine(stdout: string): Record<string, unknown> | null {
  const line = stdout.trim().split('\n').reverse().find((l) => l.startsWith('{'));
  if (!line) return null;
  try {
    return JSON.parse(line) as Record<string, unknown>;
  } catch {
    return null;
  }
}

function naturalCompare(a: string, b: string): number {
  return a.localeCompare(b, undefined, { numeric: true });
}
