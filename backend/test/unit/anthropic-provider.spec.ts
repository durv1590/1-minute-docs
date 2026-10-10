import { createServer, IncomingMessage, Server, ServerResponse } from 'node:http';
import { AddressInfo } from 'node:net';
import { AiProviderError } from '../../src/modules/ai/ai-provider';
import { AnthropicProvider } from '../../src/modules/ai/anthropic.provider';
import { testConfig } from '../helpers';

/** Exercises the real Anthropic SDK against a local fake of the Messages API. */
describe('AnthropicProvider', () => {
  let server: Server;
  let handler: (req: IncomingMessage, body: Record<string, unknown>, res: ServerResponse) => void;
  let lastBody: Record<string, unknown> = {};
  let lastHeaders: IncomingMessage['headers'] = {};

  beforeAll(async () => {
    server = createServer((req, res) => {
      let raw = '';
      req.on('data', (c) => (raw += c));
      req.on('end', () => {
        lastBody = raw ? JSON.parse(raw) : {};
        lastHeaders = req.headers;
        handler(req, lastBody, res);
      });
    });
    await new Promise<void>((r) => server.listen(0, '127.0.0.1', r));
    process.env.ANTHROPIC_BASE_URL = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
    process.env.ANTHROPIC_API_KEY = 'test-key';
  });

  afterAll(async () => {
    delete process.env.ANTHROPIC_BASE_URL;
    delete process.env.ANTHROPIC_API_KEY;
    await new Promise((r) => server.close(r));
  });

  const provider = () => new AnthropicProvider(testConfig({ ANTHROPIC_API_KEY: 'test-key', AI_TIMEOUT_SECONDS: '2' }));
  const message = (content: unknown[], stop_reason = 'end_turn') => ({
    id: 'msg_1', type: 'message', role: 'assistant', model: 'claude-opus-5-5', content, stop_reason, stop_sequence: null,
    usage: { input_tokens: 12, output_tokens: 34 },
  });
  const json = (res: ServerResponse, status: number, body: unknown) => {
    res.writeHead(status, { 'content-type': 'application/json' });
    res.end(JSON.stringify(body));
  };

  it('sends model, effort, fallback and returns text', async () => {
    handler = (_req, _b, res) => json(res, 200, message([{ type: 'text', text: '# Draft\n\nHello' }]));
    const out = await provider().complete({ system: 'sys', user: 'hi', maxOutputTokens: 100 });
    expect(out).toMatchObject({ text: '# Draft\n\nHello', refused: false, truncated: false, inputTokens: 12, outputTokens: 34 });
    expect(lastBody).toMatchObject({ model: 'claude-opus-5-5', max_tokens: 100, system: 'sys', output_config: { effort: 'medium' }, fallbacks: 'default' });
    expect(String(lastHeaders['anthropic-beta'])).toContain('server-side-fallback-2026-07-01');
    expect(lastHeaders['x-api-key']).toBe('test-key');
  });

  it('sends images as base64 blocks', async () => {
    handler = (_req, _b, res) => json(res, 200, message([{ type: 'text', text: 'line one' }]));
    await provider().transcribeImage({ system: 's', instructions: 'Transcribe', imageBase64: 'AAAA', mediaType: 'image/jpeg', maxOutputTokens: 50 });
    const content = (lastBody.messages as { content: { type: string; source?: { media_type: string } }[] }[])[0].content;
    expect(content[0]).toMatchObject({ type: 'image', source: { type: 'base64', media_type: 'image/jpeg', data: 'AAAA' } });
    expect(content[1]).toMatchObject({ type: 'text', text: 'Transcribe' });
  });

  it('reports refusals and truncation', async () => {
    handler = (_req, _b, res) => json(res, 200, message([], 'refusal'));
    expect((await provider().complete({ system: 's', user: 'u', maxOutputTokens: 10 })).refused).toBe(true);
    handler = (_req, _b, res) => json(res, 200, message([{ type: 'text', text: 'partial' }], 'max_tokens'));
    expect((await provider().complete({ system: 's', user: 'u', maxOutputTokens: 10 })).truncated).toBe(true);
  });

  it.each([
    [401, 'AUTH'],
    [403, 'AUTH'],
    [400, 'BAD_REQUEST'],
    [429, 'RATE_LIMITED'],
    [500, 'UNAVAILABLE'],
  ])('maps HTTP %d to %s', async (status, kind) => {
    handler = (_req, _b, res) => json(res, status, { type: 'error', error: { type: 'x', message: 'boom' } });
    await expect(provider().complete({ system: 's', user: 'u', maxOutputTokens: 10 })).rejects.toMatchObject({ kind });
  });

  it('maps timeouts', async () => {
    handler = () => undefined; // never respond
    const err = await provider().complete({ system: 's', user: 'u', maxOutputTokens: 10 }).catch((e) => e);
    expect(err).toBeInstanceOf(AiProviderError);
    expect(err.kind).toBe('TIMEOUT');
  }, 20000);
});
