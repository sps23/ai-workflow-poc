# Desktop prompt UI plan

## Goal

A Compose Desktop window that translates one free-text request to LlmRequest JSON,
then sends that JSON plus the complete compact reviewed catalog to OpenRouter
and renders car/trim/options tables below the input.
Extends `openrouter-simple-integration-plan.md`; lifts its "no Compose screen"
boundary for this screen only. Reuses `OpenRouterCarWorkflow` and
`CatalogContext.bundledJson()`.

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
- **Output budget:** shares the CLI's 8192-token default, capped by model metadata
  and reserved in the context-size check. Concise-answer guidance reduces excess
  output; length-limited responses remain errors rather than incomplete answers.
- **Request timeout:** shares the CLI's 10-minute per-call request/socket limit, including
  waiting for the first response while the free provider processes the full
  catalog. Connection establishment stays at 15 seconds; no automatic retry.
- **Renderer:** native Compose Material 3 text and option-table rows grouped by
  car, showing trim and configuration ID. Unknown trim displays `Unknown`;
  empty options display `No requested options returned.` Empty cars display
  `No cars returned.`
- **Sources:** each option has a required `sources` array of exact `documentId:page`
  references, one per page; citations stay out of `details`. Show a **Sources**
  column after **Details**. Group references by document, with one clickable
  underlined **source** label per PDF and document/page context underneath.
  Clicking opens a Save dialog; confirm replacement of existing files. Resolve
  document IDs through the bundled catalog registry and copy the original bundled
  PDF. Model URLs/paths are not download targets. Unknown IDs, invalid pages,
  unsafe registry paths, missing resources, LFS pointers, and save failures show
  explicit errors. Cancellation leaves the destination unchanged.
  Readable clipboard/CLI output adds a Sources column containing full references
  (plain text cannot carry a desktop Save action); raw JSON remains unchanged.
- **Answer diagnostics:** **Copy raw JSON** copies model-authored response content
  exactly as exposed by Koog, including whitespace, not the HTTP envelope or
  reasoning. **Copy readable output** copies the same cars/options as tab-separated
  text plus model label and prototype disclaimer. Both confirm copying.
  Invalid JSON or truncated output remains an explicit failure with a separate
  **Copy raw model output** button. Raw content is not included in the sanitized
  error report or logs; review before sharing because it may repeat request data.
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
- **Model choice:** a dropdown above the prompt lists `FREE_MODELS` in
  `CarPromptController.kt`; `openrouter/free` is the first entry and default.
  This router can select different underlying free models across the two calls.
  The UI ignores `OPENROUTER_MODEL`; the chosen ID still passes live metadata
  checks for listing, text output, zero pricing, and context limits.
  The dropdown is disabled while a request runs.
- **Launch:** `compose.desktop.application` with `mainClass` and
  `--enable-native-access=ALL-UNNAMED`; run with `./gradlew run`. Only
  `OPENROUTER_API_KEY` is required, read per request.

## Code

- `ui/CarPromptController.kt`: `FREE_MODELS`, selected model, and UI state
  (`Idle`, `Loading`, `Answered`, `Failed`); `submit` calls an injected
  `suspend (prompt, modelId, cachedRequest, onTranslated) -> CarAnswer`.
  One submission at a time; Send is disabled across both stages. The translation
  callback updates UI/download state before stage two starts.
  Input editing is disabled during either call. Exact unchanged input reuses the
  validated translation on Send (including a changed model); only recommendation
  runs, with fresh metadata/context checks for the selected model.
- `ui/CarPromptApp.kt`: `main`, window, and composables; builds the real
  workflow from `OPENROUTER_API_KEY` and the selected model on each submit.
- `recommendation/CarSelection.kt`: serializable response contract, strict
  decoding, structural checks, status labels, and shared readable text rendering.

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
