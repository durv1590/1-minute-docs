import { contentDisposition, replaceExtension, safeFileName } from '../../src/common/filenames';

describe('file names', () => {
  it('strips paths and dangerous characters', () => {
    expect(safeFileName('../../etc/passwd', 'x')).toBe('passwd');
    expect(safeFileName('C:\\a\\b"c.pdf', 'x')).toBe('b_c.pdf');
    expect(safeFileName('..', 'fallback.pdf')).toBe('fallback.pdf');
    expect(safeFileName('a\u0000b.pdf', 'x')).toBe('a_b.pdf');
  });

  it('keeps Hindi names and encodes them for headers', () => {
    expect(safeFileName('नोट्स.pdf', 'x')).toBe('नोट्स.pdf');
    const header = contentDisposition('नोट्स.pdf');
    expect(header).toContain("filename*=UTF-8''%E0%A4%A8");
    expect(header).toMatch(/^attachment; filename="[\x20-\x7e]+";/);
  });

  it('replaces extensions', () => {
    expect(replaceExtension('report.final.docx', 'pdf')).toBe('report.final.pdf');
    expect(replaceExtension('noext', 'pdf')).toBe('noext.pdf');
  });
});
