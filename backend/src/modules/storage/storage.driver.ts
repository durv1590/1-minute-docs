import { Readable } from 'node:stream';

/** Object storage for temporary job files. Keys are server-generated; user file names never become paths. */
export interface StorageDriver {
  put(key: string, sourcePath: string, contentType: string): Promise<void>;
  download(key: string, destinationPath: string): Promise<void>;
  stream(key: string): Promise<Readable>;
  delete(key: string): Promise<void>;
  exists(key: string): Promise<boolean>;
  /** Readiness probe. */
  check(): Promise<void>;
}

export const STORAGE = Symbol('STORAGE');

const KEY_RE = /^[a-z0-9][a-z0-9/_.-]{0,200}$/;

export function assertSafeKey(key: string): void {
  if (!KEY_RE.test(key) || key.includes('..') || key.includes('//')) {
    throw new Error('unsafe storage key');
  }
}
