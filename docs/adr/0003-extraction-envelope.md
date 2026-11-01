# 0003 – Extraction envelope with `schemaVersion` and provenance

Status: accepted, 2026-10-31.

## Context
An extracted file must be recognizable by the catalog generator, traceable to its PDF and
model, and evolvable.

## Decision
`PdfExtractionFile` (`catalog/extraction/PdfExtractionFile.kt`):

```json
{ "schemaVersion": 1,
  "provenance": { "pdfFileName", "pdfSha256", "model", "extractedAt", "inputTokens", "outputTokens" },
  "vehicle": { ...ExtractedVehicle } }
```

- `schemaVersion` is the integer `PDF_EXTRACTION_SCHEMA_VERSION` (1). Its presence at the root
  marks a file as an extraction file.
- `model` is the response's `modelVersion` when present, else the requested model ID.
- `outputTokens` = candidate tokens + thinking tokens (what is billed as output);
  null when usage is unavailable (approved for non-Google providers, 2026-11-01).
- Identity (`id`, `sourcePath`, `sourcePdfPath`) is computed by code at promotion, never by the
  model.

## Consequences
- Readers reject any other `schemaVersion` (2, `"1"`, 1.5) with "unsupported schemaVersion";
  a new version needs an explicit reader change.
- Provenance is for humans and is ignored by the catalog generator.
