import { loadConfig, AppConfig } from '../src/config/config';

export function testConfig(overrides: Record<string, string> = {}): AppConfig {
  return loadConfig({
    NODE_ENV: 'test',
    DATABASE_URL: process.env.TEST_DATABASE_URL ?? 'postgres://omd:omd@localhost:5432/omd_test',
    REDIS_URL: process.env.TEST_REDIS_URL ?? 'redis://localhost:6379/15',
    TOKEN_SECRET: 'unit-test-secret-unit-test-secret-0123456789',
    PYTHON_BIN: process.env.PYTHON_BIN ?? 'python3',
    LOG_LEVEL: 'silent',
    ...overrides,
  });
}
