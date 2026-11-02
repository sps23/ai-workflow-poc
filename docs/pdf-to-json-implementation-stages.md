# PDF → JSON extraction: staged implementation plan

Parent plan: [`pdf-to-json-extraction-plan.md`](pdf-to-json-extraction-plan.md) (decisions, model choice,
costs). This document orders the work into stages. Each stage ends with
`./gradlew spotlessApply build test` green and leaves the app releasable.

Current desktop provider/default behavior supersedes the historical stage notes below:
[desktop plan](openrouter-desktop-ui-plan.md) and
[ADR 0002](adr/0002-gemini-native-generatecontent.md), approved 2026-11-01.

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

**Status: implemented 2026-10-31.** Live run on `dacia jogger.pdf` with `gemini-3.8-flash`
passed all hard requirements (USD 0.05, 2 min). Evidence:
[`evidence/pdf-extraction-stage0.md`](evidence/pdf-extraction-stage0.md). Usage: README section
"PDF → JSON extraction spike (stage 0)". Also added `GeminiPdfSpikeTest` (offline request/response
parsing tests). R13 has no dedicated failing test: it cannot fail once R3 decoding succeeds.

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
- **Generation config:** temperature omitted (model default; Google advises 1.0 for Gemini 3)
  unless env `PDF_EXTRACTION_IT_TEMPERATURE` is set; the value used is recorded in
  `request-meta.json`. `maxOutputTokens` = 65 536 or the model's
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
  `ON_THE_ROAD`, unlabelled → `PRICE`, subsidised/after-grant price → `RRP_INCLUDING_GRANTS`).
- Grants/reliefs (`VRT_REBATE`, `SEAI_GRANT`) are reductions stored as positive magnitudes
  ("-€3,500" → 350000); the full retail price keeps its own basis and is never derived.
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
  `^[A-Z]{3}$` and `minorUnits >= 0` (zero-valued grants/options are valid); `maxAmount`, if present,
  ≥ `amount`. Negative amounts fail; R10 separately rejects implausible list prices.
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
> **Status: implemented.** Live run with schema passed (0 hard, 5 soft); see
> [evidence Run 2](evidence/pdf-extraction-stage0.md#run-2--2026-10-31-stage-1-responsejsonschema).
> Decisions from stage 0 findings: hard rule **R18** (variant names unique within a trim, normalized);
> size gate is byte-only (50 MB) + `%PDF-` header — page limit left to Gemini's 400, no PDF library;
> schema is always sent (stage 0 run is the no-schema baseline). Nullable non-primitives use
> `anyOf: [schema, {type: null}]`; recursion, polymorphism and non-string map keys are rejected.

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
> **Status: implemented (offline; no paid call needed).** Decisions: overwrite confirmation runs
> *before* the Gemini call (declining costs nothing); the whole `ExtractionRequirements` moved to
> `main` — the service calls it without a reference, so R14–R17 simply don't run; provenance
> `outputTokens` = billed output (candidates + thinking); `model` = response `modelVersion`, else the
> requested ID; `<stem>.raw.json` is written automatically whenever a failure carries model text
> (hard violations or truncation); every outcome is a sealed `PdfExtractionOutcome`
> (`Written` / `Declined` / `Failed(kind, message, violations, rawPath, usage)`), client exceptions
> included; the service calls `models.list` each run to verify the model and cap `maxOutputTokens`.
> `GeminiPdfClient` implements the new `PdfExtractionClient` interface so tests use a fake.
- `PdfExtractionFile(schemaVersion = 1, provenance, vehicle)`,
  `ExtractionProvenance(pdfFileName, pdfSha256, model, extractedAt, inputTokens, outputTokens)`.
- `PdfExtractionService.extract(pdf: Path, outputDir: Path, overwrite: suspend (Path) -> Boolean)`:
  size gate → sha256 → client → `ExtractionRequirements` hard rules R1–R13 (move validator from
  test to `main`; R14+ remain test-only since no reference exists in production) → atomic write
  `<stem>.json`. On failure: return `Failed(raw, violations)`; `saveRaw()` writes `<stem>.raw.json`.
- Unit tests with fake client: success, overwrite declined, invalid output never writes
  `<stem>.json`, raw save.

## Stage 3 – CLI
> **Status: implemented.** Decisions: non-interactive — existing `<stem>.json` without `--overwrite`
> exits 3 before any Gemini call; prints tokens only (no cost estimate; price table stays test-only);
> `extracted-catalog/` git-ignored now. Exit codes: 0 written, 1 extraction failed (hard violations
> listed, raw path printed), 2 usage/missing key, 3 file exists. Gradle task runs with
> `workingDir = projectDir`, so relative paths resolve against the repo root. `runPdfExtract` takes a
> client factory so tests use a fake (`PdfExtractCliTest`).
- `PdfExtractCli` + Gradle `extractPdf` task:
  `./gradlew extractPdf --args='<pdf> [outputDir] [--overwrite]'`.
- Output dir resolution: arg → `PDF_EXTRACT_OUTPUT_DIR` → `extracted-catalog/`.
- Exit non-zero with violation list; prints tokens + estimated cost. Tests like `CarPromptCliTest`.

## Stage 4 – Catalog generator
> **Status: implemented.** Decisions: detection lives in a shared `CatalogSourceParser.parse(text,
> relativePath)` used by **both** `DataModelCatalogGenerator` and `DataModelCatalogLoader` (a promoted
> file would otherwise break runtime/classpath loading). Promoted files are strictly decoded and
> re-checked with hard rules R5–R13/R18 (`ExtractionRequirements.validateVehicle`, no finishReason),
> since they may be hand-edited; any violation fails generation with the file + rule list and keeps
> the previous output. `schemaVersion` must be the integer 1; anything else (2, `"1"`, 1.5) fails with
> "unsupported schemaVersion". Verified on the real stage-3 Jogger file: `dacia-jogger`, 3 trims,
> period 2026-08, linked to `dacia/dacia jogger.pdf`.
- `DataModelCatalogGenerator`: JSON with root `schemaVersion` → decode `PdfExtractionFile`,
  `vehicle.toCarDataModel(relativePath)`; otherwise `CarDataModelParser`. PDF linking unchanged.
- Tests: mixed folder (source-shape + extraction file), unknown `schemaVersion` fails clearly.

## Stage 5 – UI tab
> **Status: implemented.** Decisions: the tab has its own Google key + model fields and picker
> (default `gemini-3.8-flash`); summary shows tokens only (no cost estimate); the error panel shows
> the auto-saved `<stem>.raw.json` path with Copy raw output + Open folder (no separate Save raw);
> overwrite confirmation is a Compose `AlertDialog` driven by controller state
> (`pendingOverwrite` + `answerOverwrite`) and resolved before the paid call. `CarCatalogApp` uses
> `TabRow` (material3 `PrimaryTabRow` is experimental) and locks tab switching while either tab is
> busy, since leaving a tab cancels its coroutine. Controller states: Idle / Extracting / Done /
> Declined / Failed. Tests: `PdfExtractorControllerTest` (9, fake action) and
> `PdfExtractorScreenTest` (2 renders, excluded by `-PskipUiRenderTests`).
- `TabRow` in `CarPromptApp`: Recommender | PDF Extractor.
- `PdfExtractorController` (state: Idle / Extracting / Done / Failed) + `PdfExtractorScreen`:
  PDF chooser, output folder chooser, Google key + model picker reuse (default
  `gemini-3.8-flash`), Extract, overwrite confirmation dialog, summary (trims/variants/options,
  tokens, est. cost, path, Open folder), error panel with Save raw / Copy raw.
- Controller unit tests with fake service; screen render test gated like `CarPromptScreenTest`.

## Stage 6 – Docs & housekeeping
> **Status: implemented.** Decisions grouped into 8 ADRs in `docs/adr/` (0001 normalized output +
> generated schema, 0002 Gemini/`generateContent`/no SDK, 0003 envelope, 0004 output folder +
> overwrite, 0005 failure + raw output, 0006 validation rules incl. stage-0 findings, 0007 manual
> promotion, 0008 CLI + UI tab); terms in `docs/glossary.md`; README section links both.
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
