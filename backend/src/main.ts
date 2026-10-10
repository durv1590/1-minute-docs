import 'reflect-metadata';
import { createApp } from './bootstrap';
import { loadConfig } from './config/config';

async function main(): Promise<void> {
  const config = loadConfig();
  const app = await createApp(config);
  if (config.ROLE === 'worker') {
    await app.init();
  } else {
    await app.listen(config.PORT, '0.0.0.0');
  }
}

main().catch((e: Error) => {
  process.stderr.write(`Fatal: ${e.message}\n`);
  process.exit(1);
});
