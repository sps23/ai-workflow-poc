# Simple OpenRouter CLI plan

## Goal and agreed scope

Build a small Kotlin CLI that accepts one user prompt, loads the existing car
catalog, sends two sequential inference requests to OpenRouter, and prints a readable answer
from the structured JSON response.
Use Koog for prompt execution and its Ktor transport for HTTP.

Confirmed decisions:
- One user submission per invocation, two bounded inference calls, no conversation history.
- Send a compact catalog containing all reviewed configurations and their reviewed
  facts. Omit unreviewed configurations, raw source text, and duplicate evidence.
- Use one configurable, verified `:free` model or exactly `openrouter/free`.
  The latter routes across free models; the underlying model may differ per call.
  Stop when unavailable; no application fallback.
- Return cars/trim/requested-options JSON using the contract in
  `openrouter-desktop-ui-plan.md`; render locally as readable text/tables.
  Recommendations are prototype advice, not deterministically
  checked matches against the user's requirements.
- Reject an oversized request rather than silently dropping cars or facts.

This is a simpler alternative to `openrouter-integration-plan.md`. Its deterministic
filtering/ranking architecture is not a prerequisite for this two-call workflow.
Saving this plan authorizes neither application implementation nor live API calls.

## Flow

```text
CLI prompt + bundled catalog.json
  -> validate inputs and decode catalog
  -> compact reviewed catalog
  -> verify configured free model once for this submission
  -> size check, then translate user text to LlmRequest JSON without catalog
  -> validate translation and preserve original wording
  -> publish translated JSON for desktop download
  -> size check, then send LlmRequest JSON + entire compact reviewed catalog
  -> one recommendation inference call (same model, no filtering)
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

- `VehicleCatalogCodec.decode` already validates the catalog structure.
- Catalog types and review flags are in
  `src/main/kotlin/com/sps23/aiworkflow/catalog/VehicleCatalog.kt`.
- Default input: `src/main/resources/vehicle-catalog/catalog.json`, loaded as a
  classpath resource. A custom catalog path is outside this first version.
- Koog core, OpenRouter client, Koog Ktor transport, Ktor, serialization, and
  coroutines are already declared in `build.gradle.kts`. No new library planned.

## Implementation todos

### 1. Prepare the compact catalog

Create `recommendation/CatalogContext.kt` under the existing Kotlin package.
Decode the bundled catalog using the existing codec. Retain every reviewed
configuration and join its reviewed prices, measurements, and equipment facts.
Keep IDs, names, units, ranges, equipment conditions, price basis/currency/dates,
and measurement approval status. Include only referenced feature definitions.
Represent source references compactly by document ID/page; omit raw text and
duplicate evidence. Preserve unknown values as unknown.

Done when fixture tests show that every reviewed configuration survives,
unreviewed facts are excluded, joins remain correct, and JSON is compact.

### 2. Run translation and recommendation

Create `openrouter/OpenRouterCarWorkflow.kt`; keep the small configuration and
prompt-building functions together rather than introducing a framework.

- Read `OPENROUTER_API_KEY` and `OPENROUTER_MODEL` from the environment.
  Fail clearly if either is missing; never print the key.
- Use Ktor to read current OpenRouter model metadata. Require the exact model ID
  to exist, end in `:free` or equal `openrouter/free`, support text output, and have valid zero pricing for
  applicable inference fees. Reject unverifiable or nonzero pricing before inference.
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
  model metadata). Recommendation uses an 8192-token default capped by model metadata, reserving it in
  the context-size check. The budget may include reasoning tokens. Keep simple
  answers concise and recommendations to three configurations by default,
  honoring explicit more/all requests and retaining necessary caveats.
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

Done when offline request capture verifies the free-only payload, prompt/data
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
transmission of the prompt/compact catalog, and limitations.
Keep API keys outside tracked files. Use only catalog data permitted for external
processing; no catalog upload is needed while planning or running offline tests.

Keep related tests in `CatalogContextTest`, `OpenRouterCarWorkflowTest`, and
`CarPromptCliTest`. Reuse existing test libraries and local HTTP test patterns.
Normal tests require neither credentials nor internet.

Done when formatting and targeted new tests plus existing catalog tests pass.
An optional manual live smoke uses an explicitly supplied key, a small synthetic
catalog fixture, and a non-sensitive prompt; it is separate from CI. Record that
connectivity passed, not that recommendation accuracy was proven.

## Boundaries and trade-offs

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
translation after recommendation failure. "Full catalog" means every reviewed
configuration/fact in the existing compact projection, not raw/unreviewed catalog data.

**Glossary:** LlmRequest = loss-preserving structured query plus original text;
translation = first inference without catalog; recommendation = second inference
with complete compact catalog and translated request.

All-reviewed catalog context favors simplicity over scalability. Its measured
size and a currently available free model's context capacity are an early
feasibility gate; if they do not fit, report that limitation rather than quietly
changing the agreed scope. Structurally valid JSON cannot guarantee factual accuracy
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
