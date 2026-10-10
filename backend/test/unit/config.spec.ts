import { loadConfig } from '../../src/config/config';

const base = { DATABASE_URL: 'postgres://x', REDIS_URL: 'redis://x', TOKEN_SECRET: 'x'.repeat(40) };

describe('loadConfig', () => {
  it('applies defaults', () => {
    const c = loadConfig(base);
    expect(c.AI_MODEL).toBe('claude-opus-5-5');
    expect(c.maxUploadBytes).toBe(50 * 1024 * 1024);
    expect(c.aiConfigured).toBe(false);
    expect(c.PDF_TO_DOCX_ENGINE).toBe('pdf2docx');
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

  it('forces an explicit PDF→Word engine choice in production', () => {
    expect(() => loadConfig({ ...base, NODE_ENV: 'production' })).toThrow(/PDF_TO_DOCX_ENGINE/);
    expect(loadConfig({ ...base, NODE_ENV: 'production', PDF_TO_DOCX_ENGINE: 'libreoffice' }).PDF_TO_DOCX_ENGINE).toBe('libreoffice');
  });

  it('requires a bucket for s3 storage', () => {
    expect(() => loadConfig({ ...base, STORAGE_DRIVER: 's3' })).toThrow(/S3_BUCKET/);
  });
});
