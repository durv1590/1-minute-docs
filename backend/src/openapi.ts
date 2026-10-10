import { DOCUMENT_TYPES, TASKS, TONES } from './modules/ai/prompts';
import { JOB_STATUSES } from './modules/jobs/job.types';
import { OCR_LANGUAGES, OPERATIONS } from './modules/jobs/operations';

const errorResponse = (description: string) => ({ description, content: { 'application/json': { schema: { $ref: '#/components/schemas/Error' } } } });
const bearer = [{ clientToken: [] }];
const jobIdParam = { name: 'id', in: 'path', required: true, schema: { type: 'string', format: 'uuid' } };

/** Hand-maintained OpenAPI 3.1 description served at /api/docs (UI) and /api/docs-json. */
export function openApiDocument() {
  return {
    openapi: '3.1.0',
    info: {
      title: 'One-Minute Docs API',
      version: '1.0.0',
      description:
        'Cloud processing for the One-Minute Docs Android app. Files are kept only as long as needed to process and download them. ' +
        'All job and AI endpoints require an anonymous client token from POST /api/v1/auth/anonymous.',
    },
    servers: [{ url: '/' }],
    components: {
      securitySchemes: { clientToken: { type: 'http', scheme: 'bearer', description: 'Anonymous client token' } },
      schemas: {
        Error: {
          type: 'object',
          required: ['error'],
          properties: {
            error: {
              type: 'object',
              required: ['code', 'message', 'requestId'],
              properties: {
                code: { type: 'string', examples: ['UNSUPPORTED_FORMAT'] },
                message: { type: 'string' },
                requestId: { type: 'string' },
                details: { type: 'object' },
              },
            },
          },
        },
        Job: {
          type: 'object',
          required: ['id', 'operation', 'status', 'createdAt', 'updatedAt', 'expiresAt', 'inputs'],
          properties: {
            id: { type: 'string', format: 'uuid' },
            operation: { type: 'string', enum: Object.keys(OPERATIONS) },
            status: { type: 'string', enum: [...JOB_STATUSES] },
            progress: { type: ['integer', 'null'], minimum: 0, maximum: 100, description: 'Measured progress; null when not measurable.' },
            createdAt: { type: 'string', format: 'date-time' },
            updatedAt: { type: 'string', format: 'date-time' },
            expiresAt: { type: 'string', format: 'date-time' },
            inputs: { type: 'array', items: { $ref: '#/components/schemas/FileInfo' } },
            output: {
              oneOf: [
                { type: 'null' },
                {
                  allOf: [
                    { $ref: '#/components/schemas/FileInfo' },
                    { type: 'object', properties: { meta: { type: 'object', description: 'Operation details, e.g. pages, warnings, reductionPercent.' } } },
                  ],
                },
              ],
            },
            error: { oneOf: [{ type: 'null' }, { type: 'object', properties: { code: { type: 'string' }, message: { type: 'string' } } }] },
          },
        },
        FileInfo: {
          type: 'object',
          properties: { fileName: { type: 'string' }, contentType: { type: 'string' }, sizeBytes: { type: 'integer' } },
        },
        AiRequest: {
          type: 'object',
          required: ['task'],
          additionalProperties: false,
          properties: {
            task: { type: 'string', enum: [...TASKS] },
            language: { type: 'string', enum: ['en', 'hi'], default: 'en' },
            documentType: { type: 'string', enum: [...DOCUMENT_TYPES] },
            tone: { type: 'string', enum: [...TONES] },
            instructions: { type: 'string', maxLength: 5000 },
            fields: { type: 'object', additionalProperties: { type: 'string', maxLength: 4000 }, maxProperties: 30 },
            text: { type: 'string' },
            targetLanguage: { type: 'string', enum: ['en', 'hi'] },
          },
        },
        AiResult: {
          type: 'object',
          properties: {
            task: { type: 'string' },
            language: { type: 'string' },
            markdown: { type: 'string', description: 'Draft in the app\'s Markdown subset.' },
            aiGenerated: { const: true },
            provider: { type: 'string' },
            model: { type: 'string' },
            warnings: { type: 'array', items: { type: 'string', enum: ['TRUNCATED'] } },
            usage: { type: 'object', properties: { inputTokens: { type: ['integer', 'null'] }, outputTokens: { type: ['integer', 'null'] } } },
          },
        },
      },
    },
    paths: {
      '/api/v1/auth/anonymous': {
        post: {
          summary: 'Issue an anonymous client token',
          responses: {
            '201': { description: 'Token', content: { 'application/json': { schema: { type: 'object', properties: { token: { type: 'string' }, expiresAt: { type: 'string', format: 'date-time' } } } } } },
            '429': errorResponse('Too many tokens from this IP'),
          },
        },
      },
      '/api/v1/jobs': {
        post: {
          summary: 'Upload files and start a processing job',
          security: bearer,
          parameters: [{ name: 'Idempotency-Key', in: 'header', required: false, schema: { type: 'string', pattern: '^[A-Za-z0-9_-]{8,64}$' }, description: 'Repeat-safe submission; the same key returns the existing job.' }],
          requestBody: {
            required: true,
            content: {
              'multipart/form-data': {
                schema: {
                  type: 'object',
                  required: ['operation', 'files'],
                  properties: {
                    operation: { type: 'string', enum: Object.keys(OPERATIONS) },
                    options: {
                      type: 'string',
                      description:
                        'JSON object. office_to_pdf: {}. pdf_to_docx: {ocrLanguage}. ocr: {language, output: txt|pdf}. compress_pdf: {preset: high_quality|balanced|small_file}. ' +
                        `handwriting_recognize: {language: en|hi|auto, consent: true}. OCR languages: ${OCR_LANGUAGES.join(', ')}.`,
                    },
                    files: { type: 'array', items: { type: 'string', format: 'binary' } },
                  },
                },
              },
            },
          },
          responses: {
            '202': { description: 'Job queued', content: { 'application/json': { schema: { $ref: '#/components/schemas/Job' } } } },
            '200': { description: 'Existing job for this Idempotency-Key', content: { 'application/json': { schema: { $ref: '#/components/schemas/Job' } } } },
            '400': errorResponse('Invalid operation/options, wrong file count, or CONSENT_REQUIRED'),
            '401': errorResponse('Missing or invalid token'),
            '413': errorResponse('File too large'),
            '415': errorResponse('UNSUPPORTED_FORMAT — content does not match a supported type'),
            '429': errorResponse('RATE_LIMITED or QUOTA_EXCEEDED'),
            '503': errorResponse('AI_NOT_CONFIGURED for handwriting recognition'),
          },
        },
        get: {
          summary: 'List this client\'s recent jobs',
          security: bearer,
          responses: { '200': { description: 'Jobs', content: { 'application/json': { schema: { type: 'object', properties: { jobs: { type: 'array', items: { $ref: '#/components/schemas/Job' } } } } } } } },
        },
      },
      '/api/v1/jobs/{id}': {
        get: {
          summary: 'Get job status',
          security: bearer,
          parameters: [jobIdParam],
          responses: { '200': { description: 'Job', content: { 'application/json': { schema: { $ref: '#/components/schemas/Job' } } } }, '404': errorResponse('Not found (or owned by another client)') },
        },
        delete: {
          summary: 'Delete the job and all its stored files now',
          security: bearer,
          parameters: [jobIdParam],
          responses: { '204': { description: 'Deleted' }, '404': errorResponse('Not found') },
        },
      },
      '/api/v1/jobs/{id}/cancel': {
        post: {
          summary: 'Cancel a queued or running job',
          security: bearer,
          parameters: [jobIdParam],
          responses: { '200': { description: 'Job after the cancel request', content: { 'application/json': { schema: { $ref: '#/components/schemas/Job' } } } }, '404': errorResponse('Not found') },
        },
      },
      '/api/v1/jobs/{id}/download': {
        get: {
          summary: 'Download the validated output of a completed job',
          security: bearer,
          parameters: [jobIdParam],
          responses: {
            '200': { description: 'File stream', content: { 'application/octet-stream': { schema: { type: 'string', format: 'binary' } } } },
            '404': errorResponse('Not found'),
            '409': errorResponse('JOB_NOT_READY'),
            '410': errorResponse('JOB_EXPIRED'),
          },
        },
      },
      '/api/v1/ai/generate': {
        post: {
          summary: 'Generate or transform text with the configured AI provider',
          security: bearer,
          requestBody: { required: true, content: { 'application/json': { schema: { $ref: '#/components/schemas/AiRequest' } } } },
          responses: {
            '200': { description: 'Draft', content: { 'application/json': { schema: { $ref: '#/components/schemas/AiResult' } } } },
            '400': errorResponse('Invalid request'),
            '413': errorResponse('Input exceeds AI_MAX_INPUT_CHARS'),
            '422': errorResponse('AI_REFUSED'),
            '429': errorResponse('QUOTA_EXCEEDED'),
            '502': errorResponse('AI_PROVIDER_ERROR'),
            '503': errorResponse('AI_NOT_CONFIGURED or provider busy'),
            '504': errorResponse('TIMEOUT'),
          },
        },
      },
      '/api/v1/capabilities': { get: { summary: 'Server capabilities and fair-use limits', responses: { '200': { description: 'Capabilities' } } } },
      '/api/v1/health/live': { get: { summary: 'Liveness probe', responses: { '200': { description: 'Alive' } } } },
      '/api/v1/health/ready': { get: { summary: 'Readiness probe', responses: { '200': { description: 'Ready' }, '503': { description: 'A dependency is unavailable' } } } },
    },
  };
}
