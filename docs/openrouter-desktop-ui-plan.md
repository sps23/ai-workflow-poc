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
- **API choice:** an API dropdown lists `LlmApi` entries: OpenRouter, OpenAI, Google AI Studio (default),
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
  Configuration slugging preserves `+` as `plus` (approved 2026-11-01), so
  Kodiaq `Selection` and `Selection +` remain distinct. Other IDs stay unchanged;
  remaining duplicate derived IDs are explicit errors. `fromArtifact` shares the
  same schema/path/configuration validation as bundled loading, allowing offline
  recommendation tests to consume current normalized fixtures directly.
  Changing API with
  unchanged input reuses the cached translation. Disabled while a request runs.
  The `carPrompt` CLI stays OpenRouter-only.
- **Local authentication:** show a masked optional local API-key field. A nonblank
  typed key overrides `LOCAL_AI_API_KEY`; blank retains environment behavior.
  Keep typed keys in memory only; use them for local metadata and both chat
  calls. Keys are excluded from exported diagnostics and config string representations.
  Changing key retains an unchanged prompt's translation cache.
- **Model choice:** remote providers share a popup with search separate from the
  selected exact-ID field. Reopening starts with an empty search and full catalog.
  Search IDs/display names case-insensitively over all fetched models, not just
  visible rows. Initially expose 10 matches; append 10 when scrolling to the last
  visible row. Render lazily; Up/Down moves the highlighted match, Enter selects,
  Escape dismisses. Search/dismiss never mutates selection; exact typed IDs remain
  available outside the popup. Fetch public `GET /api/v1/models` once on the
  first OpenRouter display, with explicit Refresh and a 30-second timeout.
  Only verifiable text models appear, grouped **Free/Low/Medium/High** with text
  labels and colors. Free uses all reported pricing components except discount.
  Paid tiers use output USD per million tokens: Low <=1, Medium >1 through 10,
  High >10. Use exact decimal comparisons; these are UI-defined tiers, not provider
  ratings or total-request costs. Show input/output USD per million tokens separately;
  other provider charges may apply. Typed exact IDs need not be in the fetched
  catalog. Discovery failure shows an explicit error while leaving typed IDs usable.
  Selection/refresh never changes the selected ID implicitly.
  `openrouter/free` remains OpenRouter's initial ID; the UI ignores `OPENROUTER_MODEL`.
  Every submission rechecks exact listing, text output, nonnegative pricing and
  context limits. Free-labelled IDs require zero pricing; arbitrary verified paid
  IDs are authorized in desktop and CLI. Paid selections show a charge warning
  for both stages; unknown pricing warns of possible charges. No automatic
  fallback or retry. The free router can choose different underlying models per call.
  API/model/key editing and refresh are disabled during inference.
- **OpenAI integration:** use the existing transitive Koog 1.3.0 OpenAI client
  with its Ktor transport, not the local client or an OpenRouter URL override.
  Reuse the shared two-stage workflow, prompts, response contract, telemetry and
  translation cache. The `carPrompt` CLI remains OpenRouter-only.
  Show a masked key field: nonblank overrides `OPENAI_API_KEY`, blank uses env.
  Keys stay in memory and never enter exports/logs/config string representations.
  Prefetch authenticated `GET https://api.openai.com/v1/models` after a 600-ms
  key-entry debounce and on first OpenAI display; offer manual Refresh. Discovery
  uses a 30-second timeout. Key edits clear discovery state; version checks reject
  late results/errors from old credentials. Cancellation clears the spinner and
  permits another attempt. Discovery errors stay visible; typed IDs remain usable.
  Show all unique server-listed IDs, searchable case-insensitively, with exact
  text entry. The user explicitly retained all OpenAI IDs rather than heuristic
  compatibility filtering. Model list does not report endpoint compatibility, pricing or
  token limits: label compatibility unknown and prices unavailable, without price grouping or
  invented limits. A listed ID may be an embedding/audio/Responses-only model.
  Require explicit selection (initial OpenAI ID blank); preserve separate
  OpenAI/OpenRouter IDs on provider changes. Display a paid-use/external-transmission
  warning for prompt/catalog and both stages.
  Inference uses Koog `OpenAIChatParams` and `POST /v1/chat/completions`, pinning
  the exact ID and sending `max_completion_tokens`, `store=false`, no temperature
  override, tools, retries or fallback. OpenAI validates model compatibility and
  context/output limits; preserve all input facts and surface provider failures.
  Model limits stay null in telemetry; input estimates remain available.
  `OPENAI_MAX_COMPLETION_TOKENS` independently defaults to 32768 and follows the
  existing positive-Int validation. Translation stays capped at 2048. Each chat
  call retains the 10-minute request/socket and 15-second connection timeouts.
  Surface auth, quota, unsupported model/endpoint, context, timeout, empty,
  truncated and invalid responses with OpenAI-specific actions.
- **Google AI Studio:** add `LlmApi.GOOGLE_AI` after OpenAI; keep Manual last.
  Google offers an OpenAI-compatible Gemini endpoint, so reuse compatible chat
  inference with a dedicated `GoogleAiClient` adapter for native metadata; no new
  library. The UI shows a masked key, overriding `GOOGLE_AI_API_KEY` when nonblank.
  Blank uses env. Keys remain in memory and excluded from logs/exports.
  Debounce discovery 600 ms, with Refresh, cancellation cleanup and key-version
  checks preventing stale results. Retain a separate Google ID, initially `gemini-3.5-flash-lite`,
  and unchanged-input translation across provider/model/key switches.
  Native `GET /v1beta/models` uses `x-goog-api-key`, never a query-string key;
  follow all `nextPageToken` pages, rejecting repeated tokens. List models with
  `generateContent` and valid positive input/output limits. Search IDs/display names,
  allow exact text IDs, and reverify listing/limits for each submission. Fetch all
  API pages before UI filtering so search includes models beyond the visible ten.
  Metadata does not prove OpenAI-compatible support for specialized generation
  models; surface provider errors rather than infer capabilities/pricing.
  Compatible `POST /v1beta/openai/chat/completions` uses Bearer auth, exact IDs
  without native `models/` prefix, `messages`, `max_tokens`, `stream=false`, no
  temperature override/tools/retries/fallback. Redirects are disabled.
  Google inputTokenLimit and outputTokenLimit are independent: gate estimated
  complete input against the input limit and cap requested output to output limit.
  Do not subtract/reserve output from the input limit or silently drop facts.
  Existing contextLength telemetry carries Google's input limit; modelMaxOutputTokens
  carries the separate output limit. Pricing stays unavailable; warn about Google
  external processing, quota and possible charges.
  `GOOGLE_AI_MAX_COMPLETION_TOKENS` defaults to 32768 with positive-Int validation;
  translation remains capped at 2048. Preserve existing timeout/error/raw-output
  and metadata behavior, with Google-specific suggested actions.
  `googleAiPrompt` is a separate one-call text CLI without catalog/translation,
  using required `GOOGLE_AI_API_KEY` and `GOOGLE_AI_MODEL` env variables.
  `GoogleAiClientIntegrationTest` is `@Ignore` from creation; its only live smoke
  sends a hello prompt, checks nonempty text and exact requested model, and requires
  those env vars. Remove `@Ignore` locally and pass `-PgoogleAiClientIntegration`
  to opt in; test selection alone enables neither gate. Normal tests stay offline.
  OpenRouter live client tests likewise require `-PopenRouterClientIntegration`
  after explicitly removing their retained ignore. No live smoke runs automatically.
- **Launch:** `compose.desktop.application` with `mainClass` and
  `--enable-native-access=ALL-UNNAMED`; run with `./gradlew run`. Only
  `OPENROUTER_API_KEY` is required for OpenRouter, read per request;
  OpenAI requires a typed key or `OPENAI_API_KEY`; `LOCAL_AI_API_KEY` is optional
  for local APIs.

- **Manual API:** last dropdown entry, `Manual API (OpenAI-compatible)`.
  Require a base URL including any API prefix, e.g. `https://provider.example/v1`;
  append `/models` and `/chat/completions` without inserting another `/v1`.
  HTTPS is required remotely; HTTP is allowed only for localhost/loopback IPs.
  Reject URL credentials, query strings, fragments and complete endpoint paths.
  Disable redirects for discovery/chat so credentials and catalog stay at the
  explicitly configured endpoint.
  Show an optional masked key: blank sends no authentication and never falls
  back to another provider's environment key. Nonblank sends a Bearer header.
  URL/key stay in memory. Require an explicit model ID, independently retained
  from OpenAI/OpenRouter; support text entry and case-insensitive ID search.
  After a 600-ms URL/key debounce, attempt `/models` with a 30-second timeout;
  provide manual Refresh. URL/key edits clear discovery and reject late old
  results/errors. Discovery failure remains visible; it never blocks typed IDs.
  Reuse the existing OpenAI-compatible `LocalLlmClient` transport with an explicit
  model ID, `messages`, `max_tokens`, `stream=false`, no temperature override,
  tools, retries or fallback. This is a Chat Completions contract, not arbitrary
  OpenAPI or Responses-only support. Existing local preset behavior stays unchanged.
  Preserve workflow prompts, two stages, strict result validation, telemetry and
  cancellation. Unknown pricing/context limits stay unavailable; the provider
  reports incompatibility/overflow. Warn that prompt/catalog go to this endpoint
  and charges may apply. `MANUAL_AI_MAX_COMPLETION_TOKENS` independently defaults
  to 32768 with positive-Int validation; translation stays capped at 2048.
  URL/key/model edits are disabled during inference and retain unchanged-input
  translation. API-key/provider errors use manual-specific suggested actions.

## Code

- `ui/CarPromptController.kt`: initial model ID, discovered models/search/loading/error,
  selected API/model, in-memory provider keys, and UI state
  (`Idle`, `Loading`, `Answered`, `Failed`); `submit` calls an injected
  `suspend (prompt, api, modelId, apiKeyOverride, cachedRequest, onTranslated) -> CarAnswer`.
  Failure reports name the API; local failures get local suggested actions
  (`CONNECTION`: start the server and load a model).
  One submission at a time; Send is disabled across both stages. The translation
  callback updates UI/download state before stage two starts.
  Input editing is disabled during either call. Exact unchanged input reuses the
  validated translation on Send (including a changed model); only recommendation
  runs, with each backend's existing metadata/context policy.
- `ui/CarPromptApp.kt`: `main`, window, and composables; builds the real
  workflow on each submit: OpenRouter from `OPENROUTER_API_KEY` and the selected
  model, OpenAI via `OpenAiConfig.fromEnvironment`, or a local preset from
  `LocalLlmConfig.fromEnvironment`. `RemoteModelPicker` shares the searchable UI.
- `ui/ModelPicker.kt`: independent popup search/paging/keyboard state, lazy rows,
  exact decimal OpenRouter price tiers; no invented OpenAI/Google pricing.
- `openrouter/LlmApi.kt`, `openrouter/LocalLlmClient.kt`: API presets, the shared
  `ChatBackend` interface, and the local OpenAI-compatible client.
- `openrouter/OpenRouterModel.kt`: public catalog discovery and eligible model entries,
  sharing pricing/context validation with inference.
- `openrouter/OpenAiClient.kt`: config/key resolution, authenticated model-list
  parsing/discovery and Koog OpenAI Chat Completions backend.
- `openrouter/ManualApiConfig.kt`: URL validation, optional auth/config and manual
  model discovery, delegating inference to the existing compatible client.
  The controller's injected `manualAnswer` receives the validated URL/key/model
  snapshot; the desktop wires it to the same generated-catalog workflow.
- `openrouter/GoogleAiClient.kt`: Google config, paginated model/limits discovery
  and compatible inference adapter. `GoogleAiPromptCli.kt` owns standalone text
  CLI execution and clear nonzero failures.
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
unexpected failure, default/typed model forwarding, grouped searchable discovery,
discovery errors/refresh, local-key forwarding and loading guards offline;
`OpenAiControllerTest` covers credential-scoped discovery, stale results,
provider selections, key forwarding and translation reuse. `OpenAiClientTest`
captures authenticated listing, exact Chat Completions requests, modern budgets,
metadata and typed failures without credentials/internet.
`CarSelectionTest` covers the contract and `CarPromptScreenTest`
renders populated, empty, and invalid responses offscreen. Run build/tests with
existing live integration tests excluded unless external transmission is approved. A manual
`./gradlew run` with a real key is a connectivity smoke, not a CI check.

**Decision:** user approved public discovery on first display plus manual Refresh,
price grouping, arbitrary verified paid text models, typed IDs after discovery
failure, and masked local-key overrides with blank-to-environment fallback.

**Glossary:** Free = all reported non-discount pricing components are zero;
Paid = at least one is positive; discovery = public metadata lookup, not inference.

**Decision:** user confirmed the dedicated existing-dependency Koog OpenAI backend,
provider-side compatibility/context validation, all listed IDs searchable, explicit
model selection, masked env-fallback key, debounced discovery and separate output budget.

**Glossary:** OpenAI discovery = authenticated ID listing, not proof of Chat
Completions compatibility or pricing/context metadata.

**Decision:** user approved manual setup as the last choice with base URL, optional
key, typed/searchable model IDs, HTTPS except loopback HTTP, and the broad
`max_tokens` Chat Completions contract with a separate 32768 output budget.

**Glossary:** manual base URL = API prefix, not complete chat/models endpoint;
OpenAI-compatible = supported Chat Completions wire contract, not an OpenAPI schema.

Done when offline manual tests cover dropdown order, URL rejection, optional auth,
exact paths/model IDs, discovery failure/stale results, cached translation,
provider errors and rendered UI, preserving existing provider tests.

**Decision:** user approved Google-compatible transport reuse with native model
discovery/limits, searchable key-scoped UI, env-fallback key, separate budget,
standalone env-configured CLI and ignored hello integration test.

**Glossary:** Google model ID = native name minus `models/`; Google input limit
and output limit = independent metadata bounds, not one combined context budget.

Done when offline Google tests cover pagination/filtering, header auth, limits,
exact two-stage requests, telemetry/errors/cancellation, CLI hello and UI/cache
behavior, with the live integration test skipped and existing tests preserved.

**Decision:** searchable popup replaces selection-as-search. Ten-model incremental
UI paging is independent of provider discovery pagination. OpenRouter retains its
verified-text filter; Google retains generateContent eligibility with its compatibility
warning. The user chose all OpenAI IDs with compatibility unknown rather than ID heuristics.
Missing API pricing stays unavailable. Shared Manual picker gains the same interaction,
without changing manual inference/configuration.

Done when `ModelPickerTest` covers exact batch sizes, full-catalog search, search vs
selection isolation, reopening, keyboard boundaries and price-tier boundaries offline,
and popup/screen rendering plus existing provider/controller regressions pass.

**Approved 2026-11-01:** both desktop tabs share provider/key/model controls and start
with Google AI Studio + `gemini-3.5-flash-lite`, independently of CLI environment model
selection. Google's picker prepends these display-only presets, retaining discovery,
exact entry, and live inference verification:
`Gemini 3.5 Flash-Lite (medium)`, `Gemini 3.1 Flash-Lite (low)`,
`Gemini 3.8 Flash (high)`. Labels configure neither thinking levels nor pricing.
Discovery preserves the order and deduplicates preset IDs; presets remain selectable
without credentials/discovery. No fallback if a preset is unavailable.
PDF provider behavior is defined in [ADR 0002](adr/0002-gemini-native-generatecontent.md).
