# Glossary

Terms used by the PDF → JSON extraction workflow. Decisions live in [`adr/`](adr/).

- **Source shape**: the loose snake_case per-car JSON hand-made under
  `src/main/resources/car-pricelists/<brand>/`, parsed by `CarDataModelParser`.
- **Normalized artifact**: the typed `CarDataModel` / `CarDataModelArtifact` family that the
  catalog generator emits and the app loads.
- **Extracted vehicle**: `ExtractedVehicle`, the normalized artifact minus code-owned identity
  (`id`, `sourcePath`, `sourcePdfPath`); what Gemini must return.
- **Extraction envelope**: `PdfExtractionFile` = `schemaVersion` + provenance + extracted
  vehicle; the content of `<stem>.json` ([ADR 0003](adr/0003-extraction-envelope.md)).
- **Provenance**: where an extraction came from: PDF name and SHA-256, model, time, token usage.
- **Raw output**: the model's unvalidated text, saved as `<stem>.raw.json` when a run fails
  ([ADR 0005](adr/0005-failure-handling-and-raw-output.md)).
- **Hard / soft violation**: a rule breach that rejects the output / one that is only reported
  ([ADR 0006](adr/0006-validation-rules.md)).
- **Reference**: an existing hand-made catalog entry for the same PDF, used only by tests to
  compare an extraction (R14–R17).
- **Promotion**: manually copying a reviewed `<stem>.json` next to its PDF in `car-pricelists/`
  so the catalog generator picks it up ([ADR 0007](adr/0007-manual-promotion.md)).
