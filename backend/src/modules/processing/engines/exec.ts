import { spawn } from 'node:child_process';

export class ProcessingError extends Error {
  constructor(readonly code: string, message: string, readonly retryable = false) {
    super(message);
  }
}

export interface RunOptions {
  cwd: string;
  timeoutMs: number;
  signal?: AbortSignal;
  /** Maximum bytes of stdout kept in memory. */
  maxStdoutBytes?: number;
  env?: Record<string, string>;
}

export interface RunResult {
  code: number;
  stdout: string;
  stderrTail: string;
}

/**
 * Runs an engine binary without a shell (arguments are never interpolated into a command line), in its own process
 * group so a timeout or cancellation kills every child it spawned, with a minimal environment.
 */
export function run(bin: string, args: string[], opts: RunOptions): Promise<RunResult> {
  return new Promise((resolve, reject) => {
    if (opts.signal?.aborted) return reject(new ProcessingError('CANCELLED', 'Cancelled'));
    const child = spawn(bin, args, {
      cwd: opts.cwd,
      detached: true,
      stdio: ['ignore', 'pipe', 'pipe'],
      env: {
        PATH: process.env.PATH ?? '/usr/local/bin:/usr/bin:/bin',
        HOME: opts.cwd,
        TMPDIR: opts.cwd,
        LANG: 'C.UTF-8',
        LC_ALL: 'C.UTF-8',
        OMP_THREAD_LIMIT: '1', // keep Tesseract from oversubscribing CPUs under worker concurrency
        ...opts.env,
      },
    });
    const maxOut = opts.maxStdoutBytes ?? 16 * 1024 * 1024;
    let stdout = '';
    let stdoutBytes = 0;
    let stderr = '';
    let settled = false;
    let reason: ProcessingError | null = null;

    const kill = (why: ProcessingError) => {
      if (reason) return;
      reason = why;
      try {
        if (child.pid) process.kill(-child.pid, 'SIGKILL');
      } catch {
        child.kill('SIGKILL');
      }
    };
    const timer = setTimeout(() => kill(new ProcessingError('TIMEOUT', 'Processing took too long')), opts.timeoutMs);
    const onAbort = () => kill(new ProcessingError('CANCELLED', 'Cancelled'));
    opts.signal?.addEventListener('abort', onAbort, { once: true });

    child.stdout.on('data', (c: Buffer) => {
      stdoutBytes += c.length;
      if (stdoutBytes <= maxOut) stdout += c.toString('utf8');
      else kill(new ProcessingError('CONVERSION_FAILED', 'Engine produced too much output'));
    });
    child.stderr.on('data', (c: Buffer) => {
      stderr = (stderr + c.toString('utf8')).slice(-4000);
    });
    const finish = (fn: () => void) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      opts.signal?.removeEventListener('abort', onAbort);
      fn();
    };
    child.on('error', (e) => finish(() => reject(new ProcessingError('SERVICE_UNAVAILABLE', `Engine not available: ${bin} (${(e as NodeJS.ErrnoException).code ?? e.message})`, false))));
    child.on('close', (code) => finish(() => (reason ? reject(reason) : resolve({ code: code ?? -1, stdout, stderrTail: stderr }))));
  });
}
