import { loadConfig } from '../../src/config/config';

const base = { DATABASE_URL: 'postgres://x', REDIS_URL: 'redis://x', TOKEN_SECRET: 'x'.repeat(40) };

describe('loadConfig', () => {
  it('applies defaults', () => {
    const c = loadConfig(base);
    expect(c.AI_MODEL).toBe('claude-opus-5-5');
    expect(c.maxUploadBytes).toBe(50 * 1024 * 1024);
    expect(c.aiConfigured).toBe(false);
    expect(c.PDF_TO_DOCX_ENGINE).toBe('text');
    expect(c.COMPRESS_ENGINE).toBe('pikepdf');
  });

  it('marks AI configured only with credentials', () => {
    expect(loadConfig({ ...base, ANTHROPIC_API_KEY: 'k' }).aiConfigured).toBe(true);
    expect(loadConfig({ ...base, ANTHROPIC_API_KEY: 'k', AI_PROVIDER: 'none' }).aiConfigured).toBe(false);
  });

  it('rejects short secrets without echoing them', () => {
    expect(() => loadConfig({ ...base, TOKEN_SECRET: 'short-secret' })).toThrow(/TOKEN_SECRET/);
    try {
      loadConfig({ ...base, TOKEN_SECRET: 'short-secret' });
    } catch (e) {
      expect((e as Error).message).not.toContain('short-secret');
    }
  });

  it('defaults to engines without AGPL components and allows opting in', () => {
    const prod = loadConfig({ ...base, NODE_ENV: 'production' });
    expect(prod.PDF_TO_DOCX_ENGINE).toBe('text');
    expect(prod.COMPRESS_ENGINE).toBe('pikepdf');
    const optIn = loadConfig({ ...base, PDF_TO_DOCX_ENGINE: 'pdf2docx', COMPRESS_ENGINE: 'ghostscript' });
    expect(optIn.PDF_TO_DOCX_ENGINE).toBe('pdf2docx');
    expect(optIn.COMPRESS_ENGINE).toBe('ghostscript');
    expect(() => loadConfig({ ...base, COMPRESS_ENGINE: 'magic' })).toThrow(/COMPRESS_ENGINE/);
  });

  it('requires a bucket for s3 storage', () => {
    expect(() => loadConfig({ ...base, STORAGE_DRIVER: 's3' })).toThrow(/S3_BUCKET/);
  });
});
