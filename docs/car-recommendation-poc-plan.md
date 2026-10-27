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
| Market and source | Five car brands; official Irish-market price-list PDFs |
| User journey | One form to enter requirements and preferences, followed by a short ranked recommendation list |
| Application | Kotlin desktop app using the existing Compose Desktop setup |
| PDF processing | Extract text locally with Apache Tika; start with text-based PDFs |
| Knowledge base | Small, versioned structured catalog; Markdown may be generated for people to inspect |
| Recommendation | Deterministic eligibility filtering followed by LLM-assisted ranking and explanation |
| Model provider | OpenRouter behind a replaceable integration boundary; live API use is opt-in in tests |
| Not in the first slice | Vector database/embeddings, web crawling, multiple agent roles, multiple journeys, automatic purchase advice, production deployment |

Select one recent official Irish price list per brand to begin. Record each source's URL or original filename, market, model year, retrieval date, and any visible price caveats. Use a small representative set of trims for the first end-to-end implementation rather than trying to model every variant before the workflow is proven.

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
PDF ingestion -> extraction staging -> human review/publication
      Apache Tika
```

### Suggested responsibilities

- **Presentation:** Collect budget and preferences; show validation, progress, errors, no-match results, recommendation reasons, trade-offs, and citations.
- **Use-case layer:** Validate user input, retrieve eligible catalog records, invoke ranking, validate the result, and assemble the final response.
- **Catalog/domain:** Typed records for vehicles, trims, prices, features, sources, and preferences. Keep hard-constraint filtering deterministic and independently testable.
- **Ingestion:** Extract PDF text locally with Tika, retain source/page context where possible, and produce staging records for review. Do not silently publish an empty or malformed extraction.
- **Model adapter:** Encapsulate Koog/OpenRouter behind an interface so the model/provider can be changed without changing filtering or UI behavior. Use structured requests and responses, timeouts, and visible error handling.
- **Catalog persistence:** Store the POC's reviewed records in a small, version-controlled JSON catalog. Markdown can be generated from the same structured records for human review, but should not be the authoritative runtime source.

## Data contract

Keep the facts explicit, typed, and attributable. Use stable IDs so model responses can refer to records without repeating or modifying facts.

### Vehicle/trim record

Each catalog record should include, where available:

| Field | Meaning |
| --- | --- |
| `id` | Stable ID for the exact model/trim |
| `make`, `model`, `trim` | Manufacturer, model, and variant name as published |
| `modelYear`, `market` | Applicable model year and market; market is `IE` for this POC |
| `currency`, `priceAmount`, `priceKind` | Currency (`EUR`), amount, and price basis such as list or on-the-road |
| `priceEffectiveDate` | Date or period to which the price applies |
| `bodyStyle`, `powertrain`, `transmission` | Normalized values when explicitly supported by the source |
| `specifications` | Optional measurements with units, such as boot capacity or dimensions |
| `features` | Normalized equipment names, with source wording retained where useful |
| `sources` | Source document identifier, URL/filename, page number(s), and relevant source text |
| `reviewStatus` | For example, `needs_review` or `reviewed` |
| `lastReviewed` | Date the record was last checked against its source |

Use explicit unknown or absent values. Do not fill missing price/specification fields with model-generated guesses. Keep price kinds and units explicit: a list price and an on-the-road price may not be comparable.

### Recommendation request and response

Separate **hard constraints** from **soft preferences**:

- Hard constraints might include a budget ceiling, body style, or required powertrain.
- Soft preferences might include efficiency, equipment, passenger/boot space, or performance.
- Ask for only information needed by the POC. Do not collect sensitive personal details.

The ranking request should include only the user's preferences and the bounded set of eligible catalog facts. The response should reference stable candidate IDs and include concise reasons, trade-offs, unknowns, and matching source/page references. Validate all IDs and claims against the catalog before displaying the result. If no candidate meets hard constraints, report that plainly; never relax a constraint without asking the user.

## Implementation plan

### 1. Confirm the corpus and evaluation examples

- Choose five brands and one official, current Irish price list per brand.
- Record source URL or filename, market, model year, retrieval date, and relevant price caveats.
- Check terms of use and copyright before distributing any source-derived data.
- Select a small set of representative trims and user requests. Write down expected facts and eligible candidates so results can be checked rather than judged only by how persuasive they sound.

### 2. Define the catalog schema and manual examples

- Implement Kotlin data classes for the vehicle/trim record, source/provenance, user request, and recommendation response.
- Choose a versioned JSON representation for the runtime catalog.
- Add a few records manually transcribed and checked against the PDFs before building automated extraction.
- Validate required identifiers, market, year, price/currency/basis, source references, and uniqueness.
- Represent missing data as unknown/absent, not as zero, a default, or an inferred value.

### 3. Implement PDF extraction and review staging

- Use the existing Tika dependency for local text extraction from the chosen PDFs.
- Keep extracted text and page context available for review; verify page attribution on actual sample documents.
- Produce a staging representation rather than writing directly to the published catalog.
- Clearly report extraction failures, empty output, and unsupported scanned/image-only documents. OCR can be considered later if the chosen corpus requires it.
- Test extraction against small fixtures and the real selected PDFs during development; do not make real PDFs or derived personal data part of automated CI unless licensing permits it.

### 4. Normalize facts and approve catalog records

- Parse only fields that can be extracted reliably from the document layouts.
- Normalize units, spelling variants, body style, powertrain, and equipment names while preserving original source wording and provenance.
- Require a review step before records enter the catalog used by recommendations.
- Flag ambiguous prices, missing pages, inconsistent trims, and duplicate records for correction.

### 5. Add deterministic candidate filtering

- Implement hard-constraint filtering over the structured catalog.
- Keep this behavior independent of the model/API so it is deterministic and easy to test.
- Return eligibility evidence and reasons for exclusions where helpful.
- Cover inclusive/exclusive budget boundaries, unknown values, multiple matching candidates, and no eligible candidate.
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
- Record failures and known limitations; improve extraction, schema, filtering, or prompts based on evidence.
- Recheck OpenRouter's current free-tier availability, rate limits, privacy/data handling, and model availability before relying on it for a demo.
- Decide whether to expand the corpus only after the five-brand workflow is trustworthy and understandable.

## Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| PDF layouts differ or extraction misses values | Start with the actual five documents, preserve source/page evidence, use review staging, and expose unsupported/failed extraction |
| Prices are stale or use different bases | Store model year, effective date, market, currency, and price kind; disclose these in comparisons |
| LLM invents equipment, prices, or availability | Supply only eligible structured facts, require candidate IDs, validate claims, cite sources, and show unknowns |
| Free model access changes or is rate-limited | Isolate the provider, make failures visible, allow a replaceable model, and do not assume free-tier availability is production support |
| Source content cannot be redistributed | Retain attribution and source links; verify terms before publishing PDFs or derived data |
| User preference data is sent to an external provider | Minimize submitted data, avoid sensitive inputs, and explain provider processing in the POC |
| POC scope grows before the workflow is proven | Keep one journey and five brands; defer vector search, web crawling, multiple agents, OCR, and deployment |

## Success measures and acceptance criteria

The POC succeeds if a reviewer can follow a recommendation from user input to eligible records and source evidence, and can reproduce its key decisions.

- The catalog contains reviewed records for five brands and a small set of Irish trims. Every published price has currency, price kind, model year, effective date (when available), and a source reference.
- Chosen text-based PDFs produce reviewable extraction output. Unsupported or failed documents produce a clear failure rather than an apparently valid empty catalog.
- Automated tests prove that hard constraints are never violated, including budget boundaries and no-match behavior.
- Each displayed recommendation references a real eligible catalog ID; every displayed fact is supported by catalog data; unknown data remains unknown.
- The UI displays reasons, trade-offs, price context, and source/page evidence, and handles empty/error states clearly.
- Tests cover parsing/validation, source data constraints, deterministic filtering, model output validation, and error handling. Ordinary CI uses fixtures and a fake model adapter, not live API credentials.
- A small fixed evaluation set records factual correctness, citation correctness, constraint compliance, and whether trade-offs are useful. Do not claim broad market or recommendation accuracy from this small sample.
- The app builds and tests using the repository's JDK 25 / Gradle 9.3.0 conventions and existing Kotlin, Compose, Tika, and Koog/OpenRouter dependencies. Add dependencies only when a specific requirement justifies them.

## Repository starting point

The repository already declares Kotlin 2.3.21, Compose Desktop 1.7.0, Apache Tika 3.3.3, and Koog/OpenRouter 1.3.0 in `build.gradle.kts`. `README.md` documents the JDK 25 and Gradle 9.3.0 baseline and relevant dependency/runtime considerations. There is no application entry point yet. Existing tests are compatibility smoke checks; `TikaCompatibilityTest` and `JdkCompatibilityTest` are annotated with `@Ignore`, so add focused non-ignored tests for new behavior rather than assuming those smoke checks exercise the workflow.

## Suggested first development task

Implement the catalog data classes, a small validated JSON fixture, and deterministic hard-constraint filtering with unit tests. This establishes the fact contract and a useful no-network foundation before PDF parsing or LLM integration. Then ingest and review one PDF end to end before scaling extraction to the other four brands.
