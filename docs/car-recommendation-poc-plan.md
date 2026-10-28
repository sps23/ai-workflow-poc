# Car Recommendation AI Workflow POC

## Is this a good idea?

**Yes—as a focused AI workflow proof of concept.** The problem has a useful boundary: official Irish price lists provide source material, vehicle specifications and equipment are structured facts, and a user can express a set of constraints and preferences. That gives the project a way to demonstrate a complete workflow:

1. Ingest source documents.
2. Extract and normalize facts.
3. Keep those facts in a reviewable knowledge base.
4. Apply explicit eligibility rules.
5. Use an LLM to compare eligible options and explain trade-offs.
6. Show the user the recommendation and its sources.

The strongest version is a **grounded decision-support tool**, not an autonomous car-buying agent. The model should help interpret preferences and explain comparisons; it should not be the authority for prices or specifications. Deterministic code should enforce hard requirements such as maximum budget and required powertrain. Every recommendation should be traceable to reviewed catalog facts and, where possible, the source PDF page.

This is also a good workflow POC for this repository because the project already uses Kotlin and has dependencies for Compose Desktop, Apache Tika, and Koog/OpenRouter. It does not yet have an application entry point, so creating the user journey is still part of the work.

### What the POC can and cannot prove

It can demonstrate that a small collection of official PDFs can be turned into usable structured facts, then combined with user preferences to produce understandable, evidence-linked recommendations.

It cannot establish that an LLM is always accurate, that the five-brand sample represents the whole Irish market, or that a free model/API tier is suitable for a production service. Treat the results as recommendations for exploration, not financial or professional advice.

## Recommended first slice

| Area | POC decision |
| --- | --- |
| Market and source | Five car brands; a pilot of about 2–3 model PDFs per brand (roughly 10–15 PDFs total) |
| User journey | One form to enter requirements and preferences, followed by a short ranked recommendation list |
| Application | Kotlin desktop app using the existing Compose Desktop setup |
| PDF processing | Extract locally with the implemented PDFBox batch; review raw text artifacts before cataloging |
| Knowledge base | Small, versioned structured catalog; Markdown may be generated for people to inspect |
| Recommendation | Deterministic eligibility filtering followed by LLM-assisted ranking and explanation |
| Model provider | OpenRouter behind a replaceable integration boundary; live API use is opt-in in tests |
| Not in the first slice | Vector database/embeddings, web crawling, multiple agent roles, multiple journeys, automatic purchase advice, production deployment |

Start with about 2–3 representative models for each of the five brands, using the official Irish price/specification PDF for each model where available. This is roughly 10–15 source PDFs, not five PDFs total. The exact document-to-model relationship may vary: record what each PDF covers rather than assuming every document contains exactly one model or one trim. Record each source's URL or original filename, make/model coverage, market, model year or effective period, retrieval date, and visible price caveats. The pilot is deliberately smaller than the full range each brand publishes.

## Architecture

Keep the initial application small and layered; avoid adding a backend or a vector database for a catalog of this size.

```text
Compose Desktop UI
        |
Recommendation use case
        |
  +-----+---------------------+
  |                           |
Catalog / deterministic     Ranking interface
eligibility filtering           |
                            Koog / OpenRouter
  |
Reviewed catalog records
  ^
  |
Batch document registry -> PDF extraction -> staging/review -> publication
                         Apache PDFBox
```

### Suggested responsibilities

- **Presentation:** Collect budget and preferences; show validation, progress, errors, no-match results, recommendation reasons, trade-offs, and citations.
- **Use-case layer:** Validate user input, retrieve eligible catalog records, invoke ranking, validate the result, and assemble the final response.
- **Catalog/domain:** Typed records for vehicles, trims, prices, features, sources, and preferences. Keep hard-constraint filtering deterministic and independently testable.
- **Ingestion:** Track each source PDF in a document registry, extract it locally, retain source/page context, and produce staging records for review. The current batch uses Apache PDFBox; see [PDF extraction handoff](#pdf-extraction-handoff). Batch processing reports status per document so one failed PDF does not hide successful imports or block review of the rest.
- **Model adapter:** Encapsulate Koog/OpenRouter behind an interface so the model/provider can be changed without changing filtering or UI behavior. Use structured requests and responses, timeouts, and visible error handling.
- **Catalog persistence:** Store the POC's reviewed records and source-document manifest in a small, version-controlled JSON catalog. Markdown can be generated from the same structured records for human review, but should not be the authoritative runtime source.

## Data contract

Keep facts explicit, typed, and attributable. A `VehicleModel` is the make/model identity; a `VehicleConfiguration` identifies a specific market/year/trim/powertrain/engine/transmission/drivetrain combination. Trim alone is not a unique vehicle: the 5008 Allure is offered as both Hybrid and Electric, while the Golf source separates petrol and mild-hybrid engine/gearbox configurations. Use stable configuration IDs so model responses can refer to catalog candidates without repeating or modifying facts.

### Source-document registry

Maintain one record for each input PDF. At minimum, track:

| Field | Meaning |
| --- | --- |
| `documentId` | Stable ID for this source document |
| `make`, `coveredModels` | Brand and model(s) the PDF covers |
| `market`, `modelYear` | Market and applicable year or period |
| `sourceUrl` or `localPath` | Where the original document came from |
| `retrievedAt`, `contentHash` | Retrieval date and content fingerprint for detecting changed/re-imported files |
| `documentStatus` | For example, `queued`, `extracted`, `needs_review`, `published`, or `failed` |
| `parserVersion`, `lastProcessedAt` | Enough processing metadata to diagnose and repeat an import |

A catalog fact must point back to its source document and page when available. Keep provenance on the particular price/specification/feature where facts could come from different PDFs; a generic list of documents on a model record is not enough to prove which source supports a claim.

### Vehicle configuration and catalog facts

| Record | Required meaning |
| --- | --- |
| `VehicleModel` | Stable ID, make, model, and optional generation |
| `VehicleConfiguration` | Model ID, market (`IE`), model year when known, published trim, powertrain, engine, transmission, drivetrain, and other distinguishing configuration details |
| `VehiclePrice` | One or more exact configuration IDs, integer minor-unit amount, currency, typed price basis, effective period, caveats, and evidence |
| `VehicleMeasurement` | One or more exact configuration IDs, one value or a complete range, explicit unit, test/configuration conditions, approval state, and evidence |
| `EquipmentFitment` | Feature ID, exact configuration IDs, fitment state, optional conditions, review state, and evidence |
| `SourceEvidence` | Source document ID/hash, positive page number, and short raw source wording |

Model facts and configuration facts remain separate. Use price bases such as manufacturer RRP, delivery/service charge, on-the-road total, incentive-adjusted price, or option price; do not collapse the Golf's €34,630 RRP, €2,105 delivery/service charge, and €36,735 ROTR into one unlabeled amount. A value's effective period and source caveats belong with that price fact.

Equipment state is `STANDARD`, `OPTIONAL`, `NOT_AVAILABLE`, or `UNKNOWN`. The first three require explicit source evidence; omission from a document means `UNKNOWN`, never `NOT_AVAILABLE`. Keep optional package membership/codes distinct from feature identity. Commercial sale/stock availability is a separate concern and must not reuse fitment states. Measurements retain units and conditions such as WLTP cycle or 5-seat/7-seat boot configuration; provisional values remain under review.

The hierarchy aligns with automotive data practice: a model is distinct from a market-specific, powertrain-specific vehicle configuration. Schema.org's `Car`, `Vehicle`, `Product`, and `Offer` terms are useful interoperability mappings, but are not the internal schema because they do not capture this POC's source-page evidence, fact review state, and four-state equipment fitment contract.

The initial implementation is `src/main/kotlin/com/sps23/aiworkflow/catalog/VehicleCatalog.kt`, with the versioned seed at `src/main/resources/vehicle-catalog/catalog.json`. The reviewed sample covers six Peugeot 5008 Hybrid/Electric trim configurations, two Volkswagen Golf MY27 configurations, and Toyota RAV4 PHEV grade/drivetrain examples. Toyota GR SPORT figures explicitly marked as pending final homologation remain `NEEDS_REVIEW`; the raw PDF extraction artifacts remain unchanged staging evidence.

### Recommendation request and response

Separate **hard constraints** from **soft preferences**:

- Hard constraints might include a budget ceiling, body style, or required powertrain.
- Soft preferences might include efficiency, equipment, passenger/boot space, or performance.
- Ask for only information needed by the POC. Do not collect sensitive personal details.

The ranking request should include only the user's preferences and the bounded set of eligible catalog facts. The response should reference stable candidate IDs and include concise reasons, trade-offs, unknowns, and matching source/page references. Validate all IDs and claims against the catalog before displaying the result. If no candidate meets hard constraints, report that plainly; never relax a constraint without asking the user.

### PDF extraction handoff

The local batch command is `./gradlew extractPdfs`. It reads PDFs from
`src/main/resources/car-pricelists/` and writes one JSON artifact per document
plus `batch-manifest.json` under `src/main/resources/car-pricelists-extracted/`.
The versioned artifact contract is schema v1: `source` records relative path,
make, model-coverage hints, SHA-256, file size, and PDF metadata; `extraction`
records parser identity, page count, status, and diagnostics; `pages` retains
ordered page numbers and extracted text. The schema can represent structured
blocks, tables, cells, marker evidence, and provenance, but the current parser
does not populate table/cell geometry.

Current artifacts are raw extraction evidence, not normalized vehicle/trim
records. The PDFBox parser extracts page text and document metadata only;
successful documents remain `NEEDS_REVIEW`, and image-only or unsupported
content requires review. It does not infer prices, specifications, equipment,
or canonical catalog facts. Use the extraction contract as input to the
subsequent normalization and approval work, not as the recommendation catalog.
See the [PDF extractor implementation plan](pdf-extractor-implementation-plan.md)
for implementation stages, evaluation evidence, and known layout limits.

## Implementation plan

### 1. Confirm the corpus and evaluation examples

- Choose five brands and about 2–3 representative models per brand for the first pilot.
- Find the official Irish-market price/specification PDF for each selected model. Expect roughly 10–15 PDFs, while allowing a document to cover more than one model or multiple trims.
- Create a source manifest recording document ID, make/model coverage, URL or filename, market, model year/effective period, retrieval date, and relevant price caveats.
- Check terms of use and copyright before distributing any source-derived data.
- Select representative trims and user requests across the selected brands. Write down expected facts, source pages, and eligible candidates so results can be checked rather than judged only by how persuasive they sound.

### 2. Define the catalog schema and manual examples

- **Status: implemented for catalog schema v1 and source-reviewed examples across three makes.** Kotlin types and validation live in `VehicleCatalog.kt`; the versioned JSON catalog is `src/main/resources/vehicle-catalog/catalog.json`.
- [x] Define source references, model/configuration records, price and measurement facts, feature fitment, and fact-level provenance.
- [x] Choose deterministic, versioned JSON for the reviewed runtime catalog; keep the existing all-document inventory manifest separate.
- [x] Manually transcribe and visually check Peugeot 5008, VW Golf MY27, and Toyota RAV4 PHEV examples against cited PDF pages.
- [x] Validate source hashes/pages, market, configuration identity, price periods, measurement units/ranges, references, and fitment review constraints.
- [x] Represent unknowns explicitly; do not convert absent facts to zero or unavailable.
- [ ] Define user request/response types when implementing the recommendation use case.

### 3. Implement repeatable batch PDF extraction and review staging

- Use `./gradlew extractPdfs` to run the existing local PDFBox batch over the configured source directory. Its schema-v1 JSON artifacts contain page text, metadata, source identity, and per-document status; the current parser does not extract table/cell geometry.
- Feature matrices must map each feature row to the correct trim column and tick/dot state; flattened page text does not establish row/cell boundaries. For the extraction contract, evidence, and supported-layout limits, see [`pdf-extractor-implementation-plan.md`](pdf-extractor-implementation-plan.md).
- Keep extracted text and page context associated with its document ID and verify page attribution on actual sample documents.
- Produce staging records rather than writing directly to the published catalog. Track per-document state and errors so a partial batch can be resumed and reviewed.
- Make imports idempotent: reprocessing an unchanged content hash should not create duplicate models, trims, or facts. If a source file changes, retain the revision history or explicitly mark prior extracted facts as superseded and re-review affected records.
- Detect likely duplicate/overlapping model and trim records across documents for human reconciliation; do not silently overwrite one source with another.
- Clearly report extraction failures, empty output, and unsupported scanned/image-only documents. OCR can be considered later if the chosen corpus requires it.
- Test extraction against fixtures and the real selected PDFs during development; do not make real PDFs or derived personal data part of automated CI unless licensing permits it.

### 4. Normalize facts and approve catalog records

- Parse only fields that can be extracted reliably from the document layouts.
- Normalize units, spelling variants, body style, powertrain, and equipment names while preserving original source wording and provenance.
- Require a review step before records enter the catalog used by recommendations; review source coverage as well as extracted values.
- Flag ambiguous prices, missing pages, inconsistent trim naming, overlapping facts, and conflicting/revised documents for correction.

### 5. Add deterministic candidate filtering

- Implement hard-constraint filtering over the structured catalog.
- Keep this behavior independent of the model/API so it is deterministic and easy to test.
- Return eligibility evidence and reasons for exclusions where helpful.
- Cover inclusive/exclusive budget boundaries, unknown values, multiple matching candidates, and no eligible candidate.
- Confirm filtering works across multiple makes, models, and trims without requiring a model PDF to be loaded at recommendation time.
- Do not use vector search for this first slice: exact structured filters are simpler and more reliable for a small catalog.

### 6. Add OpenRouter ranking and grounded explanations

- Put the LLM integration behind an interface and use the existing Koog/OpenRouter dependencies.
- Provide only eligible candidate records, relevant source-backed facts, and user preferences to the model.
- Request structured output referencing candidate IDs; validate the response schema, IDs, and factual claims before display.
- Treat model output as a ranking/explanation, not as catalog data or a source of new specifications.
- Configure credentials outside source control and logs. Show clear errors for a missing key, timeout, provider/model unavailability, rate limiting, and invalid output.
- Keep automated tests offline by default; use a fake ranking adapter for tests and make any live provider check opt-in.

### 7. Build the Compose Desktop experience

- Provide one simple recommendation form, with labels that distinguish required constraints from preferences.
- Validate inputs before making a model request.
- Show loading state, a concise ranked list, reasons, trade-offs, price basis/date, and source/page citations.
- Provide a way to inspect the underlying catalog evidence.
- Make no-match, incomplete-data, and provider-error states clear and distinct.

### 8. Evaluate, document, and decide what to do next

- Run the fixed representative requests and check hard constraints, factual correctness, citation correctness, and useful trade-offs.
- Check corpus coverage and import health: all selected documents have a terminal/reviewable status, failed files are visible, and every published fact traces to the right document and page.
- Record failures and known limitations; improve extraction, schema, filtering, or prompts based on evidence.
- Recheck OpenRouter's current free-tier availability, rate limits, privacy/data handling, and model availability before relying on it for a demo.
- Decide whether to expand the corpus only after the five-brand workflow is trustworthy and understandable.

## Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| PDF layouts differ across models/brands or extraction misses values | Sample documents from each brand early, preserve document/page evidence, use review staging, and expose unsupported/failed extraction |
| Model/trim facts overlap or conflict across documents | Separate model and trim entities, preserve fact-level provenance, detect duplicates, and require review before reconciling conflicts |
| A document is revised or re-imported | Track content hashes and processing state; make unchanged imports idempotent and re-review facts affected by a changed source |
| A batch has partial failures | Report processing per document and allow successful documents to proceed through review without hiding failed items |
| Prices are stale or use different bases | Store model year, effective date, market, currency, and price kind; disclose these in comparisons |
| LLM invents equipment, prices, or availability | Supply only eligible structured facts, require candidate IDs, validate claims, cite sources, and show unknowns |
| Free model access changes or is rate-limited | Isolate the provider, make failures visible, allow a replaceable model, and do not assume free-tier availability is production support |
| Source content cannot be redistributed | Retain attribution and source links; verify terms before publishing PDFs or derived data |
| User preference data is sent to an external provider | Minimize submitted data, avoid sensitive inputs, and explain provider processing in the POC |
| POC scope grows before the workflow is proven | Keep one journey, five brands, and 2–3 models per brand for the pilot; defer vector search, web crawling, multiple agents, OCR, and deployment |

## Success measures and acceptance criteria

The POC succeeds if a reviewer can follow a recommendation from user input to eligible records and source evidence, and can reproduce its key decisions.

- The source manifest covers about 2–3 selected models for each of five brands (roughly 10–15 PDFs for the pilot), recording model coverage, market/year, retrieval/source details, content hash, and processing state for every document.
- The catalog contains reviewed records across the selected brands/models and their representative Irish trims. Every published price has currency, price kind, model year, effective date (when available), and a fact-level source document/page reference.
- A batch of chosen text-based PDFs produces reviewable output per document. Unsupported or failed documents have a visible status and error rather than disappearing or producing an apparently valid empty catalog.
- Re-importing an unchanged PDF creates no duplicate records; a changed document is detected and its affected facts return for review. Overlapping records are flagged rather than silently overwritten.
- Automated tests prove that hard constraints are never violated, including budget boundaries and no-match behavior.
- Each displayed recommendation references a real eligible catalog ID; every displayed fact is supported by catalog data; unknown data remains unknown.
- The UI displays reasons, trade-offs, price context, and source/page evidence, and handles empty/error states clearly.
- Tests cover manifest/data validation, per-document parsing and page provenance, batch status/partial failure, idempotent re-import, duplicate/overlap detection, deterministic filtering, model output validation, and error handling. Ordinary CI uses fixtures and a fake model adapter, not live API credentials.
- A small fixed evaluation set records factual correctness, citation correctness, constraint compliance, and whether trade-offs are useful. Do not claim broad market or recommendation accuracy from this small sample.
- The app builds and tests using the repository's JDK 25 / Gradle 9.3.0 conventions and existing Kotlin, Compose, Tika, and Koog/OpenRouter dependencies. Add dependencies only when a specific requirement justifies them.

## Repository starting point

The repository already declares Kotlin 2.3.21, Compose Desktop 1.7.0, Apache Tika 3.3.3, PDFBox 3.0.8, and Koog/OpenRouter 1.3.0 in `build.gradle.kts`. `README.md` documents the JDK 25 and Gradle 9.3.0 baseline and relevant dependency/runtime considerations. There is no application entry point yet. The current source tree contains 22 PDFs across five makes; curate a representative subset for the recommendation catalog. See [`pdf-extractor-implementation-plan.md`](pdf-extractor-implementation-plan.md) for the implemented extraction contract and its evidence-backed limits. Existing tests are compatibility smoke checks; `TikaCompatibilityTest` and `JdkCompatibilityTest` are annotated with `@Ignore`, so add focused non-ignored tests for new behavior rather than assuming those smoke checks exercise the workflow.

## Suggested next development task

Catalog schema v1 and manually reviewed, source-backed sample records are implemented in `VehicleCatalog.kt` and `src/main/resources/vehicle-catalog/catalog.json`. Next, curate the remaining representative pilot records and implement deterministic eligibility filtering over reviewed configurations and price facts. Raw extracted text remains review staging, not a canonical vehicle catalog.
