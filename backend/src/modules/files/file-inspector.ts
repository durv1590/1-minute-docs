import { open } from 'node:fs/promises';
import yauzl from 'yauzl';

export type DetectedFormat = 'pdf' | 'jpeg' | 'png' | 'webp' | 'docx' | 'odt' | 'doc' | 'rtf' | 'unknown';

export const CONTENT_TYPES: Record<Exclude<DetectedFormat, 'unknown'>, string> = {
  pdf: 'application/pdf',
  jpeg: 'image/jpeg',
  png: 'image/png',
  webp: 'image/webp',
  docx: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  odt: 'application/vnd.oasis.opendocument.text',
  doc: 'application/msword',
  rtf: 'application/rtf',
};

const MAX_ZIP_ENTRIES = 5000;

/** Determines a file's real format from its bytes (never from the client-supplied name or MIME type). */
export async function detectFormat(path: string): Promise<DetectedFormat> {
  const fh = await open(path, 'r');
  const header = Buffer.alloc(1024);
  let n = 0;
  try {
    n = (await fh.read(header, 0, header.length, 0)).bytesRead;
  } finally {
    await fh.close();
  }
  const h = header.subarray(0, n);
  if (n >= 5 && h.indexOf('%PDF-') >= 0 && h.indexOf('%PDF-') < 1024) return 'pdf';
  if (n >= 3 && h[0] === 0xff && h[1] === 0xd8 && h[2] === 0xff) return 'jpeg';
  if (n >= 8 && h.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))) return 'png';
  if (n >= 12 && h.toString('latin1', 0, 4) === 'RIFF' && h.toString('latin1', 8, 12) === 'WEBP') return 'webp';
  if (n >= 8 && h.subarray(0, 8).equals(Buffer.from([0xd0, 0xcf, 0x11, 0xe0, 0xa1, 0xb1, 0x1a, 0xe1]))) return 'doc';
  if (n >= 5 && h.toString('latin1', 0, 5) === '{\\rtf') return 'rtf';
  if (n >= 4 && h[0] === 0x50 && h[1] === 0x4b && h[2] === 0x03 && h[3] === 0x04) return classifyZip(path);
  return 'unknown';
}

function classifyZip(path: string): Promise<DetectedFormat> {
  return new Promise((resolvePromise) => {
    yauzl.open(path, { lazyEntries: true, autoClose: true }, (err, zip) => {
      if (err || !zip) return resolvePromise('unknown');
      if (zip.entryCount > MAX_ZIP_ENTRIES) {
        zip.close();
        return resolvePromise('unknown');
      }
      let hasContentTypes = false;
      let hasWordDocument = false;
      let odtMime: string | null = null;
      const finish = (result: DetectedFormat) => {
        try { zip.close(); } catch { /* already closed */ }
        resolvePromise(result);
      };
      zip.on('error', () => finish('unknown'));
      zip.on('end', () => finish(hasContentTypes && hasWordDocument ? 'docx' : odtMime === CONTENT_TYPES.odt ? 'odt' : 'unknown'));
      zip.on('entry', (entry: yauzl.Entry) => {
        if (entry.fileName === '[Content_Types].xml') hasContentTypes = true;
        if (entry.fileName === 'word/document.xml') hasWordDocument = true;
        if (entry.fileName === 'mimetype' && entry.uncompressedSize < 256) {
          zip.openReadStream(entry, (e, stream) => {
            if (e || !stream) return zip.readEntry();
            const chunks: Buffer[] = [];
            stream.on('data', (c: Buffer) => chunks.push(c));
            stream.on('end', () => {
              odtMime = Buffer.concat(chunks).toString('ascii').trim();
              zip.readEntry();
            });
            stream.on('error', () => zip.readEntry());
          });
          return;
        }
        zip.readEntry();
      });
      zip.readEntry();
    });
  });
}
