# 0007 – Manual promotion through a shared `CatalogSourceParser`

Status: accepted, 2026-10-31.

## Context
Extracted values can be hallucinated, so a human reviews each file before it enters the catalog.
Both the build-time generator and the runtime loader read `car-pricelists/`.

## Decision
- Promotion is manual: copy a reviewed `<stem>.json` next to its PDF in
  `car-pricelists/<brand>/`, then run `./gradlew generateDataModelCatalog`.
- `CatalogSourceParser.parse(text, relativePath)` is used by both `DataModelCatalogGenerator`
  and `DataModelCatalogLoader`. A root `schemaVersion` key → strict decode of
  `PdfExtractionFile`, hard re-check with `validateVehicle` (the file may be hand-edited),
  `vehicle.toCarDataModel(relativePath)`; otherwise → `CarDataModelParser`.
- Provenance is ignored; PDF linking is unchanged.

## Consequences
- A broken promoted file fails generation with its path and rule list, and keeps the previous
  generated catalog.
- No auto-promotion; no existing brand file has a `schemaVersion` key.
