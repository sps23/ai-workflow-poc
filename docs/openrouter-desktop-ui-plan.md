# Desktop prompt UI plan

## Goal

A Compose Desktop window that translates one free-text request to LlmRequest JSON,
then sends that JSON plus the generated Data Model catalog to OpenRouter
and renders car/trim/options tables below the input.
Extends `openrouter-simple-integration-plan.md`; lifts its "no Compose screen"
boundary for this screen only. Reuses `OpenRouterCarWorkflow` and
`DataModelCatalogContext.loadBundled()`.

## Decisions

- **JSON output (supersedes Markdown):** use the minimal contract below for every
  workflow caller. Reuse catalog `FitmentStatus`; the CLI prints a readable text
  projection. No summary, extra sections, or Markdown renderer.
- **Two stages:** share the translation contract in `openrouter-simple-integration-plan.md`.
  Show `1/2 Translating request`, then `2/2 Finding cars`. Automatic second call
  after successful validation; use the same selected model for both.
- **Intermediate download:** as soon as translation succeeds, show
  **Download LLM Request JSON**, including while recommendation is loading or has
  failed. Save the pretty-printed UTF-8 JSON sent to stage two as `llm-request.json`
  via Save dialog, with overwrite confirmation and explicit save errors.
  An adjacent **Copy to clipboard** button copies the same JSON with copy confirmation.
  Retain while input is unchanged, including across model changes and recommendation
  failures. Any input edit immediately clears results, errors, translation cache,
  and download/copy controls; even editing back to the old text requires translation.
  Includes original input: warn users to review before sharing. Invalid translation
  exposes only its error/raw response copy, not a validated-request download.
- **Output budget:** OpenRouter shares the CLI's 32768-token default and
  `OPENROUTER_MAX_COMPLETION_TOKENS` environment override (positive integer up to
  Int.MAX_VALUE; invalid/blank values fail explicitly), capped by model metadata
  and reserved in the context-size check. Concise-answer guidance reduces excess
  output; length-limited responses remain errors rather than incomplete answers.
  Local recommendation targets now default to 32768, independently overridable
  with `LOCAL_AI_MAX_COMPLETION_TOKENS` (same validation). Translation stays
  capped at 2048. Per LM Studio request, cap output to the target, known output
  maximum, and loaded context minus estimated complete input. Reject input that
  leaves no output space; never drop facts. Other local presets without metadata
  use their configured target. Truncation reports actual/target budget and limits.
  Larger budgets reserve more context and can increase latency/paid costs;
  truncation guidance names the override and requires restarting with the new env.
- **Request timeout:** shares the CLI's 10-minute per-call request/socket limit, including
  waiting for the first response while the free provider processes the full
  catalog. Connection establishment stays at 15 seconds; no automatic retry.
- **Result scope:** up to three distinct make/model combinations by default,
  with every matching trim/powertrain configuration for each selected model.
  Explicit result counts/all requests override the model count; fewer genuine
  matches yield fewer models. The shared LLM instructions own this policy;
  Kotlin neither truncates configurations nor proves matching completeness.
- **Renderer:** native Compose Material 3 text and option-table rows grouped by
  model, then configuration, showing trim and configuration ID. Unknown trim displays `Unknown`;
  empty options display `No requested options returned.` Empty cars display
  `No cars returned.`
- **Catalog and sources:** the UI loads
  `vehicle-catalog/data-model-catalog.json`, generated from per-car Data Model
  files. It contains normalized trims, variants, equipment, options and specs,
  plus a linked PDF path; it does not carry fact review states or page citations.
  Derive stable configuration IDs from car ID, trim and variant name. Keep the
  first-stage `LlmRequest` and cars/trim/options response JSON unchanged. The
  model returns an empty `sources` array because no page evidence is available.
  Replace page citations with a **PDF Source** column containing a **Download**
  button for each option row (also show a download row when options are empty), resolving its
  path only from the local generated-catalog registry; never trust a model-authored path.
  Confirm overwrite and report missing resources, LFS pointers, unsafe paths and
  save failures. The CLI uses this same generated catalog and response contract.
- **Configuration details:** below the unchanged model/trim/configuration labels,
  resolve engine type, battery capacity, displacement, horsepower and drivetrain
  locally by configuration ID. Show gross battery capacity separately where published;
  prefer technical-spec capacity over the price row's battery figure. Keep missing
  facts explicitly unavailable; show kW when horsepower is absent. Flag AWD/4x4.
  Show on-the-road price first, otherwise list price/RRP, with its published basis.
  Itemize returned OPTIONAL equipment using an exact catalog name or code and
  applicable trim/variant scope. Display a total only when base and every optional
  item have unambiguous exact prices in the same currency, with no conditional notes
  or duplicate references. Unknown/conditional/range/from prices withhold the total.
  Standard equipment adds nothing. Preserve the response JSON schema and raw JSON;
  the desktop readable copy includes these locally resolved details.
  For OPTIONAL equipment, the shared recommendation prompt requests the exact
  catalog option/package name for local lookup, retaining the requested feature
  in details when wording differs. A required package appears once even when
  it supplies several requested features. Other statuses retain shared request labels.
- **Answer diagnostics:** **Copy raw JSON** copies model-authored response content
  exactly as exposed by Koog, including whitespace, not the HTTP envelope or
  reasoning. **Copy readable output** copies the same cars/options as tab-separated
  text plus model label and prototype disclaimer. Both confirm copying.
  Invalid JSON or truncated output remains an explicit failure with a separate
  **Copy raw model output** button. Raw content is not included in the sanitized
  error report or logs; review before sharing because it may repeat request data.
- **Performance metadata:** collapsed **DEBUG / Performance metadata** below
  results/errors, available after success or failure. Expand to see per-stage
  outcome/time and API/model. Translation (first request) and recommendation
  (second request) each use four stacked, full-width tables: **Request details**,
  **Token usage**, **Budgets & limits**, and **Timing & speed**. Equal-width columns
  wrap to fit the available width without horizontal scrolling. Retain consistent
  body typography and all reported token counts, input estimates,
  output budgets/context limits, stage/request timings, reported TTFT/generation
  speed, and **Output tokens / second (whole request)**. Missing values display
  `unavailable`, including absent request data on reused/failed stages.
  **Copy readable debug data** copies the same tables as tab-separated text plus
  workflow/metadata-lookup timing, with confirmation. The existing selectable
  monospace schema-v1 JSON and its controls remain unchanged. **Copy metadata JSON** and
  **Download metadata JSON** export identical metadata-only JSON; download defaults
  to `llm-metadata.json`, uses Save dialog/overwrite confirmation and visible
  save errors, sharing the request JSON export helper.
  Keep unavailable fields null, not zero. Preserve completed-stage metrics and
  usage on invalid/truncated/empty responses; distinguish preflight (not
  dispatched), HTTP/connection/timeout and metadata-discovery failures.
  Translation reuse is labelled REUSED without old usage counted again.
  New submissions clear stale metrics; input edits clear the panel. No model
  content, prompts, catalog facts, reasoning, headers, keys or arbitrary provider
  fields in exports; review provider/model identifiers before sharing.
  Request timing includes transport/body processing; derived tokens/second is
  whole-request throughput, not generation speed. TTFT and generation throughput
  appear only if explicitly reported by a provider. Missing data stays unavailable.
- **Layout:** multi-line `TextField`, a Send button, a spinner while the request
  runs, then either the rendered answer or an error-container panel below.
- **Error triage:** selectable monospace report + **Copy error details** button
  with copy confirmation. Include failure category, submitted model, stage,
  HTTP status, provider code/message, upstream code/message extracted from nested
  JSON errors, provider name and request ID where exposed,
  plus suggested action. Allowlist structured error fields; exclude raw bodies,
  headers and prompt/catalog fields. Redact the configured key and bearer tokens;
  cap provider fields at 4,000 characters with explicit truncation. Non-JSON
  failures retain status without dumping the body. Koog's HTTP-200 error wrapper
  exposes message/code text but drops provider metadata; unavailable fields
  remain absent. Provider-written messages should be reviewed before sharing.
- **No input validation:** the UI sends whatever is typed. Workflow errors
  (missing env vars, blank prompt rejected by the client, provider failures)
  appear as the error message.
- **Footer:** under the answer show the response model ID (or the requested ID
  when unreported) and `PROTOTYPE_DISCLAIMER`, matching the CLI.
- **API choice:** an API dropdown lists `LlmApi` entries: OpenRouter (default),
  LM Studio `http://localhost:1234/v1`, llama.cpp `http://localhost:8080/v1`,
  Ollama `http://localhost:11434/v1`. Local APIs hide the model dropdown and use
  `LocalLlmClient`: OpenAI-compatible `POST <baseUrl>/chat/completions`. Other local presets use no
  `model` field, optional `LOCAL_AI_API_KEY` Bearer header, no metadata/pricing
  check and no size gate (the server reports context overflow).
  LM Studio instead reads native `GET /api/v0/models`, requires exactly one
  loaded llm/vlm with ID and positive loaded_context_length, and pins that ID
  in both chat calls. Missing, invalid or ambiguous metadata stops inference.
  Use the loaded context, never substitute max_context_length. Each stage checks
  estimated full-message input and dynamically caps output before chat dispatch; failures report
  bytes, estimated tokens, output budget and loaded context. Estimates use the
  existing byte heuristic, not model tokenization; server rejection stays visible.
  All APIs use the generated Data Model catalog context; output IDs/citations
  and source download behavior remain unchanged.
  Changing API with
  unchanged input reuses the cached translation. Disabled while a request runs.
  The CLI stays OpenRouter-only.
- **Model choice:** an OpenRouter-only dropdown lists `OPENROUTER_MODELS` in
  `CarPromptController.kt`; `openrouter/free` is the first entry and default.
  This router can select different underlying free models across the two calls.
  The UI ignores `OPENROUTER_MODEL`; the chosen ID still passes live metadata
  checks for listing, text output, pricing, and context limits.
  Choices retain the three NVIDIA free models and free router, remove Cohere
  and Recursive, and add exactly `qwen/qwen3.5-flash-02-23` as an approved paid
  exception. Qwen's menu label includes `(paid)` and selection shows a charge
  warning for both workflow stages. Free choices still require zero pricing;
  Qwen allows numeric nonnegative paid pricing. No other paid IDs or automatic
  paid fallback are authorized. CLI configuration shares this exact exception.
  The dropdown is disabled while a request runs.
- **Launch:** `compose.desktop.application` with `mainClass` and
  `--enable-native-access=ALL-UNNAMED`; run with `./gradlew run`. Only
  `OPENROUTER_API_KEY` is required for OpenRouter, read per request;
  `LOCAL_AI_API_KEY` is optional for local APIs.

## Code

- `ui/CarPromptController.kt`: `OPENROUTER_MODELS`, selected API/model, and UI state
  (`Idle`, `Loading`, `Answered`, `Failed`); `submit` calls an injected
  `suspend (prompt, api, modelId, cachedRequest, onTranslated) -> CarAnswer`.
  Failure reports name the API; local failures get local suggested actions
  (`CONNECTION`: start the server and load a model).
  One submission at a time; Send is disabled across both stages. The translation
  callback updates UI/download state before stage two starts.
  Input editing is disabled during either call. Exact unchanged input reuses the
  validated translation on Send (including a changed model); only recommendation
  runs, with fresh metadata/context checks for the selected model.
- `ui/CarPromptApp.kt`: `main`, window, and composables; builds the real
  workflow on each submit: OpenRouter from `OPENROUTER_API_KEY` and the selected
  model, or a local preset from `LocalLlmConfig.fromEnvironment`.
- `openrouter/LlmApi.kt`, `openrouter/LocalLlmClient.kt`: API presets, the shared
  `ChatBackend` interface, and the local OpenAI-compatible client.
- `openrouter/LlmMetrics.kt`: typed allowlisted usage/performance capture and
  schema-v1 JSON codec; `Completion`, `CarAnswer` and workflow exceptions carry
  metadata without including it in the sanitized error report or raw answer.
- `ui/LlmDebugPanel.kt`: expandable per-stage metadata summaries and JSON;
  `CarPromptController.metadata` retains the last submission's metadata on
  success/failure, and `metadataJson` exposes the same serialization for tests.
- `recommendation/CarSelection.kt`: serializable response contract, strict
  decoding, structural checks, status labels, and shared readable text rendering.
- `recommendation/DataModelCatalogContext.kt`: loads the generated UI catalog,
  adds deterministic variant IDs to prompt context and maps them to bundled
  PDF paths for local downloads.

## Response contract

```json
{
  "cars": [
    {
      "configurationId": "catalog-configuration-id",
      "car": "Make Model",
      "trim": null,
      "options": [
        {
          "name": "Requested option",
          "status": "UNKNOWN",
          "details": "Conditions or evidence limitations",
          "sources": ["vw/Golf_MY27.pdf:8"]
        }
      ]
    }
  ]
}
```

All fields are required; trim is a string or null. Arrays may be empty.
Allowed statuses: `STANDARD`, `OPTIONAL`, `NOT_AVAILABLE`, `UNKNOWN`.
Unknown fields, missing fields, non-string scalar values, invalid enums, blank identities/names, and duplicate
configuration IDs or per-car option names fail with `INVALID_RESPONSE`.
Sources use separate positive page references; combined `:7,8,9` references are invalid.
Prompt instructions request this exact JSON shape; local decoding enforces it
without requiring provider JSON-schema support. Structural validation does not
establish factual accuracy, catalog membership, or compliance with user constraints.

**Decision:** user chose cars/trim/options only, with no summary or no-match
explanation; this intentionally narrows the previous general Markdown answer.

**Glossary:** raw output = model response text; readable output = local text
projection of the parsed response, with diagnostic footer.

**Decision:** source downloads use a native Save dialog, not the system PDF viewer
or an external URL. The bundled original is the download authority.

Done when `CarPromptControllerTest` covers success, workflow failure,
unexpected failure, default/selected model forwarding, and unknown-model
rejection offline; `CarSelectionTest` covers the contract and `CarPromptScreenTest`
renders populated, empty, and invalid responses offscreen. Run build/tests with
existing live integration tests excluded unless external transmission is approved. A manual
`./gradlew run` with a real key is a connectivity smoke, not a CI check.
