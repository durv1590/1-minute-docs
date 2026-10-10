import { openApiDocument } from './openapi';

process.stdout.write(`${JSON.stringify(openApiDocument(), null, 2)}\n`);
