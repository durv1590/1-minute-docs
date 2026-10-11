/**
 * Measures end-to-end job durations (upload → COMPLETED, through the real API, queue and engines) and Tesseract
 * character error rate on clean, synthetic printed pages. Writes docs/results/backend-baseline.json and prints a table.
 * The CER here is an upper bound on quality: pages are rendered, not photographed.
 */
import { INestApplication } from '@nestjs/common';
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs';
import { cpus, tmpdir, totalmem } from 'node:os';
import { join } from 'node:path';
import IORedis from 'ioredis';
import { Pool } from 'pg';
import request from 'supertest';
import { createApp } from '../../src/bootstrap';
import { AppConfig } from '../../src/config/config';
import { testConfig } from '../helpers';
import { Fixtures } from '../e2e/fixtures';

const RUNS = Number(process.env.BENCH_RUNS ?? 3);

const EN_TEXT =
  'The committee met on Monday to review the annual budget. Members agreed that spending on school libraries should rise by ' +
  'twelve percent, while travel costs must fall. The final report will be shared with every parent before the end of March.';
const HI_TEXT =
  'समिति ने सोमवार को वार्षिक बजट की समीक्षा के लिए बैठक की। सदस्यों ने सहमति दी कि स्कूल पुस्तकालयों पर खर्च बारह प्रतिशत ' +
  'बढ़ाया जाए, जबकि यात्रा का खर्च कम किया जाए। अंतिम रिपोर्ट मार्च के अंत से पहले हर अभिभावक के साथ साझा की जाएगी।';

function levenshtein(a: string[], b: string[]): number {
  let prev = Array.from({ length: b.length + 1 }, (_, i) => i);
  for (let i = 1; i <= a.length; i++) {
    const cur = [i];
    for (let j = 1; j <= b.length; j++) cur[j] = Math.min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
    prev = cur;
  }
  return prev[b.length];
}
/** CER over Unicode code points with whitespace collapsed (matras count as characters, as is usual for Devanagari). */
function cer(reference: string, hypothesis: string): number {
  const norm = (s: string) => [...s.normalize('NFC').replace(/\s+/g, ' ').trim()];
  const ref = norm(reference);
  return levenshtein(ref, norm(hypothesis)) / ref.length;
}
const median = (xs: number[]) => [...xs].sort((a, b) => a - b)[Math.floor(xs.length / 2)];

describe('backend baseline', () => {
  let config: AppConfig;
  let app: INestApplication;
  let http: ReturnType<typeof request>;
  let token: string;
  const fx = new Fixtures();
  const results: Record<string, unknown>[] = [];

  beforeAll(async () => {
    config = testConfig({
      STORAGE_LOCAL_DIR: mkdtempSync(join(tmpdir(), 'omd-bench-storage-')),
      WORK_DIR: mkdtempSync(join(tmpdir(), 'omd-bench-work-')),
      MAX_UPLOAD_MB: '50',
      RATE_JOBS_PER_CLIENT_HOUR: '10000',
      JOB_TIMEOUT_SECONDS: '600',
      WORKER_CONCURRENCY: '1',
    });
    const pool = new Pool({ connectionString: config.DATABASE_URL });
    await pool.query('DROP SCHEMA public CASCADE; CREATE SCHEMA public;');
    await pool.end();
    const redis = new IORedis(config.REDIS_URL);
    await redis.flushdb();
    redis.disconnect();
    app = await createApp(config);
    await app.init();
    http = request(app.getHttpServer());
    token = (await http.post('/api/v1/auth/anonymous').expect(201)).body.token;
  });

  afterAll(async () => {
    await app?.close();
    const out = join(__dirname, '../../../docs/results');
    mkdirSync(out, { recursive: true });
    const env = { cpus: cpus().length, cpuModel: cpus()[0]?.model, memGiB: Math.round(totalmem() / 2 ** 30), node: process.version, runs: RUNS };
    writeFileSync(join(out, 'backend-baseline.json'), JSON.stringify({ measuredAt: new Date().toISOString(), env, results }, null, 2) + '\n');
    // eslint-disable-next-line no-console -- the benchmark report is the output
    console.log(JSON.stringify({ env, results }, null, 2));
  });

  async function job(operation: string, files: string[], options?: object) {
    const started = Date.now();
    let req = http.post('/api/v1/jobs').set({ Authorization: `Bearer ${token}` }).field('operation', operation);
    if (options) req = req.field('options', JSON.stringify(options));
    for (const f of files) req = req.attach('files', f);
    const { id } = (await req.expect(202)).body;
    for (;;) {
      const j = (await http.get(`/api/v1/jobs/${id}`).set({ Authorization: `Bearer ${token}` }).expect(200)).body;
      if (j.status === 'COMPLETED') {
        const res = await http.get(`/api/v1/jobs/${id}/download`).set({ Authorization: `Bearer ${token}` }).buffer(true).parse((r, cb) => {
          const chunks: Buffer[] = [];
          r.on('data', (c: Buffer) => chunks.push(c));
          r.on('end', () => cb(null, Buffer.concat(chunks)));
        });
        return { ms: Date.now() - started, meta: j.output.meta, body: res.body as Buffer };
      }
      if (['FAILED', 'CANCELLED', 'EXPIRED'].includes(j.status)) throw new Error(`${operation} ${j.status}: ${JSON.stringify(j.error)}`);
      await new Promise((r) => setTimeout(r, 100));
    }
  }

  async function measure(name: string, run: () => Promise<{ ms: number; meta: Record<string, unknown> }>, extra: (m: Record<string, unknown>) => object = () => ({})) {
    const times: number[] = [];
    let meta: Record<string, unknown> = {};
    for (let i = 0; i < RUNS; i++) ({ ms: times[i], meta } = await run());
    results.push({ name, medianMs: median(times), minMs: Math.min(...times), maxMs: Math.max(...times), ...extra(meta) });
  }

  async function withEngines<T>(engines: Partial<Pick<AppConfig, 'PDF_TO_DOCX_ENGINE' | 'COMPRESS_ENGINE'>>, fn: () => Promise<T>) {
    const saved = { PDF_TO_DOCX_ENGINE: config.PDF_TO_DOCX_ENGINE, COMPRESS_ENGINE: config.COMPRESS_ENGINE };
    Object.assign(config, engines);
    try {
      return await fn();
    } finally {
      Object.assign(config, saved);
    }
  }

  it('measures conversions, compression and OCR', async () => {
    const pages = (n: number) =>
      Array.from({ length: n }, (_, i) => `<h2 style="page-break-before:${i ? 'always' : 'auto'}">Section ${i + 1}</h2>` + `<p>${EN_TEXT}</p><p>${HI_TEXT}</p>`.repeat(4)).join('');
    const docx10 = fx.docx('bench10', pages(10));
    const pdf10 = fx.textPdf('bench10', pages(10));
    const heavy = fx.imageHeavyPdf('bench-heavy');
    const scanned5 = fx.scannedPdf(fx.png(fx.textPdf('bench-scan-src', pages(1)), 'bench-scan', 300), 'bench-scan5');

    await measure('office_to_pdf · DOCX 10 pages', () => job('office_to_pdf', [docx10]), (m) => ({ pages: m.pages }));
    for (const engine of ['text', 'pdf2docx', 'libreoffice'] as const) {
      await measure(`pdf_to_docx · ${engine} · text PDF 10 pages`, () => withEngines({ PDF_TO_DOCX_ENGINE: engine }, () => job('pdf_to_docx', [pdf10])), (m) => ({ pages: m.pages }));
    }
    await measure('pdf_to_docx · scanned page → OCR (eng+hin)', () => job('pdf_to_docx', [scanned5], { ocrLanguage: 'eng+hin' }), (m) => ({ pages: m.pages }));
    for (const engine of ['pikepdf', 'ghostscript'] as const) {
      for (const preset of ['balanced', 'small_file'] as const) {
        await measure(`compress_pdf · ${engine} · ${preset} · 1 image page`, () => withEngines({ COMPRESS_ENGINE: engine }, () => job('compress_pdf', [heavy], { preset })), (m) => ({
          originalBytes: m.originalBytes,
          outputBytes: m.outputBytes,
          reductionPercent: m.reductionPercent,
        }));
      }
    }
  });

  it('measures OCR character error rate on clean printed pages', async () => {
    for (const [lang, text, code] of [['English', EN_TEXT, 'eng'], ['Hindi', HI_TEXT, 'hin']] as const) {
      for (const dpi of [150, 300]) {
        for (const px of [14, 20]) {
          const png = fx.png(fx.textPdf(`cer-${code}-${px}`, `<p style="font-size:${px}px">${text}</p>`), `cer-${code}-${px}-${dpi}`, dpi);
          const started = Date.now();
          const r = await job('ocr', [png], { language: code, output: 'txt' });
          results.push({ name: `ocr CER · ${lang} · ${px}px text · ${dpi} dpi`, ms: Date.now() - started, cer: Math.round(cer(text, r.body.toString('utf8')) * 1000) / 1000 });
        }
      }
    }
  });
});
