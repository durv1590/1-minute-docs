/** Produces a safe display file name for Content-Disposition: no path parts, control or quote characters. */
export function safeFileName(input: string | undefined, fallback: string): string {
  const base = (input ?? '').split(/[\\/]/).pop() ?? '';
  let name = base.replace(/[\u0000-\u001f\u007f"<>:|?*]/g, '_').trim().replace(/^\.+/, '');
  if (name.length > 150) name = name.slice(0, 150);
  return name || fallback;
}

export function replaceExtension(name: string, ext: string): string {
  const dot = name.lastIndexOf('.');
  const base = dot > 0 ? name.slice(0, dot) : name;
  return `${base}.${ext}`;
}

/** RFC 6266 / 5987 header value supporting Unicode (e.g. Hindi) names. */
export function contentDisposition(name: string): string {
  const ascii = name.replace(/[^\x20-\x7e]/g, '_').replace(/["\\]/g, '_');
  return `attachment; filename="${ascii}"; filename*=UTF-8''${encodeURIComponent(name)}`;
}
