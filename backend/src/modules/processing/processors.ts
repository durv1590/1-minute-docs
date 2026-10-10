import { readFile, stat, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { replaceExtension } from '../../common/filenames';
import { AiProvider, AiProviderError } from '../ai/ai-provider';
import { HANDWRITING_SYSTEM, handwritingInstructions } from '../ai/prompts';
import { detectFormat } from '../files/file-inspector';
import type { Operation } from '../jobs/operations';
import { compressPdf, EngineContext, ocrPages, officeToPdf, pdfBins, pdfToDocxLayout, pdfToDocxLibreOffice, rasterizePdf, textPagesToDocx } from './engines/engines';
import { ProcessingError } from './engines/exec';
import { inspectPdf, textDensity, validatePdf } from './engines/pdf-tools';

export interface ProcessorInput {
  path: string;
  displayName: string;
  format: string;
}

export interface ProcessorResult {
  outputPath: string;
  outputName: string;
  meta: Record<string, unknown>;
}

/** Below this many extractable characters per page a PDF is treated as scanned (image-only). */
const SCANNED_CHARS_PER_PAGE = 25;

export interface Processor {
  /** Fast checks before heavy work (page limits, encryption). */
  validate(ctx: EngineContext, inputs: ProcessorInput[]): Promise<void>;
  run(ctx: EngineContext, inputs: ProcessorInput[], options: Record<string, unknown>): Promise<ProcessorResult>;
}

async function checkPdf(ctx: EngineContext, input: ProcessorInput): Promise<number> {
  const { pages } = await inspectPdf(pdfBins(ctx.config), input.path, ctx.workDir, ctx.timeLeft(), ctx.signal);
  if (pages > ctx.config.MAX_PAGES) {
    throw new ProcessingError('TOO_MANY_PAGES', `This document has ${pages} pages; the limit is ${ctx.config.MAX_PAGES}.`);
  }
  return pages;
}

const pdfValidator = async (ctx: EngineContext, inputs: ProcessorInput[]) => {
  for (const i of inputs) if (i.format === 'pdf') await checkPdf(ctx, i);
};

export function processors(ai: AiProvider | null): Record<Operation, Processor> {
  return {
    office_to_pdf: {
      validate: async () => undefined,
      async run(ctx, [input]) {
        await ctx.progress(10);
        const out = await officeToPdf(ctx, input.path, input.format);
        await ctx.progress(90);
        const pages = await validatePdf(pdfBins(ctx.config), out, ctx.workDir, ctx.timeLeft());
        if (pages > ctx.config.MAX_PAGES) throw new ProcessingError('TOO_MANY_PAGES', `The converted document has ${pages} pages; the limit is ${ctx.config.MAX_PAGES}.`);
        return { outputPath: out, outputName: replaceExtension(input.displayName, 'pdf'), meta: { pages, engine: 'libreoffice' } };
      },
    },

    pdf_to_docx: {
      validate: pdfValidator,
      async run(ctx, [input], options) {
        const bins = pdfBins(ctx.config);
        const pages = await checkPdf(ctx, input);
        const density = await textDensity(bins, input.path, ctx.workDir, ctx.timeLeft(), Math.min(pages, 5), ctx.signal);
        await ctx.progress(10);
        if (density < SCANNED_CHARS_PER_PAGE) {
          // Scanned PDF: recognise text, then build an editable document from it (layout is not reconstructed).
          const images = await rasterizePdf(ctx, input.path, 300);
          const { texts } = await ocrPages(ctx, images, String(options.ocrLanguage ?? 'eng+hin'), false, [15, 85]);
          const out = await textPagesToDocx(ctx, texts);
          return {
            outputPath: out,
            outputName: replaceExtension(input.displayName, 'docx'),
            meta: { pages, mode: 'ocr_text', engine: 'tesseract', warnings: ['SCANNED_LAYOUT_NOT_PRESERVED'], recognizedChars: texts.join('').replace(/\s/g, '').length },
          };
        }
        const engine = ctx.config.PDF_TO_DOCX_ENGINE;
        const { path, warnings } = engine === 'pdf2docx' ? await pdfToDocxLayout(ctx, input.path) : await pdfToDocxLibreOffice(ctx, input.path);
        await ctx.progress(90);
        return {
          outputPath: path,
          outputName: replaceExtension(input.displayName, 'docx'),
          meta: { pages, mode: 'layout', engine, warnings: ['LAYOUT_APPROXIMATE', ...warnings] },
        };
      },
    },

    ocr: {
      validate: pdfValidator,
      async run(ctx, [input], options) {
        const wantPdf = options.output === 'pdf';
        const images = input.format === 'pdf' ? await rasterizePdf(ctx, input.path, 300) : [input.path];
        await ctx.progress(10);
        const { texts, pdf } = await ocrPages(ctx, images, String(options.language ?? 'eng+hin'), wantPdf, [10, 90]);
        const chars = texts.join('').replace(/\s/g, '').length;
        const meta = { pages: images.length, engine: 'tesseract', language: options.language, recognizedChars: chars, warnings: chars === 0 ? ['NO_TEXT_FOUND'] : [] };
        if (wantPdf && pdf) {
          return { outputPath: pdf, outputName: replaceExtension(input.displayName, 'pdf').replace(/\.pdf$/, '_searchable.pdf'), meta };
        }
        const txt = join(ctx.workDir, 'ocr.txt');
        await writeFile(txt, texts.join('\n\f\n'), 'utf8');
        return { outputPath: txt, outputName: replaceExtension(input.displayName, 'txt'), meta };
      },
    },

    compress_pdf: {
      validate: pdfValidator,
      async run(ctx, [input], options) {
        const preset = String(options.preset ?? 'balanced');
        await ctx.progress(10);
        const out = await compressPdf(ctx, input.path, preset);
        await ctx.progress(90);
        const pages = await validatePdf(pdfBins(ctx.config), out, ctx.workDir, ctx.timeLeft());
        const originalBytes = (await stat(input.path)).size;
        const outputBytes = (await stat(out)).size;
        return {
          outputPath: out,
          outputName: replaceExtension(input.displayName, 'pdf').replace(/\.pdf$/, '_compressed.pdf'),
          meta: {
            pages,
            preset,
            engine: 'ghostscript',
            originalBytes,
            outputBytes,
            reductionPercent: Math.round(((originalBytes - outputBytes) * 1000) / originalBytes) / 10,
            notSmaller: outputBytes >= originalBytes,
          },
        };
      },
    },

    handwriting_recognize: {
      validate: async () => undefined,
      async run(ctx, inputs, options) {
        if (!ai) throw new ProcessingError('AI_NOT_CONFIGURED', 'Handwriting recognition is not configured on this server');
        const pages: string[] = [];
        let truncated = false;
        let model = ai.model;
        for (const [i, input] of inputs.entries()) {
          const fmt = await detectFormat(input.path);
          const mediaType = fmt === 'png' ? 'image/png' : fmt === 'webp' ? 'image/webp' : 'image/jpeg';
          const bytes = await readFile(input.path);
          try {
            const res = await ai.transcribeImage({
              system: HANDWRITING_SYSTEM,
              instructions: handwritingInstructions((options.language as 'en' | 'hi' | 'auto') ?? 'auto'),
              imageBase64: bytes.toString('base64'),
              mediaType,
              maxOutputTokens: 4000,
              signal: ctx.signal,
            });
            if (res.refused) throw new ProcessingError('AI_REFUSED', 'The AI declined to transcribe one of the pages');
            truncated ||= res.truncated;
            model = res.model;
            pages.push(res.text.trim() === '[no text found]' ? '' : res.text);
          } catch (e) {
            if (e instanceof AiProviderError) {
              if (e.kind === 'CANCELLED') throw new ProcessingError('CANCELLED', 'Cancelled');
              const retryable = e.kind === 'RATE_LIMITED' || e.kind === 'UNAVAILABLE' || e.kind === 'TIMEOUT';
              throw new ProcessingError(e.kind === 'TIMEOUT' ? 'TIMEOUT' : 'AI_PROVIDER_ERROR', e.message, retryable);
            }
            throw e;
          }
          await ctx.progress(5 + (90 * (i + 1)) / inputs.length);
        }
        const out = join(ctx.workDir, 'handwriting.txt');
        await writeFile(out, pages.join('\n\f\n'), 'utf8');
        return {
          outputPath: out,
          outputName: 'handwritten-notes.txt',
          meta: {
            pages: inputs.length,
            engine: ai.name,
            model,
            aiGenerated: true,
            pageSeparator: '\f',
            warnings: [...(truncated ? ['TRUNCATED'] : []), ...(pages.some((p) => p === '') ? ['EMPTY_PAGES'] : [])],
          },
        };
      },
    },
  };
}

/** Confirms the output file is what the operation promised before the job may be marked COMPLETED. */
export async function validateOutput(ctx: EngineContext, ext: string, path: string): Promise<void> {
  const size = (await stat(path)).size;
  if (ext === 'pdf') {
    await validatePdf(pdfBins(ctx.config), path, ctx.workDir, Math.max(10_000, ctx.timeLeft()));
  } else if (ext === 'docx') {
    if ((await detectFormat(path)) !== 'docx') throw new ProcessingError('CONVERSION_FAILED', 'Engine did not produce a valid Word document');
  } else if (ext === 'txt') {
    const text = await readFile(path);
    if (!isValidUtf8(text)) throw new ProcessingError('CONVERSION_FAILED', 'Recognised text is not valid UTF-8');
  }
  if (size > 500 * 1024 * 1024) throw new ProcessingError('CONVERSION_FAILED', 'Output is unexpectedly large');
}

function isValidUtf8(buf: Buffer): boolean {
  try {
    new TextDecoder('utf-8', { fatal: true }).decode(buf);
    return true;
  } catch {
    return false;
  }
}
