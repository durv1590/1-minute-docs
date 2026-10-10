import { INestApplication } from '@nestjs/common';
import { execFileSync } from 'node:child_process';
import { existsSync, readdirSync, writeFileSync } from 'node:fs';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import IORedis from 'ioredis';
import { Pool } from 'pg';
import request from 'supertest';
import { createApp } from '../../src/bootstrap';
import { AppConfig } from '../../src/config/config';
import { AiCompletion, AiImageRequest, AiProvider, AiTextRequest } from '../../src/modules/ai/ai-provider';
import { SweeperService } from '../../src/modules/processing/sweeper.service';
import { testConfig } from '../helpers';
import { Fixtures } from './fixtures';

jest.setTimeout(300_000);

/** Deterministic in-process AI provider; the real Anthropic adapter is covered by unit tests against a fake HTTP API. */
class FakeAi implements AiProvider {
  readonly name = 'fake';
  readonly model = 'fake-model';
  mode: 'ok' | 'refuse' | 'slow' = 'ok';
  calls: (AiTextRequest | AiImageRequest)[] = [];

  async complete(req: AiTextRequest): Promise<AiCompletion> {
    this.calls.push(req);
    if (this.mode === 'refuse') return { text: '', refused: true, truncated: false, model: this.model, inputTokens: 1, outputTokens: 0 };
    return { text: `# Draft\n\n${req.user.length} chars of instructions received`, refused: false, truncated: false, model: this.model, inputTokens: 10, outputTokens: 20 };
  }

  async transcribeImage(req: AiImageRequest): Promise<AiCompletion> {
    this.calls.push(req);
    if (this.mode === 'slow') {
      await new Promise((resolve, reject) => {
        const t = setTimeout(resolve, 60_000);
        req.signal?.addEventListener('abort', () => { clearTimeout(t); reject(Object.assign(new Error('aborted'), { name: 'AbortError' })); });
      });
    }
    return { text: `page with ${req.imageBase64.length} base64 chars`, refused: false, truncated: false, model: this.model, inputTokens: 5, outputTokens: 5 };
  }
}

describe('One-Minute Docs API (e2e)', () => {
  const storageDir = mkdtempSync(join(tmpdir(), 'omd-storage-'));
  const workDir = mkdtempSync(join(tmpdir(), 'omd-work-'));
  let config: AppConfig;
  let app: INestApplication;
  let http: ReturnType<typeof request>;
  const ai = new FakeAi();
  const fx = new Fixtures();
  let tokenA: string;
  let tokenB: string;

  beforeAll(async () => {
    config = testConfig({
      STORAGE_LOCAL_DIR: storageDir,
      WORK_DIR: workDir,
      MAX_UPLOAD_MB: '30',
      AI_REQUESTS_PER_CLIENT_DAY: '5',
      RATE_TOKENS_PER_IP_HOUR: '60',
      RATE_JOBS_PER_CLIENT_HOUR: '100',
      JOB_TIMEOUT_SECONDS: '150',
      WORKER_CONCURRENCY: '2',
    });
    const pool = new Pool({ connectionString: config.DATABASE_URL });
    await pool.query('DROP SCHEMA public CASCADE; CREATE SCHEMA public;');
    await pool.end();
    const redis = new IORedis(config.REDIS_URL);
    await redis.flushdb();
    redis.disconnect();

    app = await createApp(config, { aiProvider: ai });
    await app.init();
    http = request(app.getHttpServer());
    tokenA = (await http.post('/api/v1/auth/anonymous').expect(201)).body.token;
    tokenB = (await http.post('/api/v1/auth/anonymous').expect(201)).body.token;
  });

  afterAll(async () => {
    await app?.close();
  });

  const auth = (t: string) => ({ Authorization: `Bearer ${t}` });

  async function submit(token: string, operation: string, files: string[], options?: object, expectStatus = 202) {
    let req = http.post('/api/v1/jobs').set(auth(token)).field('operation', operation);
    if (options) req = req.field('options', JSON.stringify(options));
    for (const f of files) req = req.attach('files', f);
    const res = await req;
    if (res.status !== expectStatus) throw new Error(`expected ${expectStatus}, got ${res.status}: ${JSON.stringify(res.body)}`);
    return res.body;
  }

  async function waitFor(token: string, id: string, timeoutMs = 240_000) {
    const end = Date.now() + timeoutMs;
    const seen = new Set<string>();
    while (Date.now() < end) {
      const job = (await http.get(`/api/v1/jobs/${id}`).set(auth(token)).expect(200)).body;
      seen.add(job.status);
      if (['COMPLETED', 'FAILED', 'CANCELLED', 'EXPIRED'].includes(job.status)) return { job, seen };
      await new Promise((r) => setTimeout(r, 300));
    }
    throw new Error('job did not finish');
  }

  async function download(token: string, id: string): Promise<Buffer> {
    const res = await http.get(`/api/v1/jobs/${id}/download`).set(auth(token)).buffer(true).parse((r, cb) => {
      const chunks: Buffer[] = [];
      r.on('data', (c: Buffer) => chunks.push(c));
      r.on('end', () => cb(null, Buffer.concat(chunks)));
    });
    expect(res.status).toBe(200);
    return res.body as Buffer;
  }

  function pdfPages(buf: Buffer): number {
    const p = fx.raw(`dl-${Date.now()}.pdf`, buf);
    return Number(execFileSync('qpdf', ['--show-npages', p]).toString().trim());
  }

  function docxText(buf: Buffer): string {
    const p = fx.raw(`dl-${Date.now()}.docx`, buf);
    return execFileSync('unzip', ['-p', p, 'word/document.xml']).toString('utf8').replace(/<[^>]+>/g, ' ');
  }

  describe('auth and basics', () => {
    it('requires a valid token', async () => {
      await http.get('/api/v1/jobs').expect(401);
      const res = await http.get('/api/v1/jobs').set(auth('bad.token')).expect(401);
      expect(res.body.error.code).toBe('UNAUTHORIZED');
      expect(res.body.error.requestId).toBeTruthy();
    });

    it('reports health and capabilities', async () => {
      expect((await http.get('/api/v1/health/live').expect(200)).body.status).toBe('ok');
      expect((await http.get('/api/v1/health/ready').expect(200)).body.checks).toEqual({ database: 'ok', redis: 'ok', storage: 'ok' });
      const caps = (await http.get('/api/v1/capabilities').expect(200)).body;
      expect(caps.operations.map((o: { name: string }) => o.name)).toEqual(expect.arrayContaining(['office_to_pdf', 'pdf_to_docx', 'ocr', 'compress_pdf', 'handwriting_recognize']));
      expect(caps.limits.maxUploadBytes).toBe(30 * 1024 * 1024);
      expect(caps.ai.configured).toBe(true);
    });

    it('serves the OpenAPI document', async () => {
      const doc = (await http.get('/api/docs-json').expect(200)).body;
      expect(Object.keys(doc.paths)).toEqual(expect.arrayContaining(['/api/v1/jobs', '/api/v1/jobs/{id}/download', '/api/v1/ai/generate']));
    });
  });

  describe('conversions with real engines', () => {
    it('converts DOCX to PDF (LibreOffice) and validates before COMPLETED', async () => {
      const docx = fx.docx('letter', '<h1>Leave application</h1><p>Respected Sir, please grant leave.</p><p>नमस्ते, धन्यवाद।</p><p style="page-break-before:always">Second page</p>');
      const job = await submit(tokenA, 'office_to_pdf', [docx]);
      expect(job.status).toBe('QUEUED');
      const { job: done, seen } = await waitFor(tokenA, job.id);
      expect(done.status).toBe('COMPLETED');
      expect(seen.has('FAILED')).toBe(false);
      expect(done.output.fileName).toBe('letter.pdf');
      expect(done.output.meta.pages).toBe(2);
      const pdf = await download(tokenA, job.id);
      expect(pdf.subarray(0, 5).toString()).toBe('%PDF-');
      expect(pdfPages(pdf)).toBe(2);
      const text = execFileSync('pdftotext', [fx.raw('lo.pdf', pdf), '-']).toString('utf8');
      expect(text).toContain('Leave application');
      expect(text).toContain('नमस्ते');
    });

    it('converts a text PDF to an editable DOCX with tables (pdf2docx)', async () => {
      const pdf = fx.textPdf('report', '<h1>Quarterly Report</h1><p>Sales grew by twelve percent.</p><table border="1"><tr><th>Item</th><th>Qty</th></tr><tr><td>Pens</td><td>10</td></tr></table>');
      const job = await submit(tokenA, 'pdf_to_docx', [pdf]);
      const { job: done } = await waitFor(tokenA, job.id);
      expect(done.status).toBe('COMPLETED');
      expect(done.output.meta.mode).toBe('layout');
      expect(done.output.meta.warnings).toContain('LAYOUT_APPROXIMATE');
      const xml = execFileSync('unzip', ['-p', fx.raw('p2d.docx', await download(tokenA, job.id)), 'word/document.xml']).toString('utf8');
      expect(xml).toContain('<w:tbl>');
      expect(xml.replace(/<[^>]+>/g, '')).toContain('Sales grew by twelve percent');
    });

    it('OCRs a scanned PDF into a DOCX and labels the mode', async () => {
      const textPdf = fx.textPdf('scan-src', '<p style="font-size:28px">The quick brown fox jumps over the lazy dog</p>');
      const scanned = fx.scannedPdf(fx.png(textPdf, 'scan-img', 200), 'scanned');
      expect(execFileSync('pdftotext', [scanned, '-']).toString().trim()).toBe('');
      const job = await submit(tokenA, 'pdf_to_docx', [scanned], { ocrLanguage: 'eng' });
      const { job: done } = await waitFor(tokenA, job.id);
      expect(done.status).toBe('COMPLETED');
      expect(done.output.meta.mode).toBe('ocr_text');
      expect(done.output.meta.warnings).toContain('SCANNED_LAYOUT_NOT_PRESERVED');
      expect(docxText(await download(tokenA, job.id))).toMatch(/quick\s+brown\s+fox/);
    });

    it('OCRs English and Hindi images to text', async () => {
      const en = fx.png(fx.textPdf('ocr-en', '<p style="font-size:26px">Photosynthesis needs sunlight</p>'), 'ocr-en');
      const hi = fx.png(fx.textPdf('ocr-hi', '<p style="font-size:30px">पौधे सूर्य के प्रकाश से भोजन बनाते हैं</p>'), 'ocr-hi');
      const jobEn = await submit(tokenA, 'ocr', [en], { language: 'eng', output: 'txt' });
      const jobHi = await submit(tokenA, 'ocr', [hi], { language: 'hin', output: 'txt' });
      const doneEn = (await waitFor(tokenA, jobEn.id)).job;
      const doneHi = (await waitFor(tokenA, jobHi.id)).job;
      expect(doneEn.status).toBe('COMPLETED');
      expect((await download(tokenA, jobEn.id)).toString('utf8')).toContain('Photosynthesis needs sunlight');
      expect(doneHi.status).toBe('COMPLETED');
      const hiText = (await download(tokenA, jobHi.id)).toString('utf8');
      expect(hiText).toMatch(/सूर्य/);
      expect(hiText).toMatch(/प्रकाश/);
    });

    it('produces a searchable PDF from an image', async () => {
      const png = fx.png(fx.textPdf('searchable', '<p style="font-size:26px">Searchable invoice number 4521</p>'), 'searchable');
      const job = await submit(tokenA, 'ocr', [png], { language: 'eng', output: 'pdf' });
      expect((await waitFor(tokenA, job.id)).job.status).toBe('COMPLETED');
      const pdf = await download(tokenA, job.id);
      expect(execFileSync('pdftotext', [fx.raw('s.pdf', pdf), '-']).toString()).toContain('4521');
    });

    it('compresses an image-heavy PDF and reports measured sizes', async () => {
      const pdf = fx.imageHeavyPdf('heavy');
      const job = await submit(tokenA, 'compress_pdf', [pdf], { preset: 'small_file' });
      const { job: done } = await waitFor(tokenA, job.id);
      expect(done.status).toBe('COMPLETED');
      const out = await download(tokenA, job.id);
      const meta = done.output.meta;
      expect(meta.outputBytes).toBe(out.length);
      expect(meta.originalBytes).toBe(done.inputs[0].sizeBytes);
      expect(meta.outputBytes).toBeLessThan(meta.originalBytes);
      expect(meta.notSmaller).toBe(false);
      expect(pdfPages(out)).toBe(1);
    });
  });

  describe('input validation and failure handling', () => {
    it('rejects content that does not match the operation (sniffed, not by extension)', async () => {
      const fakePdf = fx.raw('notreally.pdf', 'just some text');
      const res = await submit(tokenA, 'compress_pdf', [fakePdf], undefined, 415);
      expect(res.error.code).toBe('UNSUPPORTED_FORMAT');
      const png = fx.png(fx.textPdf('x', '<p>x</p>'), 'x');
      expect((await submit(tokenA, 'office_to_pdf', [png], undefined, 415)).error.code).toBe('UNSUPPORTED_FORMAT');
    });

    it('rejects unknown operations, bad options and wrong file counts', async () => {
      const pdf = fx.textPdf('small', '<p>small</p>');
      expect((await submit(tokenA, 'explode', [pdf], undefined, 400)).error.code).toBe('BAD_REQUEST');
      expect((await submit(tokenA, 'compress_pdf', [pdf], { preset: 'ultra' }, 400)).error.code).toBe('BAD_REQUEST');
      expect((await submit(tokenA, 'compress_pdf', [pdf, pdf], undefined, 400)).error.code).toBe('BAD_REQUEST');
      expect((await submit(tokenA, 'compress_pdf', [], undefined, 400)).error.code).toBe('BAD_REQUEST');
    });

    it('rejects uploads over the size limit', async () => {
      const big = fx.raw('big.pdf', Buffer.concat([Buffer.from('%PDF-1.4\n'), Buffer.alloc(31 * 1024 * 1024)]));
      const res = await submit(tokenA, 'compress_pdf', [big], undefined, 413);
      expect(res.error.code).toBe('PAYLOAD_TOO_LARGE');
    });

    it('fails encrypted and corrupt PDFs with specific codes and no output', async () => {
      const enc = fx.encryptedPdf(fx.textPdf('secret', '<p>secret</p>'), 'secret-enc');
      const corrupt = fx.raw('corrupt.pdf', '%PDF-1.4\n1 0 obj garbage that is not a pdf\n');
      const j1 = await submit(tokenA, 'compress_pdf', [enc]);
      const j2 = await submit(tokenA, 'pdf_to_docx', [corrupt]);
      const d1 = (await waitFor(tokenA, j1.id)).job;
      const d2 = (await waitFor(tokenA, j2.id)).job;
      expect(d1).toMatchObject({ status: 'FAILED', output: null, error: { code: 'ENCRYPTED_DOCUMENT' } });
      expect(d2).toMatchObject({ status: 'FAILED', output: null, error: { code: 'CORRUPT_FILE' } });
      const res = await http.get(`/api/v1/jobs/${j1.id}/download`).set(auth(tokenA)).expect(409);
      expect(res.body.error.code).toBe('JOB_NOT_READY');
      // Inputs of failed jobs are deleted immediately.
      expect(existsSync(join(storageDir, 'jobs', j1.id, 'input-0'))).toBe(false);
    });

    it('treats Idempotency-Key resubmissions as the same job', async () => {
      const pdf = fx.textPdf('idem', '<p>idem</p>');
      const key = 'retry-key-123456';
      const first = await http.post('/api/v1/jobs').set({ ...auth(tokenA), 'Idempotency-Key': key }).field('operation', 'compress_pdf').attach('files', pdf).expect(202);
      const second = await http.post('/api/v1/jobs').set({ ...auth(tokenA), 'Idempotency-Key': key }).field('operation', 'compress_pdf').attach('files', pdf).expect(200);
      expect(second.body.id).toBe(first.body.id);
      await waitFor(tokenA, first.body.id);
    });
  });

  describe('ownership, deletion and retention', () => {
    let jobId: string;

    beforeAll(async () => {
      const job = await submit(tokenA, 'compress_pdf', [fx.textPdf('owned', '<p>owned by A</p>')]);
      jobId = job.id;
      expect((await waitFor(tokenA, jobId)).job.status).toBe('COMPLETED');
    });

    it('hides jobs from other clients', async () => {
      await http.get(`/api/v1/jobs/${jobId}`).set(auth(tokenB)).expect(404);
      await http.get(`/api/v1/jobs/${jobId}/download`).set(auth(tokenB)).expect(404);
      await http.delete(`/api/v1/jobs/${jobId}`).set(auth(tokenB)).expect(404);
      await http.post(`/api/v1/jobs/${jobId}/cancel`).set(auth(tokenB)).expect(404);
      const list = (await http.get('/api/v1/jobs').set(auth(tokenB)).expect(200)).body.jobs;
      expect(list.find((j: { id: string }) => j.id === jobId)).toBeUndefined();
      await http.get('/api/v1/jobs/not-a-uuid').set(auth(tokenA)).expect(404);
    });

    it('deletes inputs after processing and keeps only the output until expiry', async () => {
      const files = readdirSync(join(storageDir, 'jobs', jobId));
      expect(files).toEqual(['output']);
    });

    it('expires outputs through the retention sweeper', async () => {
      const sweeper = app.get(SweeperService);
      const result = await sweeper.sweep(new Date(Date.now() + (config.OUTPUT_TTL_MINUTES + 1) * 60_000));
      expect(result.expired).toBeGreaterThanOrEqual(1);
      expect(existsSync(join(storageDir, 'jobs', jobId, 'output'))).toBe(false);
      const job = (await http.get(`/api/v1/jobs/${jobId}`).set(auth(tokenA)).expect(200)).body;
      expect(job.status).toBe('EXPIRED');
      expect((await http.get(`/api/v1/jobs/${jobId}/download`).set(auth(tokenA)).expect(410)).body.error.code).toBe('JOB_EXPIRED');
    });

    it('deletes a job and its files on request', async () => {
      const job = await submit(tokenA, 'compress_pdf', [fx.textPdf('del', '<p>delete me</p>')]);
      await waitFor(tokenA, job.id);
      await http.delete(`/api/v1/jobs/${job.id}`).set(auth(tokenA)).expect(204);
      await http.get(`/api/v1/jobs/${job.id}`).set(auth(tokenA)).expect(404);
      expect(existsSync(join(storageDir, 'jobs', job.id, 'output'))).toBe(false);
    });
  });

  describe('handwriting recognition', () => {
    let page: string;
    beforeAll(() => {
      page = fx.png(fx.textPdf('hw', '<p>notes</p>'), 'hw', 72);
    });

    it('requires explicit consent', async () => {
      expect((await submit(tokenA, 'handwriting_recognize', [page], { language: 'en' }, 400)).error.code).toBe('CONSENT_REQUIRED');
    });

    it('transcribes pages in order with page separators', async () => {
      const job = await submit(tokenA, 'handwriting_recognize', [page, page], { language: 'en', consent: true });
      const { job: done } = await waitFor(tokenA, job.id);
      expect(done.status).toBe('COMPLETED');
      expect(done.output.meta).toMatchObject({ pages: 2, aiGenerated: true, engine: 'fake' });
      expect((await download(tokenA, job.id)).toString('utf8').split('\f')).toHaveLength(2);
    });

    it('cancels a running job promptly', async () => {
      ai.mode = 'slow';
      try {
        const job = await submit(tokenA, 'handwriting_recognize', [page], { consent: true });
        for (let i = 0; i < 50; i++) {
          const s = (await http.get(`/api/v1/jobs/${job.id}`).set(auth(tokenA))).body.status;
          if (s === 'PROCESSING') break;
          await new Promise((r) => setTimeout(r, 100));
        }
        const started = Date.now();
        await http.post(`/api/v1/jobs/${job.id}/cancel`).set(auth(tokenA)).expect(200);
        const { job: done } = await waitFor(tokenA, job.id, 15_000);
        expect(done.status).toBe('CANCELLED');
        expect(Date.now() - started).toBeLessThan(10_000);
      } finally {
        ai.mode = 'ok';
      }
    });
  });

  describe('AI generation', () => {
    it('generates a draft and marks it AI-generated', async () => {
      const res = await http.post('/api/v1/ai/generate').set(auth(tokenB)).send({ task: 'generate_document', documentType: 'letter', language: 'hi', fields: { to: 'Principal' } }).expect(200);
      expect(res.body).toMatchObject({ aiGenerated: true, provider: 'fake', language: 'hi', warnings: [] });
      expect(res.body.markdown).toMatch(/^# Draft/);
    });

    it('validates requests and input size', async () => {
      expect((await http.post('/api/v1/ai/generate').set(auth(tokenB)).send({ task: 'summarize' }).expect(400)).body.error.code).toBe('BAD_REQUEST');
      const res = await http.post('/api/v1/ai/generate').set(auth(tokenB)).send({ task: 'summarize', text: 'x'.repeat(config.AI_MAX_INPUT_CHARS + 1) }).expect(413);
      expect(res.body.error.code).toBe('PAYLOAD_TOO_LARGE');
    });

    it('surfaces refusals honestly', async () => {
      ai.mode = 'refuse';
      try {
        const res = await http.post('/api/v1/ai/generate').set(auth(tokenB)).send({ task: 'summarize', text: 'hello' }).expect(422);
        expect(res.body.error.code).toBe('AI_REFUSED');
      } finally {
        ai.mode = 'ok';
      }
    });

    it('enforces the daily fair-use quota', async () => {
      const fresh = (await http.post('/api/v1/auth/anonymous').expect(201)).body.token;
      for (let i = 0; i < 5; i++) await http.post('/api/v1/ai/generate').set(auth(fresh)).send({ task: 'summarize', text: 'hi' }).expect(200);
      const res = await http.post('/api/v1/ai/generate').set(auth(fresh)).send({ task: 'summarize', text: 'hi' }).expect(429);
      expect(res.body.error.code).toBe('QUOTA_EXCEEDED');
      expect(res.body.error.details.retryAfterSeconds).toBeGreaterThan(0);
    });

    it('returns AI_NOT_CONFIGURED when no provider is set', async () => {
      const bare = await createApp({ ...config, ROLE: 'api' }, { aiProvider: null });
      await bare.init();
      try {
        const h = request(bare.getHttpServer());
        const t = (await h.post('/api/v1/auth/anonymous').expect(201)).body.token;
        const res = await h.post('/api/v1/ai/generate').set(auth(t)).send({ task: 'summarize', text: 'x' }).expect(503);
        expect(res.body.error.code).toBe('AI_NOT_CONFIGURED');
      } finally {
        await bare.close();
      }
    });
  });

  describe('rate limiting', () => {
    it('limits anonymous token issuance per IP', async () => {
      let status = 201;
      for (let i = 0; i < 70 && status === 201; i++) status = (await http.post('/api/v1/auth/anonymous')).status;
      expect(status).toBe(429);
    });
  });

  it('wrote no stray upload files', () => {
    expect(readdirSync(join(workDir, 'uploads'))).toEqual([]);
    writeFileSync(join(workDir, 'done'), '');
  });
});
