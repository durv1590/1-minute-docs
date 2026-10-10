import { execFileSync } from 'node:child_process';
import { copyFileSync, mkdtempSync, readdirSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

/** Generates real test documents with the same engines the server uses (no binary fixtures in git). */
export class Fixtures {
  readonly dir = mkdtempSync(join(tmpdir(), 'omd-fixtures-'));
  private readonly profile = `-env:UserInstallation=file://${join(this.dir, 'lo-profile')}`;

  private soffice(args: string[]): void {
    execFileSync('soffice', ['--headless', '--norestore', this.profile, ...args], { cwd: this.dir, stdio: 'ignore', timeout: 120_000 });
  }

  html(name: string, body: string): string {
    const p = join(this.dir, `${name}.html`);
    writeFileSync(p, `<!doctype html><html><head><meta charset="utf-8"></head><body style="font-family:'Noto Sans','Noto Sans Devanagari'">${body}</body></html>`);
    return p;
  }

  docx(name: string, body: string): string {
    const src = this.html(name, body);
    this.soffice(['--convert-to', 'docx:MS Word 2007 XML', '--outdir', this.dir, src]);
    return join(this.dir, `${name}.docx`);
  }

  textPdf(name: string, body: string): string {
    const src = this.html(name, body);
    this.soffice(['--convert-to', 'pdf:writer_pdf_Export', '--outdir', this.dir, src]);
    return join(this.dir, `${name}.pdf`);
  }

  /** Renders the first page of a PDF to PNG. */
  png(fromPdf: string, name: string, dpi = 200): string {
    const base = join(this.dir, name);
    execFileSync('pdftoppm', ['-r', String(dpi), '-png', '-singlefile', '-f', '1', '-l', '1', fromPdf, base]);
    return `${base}.png`;
  }

  /** Image-only PDF (simulates a scan) built from a PNG. */
  scannedPdf(fromPng: string, name: string): string {
    const copy = join(this.dir, `${name}.png`);
    copyFileSync(fromPng, copy);
    this.soffice(['--convert-to', 'pdf:draw_pdf_Export', '--outdir', this.dir, copy]);
    return join(this.dir, `${name}.pdf`);
  }

  /** A PDF carrying a large, high-resolution photographic-style image, which compression can shrink. */
  imageHeavyPdf(name: string): string {
    const w = 2400, h = 3300;
    const header = Buffer.from(`P6\n${w} ${h}\n255\n`, 'ascii');
    const pixels = Buffer.alloc(w * h * 3);
    let seed = 12345;
    for (let i = 0; i < w * h; i++) {
      seed = (seed * 1103515245 + 12345) & 0x7fffffff;
      const x = i % w, y = Math.floor(i / w);
      const noise = seed % 40;
      pixels[i * 3] = (x * 255) / w + noise > 255 ? 255 : (x * 255) / w + noise;
      pixels[i * 3 + 1] = (y * 255) / h;
      pixels[i * 3 + 2] = 128 + (noise - 20);
    }
    const ppm = join(this.dir, `${name}.ppm`);
    writeFileSync(ppm, Buffer.concat([header, pixels]));
    // Keep full resolution and lossless compression so the input is a realistic oversized scan/photo.
    this.soffice(['--convert-to', 'pdf:draw_pdf_Export:{"ReduceImageResolution":{"type":"boolean","value":"false"},"UseLosslessCompression":{"type":"boolean","value":"true"}}', '--outdir', this.dir, ppm]);
    return join(this.dir, `${name}.pdf`);
  }

  encryptedPdf(from: string, name: string): string {
    const out = join(this.dir, `${name}.pdf`);
    execFileSync('qpdf', ['--encrypt', 'user-pass', 'owner-pass', '256', '--', from, out]);
    return out;
  }

  raw(name: string, content: Buffer | string): string {
    const p = join(this.dir, name);
    writeFileSync(p, content);
    return p;
  }

  list(): string[] {
    return readdirSync(this.dir);
  }
}
