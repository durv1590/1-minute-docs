/** Benchmarks: `npm run bench`. Not part of CI; results are written to docs/results/. */
module.exports = {
  preset: 'ts-jest',
  testEnvironment: 'node',
  transform: { '^.+\\.ts$': ['ts-jest', { tsconfig: 'tsconfig.json' }] },
  testMatch: ['<rootDir>/test/bench/**/*.bench-spec.ts'],
  testTimeout: 1_800_000,
};
