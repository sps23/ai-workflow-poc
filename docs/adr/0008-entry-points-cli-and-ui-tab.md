# 0008 – Entry points: `extractPdf` CLI and a PDF Extractor tab

Status: accepted, 2026-10-31.

## Context
Extraction is occasional, one brochure at a time, and billed per run.

## Decision
- One PDF per run. Batch processing, the Files API and auto-promotion are out of scope.
- CLI: Gradle `extractPdf` (`PdfExtractCli`), non-interactive. Exit codes: 0 written, 1 failed,
  2 usage error or missing key, 3 target exists without `--overwrite`.
- UI: a **PDF Extractor** tab in the existing window (`CarCatalogApp`, `TabRow`: Recommender |
  PDF Extractor), with its own Google key and model fields and picker.
- Both report tokens only, with no cost estimate (prices change; see 0002).
- The PDF tab reuses Car search's DEBUG panel and metadata export for one native Gemini extraction
  request plus a separate model-discovery timing summary, on success and failure. PDF input-token
  estimates remain unavailable (approved 2026-11-01); capture semantics and export boundaries are
  documented in [README](../../README.md#pdf--json-extraction). Metadata is runtime-only, outside
  the extraction envelope.
- Tab switching is locked while either tab is busy, because leaving a tab cancels its coroutine.

## Consequences
- `TabRow` is used because material3 `PrimaryTabRow` is experimental and fails the build.
- `PdfExtractorScreenTest` renders through Skia and is skipped with `-PskipUiRenderTests`, like
  the other render tests.
