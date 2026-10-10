import { Pool } from 'pg';
import { migrate } from './migrator';

async function main(): Promise<void> {
  const url = process.env.DATABASE_URL;
  if (!url) throw new Error('DATABASE_URL is required');
  const pool = new Pool({ connectionString: url });
  try {
    const applied = await migrate(pool);
    process.stdout.write(applied.length ? `Applied: ${applied.join(', ')}\n` : 'Database is up to date\n');
  } finally {
    await pool.end();
  }
}

main().catch((e: Error) => {
  process.stderr.write(`${e.message}\n`);
  process.exit(1);
});
