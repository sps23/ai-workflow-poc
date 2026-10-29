# Simple OpenRouter CLI plan

## Goal and agreed scope

Build a small Kotlin CLI that accepts one user prompt, loads the existing car
catalog, sends one inference request to OpenRouter, and prints a plain-text answer.
Use Koog for prompt execution and its Ktor transport for HTTP.

Confirmed decisions:
- CLI first, with one request per invocation and no conversation history.
- Send a compact catalog containing all reviewed configurations and their reviewed
  facts. Omit unreviewed configurations, raw source text, and duplicate evidence.
- Use one configurable, verified `:free` model. Stop when unavailable; no fallback.
- Return plain text. Recommendations are prototype advice, not deterministically
  checked matches against the user's requirements.
- Reject an oversized request rather than silently dropping cars or facts.

This is a simpler alternative to `openrouter-integration-plan.md`. The two-stage
recommendation architecture in that plan is not a prerequisite for this workflow.
Saving this plan authorizes neither application implementation nor live API calls.

## Flow

```text
CLI prompt + bundled catalog.json
  -> validate inputs and decode catalog
  -> compact reviewed catalog
  -> verify configured free model and request size
  -> build system instructions + user prompt/catalog data
  -> one Koog OpenRouter inference request over Ktor
  -> print answer, actual model ID, and prototype disclaimer
```

Model metadata requests are separate from the single inference request.

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

### 2. Add the OpenRouter call

Create `openrouter/OpenRouterCarWorkflow.kt`; keep the small configuration and
prompt-building functions together rather than introducing a framework.

- Read `OPENROUTER_API_KEY` and `OPENROUTER_MODEL` from the environment.
  Fail clearly if either is missing; never print the key.
- Use Ktor to read current OpenRouter model metadata. Require the exact model ID
  to exist, end in `:free`, support text output, and have valid zero pricing for
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
- Configure one inference attempt, a bounded output, and a request timeout using
  supported installed APIs. Disable hidden inference retries. Send no fallback
  models, paid router, tools, or plugins.
- Surface authentication, rate-limit, timeout, context, provider, empty-response,
  and truncated-response failures. Propagate cancellation and close clients.
  Avoid logging headers, prompt/catalog bodies, or raw responses.

Done when offline request capture verifies the free-only payload, prompt/data
separation, one inference attempt, and visible failure paths using a local fake
server. If installed Koog cannot satisfy these essentials, report the specific
blocker rather than silently replacing Koog with a raw HTTP inference client.

### 3. Wire the CLI

Create `recommendation/CarPromptCli.kt` and a `carPrompt` Gradle `JavaExec` task.
Accept the prompt as command-line arguments; reject blank input. Run the workflow
once and print the model answer and actual response model ID when available.
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

No intent-extraction call, deterministic eligibility/ranking engine, structured
model-output schema, clarification loop, automatic retries/fallbacks, model
discovery UI, Compose screen, embeddings, persistence, or PDF changes.

All-reviewed catalog context favors simplicity over scalability. Its measured
size and a currently available free model's context capacity are an early
feasibility gate; if they do not fit, report that limitation rather than quietly
changing the agreed scope. Plain-text output cannot guarantee factual accuracy
or compliance with every user requirement. Free endpoints may be rate-limited
or unavailable even with a valid key.

## References for implementation

- [Koog providers](https://docs.koog.ai/llm-providers/):
  installed artifact APIs are authoritative for compatibility.
- [OpenRouter free variants](https://openrouter.ai/docs/guides/routing/model-variants/free):
  use actual listed `:free` entries; paid fallback models can incur charges.
- [Model metadata](https://openrouter.ai/docs/guides/overview/models):
  verify pricing and context limits before dispatch.
