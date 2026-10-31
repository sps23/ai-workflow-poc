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
takes effect once that workflow is on the default branch. PDF text extraction
is not part of this app; local source-PDF downloads require the actual LFS
objects, not pointer files.

## Build

Requires JDK 25 (a current Eclipse Temurin 25 LTS build is recommended).
Set `JAVA_HOME` and the IDE's Gradle JVM to that JDK, then use the included
Gradle 9.3.0 wrapper:

```sh
./gradlew build
./gradlew test
./gradlew dependencies --configuration runtimeClasspath
```

## Package for another computer (JDK 25)

The desktop UI can be shipped as a dependency-inclusive JAR, optionally wrapped
in a ZIP. The receiving computer needs JDK 25 and a graphical desktop, but does
not need Gradle or the source checkout.
**Build separately for each target OS and CPU architecture:** Compose Desktop
includes platform-specific native libraries, so a JAR built on macOS ARM64 is
not a universal Windows/Linux or Intel build. Run the packaging command on a
computer (or CI runner) matching the target platform.

### Build and run the JAR

First download the real PDFs and refresh the bundled catalog, then package:

```sh
git lfs pull
./gradlew generateDataModelCatalog packageUberJarForCurrentOS
```

On Windows, use `.\gradlew.bat` instead of `./gradlew`.
The resulting JAR is under `build/compose/jars/`; use that file, not the
ordinary JAR under `build/libs/`, which does not bundle runtime dependencies.
The packaged JAR contains the application, its dependencies, catalog and source
PDF resources, but not a JDK.

Copy the JAR to the other computer. In a terminal in its directory, confirm
`java -version` reports Java 25, then launch it (replace the example filename
with the actual generated filename):

```sh
java --enable-native-access=ALL-UNNAMED -jar ai-workflow-poc-<platform>.jar
```

Set `JAVA_HOME` and put its `bin` directory on `PATH` if `java` points to another
version. Pass the native-access flag explicitly when running the JAR directly;
Gradle's configured JVM arguments are not automatically applied by `java -jar`.
For OpenRouter, set `OPENROUTER_API_KEY` in that terminal before launching
(`export OPENROUTER_API_KEY=...` on macOS/Linux, or
`$env:OPENROUTER_API_KEY = "..."` in PowerShell). Never put keys in the archive.
Local providers instead require their server to be running and reachable from
the receiving computer; see the desktop UI configuration below.

### Share as a ZIP

ZIP is just a transport wrapper around the same JAR, not an installer or a
bundled JDK. After packaging, create it on macOS/Linux with:

```sh
zip -j ai-workflow-poc.zip build/compose/jars/*.jar
```

Or in Windows PowerShell:

```powershell
Compress-Archive -Path build/compose/jars/*.jar -DestinationPath ai-workflow-poc.zip
```

Send `ai-workflow-poc.zip`, extract it on a matching-platform computer with
JDK 25, and run the extracted JAR using the command above. Neither format
requires copying the repository or downloading dependencies on the receiving
computer; OpenRouter still requires internet access.

## Normalized data-model catalog

Generate `src/main/resources/vehicle-catalog/data-model-catalog.json` from every
car JSON file under `src/main/resources/car-pricelists/`:

```sh
./gradlew generateDataModelCatalog
```

The generator parses each file into the normalized Kotlin catalog and links it
to exactly one PDF in the same brand folder with an identical filename stem
(only `.json` vs `.pdf` differs). Near-matches are rejected; a missing exact
match fails the run and preserves the previous output. The linked `.pdf` resource
path is included in each vehicle for the UI's local source-PDF action. Console
logs report files read, links created, vehicles included, and the output path.
Every successful run replaces the existing output file; generation failures leave
the previous catalog intact. The Gradle task is always executed when requested.
Optional task arguments are the source directory and output JSON path, in that order.

The current generated catalog contains four linked source pairs: CUPRA's
`20260820_CUPRA_Ireland_Product_Guide_Raval`, Peugeot's `5008-spec-sheet`,
Škoda's `Peaq_MY27_PG_August_CW48_2026`, and Toyota's
`Toyota Corolla Cross - Brochure` (each with matching `.json` and `.pdf` files).

## Car prompt CLI (OpenRouter)

Uses two sequential calls to the same configured OpenRouter model via Koog:
translate user text into `LlmRequest` JSON without catalog, then send that JSON
plus the generated Data Model catalog. Validate the cars/trim/options response
shape, then print a readable text projection.
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
catalog recommendation calls. There is no automatic paid fallback.
No application fallback model, retry, tool, or plugin is
used; unavailable or unapproved paid models stop the run.

- **External transmission:** the prompt and generated Data Model catalog,
  including source-derived prices, specifications and equipment, go to OpenRouter
  and its provider. Use only data cleared for that. Raw PDF contents are not sent.
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
  figures; nothing is truncated. The complete generated catalog may exceed small free models.
- **Limitations:** answers are AI-generated and unchecked against the user's
  requirements; free endpoints can be rate-limited. The OpenRouter adapter
  captures the provider's actual response model ID from its HTTP envelope,
  even when Koog's standard metadata omits it; absent IDs use the requested ID.
- **Answer shape:** car, trim, configuration ID, and requested options with
  Standard / Optional / Not available / Unknown status and conditions/evidence
  details. No summary or free-form answer sections; no matches prints
  `No cars returned.` Shape validation does not verify recommendation accuracy.
- **Answer length:** option details stay concise; recommendations default
  to up to three distinct make/model combinations, with every matching trim/powertrain
  configuration for each. Explicit result counts/all requests override the model count;
  fewer matching models yield fewer results. This policy is in the shared LLM prompt,
  not a Kotlin row limit; matching/completeness remain AI-generated, not guaranteed.
  Necessary source caveats remain included. Truncated responses still fail visibly,
  even when the token budget is exhausted before any visible answer.
- **Timeout:** each of the two calls allows up to 10 minutes, including waiting
  for the provider's first response. Even a short question sends the full generated
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
The UI and CLI use the generated
`vehicle-catalog/data-model-catalog.json` (run `./gradlew generateDataModelCatalog`
to refresh it).
The UI catalog contains normalized car/trim/variant, equipment, option, pricing
and technical-spec data, but has no fact review flags or page-level citations.
The first-stage `LlmRequest` and response JSON shape stay unchanged. Variant IDs
are derived deterministically from the car, trim and variant name. The model
returns empty `sources` arrays rather than invented page citations; each result
offers a **PDF Source** column with **Download** buttons resolved locally from the catalog.
These Data Model facts have not been independently reviewed; recommendations
remain prototype advice. OpenRouter receives this UI catalog along with the
translated request; use it only if its source-derived contents are cleared for
external processing. The UI catalog needs a large context window; provider
overflow still surfaces explicitly because byte estimates are not exact token counts.
Ollama may require a `model` field and reject such requests. Then
type a prompt, press **Send**, and
native car/trim/options tables render below, grouped by model then configuration,
with the model ID and prototype disclaimer.
Below each configuration, engine details and prices resolve from the bundled
catalog, not model-authored specifications. Missing facts display as unavailable.
Prices prefer on-the-road, then list price/RRP, with the basis labelled.
Returned optional equipment is itemized when its name/code and trim scope match
the catalog exactly. Totals appear only when all included prices are exact,
unconditional and in the same currency; otherwise the total is unavailable.
Standard equipment adds no charge. **Copy readable output** includes these
details; raw response JSON remains unchanged.
Progress shows translation then car selection. After translation,
**Download LLM Request JSON** saves the validated intermediate JSON via a Save
dialog—even while selection runs or after it fails. This is the same JSON sent
to the second call. The adjacent **Copy to clipboard** button copies that JSON
and confirms copying. The JSON includes your original request. Review before sharing.
Editing the input immediately clears prior results and JSON actions. Input editing
is disabled while either call runs. With unchanged text, Send reuses the validated
translation—even after changing models or APIs—and runs only car selection. Switching
models still triggers fresh metadata/context checks. Selection starts automatically.
Each car links to its original bundled PDF with a Save dialog and overwrite
confirmation. PDF paths come from the local data-model catalog, not model output.
The option-level **PDF Source** column downloads the configuration's underlying
PDF, including when no options were requested. The Data Model catalog has no page
citations, so the model returns `[]` in `sources`; the response contract is unchanged.
Readable copied output preserves any returned page references.
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
or a running local server for local APIs (`LOCAL_AI_API_KEY` optional). The UI
does not upload PDFs or raw PDF contents.

```sh
./gradlew run
```

### DEBUG performance metadata

After a response or failure, expand **DEBUG / Performance metadata** below the
result/error. Per-stage summaries and selectable JSON distinguish model metadata
lookup, request translation, and car recommendation. Translation and recommendation
each have four stacked, full-width tables: **Request details**, **Token usage**,
**Budgets & limits**, and **Timing & speed**. Columns share consistent typography,
wrap to fit the window without horizontal scrolling, and retain every metric,
including **Output tokens / second (whole request)**.
Missing values display `unavailable`; reused translation carries no old usage.
**Copy readable debug data** copies all four groups for each request as tab-separated text,
including workflow/metadata-lookup timing, with copy confirmation.
**Copy metadata JSON** copies
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

### Live OpenRouter integration test

`OpenRouterClient` (in `openrouter/`) is the reusable real client: it checks
the configured allowed model against `https://openrouter.ai/api/v1/models`, then
sends one Koog prompt. `complete(..., context = json)` wraps the question and a
context document as delimited data blocks and rejects requests whose estimated
size exceeds the model context (`REQUEST_TOO_LARGE`, nothing sent).
`OpenRouterClientIntegrationTest` covers a simple prompt and a catalog prompt
using the bundled generated Data Model catalog. It is ignored by default; remove
its `@Ignore` annotation locally before running against the live API:

```sh
export OPENROUTER_API_KEY=...        # never commit it
export OPENROUTER_MODEL=vendor/model:free
./gradlew test --tests 'com.sps23.aiworkflow.openrouter.OpenRouterClientIntegrationTest'
```

A pass shows connectivity, not answer quality. Free models can return HTTP 429;
retry later or pick another current free model.

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
bytecode. Running the resulting application on Java 21 is not supported. The
Compose Desktop app runs with `./gradlew run`; native distribution packaging is
not configured yet.

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
OpenRouter calls, interactive GPU windows, native packaging, or other operating systems.

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
