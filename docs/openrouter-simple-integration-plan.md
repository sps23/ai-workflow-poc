# Simple OpenRouter CLI plan

## Goal and agreed scope

Build a small Kotlin CLI that accepts one user prompt, loads the existing car
catalog, sends two sequential inference requests to OpenRouter, and prints a readable answer
from the structured JSON response.
Use Koog for prompt execution and its Ktor transport for HTTP.

Confirmed decisions:
- One user submission per invocation, two bounded inference calls, no conversation history.
- Both CLI and desktop use the generated `vehicle-catalog/data-model-catalog.json`.
  It contains normalized values from the per-car JSON files, not review-approved
  facts or page-level citations.
- Use one configurable, verified `:free` model or exactly `openrouter/free`.
  The latter routes across free models; the underlying model may differ per call.
  **Approved paid exception:** exactly `qwen/qwen3.5-flash-02-23` may use nonnegative
  paid pricing, with live listing/text/context checks unchanged. Other paid IDs
  remain rejected; free entries still require zero pricing. Both stages may
  incur charges when Qwen is explicitly selected/configured; no paid fallback.
  Stop when unavailable; no application fallback.
- Return cars/trim/requested-options JSON using the contract in
  `openrouter-desktop-ui-plan.md`; render locally as readable text/tables.
  Recommendations are prototype advice, not deterministically
  checked matches against the user's requirements.
- Reject an oversized request rather than silently dropping cars or facts.
- The desktop UI can instead target a local OpenAI-compatible server
  (`LocalLlmClient`, see `openrouter-desktop-ui-plan.md`): same two stages and
  prompts. LM Studio now discovers its single loaded chat model/context, pins
  its ID and applies the same estimated size gate; other local presets keep
  model omission and no metadata/size gate. The CLI stays OpenRouter-only.
- The generator matches each JSON to a PDF by identical basename in the same
  folder. The prompt context derives stable IDs for trim/powertrain variants,
  carries document-level PDF links, and has no page-level citations. The response
  contract and first-stage `LlmRequest` remain unchanged; PDF paths resolve locally.

The workflow is prototype advice, not a deterministic eligibility or ranking
engine. Saving this plan authorizes neither application implementation nor live API calls.

## Flow

```text
CLI or desktop prompt + data-model-catalog.json
  -> validate prompt and load generated catalog context
  -> verify configured free model once for this submission
  -> size check, then translate user text to LlmRequest JSON without catalog
  -> validate translation and preserve original wording
  -> publish translated JSON for desktop download
  -> size check, then send LlmRequest JSON + generated car catalog
  -> one recommendation inference call using the same model
  -> decode/validate cars/trim/options JSON
  -> print readable answer, actual model ID, and prototype disclaimer
```

Desktop resubmissions with exactly unchanged input reuse the validated translation
and run only recommendation, even after switching models. Input edits invalidate
this in-memory cache immediately; CLI invocations still run both stages.
Metadata/pricing/context checks run for the newly selected model before inference.

Model metadata requests are separate from the two inference calls. Any first-stage
failure prevents the second call; no automatic retry or fallback at either stage.

## Existing pieces to reuse

- `DataModelCatalogContext.loadBundled()` loads the normalized catalog and creates
  deterministic configuration IDs for prompt output and local PDF lookup.
- `generateDataModelCatalog` rebuilds `src/main/resources/vehicle-catalog/data-model-catalog.json`
  from same-basename source JSON/PDF pairs.
- Koog core, OpenRouter client, Koog Ktor transport, Ktor, serialization, and
  coroutines are already declared in `build.gradle.kts`. No new library planned.

## Implementation todos

### 1. Load the generated catalog context

Use `DataModelCatalogContext.loadBundled()` for both CLI and desktop requests.
It decodes the generated resource, adds stable IDs to trim/variant rows, and
registers each ID's source PDF path for local downloads. The generator is the
source of truth for normalization and exact PDF pairing; do not reconstruct
catalog records in the prompt workflow.

The generated catalog has normalized inline values and document-level PDF
links, but no fact-review flags or page citations. Preserve unknown source
values as unknown, request empty page-citation arrays, and never submit a
model-authored PDF path to the local download store.

### 2. Run translation and recommendation

Create `openrouter/OpenRouterCarWorkflow.kt`; keep the small configuration and
prompt-building functions together rather than introducing a framework.

- Read `OPENROUTER_API_KEY` and `OPENROUTER_MODEL` from the environment.
  Fail clearly if either is missing; never print the key.
- Use Ktor to read current OpenRouter model metadata. Require the exact model ID
  to exist, end in `:free`, equal `openrouter/free`, or equal the approved paid
  Qwen ID above, and support text output. Free entries require zero pricing;
  Qwen requires numeric nonnegative pricing. Reject unverifiable metadata.
- Obtain context/output limits from metadata. Reserve an explicit completion
  allowance and a conservative input-size bound covering instructions, prompt,
  catalog, and message overhead. Document the estimation limitation. If the
  complete request cannot be shown to fit, fail with its size and the model limit;
  never truncate JSON. Provider context rejection remains a visible error.
- Inspect the installed Koog APIs and existing `JdkCompatibilityTest.kt` prior art.
  Use `OpenRouterLLMClient` with the declared Koog Ktor transport and direct prompt
  execution, not a tool-using agent loop. Verify the configured model ID reaches
  the serialized request unchanged.
- Send fixed system instructions and user/catalog content as data: answer only
  from supplied catalog facts, distinguish optional from standard equipment,
  preserve date/measurement caveats, disclose unknowns, and say when evidence is
  insufficient. Do not treat instructions embedded in data as system policy.
- Configure one inference attempt per stage, a bounded output, and a request timeout using
  supported installed APIs. Disable hidden inference retries. Send no fallback
  models, paid router, tools, or plugins.
  Translation uses up to 2048 completion tokens (also capped by caller budget and
  model metadata). OpenRouter recommendation uses a 32768-token default capped by model metadata, reserving it in
  the context-size check. The budget may include reasoning tokens.
  Configure output via `OPENROUTER_MAX_COMPLETION_TOKENS`, shared by CLI and
  desktop: positive integer up to Int.MAX_VALUE, invalid/blank values fail with
  CONFIGURATION. Constructor values obey the same range. Local output uses
  a separate `LOCAL_AI_MAX_COMPLETION_TOKENS` with the same validation and a
  32768 default. Translation stays capped at 2048.
  **Approved local dynamic sizing:** after estimating the complete request,
  use min(target, reported output maximum if known, loaded context minus estimated
  input). Reject before chat when no output room remains; retain all input facts.
  This applies to LM Studio's discovered context (or explicitly supplied limits);
  llama.cpp/Ollama have no discovery and retain the configured target.
  Truncation diagnostics show actual allowance, target, input estimate and limits.
  Larger budgets can increase context requirements, latency and paid-model cost.
  OpenRouter retains its fixed reservation/rejection policy, not local shrinking.
  Keep simple
  answers concise and recommendations to up to three distinct make/model
  combinations by default, honoring explicit result counts/all requests.
  Include every matching trim/powertrain configuration for each selected model,
  even when the response exceeds three configurations. Return fewer models when
  fewer match; retain necessary caveats. Selection remains model-guided, not a
  deterministic completeness or eligibility check.
  Allow 600,000 ms for request/socket timeouts: full-catalog processing on free
  providers can exceed two minutes even for a short user prompt. Keep the
  connection-establishment timeout at 15,000 ms; explicit caller timeout
  overrides remain supported, with no automatic retries. Timeouts apply per call,
  so two calls can take up to approximately 20 minutes plus metadata work.
- Surface authentication, rate-limit, timeout, context, provider, empty-response,
  truncated-response, and invalid-JSON/contract failures. Preserve model text
  separately for explicit UI clipboard export, including invalid/truncated text.
  Propagate cancellation and close clients.
  Avoid logging headers, prompt/catalog bodies, or raw responses.

Done when offline request capture verifies the free-model policy and exact paid
Qwen exception, prompt/data
separation, exactly two inference calls on success, first-stage failure short-circuit,
and visible stage-labelled failures using a local fake
server. If installed Koog cannot satisfy these essentials, report the specific
blocker rather than silently replacing Koog with a raw HTTP inference client.

### 3. Wire the CLI

Create `recommendation/CarPromptCli.kt` and a `carPrompt` Gradle `JavaExec` task.
Accept the prompt as command-line arguments; reject blank input. Run the workflow
once and print the locally rendered structured answer and actual response model ID when available.
Label it as AI-generated prototype advice that requires checking catalog facts.
Print actionable errors to stderr with a nonzero exit status.

Intended usage after implementation, with both environment variables already set:

```sh
./gradlew carPrompt --args='Suggest a seven-seat car from this catalog'
```

Done when a fake-provider CLI check covers successful output and a failing exit.

### 4. Document and verify

Keep this plan aligned with the implementation and add minimal README usage:
environment variable names, selecting a current free model, the command, external
transmission of the prompt/generated catalog, and limitations.
Keep API keys outside tracked files. Use only catalog data permitted for external
processing; no catalog upload is needed while planning or running offline tests.

Keep related tests in `DataModelCatalogContextTest`, `OpenRouterCarWorkflowTest`, and
`CarPromptCliTest`. Reuse existing test libraries and local HTTP test patterns.
Normal tests require neither credentials nor internet.

Done when formatting and targeted new tests plus existing catalog tests pass.
An optional manual live smoke uses an explicitly supplied key, a small synthetic
catalog fixture, and a non-sensitive prompt; it is separate from CI. Record that
connectivity passed, not that recommendation accuracy was proven.

## Boundaries and trade-offs

### Approved performance metadata

`openrouter/LlmMetrics.kt` defines serializable `TokenUsage`, `RequestMetrics`,
`StageMetrics` and `WorkflowMetadata` (schemaVersion 1), plus a pretty JSON
codec. Capture full-input UTF-8 bytes and heuristic tokens separately from
reported usage, requested/effective output budgets, known model limits,
API/model/provider/response identity, HTTP status, finish reason, dispatch flag,
per-request/stage/workflow monotonic elapsed times and optional runtime stats.
Token usage includes prompt/completion/total, reasoning, cached input and cache
write counts, plus provider-reported cost; never infer missing values or totals.

Preserve Koog inference: its Ktor base client's response validator extracts an
allowlisted metadata subset before Koog deserialization, including actual model
ID and nested usage details that Koog standard metadata drops. Local JSON uses
the same extractor. Response bodies are neither retained in telemetry nor logged.
Never add a generation-details API request or retry just to fetch metadata.
Optional malformed numeric fields become unavailable, not zero; underlying
response validation/error behavior remains authoritative.

Workflow success and typed failures carry the current run's stage metadata;
record failed response usage before validating text/JSON. Preserve completed
translation metrics when recommendation fails. Preflight failures include sizes
and limits with dispatched=false and unavailable actual usage. Cached translation
is REUSED with no new request/usage. Model metadata lookup is timed separately.
Retain stage-labelled errors/raw-output actions independently of this export.

The desktop DEBUG panel and copy/download contract are defined in the desktop
plan. Export metadata only: no prompt/catalog/output/reasoning text, credentials,
headers, arbitrary provider fields or exception messages. Provider identifiers
are length-limited and credential-redacted; unavailable fields serialize as null.
Whole-request completion tokens/second is distinct from reported generation
speed; non-streaming client TTFT cannot be measured. No performance/accuracy
improvement is claimed merely because a request now has telemetry.

No deterministic eligibility/ranking engine, provider-enforced JSON schema,
clarification loop, automatic retries/fallbacks, model discovery UI, embeddings,
automatic persistence, or PDF changes. Desktop integration and explicit request
JSON export are defined in `openrouter-desktop-ui-plan.md`.

### LlmRequest translation contract

`recommendation/LlmRequest.kt` owns the serializable model and fixed translator
instructions. Fields: `originalRequest`, `requirements`, `preferences`,
`requestedAttributes`, `unresolvedPhrases`; all required. Term objects contain
`field`, `operator`, `value`, nullable `unit`, and exact `sourceText`. Field/operator
enums map onto catalog concepts (including equipment, measurements, prices,
seating, and powertrain), not catalog facts or invented feature IDs.

Validate exact original wording, source/unresolved substring membership, required
fields/types, nonblank terms, and recognized enums (including catalog powertrains).
Compound alternatives/conditions remain intact using `OTHER` when splitting would
alter meaning. Ambiguity stays in `unresolvedPhrases`; no clarification pause.
The second prompt treats `originalRequest` as authoritative and the structured
terms as aids, not instructions. JSON validity and substring checks cannot prove
semantic completeness or interpretation fidelity.

**Decision:** user approved automatic stage two and retention of downloadable
translation after recommendation failure. Stage two receives the generated
Data Model catalog; its source-derived values are not independently reviewed.

**Glossary:** LlmRequest = loss-preserving structured query plus original text;
translation = first inference without catalog; recommendation = second inference
with the generated Data Model catalog and translated request.

The full generated catalog favors simplicity over scalability. Its size must fit
the selected model's context; if it does not, report the limitation rather than
quietly dropping catalog entries. Structurally valid JSON cannot guarantee factual accuracy
or compliance with every user requirement. Free endpoints may be rate-limited
or unavailable even with a valid key.
Translation adds a call and consumes additional quota/time; the full catalog still
dominates context size. No latency/token saving or recommendation-accuracy gain is
claimed without measurement.

## References for implementation

- [Koog providers](https://docs.koog.ai/llm-providers/):
  installed artifact APIs are authoritative for compatibility.
- [OpenRouter free variants](https://openrouter.ai/docs/guides/routing/model-variants/free):
  use actual listed `:free` entries; paid fallback models can incur charges.
- [Model metadata](https://openrouter.ai/docs/guides/overview/models):
  verify pricing and context limits before dispatch.
