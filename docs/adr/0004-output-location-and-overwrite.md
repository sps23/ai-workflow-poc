# 0004 – Output folder, file naming and overwrite policy

Status: accepted, 2026-10-31.

## Context
Extraction results need human review before they enter the catalog, and each run is billed.

## Decision
- Output folder: explicit argument or folder chooser → `PDF_EXTRACT_OUTPUT_DIR` →
  `extracted-catalog/` (git-ignored). The CLI resolves relative paths against the repository
  root.
- File name `<pdf stem>.json`.
- `PdfExtractionService` asks the overwrite callback **before** the paid call; declining
  returns `Declined` and Gemini is not called. The CLI declines unless `--overwrite` is given
  (exit 3); the UI shows an `AlertDialog`.
- Writes are atomic: a UUID-named temp file in the target folder, then `ATOMIC_MOVE`.

## Consequences
- An invalid `<stem>.json` is never written.
- `Files.createTempFile` was rejected because it creates files with mode 0600 (evidence
  Run 3); a test asserts the default permissions.
