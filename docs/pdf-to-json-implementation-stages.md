# PDF → JSON extraction: staged implementation plan

Parent plan: [`pdf-to-json-extraction-plan.md`](pdf-to-json-extraction-plan.md) (decisions, model choice,
costs). This document orders the work into stages. Each stage ends with
`./gradlew spotlessApply build test` green and leaves the app releasable.

| Stage | Goal | Live API? | Parent todos |
|---|---|---|---|
| 0 | Integration-test spike: PDF resource + prompt + model definition → JSON string → validated | yes (opt-in) | – |
| 1 | Production `GeminiPdfClient` + JSON-Schema structured output | unit: fake server | json-schema, gemini-pdf-client |
| 2 | Extraction envelope + `PdfExtractionService` (write, overwrite, raw save) | no | extraction-model, extraction-service |
| 3 | `extractPdf` CLI + Gradle task | no | cli |
| 4 | Catalog generator reads extraction files | no | generator-support |
| 5 | PDF Extractor UI tab | no | ui-tab |
| 6 | Docs, ADRs, glossary, README, `.gitignore` | no | docs, gitignore-readme |

---

## Stage 0 – Integration-test spike (start here)

### Goal
Prove, with the smallest amount of code, that Gemini can turn one repository PDF into a JSON
string that satisfies our Kotlin data model and content rules. Results guide prompt wording,
model choice and output budget for later stages. No UI, no CLI, no file writing outside `build/`.

### Test contract
Input: a resource file name (relative to `src/main/resources/car-pricelists/`).
Steps:
1. Load the PDF bytes from the classpath.
2. Build the request: **system prompt** (custom, from a resource file) + **context** (the model
   definition: JSON example + field rules derived from the Kotlin classes) + the PDF as an
   inline `application/pdf` part.
3. Call Gemini native `POST /v1beta/models/{model}:generateContent`.
4. Return the response text as a `String` (expected: one JSON object).
5. Validate it against every requirement in [Validation requirements](#validation-requirements).
6. Write request metadata, raw response and validation report to `build/pdf-extraction-it/`.

### Scope decisions for stage 0
- **Model definition via context, not schema.** Stage 0 sends
  `responseMimeType: application/json` only and describes the shape in the context resource.
  `responseJsonSchema` (generated from `SerialDescriptor`) arrives in stage 1, so stage 0 also
  gives a baseline to compare against.
- **Target type:** a new `@Serializable ExtractedVehicle` (the `vehicle` part of the planned
  envelope): `title, make, model, effectiveDate, effectivePeriod, trims, options,
  technicalSpecifications, additional`. It reuses existing `Trim`, `VehicleOption`,
  `TechnicalSpecifications` etc. unchanged. `id`, `sourcePath`, `sourcePdfPath` are not requested
  from the LLM.
- **Generation config:** `temperature = 0`, `maxOutputTokens` = 65 536 or the model's
  `outputTokenLimit`, whichever is lower (read via existing `fetchGoogleAiModels`).
- **Timeout:** 10 min request/socket, 15 s connect (repo convention).
- **No retries, no fallback model.**

### Fixture
Default: `dacia/dacia jogger.pdf` (1.7 MB, has hand-made reference `dacia jogger.json`).
Override with env `PDF_EXTRACTION_IT_RESOURCE`, e.g. `toyota/Toyota Yaris - Hybrid Brochure.pdf`.
The resource must have a sibling `.json` reference file (used for anchor checks).
Requires real LFS objects (`git lfs pull`); an LFS pointer file (< 1 KB, starts with
`version https://git-lfs`) fails fast with a clear message.

### Files to add

| File | Purpose |
|---|---|
| `src/main/kotlin/com/sps23/aiworkflow/catalog/extraction/ExtractedVehicle.kt` | `@Serializable data class ExtractedVehicle(...)` + `toCarDataModel(sourcePath)` using `DataModelValues.slug` / `effectivePeriod` |
| `src/main/resources/pdf-extraction/system-prompt.md` | Custom extraction instructions (rules below) |
| `src/main/resources/pdf-extraction/model-definition.md` | Model definition context: field list, enum values, units, one compact JSON example |
| `src/test/kotlin/com/sps23/aiworkflow/catalog/extraction/GeminiPdfSpike.kt` | Minimal test-only Ktor caller (`generateContent` with `inline_data`), returns `SpikeResult(text, finishReason, usage, modelVersion)` |
| `src/test/kotlin/com/sps23/aiworkflow/catalog/extraction/ExtractionRequirements.kt` | Pure validator: `validate(json: String, reference: CarDataModel): List<Violation>` |
| `src/test/kotlin/com/sps23/aiworkflow/catalog/extraction/ExtractionRequirementsTest.kt` | Offline unit tests of the validator (always run) |
| `src/test/kotlin/com/sps23/aiworkflow/catalog/extraction/PdfExtractionIntegrationTest.kt` | Live opt-in test wiring it together |

Prompt/context live in `src/main/resources` so stages 1–5 reuse them unchanged.

### System prompt rules (`system-prompt.md`)
- Output exactly one JSON object matching the model definition; no Markdown fences, no prose.
- Extract only facts printed in the PDF; never infer, compute or translate values.
- Unknown/absent scalar → `null`; absent list → `[]`; facts without a field → `additional` map
  (string values, source wording).
- Prices: `currency` = `"EUR"` unless printed otherwise; `minorUnits` = amount × 100 as integer
  (€26,990 → 2699000); always keep the printed text in `rawText`; non-numeric prices →
  `amount: null`.
- `PriceBasis` choice table (e.g. "RRP excl. grants" → `RRP_EXCLUDING_GRANTS`, OTR/ROTR →
  `ON_THE_ROAD`, unlabelled → `PRICE`).
- One `Trim` per published trim level; one `TrimVariant` per priced row; `trimName` equals the
  parent trim `name`.
- `PowertrainType` mapping table (BEV → `ELECTRIC`, PHEV → `PLUG_IN_HYBRID`, HEV/self-charging →
  `HYBRID`, MHEV → `MILD_HYBRID`, TCe/TSI/petrol → `PETROL`, TDI/dCi/diesel → `DIESEL`,
  unclear → `UNKNOWN`).
- Equipment: group by printed section as `category` (snake_case), `isAddition=true` for
  "in addition to …" lists.
- Options: `FitmentState` from matrix symbols (●/S → `STANDARD`, ○/O/price → `OPTIONAL`,
  – → `NOT_AVAILABLE`, otherwise `UNKNOWN`).
- `effectiveDate` = printed validity text; `effectivePeriod` = `YYYY-MM` or `null`.

### Validation requirements
The validator returns all violations (not first only) so one paid run gives a full report.
**Hard** = test fails. **Soft** = reported in `report.md`, does not fail.

Response level (hard)
- R1 `finishReason == STOP` (MAX_TOKENS → "truncated" with token figures).
- R2 Text is a single JSON object (no fences, no trailing text); BOM stripped.
- R3 Strict decode into `ExtractedVehicle` with
  `Json { ignoreUnknownKeys = false; explicitNulls = false; coerceInputValues = false }` succeeds.
  Unknown enum values / missing required fields fail here.
- R4 No forbidden keys at root: `id`, `sourcePath`, `sourcePdfPath`.

Content level (hard)
- R5 `title`, `make`, `model` non-blank; `make` equals reference `make` (case-insensitive).
- R6 `effectivePeriod` is `null` or matches `^\d{4}-(0[1-9]|1[0-2])$`.
- R7 `trims` non-empty; every trim has non-blank unique `name` and ≥ 1 variant.
- R8 Every variant: non-blank `name`, `trimName == parent.name`, ≥ 1 `PriceFact`.
- R9 Every `PriceFact`: non-blank `rawText`; if `amount != null` then `currency` matches
  `^[A-Z]{3}$` and `minorUnits > 0`; `maxAmount`, if present, ≥ `amount`.
- R10 Price sanity: a `PRICE`/`RRP*`/`ON_THE_ROAD` amount is within €5,000–€250,000
  (catches missing ×100 or ×100 twice).
- R11 Every `NumericRange` has `min <= max`; dimensions, when present, are within plausible
  bounds (length 2 500–6 000 mm, width 1 400–2 300 mm, boot 0–3 000 l).
- R12 Every `OptionAvailability.scope` is non-blank; every `VehicleOption.name` non-blank.
- R13 `toCarDataModel()` succeeds and the result round-trips through
  `DataModelCatalogArtifact` serialization.

Reference anchors vs. sibling hand-made JSON (parsed with `CarDataModelParser`)
- R14 (hard) Trim name set equals reference set (case/whitespace-insensitive).
- R15 (hard) Each reference trim's lowest price (any RRP/PRICE basis) appears among the
  extracted amounts of the same trim (exact minor units).
- R16 (soft) Variant count per trim, option count, equipment item count within ±20 % of
  reference; powertrain types per variant equal.
- R17 (soft) Dimensions equal to reference where both present.

Operational (soft, recorded)
- Latency, `usageMetadata` prompt/candidates/thinking tokens, estimated USD cost using the parent
  plan's price table, `modelVersion` returned by the API.

### Test gating & commands
- Gate with Gradle property like the existing Google discovery test. In `build.gradle.kts`
  test filter: exclude `com.sps23.aiworkflow.catalog.extraction.PdfExtractionIntegrationTest`
  unless `-PpdfExtractionIntegration` is present.
- Env: `GOOGLE_AI_API_KEY` (required), `GOOGLE_AI_MODEL` (default `gemini-3.8-flash`),
  `PDF_EXTRACTION_IT_RESOURCE` (default `dacia/dacia jogger.pdf`).
- Missing key with the property set → test fails with an explicit message (not skipped).

```sh
git lfs pull
export GOOGLE_AI_API_KEY=...            # never commit
./gradlew spotlessApply test -PpdfExtractionIntegration \
  --tests 'com.sps23.aiworkflow.catalog.extraction.PdfExtractionIntegrationTest'
# offline validator tests (part of normal ./gradlew test)
./gradlew spotlessApply test --tests 'com.sps23.aiworkflow.catalog.extraction.ExtractionRequirementsTest'
```

Artifacts in `build/pdf-extraction-it/<stem>/`: `request-meta.json` (model, sizes, config; no
key, no PDF bytes), `response.raw.txt`, `vehicle.json` (pretty, if R3 passes), `report.md`
(hard/soft results, tokens, cost).

### Offline tests (`ExtractionRequirementsTest`, always run)
- Valid minimal JSON → no violations.
- One case per rule R1–R15 producing exactly the expected violation id.
- Fenced JSON (```` ```json ````) → R2 violation.
- Unknown enum value → R3 violation.
- `minorUnits` 26990 for €26,990 → R10 violation.
- Reference anchor mismatch → R14/R15 violations.

### Definition of done (stage 0)
- Offline tests green in the normal build; live test excluded by default.
- One live run on `dacia jogger.pdf` with `gemini-3.8-flash` passes all hard requirements;
  `report.md` numbers (tokens, cost, latency, soft deltas) pasted into
  `docs/evidence/pdf-extraction-stage0.md`.
- Optional comparison run with `gemini-3.1-flash-lite` recorded in the same evidence file.
- Prompt/context adjustments learned during the spike committed to the resource files.

### Exit criteria / decision gate
- If hard requirements fail repeatedly for structural reasons → stage 1 schema work is the fix
  (expected).
- If R14/R15 fail on facts (wrong trims/prices) even with schema → revisit model choice (try
  `gemini-3.5-flash`/Pro) before building UI.
- If output truncates → raise `maxOutputTokens` or split extraction (trims/prices vs. equipment)
  — record as ADR.

---

## Stage 1 – Production client + structured output
- `JsonSchemaGenerator`: `SerialDescriptor` → JSON Schema subset accepted by Gemini
  (`object/properties/required`, `array/items`, `enum`, nullable via `type: [x, "null"]`,
  `Map<String,String>` → `additionalProperties: {type: string}`). Unit tests on `ExtractedVehicle`.
- `catalog/extraction/GeminiPdfClient` in `main`: promote spike code; add `responseJsonSchema`,
  50 MB / 1000-page size gate, HTTP error → `FailureKind` mapping reused from `GoogleAiClient`
  (`googleMetadataFailure` style), key only in `x-goog-api-key` header.
- Tests with a fake Ktor server (pattern: `FakeOpenRouterServer`): request body shape,
  MAX_TOKENS → truncation error, 401/429/5xx mapping, usage parsing.
- Integration test switches to `GeminiPdfClient`; rerun live and record schema vs. no-schema
  results in the evidence file. `GeminiPdfSpike` deleted.

## Stage 2 – Envelope + service
- `PdfExtractionFile(schemaVersion = 1, provenance, vehicle)`,
  `ExtractionProvenance(pdfFileName, pdfSha256, model, extractedAt, inputTokens, outputTokens)`.
- `PdfExtractionService.extract(pdf: Path, outputDir: Path, overwrite: suspend (Path) -> Boolean)`:
  size gate → sha256 → client → `ExtractionRequirements` hard rules R1–R13 (move validator from
  test to `main`; R14+ remain test-only since no reference exists in production) → atomic write
  `<stem>.json`. On failure: return `Failed(raw, violations)`; `saveRaw()` writes `<stem>.raw.json`.
- Unit tests with fake client: success, overwrite declined, invalid output never writes
  `<stem>.json`, raw save.

## Stage 3 – CLI
- `PdfExtractCli` + Gradle `extractPdf` task:
  `./gradlew extractPdf --args='<pdf> [outputDir] [--overwrite]'`.
- Output dir resolution: arg → `PDF_EXTRACT_OUTPUT_DIR` → `extracted-catalog/`.
- Exit non-zero with violation list; prints tokens + estimated cost. Tests like `CarPromptCliTest`.

## Stage 4 – Catalog generator
- `DataModelCatalogGenerator`: JSON with root `schemaVersion` → decode `PdfExtractionFile`,
  `vehicle.toCarDataModel(relativePath)`; otherwise `CarDataModelParser`. PDF linking unchanged.
- Tests: mixed folder (source-shape + extraction file), unknown `schemaVersion` fails clearly.

## Stage 5 – UI tab
- `TabRow` in `CarPromptApp`: Recommender | PDF Extractor.
- `PdfExtractorController` (state: Idle / Extracting / Done / Failed) + `PdfExtractorScreen`:
  PDF chooser, output folder chooser, Google key + model picker reuse (default
  `gemini-3.8-flash`), Extract, overwrite confirmation dialog, summary (trims/variants/options,
  tokens, est. cost, path, Open folder), error panel with Save raw / Copy raw.
- Controller unit tests with fake service; screen render test gated like `CarPromptScreenTest`.

## Stage 6 – Docs & housekeeping
- ADRs in `docs/adr/` (one per parent-plan decision + stage-0 findings), `docs/glossary.md`.
- README: extraction section, data-transmission warning (PDF sent to Google; free tier may be
  used for training), commands, cost note.
- `.gitignore`: `extracted-catalog/`.

## Risks tracked across stages
- Output size vs. `maxOutputTokens` (equipment-heavy brochures).
- Gemini schema subset limits (deep nesting, `additionalProperties`).
- LLM nondeterminism → anchors kept few and exact; everything else soft.
- Price promotion ends 2026-12-31 (cost doubles) – cost estimate reads a price table constant to
  update.
