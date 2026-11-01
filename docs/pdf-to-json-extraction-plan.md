# PDF → normalized JSON extraction with Gemini (plan)

## Problem
Per-car JSON files under `src/main/resources/car-pricelists/<brand>/` are hand-made in a
loose snake_case "source shape", then normalized by `CarDataModelParser` into the Kotlin
`CarDataModelArtifact` family. Adding a new brochure requires manual JSON authoring.
Goal: a UI + CLI workflow that sends one PDF to Google Gemini and writes a JSON file that
deserializes into the Kotlin classes, ready for human review and promotion into the catalog.

## Investigation summary
- **Repo today:** Compose Desktop single-window app (`CarPromptApp.kt`), Google AI support via
  `GoogleAiClient` (model discovery on native `/v1beta/models`, inference via the OpenAI-compatible
  endpoint – text only). Deps already include Ktor CIO + kotlinx-serialization → no new library needed.
- **Gemini PDF support (official docs, checked 2026-10-31):** native PDF vision, up to 50 MB /
  1000 pages, inline or Files API; ~258 tokens/page. Largest repo PDF ≈ 9.4 MB → inline base64 is fine.
- **Structured output:** `generationConfig.responseMimeType=application/json` +
  `responseJsonSchema`. kotlinx-serialization has no JSON-Schema generator → build one by walking
  `SerialDescriptor`s (objects, lists, `Map<String,String>` → `additionalProperties`, enums, nullables).
- **API choice:** native `POST /v1beta/models/{id}:generateContent` with `inline_data` part
  (Google also documents a newer `/v1beta/interactions` API; generateContent is stable and matches
  our existing discovery filter on `generateContent`). Recorded as an ADR; revisit if deprecated.
- **Model/cost (paid tier, per 1M tokens):** `gemini-3.8-flash` $0.75 in / $3.75 out (promo until
  2026-12-31, then $1.50/$7.50); `gemini-3.1-flash-lite` $0.25/$1.50; `gemini-3.5-flash-lite`
  $0.30/$2.50. A ~30-page brochure ≈ 8k input + 15–25k output tokens → ≈ $0.06–0.10 on 3.8 Flash.
  Free tier exists but its content may be used by Google – documented warning.
- **Risks:** large output JSON (set `maxOutputTokens` to model `outputTokenLimit`; truncation =
  failure), `Money.minorUnits` must be cents (prompt + `PriceFact.rawText` keeps source text),
  schema subset limits of Gemini (keep schema simple, no `$ref` recursion needed), hallucinated values
  (human review before promotion; no auto-promotion).

## Decisions (recorded as ADRs in [`adr/`](adr/), grouped 0001–0008)
1. Output = normalized Kotlin classes (schema derived from `@Serializable` types; validated by
   deserializing). Not the loose source shape.
2. UI = new **PDF Extractor** tab in the existing window (tabs: Recommender | PDF Extractor),
   reusing Google AI key + model picker.
3. Desktop default Google AI Studio + `gemini-3.5-flash-lite`; ordered presets and shared
   provider controls follow the [desktop plan](openrouter-desktop-ui-plan.md).
   CLI default stays `gemini-3.8-flash`, overridden with `GOOGLE_AI_MODEL`.
4. Output folder: `extracted-catalog/` (cwd, git-ignored), override `PDF_EXTRACT_OUTPUT_DIR` and a
   folder chooser; file = `<pdf stem>.json`; overwrite requires confirmation.
5. Invalid/truncated output → visible error, offer save/copy raw as `<stem>.raw.json`; never write an
   invalid `<stem>.json`. No automatic retry.
6. One PDF per run (UI) + `extractPdf` Gradle CLI task for one PDF.
7. File envelope: `{schemaVersion, provenance:{pdfFileName, pdfSha256, model, extractedAt,
   inputTokens, outputTokens}, vehicle:{title, make, model, effectiveDate, effectivePeriod, trims,
   options, technicalSpecifications, additional}}`. `id`/`sourcePath`/`sourcePdfPath` are computed
   by code, never by the LLM.
8. Promotion is manual: user copies reviewed `<stem>.json` next to its PDF in
   `car-pricelists/<brand>/`; `DataModelCatalogGenerator` detects `schemaVersion` and deserializes
   directly, otherwise falls back to `CarDataModelParser`. Provenance ignored by generator.
9. Native `generateContent` REST via Ktor (no SDK dependency).
10. Approved 2026-11-01: desktop non-Google extraction sends PDFBox-extracted text
    through existing chat backends; see [ADR 0002](adr/0002-gemini-native-generatecontent.md).
    Images/layout are omitted; textless PDFs fail explicitly. Output token provenance
    may be null when the provider omits usage.

## Implementation todos
Staged execution order (starting with a live integration-test spike):
[`pdf-to-json-implementation-stages.md`](pdf-to-json-implementation-stages.md).

1. **docs** – `docs/pdf-to-json-extraction-plan.md` (investigation + plan), `docs/adr/000N-*.md` for
   decisions above, `docs/glossary.md` (Source shape, Normalized artifact, Extraction envelope,
   Provenance, Promotion…). README section.
2. **extraction-model** – `catalog/extraction/ExtractedCarDataModel.kt`: `@Serializable`
   `PdfExtractionFile(schemaVersion, provenance, vehicle)`, `ExtractionProvenance`, `ExtractedVehicle`;
   mapping `ExtractedVehicle → CarDataModel` (id via `DataModelValues.slug`, `effectivePeriod` parse).
3. **json-schema** – `JsonSchemaGenerator` from `SerialDescriptor` (+ unit tests on `ExtractedVehicle`
   schema: enums, nullables, maps, lists).
4. **gemini-pdf-client** – `GeminiPdfClient` (native generateContent, inline base64 PDF, system
   prompt, responseJsonSchema, maxOutputTokens, timeout 10 min, `x-goog-api-key` header); parse
   `candidates[0]`, `finishReason` (MAX_TOKENS → truncation error), `usageMetadata`; map HTTP errors to
   existing `FailureKind`s; size gate 50 MB. Tests with a fake Ktor server like `FakeOpenRouterServer`.
5. **extraction-service** – `PdfExtractionService`: read PDF, sha256, call client, strict decode
   (`Json { ignoreUnknownKeys = false }`), post-validate (non-blank title, ≥1 trim, currency EUR…),
   build envelope, write atomically with overwrite policy callback, raw-response save on failure.
6. **generator-support** – `DataModelCatalogGenerator`: detect `schemaVersion` key → decode
   `PdfExtractionFile`, map to `CarDataModel`, link PDF as today; tests incl. mixed folder.
7. **cli** – `PdfExtractCli` + Gradle `extractPdf` task (`--args='<pdf> [outputDir] [--overwrite]'`),
   uses `GOOGLE_AI_API_KEY`, `GOOGLE_AI_MODEL` (default `gemini-3.8-flash`), `PDF_EXTRACT_OUTPUT_DIR`.
8. **ui-tab** – Add `TabRow` in `CarPromptApp`; `PdfExtractorController` + `PdfExtractorScreen`:
   PDF file chooser, output folder chooser, Google key/model picker reuse, Extract button, progress,
   result summary (trims/variants/options counts, tokens, est. cost, output path, Open folder),
   overwrite confirm dialog, error panel with raw save/copy. Controller unit tests.
9. **gitignore-readme** – add `extracted-catalog/` to `.gitignore`; README usage + data-transmission
   note (PDF content sent to Google).
10. **verify** – `./gradlew spotlessApply build test`; optional manual run on one repo PDF
    (e.g. `dacia jogger.pdf`) and diff vs. existing normalized catalog entry.

## Notes
- Follow repo conventions: `OpenRouterWorkflowException`/`FailureKind`, keys only in headers, no
  retries, explicit errors, atomic writes, ktlint via spotless.
- Out of scope: batch/folder processing, auto-promotion, Files API, page citations, Batch API pricing.
