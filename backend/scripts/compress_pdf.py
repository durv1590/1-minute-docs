"""Compresses a PDF without Ghostscript. Usage: compress_pdf.py INPUT.pdf OUTPUT.pdf PRESET

Uses pikepdf (MPL-2.0, on qpdf: Apache-2.0) and Pillow (HPND), so no AGPL component is involved.
- Raster images (8-bit Gray/RGB, no colour-key mask) are downsampled so they are never below the preset's DPI at full-page
  display size, then re-encoded as JPEG, but only when that is at least 5% smaller than the original stream.
- Soft masks (/SMask) are kept as they are; the PDF specification lets a soft mask have its own resolution.
- Streams are recompressed, object streams generated, and unreferenced objects dropped by qpdf on save.
Text, fonts and vector content are not touched, so text stays selectable.
Prints one JSON line: {"ok": true, "imagesRecompressed": N, "imagesSkipped": M}.
"""
import io
import json
import math
import sys

import pikepdf
from pikepdf import Name, PdfImage
from PIL import Image

PRESETS = {
    "high_quality": {"dpi": 200, "quality": 85},
    "balanced": {"dpi": 144, "quality": 75},
    "small_file": {"dpi": 96, "quality": 60},
}
MAX_PIXELS = 40_000_000


def largest_page_inches(pdf: pikepdf.Pdf) -> float:
    longest = 0.0
    for page in pdf.pages:
        box = [float(v) for v in page.mediabox]
        longest = max(longest, abs(box[2] - box[0]), abs(box[3] - box[1]))
    return max(longest, 1.0) / 72.0


def recompress(obj: pikepdf.Stream, max_long_px: int, quality: int) -> bool:
    if obj.get("/ImageMask", False) or "/Mask" in obj or "/Decode" in obj:
        return False
    if int(obj.get("/BitsPerComponent", 8)) != 8:
        return False
    width, height = int(obj.Width), int(obj.Height)
    if width * height > MAX_PIXELS:
        return False
    image = PdfImage(obj).as_pil_image()
    if image.mode not in ("L", "RGB"):
        return False
    scale = min(1.0, max_long_px / max(width, height))
    if scale < 1.0:
        image = image.resize((max(1, math.floor(width * scale)), max(1, math.floor(height * scale))), Image.LANCZOS)
    buf = io.BytesIO()
    image.save(buf, format="JPEG", quality=quality, optimize=True)
    data = buf.getvalue()
    if len(data) >= len(obj.read_raw_bytes()) * 0.95:
        return False
    for key in ("/DecodeParms", "/Filter"):
        if key in obj:
            del obj[key]
    obj.write(data, filter=Name.DCTDecode)
    obj.Width, obj.Height = image.width, image.height
    obj.ColorSpace = Name.DeviceGray if image.mode == "L" else Name.DeviceRGB
    obj.BitsPerComponent = 8
    return True


def main() -> int:
    src, dst, preset_name = sys.argv[1], sys.argv[2], sys.argv[3]
    preset = PRESETS.get(preset_name, PRESETS["balanced"])
    done = skipped = 0
    with pikepdf.open(src) as pdf:
        max_long_px = math.ceil(largest_page_inches(pdf) * preset["dpi"])
        for obj in pdf.objects:
            if not isinstance(obj, pikepdf.Stream) or obj.get("/Subtype") != Name.Image:
                continue
            try:
                if recompress(obj, max_long_px, preset["quality"]):
                    done += 1
                else:
                    skipped += 1
            except Exception:  # noqa: BLE001 - an image we cannot decode is left untouched
                skipped += 1
        pdf.remove_unreferenced_resources()
        pdf.save(dst, compress_streams=True, recompress_flate=True, object_stream_mode=pikepdf.ObjectStreamMode.generate)
    print(json.dumps({"ok": True, "imagesRecompressed": done, "imagesSkipped": skipped}))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as exc:  # noqa: BLE001
        print(json.dumps({"ok": False, "error": type(exc).__name__}))
        sys.exit(1)
