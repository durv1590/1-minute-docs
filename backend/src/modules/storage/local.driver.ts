import { createReadStream } from 'node:fs';
import { access, copyFile, mkdir, rename, rm, writeFile } from 'node:fs/promises';
import { dirname, join, resolve, sep } from 'node:path';
import { Readable } from 'node:stream';
import { assertSafeKey, StorageDriver } from './storage.driver';

/** Filesystem storage for development and single-node deployments. */
export class LocalStorageDriver implements StorageDriver {
  private readonly root: string;

  constructor(rootDir: string) {
    this.root = resolve(rootDir);
  }

  private path(key: string): string {
    assertSafeKey(key);
    const p = resolve(join(this.root, key));
    if (!p.startsWith(this.root + sep)) throw new Error('storage key escapes root');
    return p;
  }

  async put(key: string, sourcePath: string): Promise<void> {
    const dest = this.path(key);
    await mkdir(dirname(dest), { recursive: true, mode: 0o700 });
    const tmp = `${dest}.part`;
    await copyFile(sourcePath, tmp);
    await rename(tmp, dest);
  }

  async download(key: string, destinationPath: string): Promise<void> {
    await copyFile(this.path(key), destinationPath);
  }

  async stream(key: string): Promise<Readable> {
    const p = this.path(key);
    await access(p);
    return createReadStream(p);
  }

  async delete(key: string): Promise<void> {
    await rm(this.path(key), { force: true });
  }

  async exists(key: string): Promise<boolean> {
    try {
      await access(this.path(key));
      return true;
    } catch {
      return false;
    }
  }

  async check(): Promise<void> {
    await mkdir(this.root, { recursive: true, mode: 0o700 });
    const probe = join(this.root, '.probe');
    await writeFile(probe, 'ok');
    await rm(probe, { force: true });
  }
}
