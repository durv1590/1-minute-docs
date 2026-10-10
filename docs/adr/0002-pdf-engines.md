# ADR-0002: PDF engines on device
Status: Accepted · 2026-10-10

Context: Need merge/split/rotate/encrypt/annotate/compress without rasterising text, plus correct Hindi rendering.
Options: iText 7 (AGPL — rejected for licensing), Apache PDFBox (desktop; depends on java.awt), PdfBox-Android (Apache-2.0 port),
MuPDF (AGPL — rejected), platform PdfDocument/PdfRenderer.
Decision: PdfBox-Android for object-level manipulation; platform `PdfDocument` + `StaticLayout` for generating text documents
(Devanagari shaping, font embedding); platform `PdfRenderer` for rendering.
Consequences: PDFBox cannot shape Devanagari, so text *overlays* containing complex scripts are drawn as high-resolution
transparent images; Latin overlays and shapes are vector. Documented as a limitation.
