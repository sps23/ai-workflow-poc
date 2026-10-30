# ai-workflow-poc
AI Workflow POC

## PDF resources and Git LFS

Install [Git LFS](https://git-lfs.com/) before cloning this repository
(`brew install git-lfs` on macOS), then run:

```sh
git lfs install
git clone https://github.com/sps23/ai-workflow-poc.git
```

For an existing checkout, run `git lfs install --local` followed by
`git lfs pull` to download the actual PDFs. Git stores small LFS pointers;
the working tree contains the original documents.

Add PDF files with the usual `git add` command; `.gitattributes` routes them
through LFS. A normal `git push` uploads the referenced LFS objects through
the installed pre-push hook. Downloads and storage use the repository owner's
GitHub LFS quota.

The build workflow and [Copilot setup workflow](.github/workflows/copilot-setup-steps.yml)
download LFS objects so tools can read the PDF resources. Copilot's cloud setup
takes effect once that workflow is on the default branch. Catalog source
downloads require the original PDFs; an LFS pointer is not a readable PDF.

## Build

Requires JDK 25 (a current Eclipse Temurin 25 LTS build is recommended).
Set `JAVA_HOME` and the IDE's Gradle JVM to that JDK, then use the included
Gradle 9.3.0 wrapper:

```sh
./gradlew build
./gradlew test
./gradlew dependencies --configuration runtimeClasspath
```

## Catalog data and external extraction

PDF extraction is handled outside this application. The local extractor,
inventory/matrix probes, extraction tests, and Tika/PDFBox dependencies have
been removed. Their former Gradle tasks are no longer available.

Catalog search still uses `catalog/VehicleCatalog.kt` and the reviewed JSON at
`src/main/resources/vehicle-catalog/catalog.json`; its model, validation, CLI,
and desktop recommendation workflow are unchanged. A replacement data model
and ingestion of externally parsed JSON are deferred to the next step.

Existing generated JSON under `src/main/resources/car-pricelists-extracted/`
and plans/evidence under `docs/` are retained as historical artifacts.
Extraction commands and implementation claims in those plans describe the
removed pipeline, not current functionality. Original PDFs remain bundled for
catalog citation downloads.

## Car prompt CLI (OpenRouter)

Uses two sequential calls to the same configured OpenRouter model via Koog:
translate user text into `LlmRequest` JSON without catalog, then send that JSON
plus the complete compact catalog of all reviewed configurations/facts. Validate
the cars/trim/options response shape, then print a readable text projection.
Plan: [`docs/openrouter-simple-integration-plan.md`](docs/openrouter-simple-integration-plan.md).

```sh
export OPENROUTER_API_KEY=...        # never commit it
export OPENROUTER_MODEL=vendor/model:free
./gradlew carPrompt --args='Suggest a seven-seat car from this catalog'
```

Pick a current model from [OpenRouter's free models](https://openrouter.ai/models?q=free).
The CLI checks live metadata first: the exact ID must be listed, end in `:free`,
have text output and zero pricing. The exact free-router ID `openrouter/free`
is also accepted; it may choose different underlying free models per call.
The exact paid model ID `qwen/qwen3.5-flash-02-23` is an explicitly approved exception:
set `OPENROUTER_MODEL=qwen/qwen3.5-flash-02-23` to use it in the CLI.
Listing, text output, numeric nonnegative pricing and context limits are still
validated; zero pricing is required for every other allowed model.
**Qwen requests incur OpenRouter charges**, including both translation and
full-catalog recommendation calls. There is no automatic paid fallback.
No application fallback model, retry, tool, or plugin is
used; unavailable or unapproved paid models stop the run.

- **External transmission:** the prompt and the compact catalog (prices,
  measurements, equipment, `documentId:page` references; no raw PDF text) go to
  OpenRouter and its provider. Use only data cleared for that.
- **Lossless catalog indexing:** the model-facing `indexed-v1` JSON retains
  every reviewed configuration and fact. Per-configuration prices, measurements
  and equipment are zero-based indexes into shared top-level arrays; fact
  `featureId` values index `features`, and all source entries index `sources`.
  Full configuration IDs and original `documentId:page` strings remain available
  for answers/downloads. Conditions, units, dates and unknowns are unchanged.
  The stored catalog schema is unchanged; JSON is already minified.
- **Translation:** `LlmRequest` preserves original text, separates hard requirements,
  preferences and requested attributes, and retains unresolved phrases instead of
  guessing. Terms map to catalog fields with operators, string values, units and
  original source wording. Invalid translations stop before catalog inference.
  Validation checks shape/original text/source phrases, not semantic equivalence.
  The original request remains authoritative in the second call.
- **Size gate:** the request must fit the model's context with a 32,768-token
  output allowance (capped by the model's advertised maximum). This budget may
  include reasoning tokens as well as visible text. The input estimate is a conservative heuristic (1 token per
  2 UTF-8 bytes), not a tokenizer count. Oversized requests fail with both
  figures; nothing is truncated. The full catalog may exceed small free models.
- **Limitations:** answers are AI-generated and unchecked against the user's
  requirements; free endpoints can be rate-limited. The OpenRouter adapter
  captures the provider's actual response model ID from its HTTP envelope,
  even when Koog's standard metadata omits it; absent IDs use the requested ID.
- **Answer shape:** car, trim, configuration ID, and requested options with
  Standard / Optional / Not available / Unknown status and conditions/evidence
  details. No summary or free-form answer sections; no matches prints
  `No cars returned.` Shape validation does not verify recommendation accuracy.
- **Answer length:** option details stay concise; recommendations default
  to at most three configurations unless more/all matches are explicitly requested.
  Necessary source caveats remain included. Truncated responses still fail visibly,
  even when the token budget is exhausted before any visible answer.
- **Timeout:** each of the two calls allows up to 10 minutes, including waiting
  for the provider's first response. Even a short question sends the full compact
  catalog, so free providers can take several minutes. Connection establishment
  remains limited to 15 seconds. Requests are not automatically retried; a longer
  timeout does not guarantee provider availability.
  Translation output is capped at 2048 tokens; OpenRouter recommendations default to 32,768,
  both capped by model metadata. Each stage has its own context check; a second-stage
  size failure can occur after translation. Two calls consume more quota/time;
  no optimization gain is assumed without measurement.
- **Output configuration:** `OPENROUTER_MAX_COMPLETION_TOKENS` overrides the
  OpenRouter recommendation budget in both CLI and desktop UI; default 32,768.
  For example, `export OPENROUTER_MAX_COMPLETION_TOKENS=65536` before
  `./gradlew run`. Restart the app after changing its launch environment.
  Values must be positive integers up to 2,147,483,647; invalid/blank values fail
  explicitly. Actual output is capped by the model's advertised maximum.
  The budget includes reasoning where the provider counts it; a larger budget
  can prevent truncation but does not guarantee completion. It reserves more
  context, can increase latency and paid-model costs, and does not change the
  10-minute timeout. Translation stays capped at 2048; local output targets
  default to 32,768 and use the separate configuration below.
- Other OpenRouter tests use a local fake server; no key or internet needed.

### Desktop prompt UI

Same workflow in a Compose Desktop window: pick an **API**—**OpenRouter**
(default), **LM Studio** (`http://localhost:1234/v1`), **llama.cpp**
(`http://localhost:8080/v1`) or **Ollama** (`http://localhost:11434/v1`).
For OpenRouter, pick a model from the dropdown:
`openrouter/free`, the three NVIDIA free models, or `qwen/qwen3.5-flash-02-23` (paid).
Qwen is marked paid in the menu and shows a charge warning when selected.
Cohere and Recursive entries have been removed.
The default remains free,
with **openrouter/free** listed first and selected by default.
Local APIs hide the model dropdown and skip OpenRouter metadata/pricing checks.
LM Studio reads `/api/v0/models`, requires exactly one loaded chat model,
uses its **loaded_context_length** (not advertised maximum), pins its model ID
for both stages and dynamically sizes each request's output allowance.
Unavailable/invalid metadata stops inference rather than bypassing the check.
llama.cpp and Ollama retain server-selected models, omit `model`, and have no
metadata/size preflight. Local APIs send
`Authorization: Bearer $LOCAL_AI_API_KEY` only when that variable is set.
The full catalog needs a large local context window; provider overflow still
surfaces explicitly because byte estimates are not exact token counts.
Ollama may require a `model` field and reject such requests. Then
type a prompt, press **Send**, and
native car/trim/options tables render below with the model ID and prototype disclaimer.
Progress shows translation then car selection. After translation,
**Download LLM Request JSON** saves the validated intermediate JSON via a Save
dialog—even while selection runs or after it fails. This is the same JSON sent
to the second call. The adjacent **Copy to clipboard** button copies that JSON
and confirms copying. The JSON includes your original request. Review before sharing.
Editing the input immediately clears prior results and JSON actions. Input editing
is disabled while either call runs. With unchanged text, Send reuses the validated
translation—even after changing models or APIs—and runs only car selection. Switching
models still triggers fresh metadata/context checks. Selection starts automatically.
The **Sources** column follows **Details**. Each **source** link opens a Save dialog
for the original bundled PDF, with confirmation before replacing an existing file.
Source documents/pages are shown beside the links; missing or invalid sources and
save errors appear explicitly. Sources are resolved through the catalog, not
model-supplied URLs. Readable copied output includes source references in a
separate tab-separated Sources column.
**Copy raw JSON** copies the exact model response text exposed by Koog (not the
HTTP envelope or reasoning). **Copy readable output** copies the displayed
information as tab-separated text plus model/disclaimer for diagnostics.
Both buttons confirm copying. Invalid JSON shows an explicit `INVALID_RESPONSE`
error rather than rendering Markdown or falling back to plain text. Invalid or
truncated responses retain a **Copy raw model output** button. Raw model content
can repeat request/catalog data: review before sharing.
No input validation; errors (including a missing key) appear in a selectable
error panel. **Copy error details** copies the displayed report: failure
category, submitted model, request stage, HTTP status, provider code/message,
upstream JSON error reason, provider name and request ID when available, plus a
suggested action.
Credentials are redacted; raw response dumps, request headers and prompt/catalog
fields are excluded. Review provider-written messages before sharing.
Plan: [`docs/openrouter-desktop-ui-plan.md`](docs/openrouter-desktop-ui-plan.md).

Prerequisites: `OPENROUTER_API_KEY` for OpenRouter (the dropdown replaces `OPENROUTER_MODEL`),
or a running local server for local APIs (`LOCAL_AI_API_KEY` optional), and a compact catalog JSON file. The UI does not upload PDFs or raw text.

```sh
./gradlew run
```

### DEBUG performance metadata

After a response or failure, expand **DEBUG / Performance metadata** below the
result/error. Per-stage summaries and selectable JSON distinguish model metadata
lookup, request translation, and car recommendation. **Copy metadata JSON** copies
the export with confirmation; **Download metadata JSON** saves `llm-metadata.json`
via Save dialog, with overwrite confirmation and explicit save errors.
Both actions export the same schema-versioned JSON.

The typed metadata records:

- Provider-reported prompt/completion/total tokens, reasoning tokens, cached
  input/cache-write tokens and cost, when present.
- API, requested/actual model, provider, response ID, HTTP status, finish reason,
  stage outcome, elapsed milliseconds, input UTF-8 bytes and estimated input tokens.
- Requested/effective output budget, known context/output limits, and whether
  the chat request was dispatched.
- Completion tokens per second over the entire measured request; optional
  provider-reported generation tokens/second and time-to-first-token seconds.

OpenRouter still executes via Koog 1.3.0. Its declared Ktor transport observes
the response envelope and retains only allowlisted metadata; local responses
use the same allowlist over OpenAI-compatible JSON. Nested usage details are
provider-dependent, not guaranteed. Missing/invalid optional values are `null`,
displayed as unavailable, never invented as zero or substituted with estimates.
Reasoning/cached counts are subsets: do not add them to totals again. Reported
cost is passed through, not calculated from model prices.

Elapsed times use a monotonic client clock. Request timing includes transport
and response processing, not just model generation; whole-request tokens/second
is **not** generation speed. Non-streaming requests cannot measure client-side
time to first token, so that field stays unavailable unless the server reports
it explicitly. Usage/finish reason survive truncated, empty and invalid-answer
failures where the response provides them. Timeouts/connection/preflight failures
retain only known timing/size/limit data. Metadata lookup failures may have no
chat request record. Successful prior stages remain visible after a later failure.

Unchanged-input resubmissions mark translation `REUSED` without copying old
token counts into the new run. New submissions clear old metrics; editing input
clears the panel. Metadata contains **no prompts, catalog facts, generated text,
reasoning text, headers or keys**. Provider identifiers are capped/redacted;
review them before sharing. No raw metadata body or extra provider fields are
dumped or logged. Collection adds no inference calls, retries, or paid lookups.

### LM Studio context troubleshooting

Measure the current model-facing payload without making any inference request:

```sh
./gradlew catalogSize
curl --silent --show-error http://localhost:1234/api/v0/models
```

For the current bundled catalog, indexing reduces 1,024,558 UTF-8 bytes to
235,979 bytes (76% smaller), retaining all 105 reviewed configurations.
The catalog-only heuristic is 117,990 tokens; the recommendation baseline is
about 120,472 input tokens plus a 32,768-token output target for both local and
OpenRouter defaults, **before adding the actual
translated request**. Rerun the command after catalog/prompt changes.
The estimate uses one token per two UTF-8 bytes plus message overhead, not
Qwen's tokenizer or its exact chat template. It can reject requests that would
actually fit and cannot guarantee server acceptance.

In LM Studio, start the server on port 1234, load exactly one chat model, set
its context in the load settings, and reload if needed. Confirm the returned
`state` is `loaded` and `loaded_context_length` is sufficient. A model's
`max_context_length` is its advertised capability, not proof it was loaded
with that capacity. A 262,144-token loaded context passes the current bundled
catalog's baseline estimate; actual tokenization and runtime memory can differ.
Do not raise context beyond supported model/runtime/memory limits or enable
silent prompt truncation to conceal overflow.

Local output configuration:

```sh
export LOCAL_AI_MAX_COMPLETION_TOKENS=32768
./gradlew run
```

The local recommendation target is 32,768 by default (previously 8,192).
Values must be positive integers up to 2,147,483,647; invalid/blank values fail
explicitly. Restart with the updated launch environment. This variable is
independent of `OPENROUTER_MAX_COMPLETION_TOKENS`.
For each LM Studio call:

```text
output allowance = min(request target, reported model output maximum if known,
                       loaded context - estimated full-message input)
```

Translation's target remains at most 2,048. Recommendation uses the configured
target; it can shrink when a request leaves less context. The input estimate
includes system instructions, request, catalog, delimiters and message overhead.
LM Studio currently reports loaded context but no separate output maximum.
No remaining output space means an explicit preflight failure; input/catalog
are never truncated. llama.cpp/Ollama lack context discovery in this app and use
the configured target without dynamic context sizing.

`REQUEST_TOO_LARGE` reports UTF-8 input bytes, estimated input tokens, requested
output and loaded context; the oversized chat call is not sent. Stage two can
fail after translation succeeds. `CONTEXT` means the server rejected the
request despite the estimate. Reducing `max_tokens` only frees the output
reservation; it cannot fix an input that already exceeds the whole context.
Local truncation diagnostics show the actual output allowance, requested target,
estimated input and known limits. If context already caps output, increasing the
target alone will not help. Larger targets may take longer and do not increase
the existing 10-minute timeout; heuristic sizing still cannot guarantee a fit.
Whitespace removal is already applied; gzip reduces transport size, not the
text the model must tokenize.

More parameters do not imply more context. Start with the existing Qwen model;
consider a stronger model if valid JSON, index resolution or recommendation
quality is poor, while independently checking its loaded context and memory
requirements. Lossless encoding preserves data, not a guarantee of reasoning
accuracy, especially on small models with long prompts.

Local validation with `qwen3.5-2b-mlx` loaded at 262,144 tokens: a tiny request
returned `OK`, but a recommendation request containing the entire indexed
catalog timed out at the existing 600,000 ms limit. No full-catalog answer was
validated. Passing the context estimate does not establish practical latency;
increasing context alone does not solve this timeout. Check LM Studio's
prompt-processing/generation progress and memory pressure before increasing
timeouts. Request-specific projection or batching would be a separate workflow
change; neither happens automatically.

### Live OpenRouter integration test

`OpenRouterClient` (in `openrouter/`) is the reusable real client: it checks
the configured allowed model against `https://openrouter.ai/api/v1/models`, then
sends one Koog prompt. `complete(..., context = json)` wraps the question and a
context document as delimited data blocks and rejects requests whose estimated
size exceeds the model context (`REQUEST_TOO_LARGE`, nothing sent).
`OpenRouterClientIntegrationTest` sends a simple prompt to the real API and
asserts a non-empty answer; a second test sends "Show me all hybrid cars under
50,000 EUR." with the full indexed catalog (~236 KB, ~118k catalog-only estimated
tokens; run `catalogSize` for current figures) and asserts a non-empty
answer, allowing up to 8192 completion tokens and a 10-minute timeout. Both run with the
normal tests and fail if either variable is missing:

```sh
export OPENROUTER_API_KEY=...        # never commit it
export OPENROUTER_MODEL=vendor/model:free
./gradlew test --tests 'com.sps23.aiworkflow.openrouter.OpenRouterClientIntegrationTest'
```

When done, add `@Ignore` (`kotlin.test.Ignore`) to the test class; otherwise
`./gradlew test` and CI need a key and internet. A pass shows connectivity, not
answer quality. Free models can return HTTP 429; retry later or pick another
current free model.

On Windows, use `gradlew.bat` instead of `./gradlew`.

The [Gradle build and test workflow](.github/workflows/gradle.yml) runs
`./gradlew build test --no-daemon -PskipUiRenderTests` on Ubuntu with Temurin JDK 25
for pushes and pull requests, excluding `CarPromptScreenTest`, which renders Compose
UI offscreen but does not launch the desktop application. Run it locally with
`./gradlew test --tests 'com.sps23.aiworkflow.ui.CarPromptScreenTest'` when needed.
The workflow can also be started manually from the Actions tab.

On macOS, after installing [Temurin 25](https://adoptium.net/temurin/releases/?version=25):

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
export PATH="$JAVA_HOME/bin:$PATH"
```

The Kotlin toolchain compiles and runs tests with JDK 25 and produces Java 25
bytecode. Running the resulting application on Java 21 is not supported.
There is no application entry point or native distribution configuration yet.

## Kotlin formatting

Spotless 8.10.4 uses ktlint 1.8.0 with the `ktlint_official` style, based on
Kotlin's official coding conventions. The shared `.editorconfig` configures
four-space indentation and LF line endings for Kotlin sources and Gradle Kotlin
scripts, including in IntelliJ IDEA.

```sh
./gradlew spotlessApply   # Format Kotlin sources and root Gradle Kotlin scripts
./gradlew spotlessCheck   # Verify formatting without changing files
```

`./gradlew build` automatically runs `spotlessApply` before Kotlin compilation
and formatting verification, so builds may modify source files.
Every Gradle `Test` task depends on `spotlessCheck` (Spotless's verification
task). A standalone `./gradlew test` fails on formatting violations without
rewriting files. `check` also includes Spotless verification through the
plugin's standard lifecycle integration.

The plugin resolves its formatter dependencies automatically; no application
runtime dependency is needed. Tests launched directly through IntelliJ's own
test runner bypass Gradle tasks; delegate tests to Gradle to enforce this gate.

## Why JDK 25 rather than 21?

Both are LTS releases. For this new application, 25 is the preferred baseline:
it has a later vendor support horizon, includes JVM improvements since 21, and
avoids an early baseline migration. LTS update availability and licensing depend
on the JDK vendor; this is not a promise of free Oracle support.

Java 21 remains a reasonable choice when a deployment platform or a required
library only certifies 21, or when distributing a library to Java 21 consumers.
Neither constraint currently applies here. Kotlin does not automatically use
new Java language syntax or switch coroutines to virtual threads just because
the JDK is newer; any performance benefit must be measured for the application.

Build compatibility matters separately from library bytecode compatibility:

- Gradle 8.14.3 was replaced with 9.3.0 because Java 25 support starts at 9.1.
  The wrapper includes the distribution SHA-256 checksum.
- Kotlin JVM, serialization, and Compose compiler plugins were moved together
  from 2.3.10 to 2.3.21. Kotlin 2.3 supports Java 25 bytecode; 2.3.21 also
  officially supports Gradle 9.3.0, whereas 2.3.10's fully supported range ends
  at Gradle 9.0.0.
- Compose Multiplatform stays at 1.7.0: the existing version passed the
  offscreen rendering check, so a UI framework upgrade is not required by this
  JDK change.

## Dependency compatibility

The following smoke checks passed on macOS arm64 with Temurin 21.0.8 and
25.0.5. The Java 21 comparison temporarily selected a 21 toolchain and rebuilt
the tests for 21; the committed configuration targets 25 only.

| Dependency | Resolved version | Exercised on both JDKs |
| --- | --- | --- |
| Koog core / OpenRouter | 1.3.0 | Core agent class loading and OpenRouter client construction/cleanup |
| Koog Ktor HTTP transport | 1.3.0 | Service-provider discovery during OpenRouter initialization |
| Ktor core / CIO / content negotiation / JSON | 3.3.3 | Local HTTP request and JSON response decoding |
| Coroutines core / Swing | 1.10.2 | Concurrent I/O and Swing event-thread dispatch |
| kotlinx serialization JSON | 1.10.0 | Generated serializer and JSON round trip |
| Compose Desktop / Skiko | 1.7.0 / 0.8.15 | Compose compiler, state, offscreen text rendering, native library loading |

These are compatibility smoke checks, not blanket upstream certification of
every transitive dependency or execution path. They do not cover authenticated
OpenRouter calls, interactive GPU windows, native
packaging, or other operating systems.

JDK 24+ warns about JNI native access. Tests explicitly pass
`--enable-native-access=ALL-UNNAMED` for Skiko. Apply the same option to the
classpath-based desktop application's JVM arguments when adding its launcher
and native distributions; it belongs on the application/test JVM, not just
the Gradle daemon. This authorizes native access rather than disabling a
compatibility check.

Other dependency details:

- Koog publishes its core as `ai.koog:agents-core`, not `ai.koog:koog-core`.
- Koog 1.3.0 requires an HTTP transport provider. The explicit
  `ai.koog:http-client-ktor` dependency fixes client initialization, which
  otherwise fails on both JDK versions; plain Ktor client dependencies do not
  register a Koog transport provider.
- The older direct Ktor, coroutines, and serialization declarations resolve to
  the newer versions required by Koog, as shown above. The dependency report is
  the source of truth if dependencies change.

References:
[Java LTS roadmap](https://www.oracle.com/java/technologies/java-se-support-roadmap.html),
[Gradle Java 25 support](https://docs.gradle.org/9.1.0/release-notes.html#java-25),
[Kotlin/Gradle compatibility](https://kotlinlang.org/docs/gradle-configure-project.html),
[Compose JVM requirements](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html),
[JNI native-access changes](https://openjdk.org/jeps/472).
