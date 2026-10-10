import { z } from 'zod';
import type { DetectedFormat } from '../files/file-inspector';

export const OCR_LANGUAGES = ['eng', 'hin', 'eng+hin'] as const;

export const OPERATIONS = {
  /** DOCX/DOC/ODT/RTF → PDF with LibreOffice. */
  office_to_pdf: {
    inputs: ['docx', 'doc', 'odt', 'rtf'] as DetectedFormat[],
    minFiles: 1,
    maxFiles: 1,
    options: z.object({}).strict(),
  },
  /** PDF → DOCX: layout reconstruction with pdf2docx; scanned PDFs are OCR'd first. */
  pdf_to_docx: {
    inputs: ['pdf'] as DetectedFormat[],
    minFiles: 1,
    maxFiles: 1,
    options: z.object({ ocrLanguage: z.enum(OCR_LANGUAGES).default('eng+hin') }).strict(),
  },
  /** Tesseract OCR of an image or PDF → plain text or searchable PDF. */
  ocr: {
    inputs: ['pdf', 'jpeg', 'png'] as DetectedFormat[],
    minFiles: 1,
    maxFiles: 1,
    options: z
      .object({ language: z.enum(OCR_LANGUAGES).default('eng+hin'), output: z.enum(['txt', 'pdf']).default('txt') })
      .strict(),
  },
  /** Ghostscript PDF optimisation. */
  compress_pdf: {
    inputs: ['pdf'] as DetectedFormat[],
    minFiles: 1,
    maxFiles: 1,
    options: z.object({ preset: z.enum(['high_quality', 'balanced', 'small_file']).default('balanced') }).strict(),
  },
  /** Handwriting transcription with the configured vision-capable AI provider (requires explicit consent). */
  handwriting_recognize: {
    inputs: ['jpeg', 'png', 'webp'] as DetectedFormat[],
    minFiles: 1,
    maxFiles: 20,
    /** Vision APIs reject very large images; the app downsamples pages before upload. */
    maxFileBytes: 5 * 1024 * 1024,
    options: z
      .object({
        language: z.enum(['en', 'hi', 'auto']).default('auto'),
        consent: z.literal(true, { errorMap: () => ({ message: 'consent must be true to upload notes for AI recognition' }) }),
      })
      .strict(),
  },
} as const;

export type Operation = keyof typeof OPERATIONS;

export function isOperation(v: string): v is Operation {
  return Object.prototype.hasOwnProperty.call(OPERATIONS, v);
}

export const OUTPUT_FORMATS: Record<Operation, (options: Record<string, unknown>) => { ext: string; contentType: string }> = {
  office_to_pdf: () => ({ ext: 'pdf', contentType: 'application/pdf' }),
  pdf_to_docx: () => ({ ext: 'docx', contentType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document' }),
  ocr: (o) => (o.output === 'pdf' ? { ext: 'pdf', contentType: 'application/pdf' } : { ext: 'txt', contentType: 'text/plain; charset=utf-8' }),
  compress_pdf: () => ({ ext: 'pdf', contentType: 'application/pdf' }),
  handwriting_recognize: () => ({ ext: 'txt', contentType: 'text/plain; charset=utf-8' }),
};
