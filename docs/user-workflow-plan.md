# First-run desktop workspace and vehicle-catalog workflow

## Problem and approach

The desktop application must work on a new computer with no PDFs, extraction JSON files, or generated catalog. Replace the desktop application's dependency on bundled user data with one persistent, user-selected workspace. Guide users through importing and extracting individual PDFs, explicitly building a complete catalog, and searching that catalog using the existing Recommender UI.

This plan tracks implementation of the workspace workflow. Workspace storage, catalog context, managed extraction, catalog building, desktop integration, and onboarding documentation are implemented. Controller regression coverage and an empty-home packaged launch smoke pass; a full interactive packaged workflow smoke remains pending. The current-code baseline below reflects the repository before implementation. Changes affect desktop storage and catalog flow, not the existing CLI or resource-generation workflow.

## Current-code baseline

- `CarCatalogApp` currently has Recommender and PDF Extractor tabs. Recommender loads one bundled catalog/context lazily; source-PDF downloads resolve from classpath resources.
- Both tabs use the shared `LlmProviderControls` and provider/model infrastructure. Their controller selections are separate; both currently start with Google AI Studio and `gemini-3.5-flash-lite`, and typed keys stay in memory. Google extraction sends the whole PDF through native Gemini `generateContent`; OpenAI, OpenRouter, local providers, and Manual API use `TextPdfClient` and PDFBox 3.0.6 to send extracted text only. Text-based providers omit images/layout and reject PDFs with no extractable text. PDFBox is already declared; no dependency is needed for this behavior.
- `PdfExtractionService` writes validated schema-version-1 envelopes and currently saves failed model text verbatim as `<stem>.raw.json`. The CLI uses the existing output-dir/environment defaults, overwrite-before-call behavior, and fallback atomic-move policy.
- `CatalogSourceParser` already handles both hand-written Data Model JSON and validated extraction envelopes. The generator links each JSON to one same-stem sibling PDF, preserves its previous output on validation failure, and remains a resource/CLI workflow.

## Confirmed decisions

| Area | Agreed behavior |
|---|---|
| Workspace | One persistent workspace per computer/user, not a multi-workspace feature. |
| First run | Offer `~/CarCatalogAssistant` and a folder chooser. Remember the selected absolute path in per-user application settings. |
| Environment | Do not create or modify system environment variables. Existing API-key environment variables remain supported. |
| Extraction | Select one PDF anywhere on disk; copy it into `input/` when Extract is clicked, before sending it to the selected provider. Google receives the PDF; other supported providers receive PDFBox-extracted text. Save the result beside the copied PDF. |
| Filename collisions | Different PDFs with the same filename get unique imported names. Re-extracting an identical imported PDF requires confirmation. |
| Retry failure | Preserve any last successful JSON; write separate raw diagnostics when available. |
| Raw results | Valid JSON diagnostic envelopes containing the PDF filename and raw provider text. Display them in Build Catalog, but exclude them from catalog inputs. |
| Build inputs | All supported extraction and hand-written JSON/PDF pairs under `input/`, with no selection checkboxes. |
| Catalog output | Exactly `data/data-model-catalog.json`. Confirm replacement; no catalog versions or backups. |
| Build failure | All or nothing. An invalid included source aborts the build and preserves any previous catalog. |
| Search | Desktop uses the workspace catalog, not a bundled fallback. Preserve the current Recommender controls, provider behavior, and result presentation. |
| Reload | Successful builds immediately activate the new catalog and clear old recommendation results. Later extractions do not change the active catalog until another successful build. |
| Recovery | Missing/inaccessible saved workspace triggers explicit retry, locate, or replacement setup; no silent fallback. |
| Scope | Desktop workflow only. Existing CLI defaults, flags, raw-output convention, resource fixtures, and developer commands remain compatible. Bundled resources are not automatically copied into a user's workspace. |

## 1. Workspace and startup

### Directory layout

```text
<workspace>/                         # default: ~/CarCatalogAssistant
  input/
    brochure.pdf
    brochure.json                   # validated extraction or supported hand-written model
    brochure.raw.json               # optional excluded diagnostic
    brochure-2.pdf                  # different PDF with colliding original filename
    brochure-2.json
  data/
    data-model-catalog.json          # absent until a successful build
  logs/
    application.log                 # app-owned INFO/ERROR JSON Lines
    log_yyyyMMddHHmmss_assistant.log # translation or recommendation attempt
    log_yyyyMMddHHmmss_extractor.log # PDF extraction attempt
    log_yyyyMMddHHmmss_builder.log   # catalog build, not an LLM call
```

User PDFs downloaded outside the workspace remain untouched. The workspace does not have to be adjacent to the application JAR or the current working directory. A stable home-directory default avoids permissions problems in installation directories and changes caused by how the application is launched.

### Minimal persisted configuration

Persist only a settings schema version and the normalized absolute workspace path. Do not persist keys, prompts, selected models, recommendation history, or extraction UI state.

Recommended settings locations:

| OS | Settings file |
|---|---|
| macOS | `~/Library/Application Support/CarCatalogAssistant/settings.json` |
| Windows | `%APPDATA%/CarCatalogAssistant/settings.json`; show a configuration error if the location cannot be resolved. |
| Linux | `$XDG_CONFIG_HOME/CarCatalogAssistant/settings.json`, otherwise `~/.config/CarCatalogAssistant/settings.json` |

Use existing JSON serialization and Java filesystem APIs; no new dependency is needed. Write settings safely through a temporary sibling file and replacement. Surface read, decode, permission, and save failures explicitly.

The app remains stateless with respect to interactive session/business state.
Persistent PDFs, JSON, the generated catalog, diagnostic logs, and the single
workspace setting are intentional disk artifacts. Diagnostic logs intentionally
retain request/output history under the logging decision below; this does not
enable persistent credentials, UI history restoration or conversation memory.

### Approved diagnostic logging, 2026-11-01

Create `logs/` during setup and add it automatically to existing valid workspaces
on startup. Failure to create/access logs is explicit workspace recovery/error,
not silent logging disablement. `logging/WorkspaceLogs.kt` owns JSON Lines
storage using Java filesystem APIs and existing serialization; no dependency.

One collision-safe file per Assistant inference attempt (translation and
recommendation separately), per extraction-service attempt, and per catalog build.
Use local `yyyyMMddHHmmss` with a counter before the component on collisions.
`application.log` appends app-owned INFO/ERROR summaries and workspace/catalog/
import lifecycle events, not arbitrary library console output.

Record request prompts, structured request/catalog JSON, exact exposed model text
plus parsed JSON, schema, available requested/reported models, performance metadata,
validation/errors and terminal completion/failure/cancellation. Text extraction
records the actual extracted-text request; native PDF requests record identity/
hash/size but omit binary/base64 data. Builder logs inventory, source snapshots
and normalized output. Preflight/model failures belong to the attempted stage;
reused translation makes no new inference log. A crash may leave an incomplete log.

Redact configured environment/typed keys, bearer tokens and credential-labelled
JSON fields. Keep other prompt/source content in full: these are sensitive debug
artifacts, not sanitized metadata exports. Create new POSIX directories/files
700/600; other filesystems inherit ACLs. Reject log symlinks, never overwrite
prior operation logs, lock shared appends and flush writes. Propagate log-write
errors with an explicit warning that publication/inference may have completed.
No automatic retries, retention cleanup or rotation; the user manages log size.
CLI defaults and sanitized UI exports remain unchanged.

### First-run flow

1. Show a setup screen before enabling workspace-dependent operations.
2. Explain the root folder, the `input/` and `data/` children, and which files the app will copy/write.
3. Offer the default path and a directory chooser. Cancel leaves setup incomplete and performs no extraction.
4. Validate the path and ensure it is a directory, not a file. Create missing directories after the user confirms setup.
5. Check the required read/write access using actual filesystem operations rather than relying only on permission flags. Never overwrite unrelated files.
6. Persist settings only after successful initialization. If saving settings fails, show the failure and keep setup incomplete; never claim the path will be remembered.
7. Show the active workspace path in the application shell. Start a new empty workspace on PDF Extractor with brief next-step guidance.

### Later startup and recovery

- Load the remembered path, validate workspace access, and inspect the catalog without requiring API keys.
- If a catalog is present and valid, make Recommender ready; otherwise keep extraction and catalog-building available.
- Missing catalog is an expected empty state, not a startup exception. Invalid/unreadable catalog is an explicit error.
- If the root is missing or inaccessible, show recovery: Retry, Locate existing workspace, or Create replacement workspace. Replacement must be explicitly chosen.
- If `input/` or `data/` is missing in an existing root, explain what is missing and offer repair rather than implying that user data was recovered.
- Do not move or delete an old workspace during recovery. Save a replacement path only after successful validation.
- Corrupt settings must be reported and corrected through setup, not silently ignored.

## 2. PDF Extractor user process

### UI

- Retain single-file selection, the shared provider/model controls, provider-specific key handling and disclosures, progress, debug metadata, and actionable error results. Keep model discovery, context/output limits, timeouts, telemetry, and failure handling consistent with each provider's existing Recommender behavior.
- Retain the current Google AI Studio / `gemini-3.5-flash-lite` initial selection, separate per-tab provider selections, and in-memory typed keys. Workspace settings must not persist provider keys, model choices, or extraction UI state.
- Preserve the provider distinction already implemented: Google uses native Gemini PDF input, including visual content; all other supported providers use PDFBox-extracted text only. Do not add OCR or silently fall back between providers. Explain that images/layout are omitted for text extraction and that scanned/image-only PDFs require Google.
- Replace the independently editable output-directory field with the read-only workspace `input/` location.
- Display the selected external PDF path and, after import, the actual imported filename/path.
- Update success guidance to point to Build Catalog; remove instructions to copy JSON into repository resources or run Gradle from the desktop flow. Preserve those instructions for CLI/developer workflows.
- Selecting a file alone must not copy it or call a provider.
- Show success/raw paths and an Open folder action. Raw-text display/copy reads the envelope's `rawText`, not the serialized envelope.

### Execution sequence

1. Validate local prerequisites: selected regular file, supported PDF bytes, provider-specific model/key configuration, and accessible workspace. Keep provider-specific PDF checks and context/output gates explicit.
2. Read a stable PDF byte snapshot and calculate its SHA-256 using the existing helper. Use those same bytes for identity, imported content, and extraction provenance. The current service reads from a path itself; the desktop-managed path must pass the captured snapshot through import and extraction rather than rereading the external source.
3. Find an identical already-imported PDF by hash, not filename alone. Reuse its existing workspace name and ask before re-extracting, including when only a raw result or no JSON exists.
4. If the content is different but the filename collides, allocate `stem-2.pdf`, `stem-3.pdf`, etc. Consider PDF, validated JSON, and raw sidecar collisions so unrelated artifacts are never overwritten.
5. Copy the validated bytes safely to `input/` before the paid extraction. Do not self-copy when the selected file is already the identical workspace file. Never mutate the original downloaded PDF.
6. If confirmation is declined, leave existing files unchanged and make no paid call.
7. Run the existing extraction and validation flow using the imported identity: native full-PDF Gemini for Google; PDFBox-extracted text plus schema instructions through the selected chat backend for other providers.
8. On success, safely write `<imported stem>.json` beside the PDF. Preserve the existing schema-version-1 extraction envelope and its `provenance.pdfFileName`, SHA-256, model, timestamp, and nullable usage fields.
9. On invalid provider output, safely write `<imported stem>.raw.json` as a desktop diagnostic envelope. Do not promote it to validated JSON. Keep the existing CLI's verbatim raw-output format unchanged.
10. Report completion or failure explicitly, including whether an older successful extraction remains available.

### Result contracts

Validated files continue to use the existing `PdfExtractionFile` envelope with `schemaVersion` 1. `provenance.pdfFileName` must refer to the actual copied PDF basename, including any collision suffix, not the external source path. Keep `outputTokens` nullable when a provider does not report usage, as already supported.

Desktop raw files use a separate, explicitly identifiable diagnostic envelope:

```json
{
  "kind": "pdf-extraction-diagnostic",
  "schemaVersion": 1,
  "provenance": {
    "pdfFileName": "brochure.pdf",
    "pdfSha256": "<sha256>",
    "model": "<requested-or-reported-model>",
    "extractedAt": "<ISO-8601 UTC>"
  },
  "failure": {
    "kind": "INVALID_RESPONSE",
    "message": "<actionable explanation>",
    "violations": []
  },
  "rawText": "<provider text, escaped as a JSON string>"
}
```

Available usage/diagnostic metadata can be included without fabricating unavailable values. The envelope must remain valid JSON even when the provider returns malformed JSON or plain text.

Transport/authentication/configuration failures with no provider output do not fabricate raw results. If import has completed, keep the copied PDF for retry and say that no extraction JSON was produced. A successful retry removes only its matching desktop diagnostic; do not alter the CLI's existing raw-file semantics.

On a successful retry, remove an obsolete matching raw diagnostic; do not delete a last successful JSON on retry failure. If cleanup fails, report it as a warning and leave the valid success intact. Raw diagnostics represent the latest retained failure, not a versioning system.

If a raw/validated write fails, show the persistence error and actual surviving artifact paths; never present unsaved data as successfully saved.

## 3. Build Catalog tab

### Discovery and presentation

- Add a Build Catalog tab between PDF Extractor and Recommender in process order.
- Scan `input/` recursively, preserving compatibility with manually organized legacy source pairs in subfolders.
- Use a bounded-height scrollable/lazy table with JSON filename or relative path, linked PDF, source type, inclusion/status, and errors/warnings.
- Show all discovered JSON files, including excluded raw diagnostics. Raw rows are clearly labeled as not used.
- Include every other supported source. Unknown/malformed non-raw JSON blocks the build instead of being silently skipped.
- Recognize reserved `*.raw.json` diagnostic sidecars and the new diagnostic marker; legacy raw sidecars can be shown as excluded without requiring valid JSON.
- Keep scan order deterministic. Ignore temporary files and do not follow directory symlinks outside the workspace.
- Provide Refresh and Build Catalog actions. Refresh on tab entry and after extraction completion.
- Show included/excluded counts, output path, existing catalog status, progress, final vehicle count, and per-source failure information.
- With no included sources, show an actionable empty state and disable building. Do not create an empty catalog.
- Preserve the exact source list used by the last build in the current session; refresh must not make an old build report appear to describe new inputs.

### PDF linking and compatibility

- For validated extraction envelopes, honor `provenance.pdfFileName` as a safe sibling filename and verify it resolves to a real PDF within `input/`.
- Reject absolute paths, separators/traversal in basename provenance, escaped symlink targets, missing files, and invalid PDF content.
- For app-produced extraction files, verify the referenced PDF hash matches the recorded extraction hash; changing the copied PDF must not silently misattribute extracted data.
- Preserve support for hand-written Data Model JSON. Link those files to exactly one sibling PDF with the matching filename stem, using existing parsing rules.
- Keep existing normalization and validation behavior, including supported schema checks and extraction hard requirements. Desktop provenance checks augment this validation; do not duplicate or weaken `CatalogSourceParser` rules.
- Reject duplicate vehicle/configuration IDs before publication. Different filenames do not guarantee distinct catalog IDs.
- Store catalog `sourcePath` and `sourcePdfPath` relative to `input/`, not to `data/` and not as machine-specific absolute paths.
- No fuzzy matching or automatic choosing between multiple catalog editions of the same vehicle.

### Transaction and overwrite behavior

1. Refresh/capture the candidate source list and show what will be included.
2. If `data/data-model-catalog.json` exists, ask explicitly to replace it. Cancel preserves it and does not build.
3. Read a stable source snapshot and validate all included JSON/PDF pairs. If sources change before publication, abort and ask the user to refresh/retry rather than publish a mismatched report.
4. Aggregate actionable source errors; any included-source error prevents publication.
5. Build and serialize the full normalized artifact in memory.
6. Run the same catalog-context validation used by Recommender before publishing, including derived configuration ID uniqueness. This prevents publishing a catalog that search cannot load.
7. Write a temporary file in `data/`, then atomically replace the target. For the desktop strict all-or-nothing contract, unsupported atomic replacement is a visible build error; do not fall back to a potentially non-atomic overwrite.
8. Clean up only the operation's own temporary files. On any failure, no new partial target exists and the previous target remains unchanged.
9. After publication, activate the already-validated context and report success with the exact included source list.

This is transactional file publication, not catalog versioning. It does not promise durability beyond what the host filesystem provides after sudden hardware/power failure.

Use one shared busy state for extraction, build, and search, extending the existing tab-lock behavior. Guard desktop workspace mutations against a second desktop process writing concurrently using a narrowly scoped filesystem lock; contention is an explicit retryable error. External editors/CLI tools are not controlled by that lock, so source consistency checks still matter.

## 4. Recommender and source PDFs

- Replace desktop global lazy bundled catalog access with an injected workspace catalog/context.
- Load only `<workspace>/data/data-model-catalog.json`; never silently use application resources.
- Missing/invalid catalog disables Send and provides Build Catalog guidance or the concrete load error. Provider/model controls remain recognizable.
- Use a single immutable context snapshot for each recommendation: request catalog JSON, engine/price details, readable output, and PDF actions must all agree.
- On successful build, replace the active context and clear old selections/results and catalog-dependent caches. Retain user prompt, provider/model selection, and in-memory keys.
- Extraction only refreshes source inventory. Continue using the last built catalog and show a current-session reminder to rebuild when source data changes.
- Across restarts, load the last valid catalog from disk; do not claim it includes all current inputs unless a build has verified that. No persisted freshness manifest, filesystem watcher, or automatic build is required.
- Keep existing source-PDF Download/save behavior, but read the registered catalog path from workspace `input/`, not `/car-pricelists` resources.
- Resolve only catalog-registered paths, normalize/check containment, reject symlink escapes, and verify PDF content. Never trust a model-authored filesystem path.
- Missing/moved/deleted PDF produces an actionable source-PDF error, not a resource/Git LFS instruction.
- Search may use a valid catalog without eagerly loading every PDF; a missing PDF is surfaced when the user requests that source.
- Refresh the disk catalog on Recommender entry or before a new request so externally removed/corrupt files produce errors rather than an unnoticed stale global cache.

## 5. Codebase findings and implementation surfaces

| Existing surface | Current behavior and planned change |
|---|---|
| `ui/CarPromptApp.kt` | `CarCatalogApp` currently has two tabs, a lazy bundled context, and shared provider controls. Add setup gating and Build Catalog; inject workspace context into recommendation, details, and PDF actions. Preserve provider behavior and per-tab controller state. |
| `ui/CarPromptController.kt` | In-memory recommendation state plus provider/model/key selection and injected request actions. Add catalog readiness/invalidation wiring without changing provider workflows. |
| `ui/PdfExtractorController.kt` | Uses the same provider-control infrastructure but separate selection state; Google delegates to `GeminiPdfClient`, other providers to `TextPdfClient`; PDF/output paths remain editable. Inject managed workspace extraction/import while retaining each provider's existing limits and metadata. |
| `ui/PdfExtractorScreen.kt` | External PDF/output choosers, shared provider controls, and repository-promotion instructions. Replace destination editing and desktop promotion instructions with workspace guidance; preserve the extraction provider disclosures and manual CLI workflow. |
| `catalog/extraction/PdfExtractionService.kt` | Writes validated envelopes and verbatim `<stem>.raw.json`; reads PDF bytes itself and falls back when atomic move is unavailable. Reuse validation while adding a desktop-managed snapshot/import path and diagnostic envelope; keep CLI behavior intact. |
| `catalog/extraction/GeminiPdfClient.kt` / `TextPdfClient.kt` | Existing native whole-PDF and PDFBox text-only implementations. Reuse them without changing provider selection, auth, schema validation, or provider-specific limits. PDFBox 3.0.6 is already declared. |
| `catalog/extraction/PdfExtractionFile.kt` | Schema-version-1 envelope has PDF filename/hash, model, time, and nullable token usage. Preserve it; add a separate desktop diagnostic type rather than weakening validated extraction decoding. |
| `catalog/datamodel/DataModelCatalogGenerator.kt` | Recursively consumes JSON, matches one same-stem sibling PDF, aggregates errors, and uses fallback atomic replacement. Keep this CLI/resource path unchanged; implement workspace inventory and stricter desktop publication separately. |
| `catalog/datamodel/CatalogSourceParser.kt` | Already supports hand-written and extraction envelopes, strictly validates promoted extractions, and ignores provenance. Reuse decoding/validation; add workspace-specific PDF provenance/hash checks in the desktop build layer rather than changing the legacy parser's linking contract. |
| `catalog/datamodel/DataModelCatalogArtifact.kt` | Current artifact paths are described and consumed as resource-relative. Preserve schema version and serialize workspace-relative paths for desktop catalogs; make the context/PDF resolver aware of the artifact origin. |
| `recommendation/DataModelCatalogContext.kt` | Current context validates an artifact and derives configuration details/IDs, but bundled loading and `DataModelPdfStore` resolve only classpath resources. Reuse context validation and add disk catalog loading plus workspace PDF resolution; keep bundled helpers for CLI/tests. |
| `README.md` | Packaging, desktop, and developer docs currently describe bundled data and manual promotion. Add the first-run/workspace desktop workflow while preserving accurate CLI, resource-generation, Git LFS, and packaging instructions. |

Likely new components are a small workspace/settings service, an app-level workspace/catalog state owner, a managed PDF import/extraction coordinator, and Build Catalog controller/screen. Keep them cohesive; no generic plugin framework, database, or configurable workflow engine.

Shared functions should accept explicit inputs for desktop behavior. Keep legacy CLI wrappers/defaults, provider behavior, and raw-output format intact rather than making them consult desktop settings. If shared extraction/generation code needs a different disk-write or raw-diagnostic policy, make desktop semantics explicit and test both entry paths. Desktop must not require bundled resource files or Git LFS PDFs at runtime; developer packaging and CLI workflows may continue to use them.

## 6. Implementation todos

| ID | Todo | Prerequisites |
|---|---|---|
| workspace-storage | **Implemented:** workspace paths, per-user settings persistence, initialization/recovery states, mutation guard, and temporary-directory tests. | None |
| catalog-context | **Implemented:** strict workspace catalog loading reuses artifact/context validation; workspace PDF resolution checks normalized and real-path containment while preserving bundled/CLI resolution. | workspace-storage |
| managed-extraction | **Implemented:** imports captured PDF snapshots with hash deduplication/collision naming, confirmed retries, schema-1 result persistence, JSON diagnostic envelopes, and preservation of earlier successful results. CLI raw-output behavior remains unchanged. | workspace-storage |
| catalog-build-service | **Implemented:** deterministic inventory, raw exclusion, provenance/hash and legacy PDF linking, source-stability/duplicate validation, and strict atomic catalog publication. | workspace-storage, catalog-context |
| desktop-integration | **Implemented:** startup/recovery/setup screens, managed extraction destination, Build Catalog inventory/build screen, busy-state coordination, workspace catalog reload, and result invalidation. | catalog-context, managed-extraction, catalog-build-service |
| workflow-validation | **In progress:** workspace startup/build/restart and managed extraction controller tests pass; an empty-home packaged JAR launches without bundled workspace data. Full interactive packaged workflow smoke remains. | desktop-integration |
| user-documentation | **Implemented:** README covers workspace onboarding, recovery, extraction, explicit catalog build/search, and packaging without requiring bundled PDFs; CLI/resource instructions remain. | desktop-integration |

## 7. Acceptance and verification matrix

| Scenario | Required outcome |
|---|---|
| New user, no files/keys | Setup and empty tabs work without bundled catalog or credentials; no startup crash. |
| Default or custom root | Creates only the intended directories and remembers the absolute path across launches. |
| Launch from another directory | Uses the same saved workspace, independent of the JAR/current working directory. |
| Invalid path/settings/permissions | Explicit error and recovery; no silent empty-data fallback. |
| Choose PDF without Extract | No copy, JSON write, or provider call. |
| Successful extraction from external path | Original unchanged; copied PDF and validated JSON siblings; provenance filename/hash match imported bytes. |
| Invalid provider JSON/plain text | Valid raw envelope beside PDF with matching provenance; no newly promoted JSON. |
| Failure with no raw output | Copied PDF remains if import completed; clear error; no fabricated raw/success file. |
| Different content, identical filename | Unique PDF and matching JSON/raw names; existing pair unchanged. |
| Identical PDF/retry declined | No paid call and no artifact mutation. |
| Retry fails after previous success | Previous validated JSON unchanged; diagnostic excluded from build. |
| Retry succeeds | Validated JSON replaced safely; obsolete raw cleanup succeeds or is warned about. |
| Scan previous-session files | Same supported files discovered without in-memory history. |
| Raw plus valid sources | Raw rows visible/excluded; all valid sources included. |
| Hand-written/nested source pairs | Supported legacy parsing and sibling matching; deterministic relative paths. |
| Invalid included JSON/missing or changed PDF/duplicate IDs | All build errors visible; no output on first build; previous output byte-for-byte unchanged on rebuild. |
| Overwrite declined | Existing catalog and active recommendation state unchanged. |
| Atomic replacement unsupported/write failure | Explicit build failure; no unsafe fallback or partial target; owned temporary files cleaned. |
| Sources change during build | Abort/retry rather than publish a source list inconsistent with generated content. |
| Large source inventory | Scrollable bounded table remains usable and shows all rows/statuses. |
| Successful build | One complete artifact; exact inputs shown; immediately searchable without restart. |
| Build then extract more PDFs | Old built catalog remains active; reminder to explicitly rebuild. |
| Missing/corrupt disk catalog | Send disabled/error before paid request; no resource fallback. |
| Source-PDF save | Reads workspace PDF registered by catalog configuration; retains current save/overwrite UX. |
| Traversal/symlink escape or missing source | Rejected with an actionable error; no arbitrary file read. |
| Concurrent desktop mutation | One operation owns the workspace write guard; other operation fails clearly without conflicting writes. |
| Desktop provider parity | Google sends the imported PDF through native Gemini; non-Google providers send only PDFBox-extracted text. Textless, encrypted, or damaged PDFs fail explicitly; no OCR or provider fallback occurs. |
| CLI regression | Existing flags/default paths/raw conventions remain compatible; CLI does not read desktop settings or use desktop diagnostic envelopes. |

Use Kotlin temporary-directory tests and injected extraction/provider actions; normal validation must not require real paid calls.

Start with targeted tests:

```sh
./gradlew spotlessApply test \
  --tests 'com.sps23.aiworkflow.workspace.*' \
  --tests 'com.sps23.aiworkflow.catalog.extraction.*' \
  --tests 'com.sps23.aiworkflow.catalog.datamodel.*' \
  --tests 'com.sps23.aiworkflow.recommendation.*' \
  --tests 'com.sps23.aiworkflow.ui.*'
```

Adjust selectors to the final component package names. The repository's test task depends on `spotlessCheck`, so run formatting first. Existing paid/integration tests remain opt-in. Some current catalog/resource tests are intentionally ignored; new workspace tests must use independent fixtures and must not rely on those ignores as evidence of correctness.

Perform a desktop smoke test from a packaged matching-platform JAR with isolated settings/home paths and no catalog/PDF fixtures: setup, fake/test extraction, build, recommendation through an injected/local test backend, source-PDF save, exit/relaunch, and overwrite/failure recovery. Verify desktop behavior does not require regenerating bundled resources or Git LFS data on the receiving computer. Preserve existing build/CLI requirements where still applicable.

## 8. Scope boundaries

Included: guided first-run storage, persistent data across sessions, individual PDF import/extraction, raw diagnostics, explicit complete rebuild, source inventory UI, workspace-backed recommendation/details/PDF actions, recovery, regression coverage, and user documentation.

Excluded: downloading brochures inside the app, batch extraction, catalog versioning/backups, multiple actively switchable workspaces, automatic migration of bundled fixtures, automatic catalog rebuilds, source-selection checkboxes, semantic merging/deduplication of competing vehicle editions, database storage, persistent credentials or restored conversation history, background filesystem watching, and changes to provider/model/recommendation semantics. On-disk diagnostic request history is explicitly included above.

No automatic system environment-variable installation is needed. The workspace chooser plus per-user settings provides persistence without administrator access or dependence on terminal environment propagation.
