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
still requires PDF tooling; an LFS pointer alone is not a readable PDF.

## Build

Requires JDK 25 (a current Eclipse Temurin 25 LTS build is recommended).
Set `JAVA_HOME` and the IDE's Gradle JVM to that JDK, then use the included
Gradle 9.3.0 wrapper:

```sh
./gradlew build
./gradlew test
./gradlew dependencies --configuration runtimeClasspath
```

## PDF corpus inventory

Generate the checked-in, metadata-only inventory with:

```sh
./gradlew inventoryPdfs
```

The task writes `src/main/resources/car-pricelists-extracted/manifest.json`
with each PDF's relative path, make, size, SHA-256, page count, detected
text/image status, initial processing status, and collision-checked output
path. It does not store extracted PDF text. Text-based PDFs start as `QUEUED`;
image-only or non-extractable documents start as `NEEDS_REVIEW`; unreadable
files remain in the manifest as `FAILED`.

The initial representative samples cover four makes: Peugeot's `208-spec-sheet.pdf`,
CUPRA's `20260618-cupra-price-list.pdf`, Toyota's `Toyota Corolla Cross - Brochure.pdf`,
and Škoda's `Epiq_MY27_PG_September_CW48_2026.pdf` (product guide).

The Stage 2 matrix spike runs with:

```sh
./gradlew probePdfMatrix
./gradlew probePeugeot5008Matrix
```

These compare Tika's document text/metadata with PDFBox glyph coordinates on
Volkswagen Golf MY27 page 8 and Peugeot 5008 page 17. Annotated, label-free
references and measured limits are in
[`docs/pdf-extractor-stage-2-spike.md`](docs/pdf-extractor-stage-2-spike.md).

## Car prompt CLI (OpenRouter)

Sends one prompt plus a compact JSON of all reviewed catalog configurations to
one `:free` OpenRouter model via Koog, then prints a plain-text answer.
Plan: [`docs/openrouter-simple-integration-plan.md`](docs/openrouter-simple-integration-plan.md).

```sh
export OPENROUTER_API_KEY=...        # never commit it
export OPENROUTER_MODEL=vendor/model:free
./gradlew carPrompt --args='Suggest a seven-seat car from this catalog'
```

Pick a current model from [OpenRouter's free models](https://openrouter.ai/models?q=free).
The CLI checks live metadata first: the exact ID must be listed, end in `:free`,
have text output and zero pricing. No fallback model, retry, tool, or plugin is
used; unavailable or non-free models stop the run.

- **External transmission:** the prompt and the compact catalog (prices,
  measurements, equipment, `documentId:page` references; no raw PDF text) go to
  OpenRouter and its provider. Use only data cleared for that.
- **Size gate:** the request must fit the model's context with a 1024-token
  output allowance. The input estimate is a conservative heuristic (1 token per
  2 UTF-8 bytes), not a tokenizer count. Oversized requests fail with both
  figures; nothing is truncated. The full catalog may exceed small free models.
- **Limitations:** answers are AI-generated and unchecked against the user's
  requirements; free endpoints can be rate-limited. Koog 1.3.0 does not expose
  the provider's response model ID, so the CLI prints the requested ID unless
  Koog reports one.
- Other OpenRouter tests use a local fake server; no key or internet needed.

### Live OpenRouter integration test

`OpenRouterClient` (in `openrouter/`) is the reusable real client: it checks
the configured `:free` model against `https://openrouter.ai/api/v1/models`, then
sends one Koog prompt. `complete(..., context = json)` wraps the question and a
context document as delimited data blocks and rejects requests whose estimated
size exceeds the model context (`REQUEST_TOO_LARGE`, nothing sent).
`OpenRouterClientIntegrationTest` sends a simple prompt to the real API and
asserts a non-empty answer; a second test sends "Show me all hybrid cars under
50,000 EUR." with the full compact catalog (~1 MB, ~512k estimated tokens, so it
needs a large-context model such as a 1M-token one) and asserts a non-empty
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
`./gradlew build test --no-daemon` on Ubuntu with Temurin JDK 25 for pushes and
pull requests. It can also be started manually from the Actions tab.

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
| Tika core / standard parsers | 3.2.3 (historical comparison) | Parser discovery and HTML text extraction |
| Ktor core / CIO / content negotiation / JSON | 3.3.3 | Local HTTP request and JSON response decoding |
| Coroutines core / Swing | 1.10.2 | Concurrent I/O and Swing event-thread dispatch |
| kotlinx serialization JSON | 1.10.0 | Generated serializer and JSON round trip |
| Compose Desktop / Skiko | 1.7.0 / 0.8.15 | Compose compiler, state, offscreen text rendering, native library loading |

These are compatibility smoke checks, not blanket upstream certification of
every transitive dependency or execution path. They do not cover authenticated
OpenRouter calls, all Tika file formats, interactive GPU windows, native
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
- Tika core and the full standard parser bundle now use 3.3.3, a supported 3.x
  maintenance release with dependency upgrades and parser hardening. All
  standard formats remain available; no parser modules are excluded.
  `TikaCompatibilityTest` checks HTML extraction and HTML/PDF/DOCX parser
  registration on JDK 25 independently of the ignored JDK comparison suite.
  The table above records the earlier 3.2.3 comparison, not a 3.3.3/JDK 21 run.
- Updating Tika does not guarantee that all transitive dependencies are free of
  vulnerabilities. Re-scan the resolved runtime dependencies and match each
  warning to its exact artifact/version and advisory. For hostile documents,
  use a restricted worker process with memory/time limits, least-privilege
  filesystem access, and no unnecessary network access; Tika is not a sandbox.
  See the [3.3.3 release notes](https://tika.apache.org/3.3.3/index.html) and
  [Tika security model](https://tika.apache.org/security-model.html).
- The older direct Ktor, coroutines, and serialization declarations resolve to
  the newer versions required by Koog, as shown above. The dependency report is
  the source of truth if dependencies change.

References:
[Java LTS roadmap](https://www.oracle.com/java/technologies/java-se-support-roadmap.html),
[Gradle Java 25 support](https://docs.gradle.org/9.1.0/release-notes.html#java-25),
[Kotlin/Gradle compatibility](https://kotlinlang.org/docs/gradle-configure-project.html),
[Compose JVM requirements](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html),
[JNI native-access changes](https://openjdk.org/jeps/472).
