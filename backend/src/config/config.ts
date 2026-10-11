import { z } from 'zod';

const bool = z
  .enum(['true', 'false', '1', '0'])
  .transform((v) => v === 'true' || v === '1');

const schema = z
  .object({
    NODE_ENV: z.enum(['development', 'test', 'production']).default('development'),
    ROLE: z.enum(['api', 'worker', 'all']).default('all'),
    PORT: z.coerce.number().int().positive().default(8080),
    TRUST_PROXY: bool.default('false'),
    LOG_LEVEL: z.enum(['fatal', 'error', 'warn', 'info', 'debug', 'trace', 'silent']).default('info'),
    CORS_ORIGINS: z.string().default(''),

    DATABASE_URL: z.string().min(1),
    DATABASE_POOL_MAX: z.coerce.number().int().positive().default(10),
    REDIS_URL: z.string().min(1),
    QUEUE_PREFIX: z.string().default('omd'),

    TOKEN_SECRET: z.string().min(32, 'TOKEN_SECRET must be at least 32 characters'),
    TOKEN_TTL_DAYS: z.coerce.number().int().positive().default(30),

    STORAGE_DRIVER: z.enum(['local', 's3']).default('local'),
    STORAGE_LOCAL_DIR: z.string().default('./storage-data'),
    S3_BUCKET: z.string().optional(),
    S3_REGION: z.string().default('us-east-1'),
    S3_ENDPOINT: z.string().optional(),
    S3_FORCE_PATH_STYLE: bool.default('false'),
    WORK_DIR: z.string().default('/tmp/omd-work'),

    MAX_UPLOAD_MB: z.coerce.number().positive().default(50),
    MAX_FILES_PER_JOB: z.coerce.number().int().positive().default(20),
    MAX_PAGES: z.coerce.number().int().positive().default(300),
    JOB_TIMEOUT_SECONDS: z.coerce.number().int().positive().default(180),
    WORKER_CONCURRENCY: z.coerce.number().int().positive().default(2),
    OUTPUT_TTL_MINUTES: z.coerce.number().int().positive().default(60),
    SWEEP_INTERVAL_SECONDS: z.coerce.number().int().positive().default(60),

    RATE_TOKENS_PER_IP_HOUR: z.coerce.number().int().positive().default(20),
    RATE_JOBS_PER_CLIENT_HOUR: z.coerce.number().int().positive().default(30),
    AI_REQUESTS_PER_CLIENT_DAY: z.coerce.number().int().positive().default(50),
    HANDWRITING_PAGES_PER_CLIENT_DAY: z.coerce.number().int().positive().default(60),

    AI_PROVIDER: z.enum(['anthropic', 'none']).default('anthropic'),
    ANTHROPIC_API_KEY: z.string().optional(),
    ANTHROPIC_AUTH_TOKEN: z.string().optional(),
    ANTHROPIC_BASE_URL: z.string().optional(),
    AI_MODEL: z.string().default('claude-opus-5-5'),
    AI_EFFORT: z.enum(['low', 'medium', 'high', 'xhigh', 'max']).default('medium'),
    AI_REFUSAL_FALLBACK: bool.default('true'),
    AI_MAX_INPUT_CHARS: z.coerce.number().int().positive().default(20000),
    AI_MAX_OUTPUT_TOKENS: z.coerce.number().int().positive().default(8000),
    AI_TIMEOUT_SECONDS: z.coerce.number().int().positive().default(120),

    SOFFICE_BIN: z.string().default('soffice'),
    TESSERACT_BIN: z.string().default('tesseract'),
    PDFTOPPM_BIN: z.string().default('pdftoppm'),
    PDFTOTEXT_BIN: z.string().default('pdftotext'),
    QPDF_BIN: z.string().default('qpdf'),
    GS_BIN: z.string().default('gs'),
    PYTHON_BIN: z.string().default('python3'),
    /**
     * Licensing (docs/DEPENDENCY_REGISTER.md, ADR-0007): the defaults use no AGPL component.
     * `text` (poppler + python-docx) rebuilds headings and paragraphs, with correct Devanagari, but no tables/images.
     * `pdf2docx` gives the best results (tables, images) but depends on PyMuPDF (AGPL-3.0 or a commercial Artifex
     * licence). `libreoffice` (MPL-2.0) produces positioned text boxes and mis-orders Devanagari, so it is not advised
     * for Hindi documents.
     */
    PDF_TO_DOCX_ENGINE: z.enum(['text', 'pdf2docx', 'libreoffice']).default('text'),
    /** `pikepdf` (MPL-2.0 + Apache-2.0 qpdf) is the default; `ghostscript` (AGPL-3.0) also subsets fonts. */
    COMPRESS_ENGINE: z.enum(['pikepdf', 'ghostscript']).default('pikepdf'),
  })
  .superRefine((c, ctx) => {
    if (c.STORAGE_DRIVER === 's3' && !c.S3_BUCKET) {
      ctx.addIssue({ code: z.ZodIssueCode.custom, path: ['S3_BUCKET'], message: 'required when STORAGE_DRIVER=s3' });
    }
    if (c.NODE_ENV === 'production' && /change-me|example|test/i.test(c.TOKEN_SECRET)) {
      ctx.addIssue({ code: z.ZodIssueCode.custom, path: ['TOKEN_SECRET'], message: 'looks like a placeholder' });
    }
  });

export type AppConfig = z.infer<typeof schema> & {
  maxUploadBytes: number;
  aiConfigured: boolean;
  corsOrigins: string[];
};

export const APP_CONFIG = Symbol('APP_CONFIG');

/** Parses and validates environment configuration; throws with every problem listed (never echoing secret values). */
export function loadConfig(env: NodeJS.ProcessEnv = process.env): AppConfig {
  const parsed = schema.safeParse(env);
  if (!parsed.success) {
    const problems = parsed.error.issues.map((i) => `${i.path.join('.')}: ${i.message}`).join('; ');
    throw new Error(`Invalid configuration: ${problems}`);
  }
  const c = parsed.data;
  return {
    ...c,
    maxUploadBytes: Math.floor(c.MAX_UPLOAD_MB * 1024 * 1024),
    aiConfigured: c.AI_PROVIDER === 'anthropic' && Boolean(c.ANTHROPIC_API_KEY || c.ANTHROPIC_AUTH_TOKEN),
    corsOrigins: c.CORS_ORIGINS.split(',').map((s) => s.trim()).filter(Boolean),
  };
}
