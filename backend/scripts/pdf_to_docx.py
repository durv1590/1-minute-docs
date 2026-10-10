"""PDF -> DOCX using pdf2docx. Usage: pdf_to_docx.py INPUT.pdf OUTPUT.docx

Prints one JSON line with warnings. Exit code != 0 on failure. Never prints document text.
"""
import json
import logging
import sys

logging.disable(logging.CRITICAL)


def main() -> int:
    if len(sys.argv) != 3:
        print(json.dumps({"error": "usage"}))
        return 2
    src, dst = sys.argv[1], sys.argv[2]
    from pdf2docx import Converter  # imported lazily so usage errors are fast

    warnings = []
    cv = Converter(src)
    try:
        cv.convert(dst, multi_processing=False)
    finally:
        cv.close()
    print(json.dumps({"ok": True, "warnings": warnings}))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as exc:  # noqa: BLE001 - report the class only, not content
        print(json.dumps({"error": type(exc).__name__}))
        sys.exit(1)
