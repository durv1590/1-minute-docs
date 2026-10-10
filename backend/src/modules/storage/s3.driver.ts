import {
  DeleteObjectCommand,
  GetObjectCommand,
  HeadBucketCommand,
  HeadObjectCommand,
  PutObjectCommand,
  S3Client,
} from '@aws-sdk/client-s3';
import { createReadStream, createWriteStream } from 'node:fs';
import { stat } from 'node:fs/promises';
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import { assertSafeKey, StorageDriver } from './storage.driver';

/**
 * S3-compatible storage (AWS S3, Cloudflare R2, MinIO, …). The bucket must be private; objects are server-side
 * encrypted and should additionally be covered by a bucket lifecycle rule as a backstop for the retention sweeper.
 */
export class S3StorageDriver implements StorageDriver {
  private readonly s3: S3Client;

  constructor(private readonly bucket: string, opts: { region: string; endpoint?: string; forcePathStyle: boolean }) {
    this.s3 = new S3Client({ region: opts.region, endpoint: opts.endpoint, forcePathStyle: opts.forcePathStyle });
  }

  async put(key: string, sourcePath: string, contentType: string): Promise<void> {
    assertSafeKey(key);
    const { size } = await stat(sourcePath);
    await this.s3.send(
      new PutObjectCommand({
        Bucket: this.bucket,
        Key: key,
        Body: createReadStream(sourcePath),
        ContentLength: size,
        ContentType: contentType,
        ServerSideEncryption: 'AES256',
      }),
    );
  }

  async download(key: string, destinationPath: string): Promise<void> {
    await pipeline(await this.stream(key), createWriteStream(destinationPath));
  }

  async stream(key: string): Promise<Readable> {
    assertSafeKey(key);
    const out = await this.s3.send(new GetObjectCommand({ Bucket: this.bucket, Key: key }));
    if (!out.Body) throw new Error('empty object body');
    return out.Body as Readable;
  }

  async delete(key: string): Promise<void> {
    assertSafeKey(key);
    await this.s3.send(new DeleteObjectCommand({ Bucket: this.bucket, Key: key }));
  }

  async exists(key: string): Promise<boolean> {
    assertSafeKey(key);
    try {
      await this.s3.send(new HeadObjectCommand({ Bucket: this.bucket, Key: key }));
      return true;
    } catch {
      return false;
    }
  }

  async check(): Promise<void> {
    await this.s3.send(new HeadBucketCommand({ Bucket: this.bucket }));
  }
}
