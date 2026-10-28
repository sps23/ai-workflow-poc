# OpenRouter car recommendation component: implementation plan

## Goal and approved scope

Implement a Kotlin CLI and reusable recommendation service that interpret a user's open-text request, select eligible vehicles from the existing catalog, and obtain a grounded recommendation from OpenRouter using free models only.

The two model stages are bounded operations, not an autonomous tool-using loop:

1. Interpret the user's text into compact, typed requirements without changing its meaning.
2. Run a dedicated car-recommendation agent prompt against a compact subset of eligible, reviewed catalog facts.

User-confirmed decisions:

- Never use paid models or paid fallback, even when free models fail.
- Use the updated `src/main/resources/vehicle-catalog/catalog.json`.
- Create a dedicated runtime recommendation prompt; do not reuse the catalog-parser agent.
- Keep the full catalog local; send relevant candidate facts rather than the whole JSON.
- Deliver a CLI and reusable Kotlin service first; defer Compose UI.
- Use reviewed facts only. An unknown/unreviewed required fact cannot establish eligibility.
- Ask targeted clarification when interpretation is ambiguous.
- Select specific verified `:free` models from live metadata, with free-only fallbacks.

This document defines the implementation plan; saving it does not authorize application implementation.

## Current repository and implications

Verified against the updated working tree:

| Existing asset | Reuse / implication |
| --- | --- |
| `docs/car-recommendation-poc-plan.md` | Already requires deterministic eligibility, bounded model context, evidence-linked output, and offline tests. This plan elaborates its filtering and OpenRouter stages. |
| `catalog/VehicleCatalog.kt` | Reuse schema-v1 data classes, `VehicleCatalogCodec.decode`, and `VehicleCatalogValidator`; do not introduce a competing catalog schema. |
| `vehicle-catalog/catalog.json` | Approximately 1.87 MB: 14 sources, 14 models, 118 configurations, 820 features, 102 prices, 535 measurements, 1,047 fitments. It is not efficient to send in full. |
| Review flags | 13 configurations, 48 prices, 80 measurements, and 196 fitments need review. Structural validity is not semantic approval. |
| Catalog tests | `src/test/kotlin/com/sps23/aiworkflow/catalog/VehicleCatalogTest.kt` exercises real catalog structure, provenance, and configuration distinctions. Preserve it. |
| Dependencies | Koog core/OpenRouter/Ktor transport, Ktor CIO/content negotiation, coroutines, serialization, Kotlin test/JUnit already exist. No additional library is planned. |
| Runtime | JDK 25; Gradle wrapper; Spotless/ktlint. No recommendation entry point exists. |
| Agent definitions | Only Kotlin implementer and catalog parser are present. Copilot agent Markdown is not an executable OpenRouter agent. |

Important data limitations:

- Kodiaq petrol mild-hybrid and diesel configurations explicitly have seven seats; its PHEV has five.
- Kodiaq electric-tailgate fitment is reviewed, but electric seat adjustment is not established by the catalog. Heated seats are not electric adjustment.
- Peaq electric seats/tailgate are recorded, but its seating capacity is unknown. It must not pass a seven-seat filter.
- Kodiaq boot measurements contain a 340-845 litre range without explicit non-PHEV seating-state mapping. Do not invent which endpoint corresponds to seven occupied seats.
- Some catalog prices are future-effective or lack effective dates. Do not present future prices as current offers or undated prices as verified current quotes.
- A `REVIEWED` measurement can still have ambiguous conditions or `PENDING_HOMOLOGATION` approval. Check comparability and disclose limitations.

## Architecture and execution flow

```text
CLI text / clarification answer
  -> validate input and load validated catalog snapshot
  -> free-model policy / capability preflight
  -> stage 1: IntentInterpreter (OpenRouter structured output)
  -> semantic validation
       -> NeedsClarification -> CLI asks -> revised structured request
       -> Ready
  -> deterministic CatalogEligibilityFilter
       -> NoMatch / InsufficientReviewedData (no ranking call)
       -> eligible candidates
  -> comparable-objective selection and bounded CatalogProjection
  -> stage 2: RecommendationRanker (dedicated agent prompt)
  -> schema, eligibility, fact-reference and ranking validation
  -> local evidence hydration and CLI rendering
```

Keep application orchestration independent of the provider. Suggested interfaces: `IntentInterpreter`, `RecommendationRanker`, and an injectable model/transport boundary. Use simple constructor injection, suspend functions, and explicit resource ownership; no DI framework.

Load/hash the catalog once per run and keep an immutable snapshot. Include its hash, prompt versions, request ID, and actual response model IDs in diagnostic metadata.

## Proposed files

Paths are relative to `src/main/kotlin/com/sps23/aiworkflow/` unless stated otherwise. Keep related small types together; split files only when implementation warrants it.

| File / package | Responsibility |
| --- | --- |
| `recommendation/RecommendationContracts.kt` | Typed intent, clarification, constraints, objectives, ranking, facts, and result/error contracts. |
| `recommendation/RecommendationService.kt` | Two-stage orchestration, cancellation, no-match handling, validation gates, total attempt budget. |
| `recommendation/CatalogEligibilityFilter.kt` | Reviewed-data eligibility, feature matching, price policy, exclusions and unknowns. |
| `recommendation/CatalogProjection.kt` | Stable joins/indexes over existing catalog types, comparable metrics, deterministic candidate reduction, compact payload. |
| `recommendation/RecommendationValidator.kt` | Output shape, candidate membership, references, constraint checks, objective ordering. |
| `openrouter/OpenRouterConfig.kt` | Environment configuration and fail-closed validation; no credentials in files. |
| `openrouter/FreeModelPolicy.kt` | Current model metadata, pricing/capability/context checks, approved free fallback order. |
| `openrouter/OpenRouterRecommendationClient.kt` | Koog integration, request policy, error translation, lifecycle and telemetry. |
| `recommendation/CarRecommendationCli.kt` | Text input, clarification loop, JSON/text output, exit statuses. |
| `src/main/resources/prompts/car-intent-v1.txt` | Small, versioned intent-interpreter system instructions. |
| `src/main/resources/prompts/car-recommendation-v1.txt` | Dedicated recommendation-agent system instructions. |
| Matching tests under `src/test/kotlin/.../recommendation` and `.../openrouter` | Offline fixtures, fake clients, local HTTP contract tests. |
| `build.gradle.kts` | `recommendCars` launcher and optional, explicitly gated live smoke launcher. |
| `README.md`, `docs/openrouter-integration-plan.md`, main POC plan | Usage, configuration, privacy, limitations, and design linkage. |

Runtime instructions belong in packaged resources, not `.github/agents`. Do not automatically load arbitrary Markdown, execute tools, or give the model filesystem/network access.

## Detailed implementation stages / todos

### OR-1: Define contracts and input boundaries

Dependencies: none.

- Use existing `Powertrain`, `PriceBasis`, review and fitment enums rather than duplicate concepts.
- Define an interpreted request containing hard constraints, ordered soft objectives, unresolved terms, and clarification status.
- Preserve the original text only in process memory for intent validation and clarification; do not log it or automatically persist it.
- Include explicit market, seat count, allowed powertrains, budget in EUR minor units, required features, acceptable optional equipment, and measurement conditions when specified.
- Track the original phrase supporting each interpreted hard constraint. Reject unsupported enum values, impossible numeric values, duplicate/conflicting constraints, or references absent from the controlled vocabulary.
- A source phrase is an audit aid, not proof the LLM interpreted it correctly. Cover fidelity with fixtures and clarification; expose the interpreted requirements in CLI output.
- Do not infer a seven-seat requirement from previous CLI invocations. This first slice is single-request plus targeted clarification, not persistent conversation memory.
- Keep blank input, excessive input size, non-car requests, ambiguity, no matches, incomplete reviewed data, and provider failures distinct.
- Proposed initial limits: 4,000 input characters, three displayed recommendations, at most three clarification rounds; on exhaustion return unresolved requirements, not guessed recommendations.
- Unknown user concepts are clarification candidates; do not silently drop them. Do not fabricate unavailable feature definitions.

Deliverable: serializable request/result types and table-driven validation tests.

### OR-2: Load and index reviewed catalog facts

Dependencies: OR-1.

- Decode the bundled catalog using the existing codec and validator. Report missing/invalid resources before any inference request.
- Index configurations by ID; join models, applicable prices, measurements, fitments, feature definitions, and source evidence.
- Accept only reviewed configurations and reviewed individual facts. Do not discard an otherwise useful configuration because an unrelated fact needs review.
- Preserve fact IDs, configuration applicability, source hash/page, units, range endpoints, price basis/effective periods, equipment conditions and review/approval status.
- Exclude unreviewed values from model-visible facts; expose only a local gap explanation such as "reviewed price unavailable."
- Detect conflicting applicable reviewed facts; treat unresolved conflicts as unavailable for hard filtering/comparison, rather than choosing an arbitrary record.
- Keep source text out of the routine payload where a fact ID plus structured fact suffices. Resolve citations locally.

Deliverable: stable projection/index tests, including catalog counts and reviewed-fact coverage diagnostics.

### OR-3: Enforce free-only routing and verify Koog compatibility

Dependencies: OR-1. Can proceed independently of OR-2.

- Read `OPENROUTER_API_KEY` from the process environment; reject blank/missing values without printing them. Never request a pasted key in chat or commit it.
- Accept a primary `OPENROUTER_MODEL` and optional ordered free-only fallback IDs; require the primary configuration rather than shipping a soon-stale hardcoded model.
- Discover current metadata using OpenRouter's models API. Select only concrete `:free` catalog entries with text output, suitable context/output capacity, and structured-output support.
- Check returned IDs and applicable pricing fields, parsing decimal strings exactly. Reject paid IDs, suffix tricks, absent/invalid required prices, nonzero applicable fees, unsupported parameters and stale/unavailable verification.
- Never strip `:free`, substitute a paid sibling, use `openrouter/auto`, or enable plugins/tools that can add fees.
- Configure server-side provider price ceilings at zero for supported relevant fields and `require_parameters: true`; test the actual serialized request, not just configuration objects.
- Allow provider fallback only within a verified free variant; application/model fallback is restricted to the verified free allowlist. These are different routing layers.
- Revalidate model policy for each recommendation run; keep metadata only for that run. Live metadata is not a permanent guarantee.
- Preserve actual response model ID and usage. Reject unexpected routing metadata; if nonzero cost is reported, stop and surface a policy breach. Post-response checks detect a breach but cannot undo a charge, so enforce policy before dispatch.
- Inspect the installed Koog 1.3.0 APIs/source and prove JSON schema, provider preferences/zero ceilings, token limits, errors and cancellation using offline request capture.
- Prefer `OpenRouterLLMClient`/Koog prompt execution. If that version cannot serialize mandatory safeguards, implement the narrowly scoped OpenRouter HTTP adapter using already-declared Ktor dependencies behind the same boundary. Do not silently drop safeguards, upgrade Koog, or add an SDK.
- Do not instantiate an autonomous `AIAgent` tool loop just to issue two requests; the dedicated agent is a controlled prompt contract.
- Honor provider processing restrictions; require an explicit runtime acknowledgement before first external inference. If approved data-use policy cannot be met by free endpoints, report unavailability rather than relaxing it.

Deliverable: free-model policy tests and captured-wire compatibility evidence. No live key needed.

### OR-4: Implement intent interpretation / efficient prompt translation

Dependencies: OR-1, OR-3.

- Stage 1 receives fixed interpreter instructions, a compact controlled vocabulary, the user's text, and explicit clarification answers. Do not send the 1.87 MB catalog.
- Derive vocabulary from available feature/measurement definitions; normalize approved aliases locally. Include only request-relevant terms and keep unsupported mappings unresolved.
- Request strict JSON-schema output for interpreted requirements, objectives, source phrases and clarification status. Parse and validate locally even when the provider advertises strict output.
- Efficiency means semantic compression into typed requirements, not an unconstrained paraphrase or a third "prompt polishing" call.
- Clarify "petrol, not electric" when hybrid/PHEV acceptance is unclear; distinguish electric adjustment from heated seats, driver-only from both front seats, and standard from optional equipment.
- Clarify competing "lowest consumption and biggest boot" priorities, seating state, and acceptable trade-offs when they determine the winner.
- Generate the stage-2 user message deterministically from the validated intent and catalog projection. A model-generated rewritten prompt must never become system instructions.
- Protect system/data separation: user text and catalog wording are untrusted data. Instructions to ignore filters, reveal secrets, or use outside knowledge cannot override application policy.

Deliverable: fake-model interpreter, validated live adapter path, and fidelity/ambiguity regression fixtures.

### OR-5: Implement deterministic eligibility and objective comparison

Dependencies: OR-2, OR-4.

- Enforce required market, seats, powertrain, body style/transmission when supplied, budget and equipment in Kotlin before ranking.
- Use three-way fact reasoning: satisfied, contradicted, or unknown. Unknown required facts do not pass.
- Standard equipment satisfies a required feature when reviewed and applicable. Optional equipment needs explicit user acceptance, verified applicability/conditions and a defensible total price when budget matters. If option constraints cannot be resolved, exclude with an explanation.
- Do not map model-level facts across trims or engine variants unless applicability is explicit.
- Budget filtering uses a single comparable price basis and currency. Never add list and on-the-road totals together or treat option/delivery prices as the vehicle price.
- Ask whether a budget means base/list or delivered total if unclear. Missing delivery/option/VRT amounts cannot establish an all-in budget.
- Inject a clock. Preserve effective-date precision; reject not-yet-effective/expired prices for a current-price hard constraint, and label undated prices as undated rather than current. Do not silently choose another date.
- For fuel economy, require compatible units/test cycles and known accepted powertrains. Do not compare PHEV weighted consumption to petrol/diesel usage as if equivalent.
- For boot space, require comparable seating/luggage conditions. Do not treat 340-845 litres as a confirmed 845 litres with seven passengers or confuse maximum folded-seat capacity with normal boot capacity.
- Soft objectives lacking comparable data remain unknown; do not rank them as zero, best or worst without disclosure.
- Rank directly measurable single objectives locally with stable-ID tie breaks; the model explains, not overrides, the verified extreme. Use user-confirmed priority order for multiple objectives, preserving trade-offs/Pareto alternatives.
- If no reviewed candidates can satisfy requirements, return `NoMatch` or `InsufficientReviewedData` with reasons, without calling the ranking stage or relaxing constraints.

Deliverable: deterministic filter/comparison tests independent of OpenRouter.

### OR-6: Build bounded catalog context and the recommendation agent

Dependencies: OR-3, OR-5.

- Compact each candidate to configuration/model labels, requested verified attributes, matching equipment, applicable prices, comparable measurements and stable fact IDs.
- Send only relevant reviewed facts, not PDF bodies, unrelated fitments or duplicate evidence text. Keep a local fact-reference allowlist for the exact payload.
- Preserve units, ranges, conditions and effective dates even when compressing. Do not change facts to fit the context.
- Budget system instructions, schema, user requirements, candidate JSON, output allowance and safety margin against the selected model's context and output limits.
- Initial caps: 12 projected candidates, 1,024 completion tokens for interpretation, 2,048 for ranking; use smaller limits when supported capacity requires them.
- Establish conservative token accounting with documented limitations; byte/character counts are not exact token counts. Reject or reduce safely if the request cannot fit; never rely on automatic context truncation.
- Use deterministic objective-preserving reduction, include trade-off representatives, and retain counts of eligible versus transmitted candidates. Do not simply take the first 12.
- If the context cannot include all eligible candidates, disclose subset scope. An overall "best in catalog" claim must be established locally over all comparable eligible records, not inferred from the transmitted subset.
- Pin the primary model across the two stages where feasible; independently recheck context/capabilities before free fallback.
- Agent system instructions restrict selection to supplied IDs, forbid outside facts and constraint relaxation, require fact-linked reasons/trade-offs/unknowns, and prefer no recommendation to fabrication.
- Output schema: ordered entries with `configurationId`, supported reason/comparison codes and applicable fact references; separate known-data limitations. Never request chain-of-thought.
- Prefer structured reason codes over unconstrained factual prose. Render factual sentences locally from validated catalog facts; any model prose is non-authoritative and must not introduce new specifications.

Deliverable: versioned prompts, deterministic prompt snapshots, payload-size tests, and fake-model ranking tests.

### OR-7: Validate output and handle provider failures

Dependencies: OR-6.

- Reject malformed JSON, extra/unknown fields, duplicate/ineligible IDs, incorrect cardinality, missing references, incompatible comparisons and facts not applicable to that configuration.
- Recheck all hard constraints after model output. A valid JSON schema does not prove a valid recommendation.
- Validate rank consistency with confirmed deterministic objective order; refuse unsupported superlatives.
- Hydrate displayed vehicle names, numeric facts, prices and source/page citations exclusively from the local snapshot. Do not trust model-supplied prices or citation text.
- Detect refusals, empty/multiple-choice surprises, non-text responses, truncated output and non-success finish reasons even on HTTP 200.
- Translate missing credentials, auth failure, policy rejection, context overflow, no compatible free model, quota exhaustion, transient provider failure, timeout, cancellation and invalid model output into distinct visible errors.
- Proposed limits: one inference at a time; per-call 60-second timeout; 180-second active-processing deadline per submitted interpretation/recommendation pass, excluding user clarification think time.
- Maximum four inference attempts per pass: one call per stage plus at most one retry per stage. Count adapter, Koog and application retries together; disable hidden retry amplification.
- Retry only transient network failures, suitable 5xx or transient 429; honor `Retry-After`, jitter and remaining deadline. Do not retry known daily quota exhaustion, auth failures, invalid intent/output or deterministic no-match.
- Fallback consumes the same bounded retry budget. No model auto-repair loops or unbounded agent iterations.
- Always propagate coroutine cancellation; close clients exactly once. Never catch cancellation as a normal provider failure.
- Log only request ID, stage, prompt/catalog versions, model, durations, counts, status and usage where available. Redact headers, keys, raw prompts and payload/response bodies.

Deliverable: complete offline error matrix and cancellation/retry tests.

### OR-8: Add CLI, documentation and opt-in evaluation

Dependencies: OR-7.

- Add a `recommendCars` Gradle JavaExec task pointing to the CLI entry point. Keep Compose and packaging configuration unchanged.
- Support a single text request, interactive clarification, and a structured-output mode for evaluation. In noninteractive mode return a typed clarification request instead of hanging for input.
- Display normalized requirements, ranked recommendations, verified facts, trade-offs, unknowns, source document/page and price context. Keep no-match distinct from provider failure.
- Document environment setup, choosing a currently valid free model, no paid fallback, external processing acknowledgement, limits and troubleshooting. Examples use placeholders; no key values or shell history instructions containing keys.
- Keep normal tests and CI entirely offline. Add a separately gated live smoke command, requiring both a key and explicit opt-in, using synthetic/public non-sensitive fixture data.
- The live smoke checks connectivity and both stage contracts; record model/date and fact/reference validity, not exact prose or stable quality claims.
- Do not automatically upload the real catalog in CI or perform live calls during this planning task. Operational catalog use requires appropriate rights and approved data handling.
- Update the main POC plan's starting-point statements to acknowledge that catalog schema and data now exist; leave unrelated ingestion work untouched.

Deliverable: runnable CLI, usage documentation, example outputs from offline fixtures, optional live-smoke instructions.

## Evaluation and acceptance criteria

Use synthetic minimal fixtures plus existing-catalog regression cases. No gold answer should depend on unrevised assumptions from earlier conversational recommendations.

| Case | Required behavior |
| --- | --- |
| Seven seats + electric seats + tailgate | Do not recommend Peaq on unknown seat count or claim Kodiaq electric adjustment from heated seats. |
| Petrol Kodiaq + seven seats | Preserve explicit petrol/mild-hybrid policy; exclude its five-seat PHEV. |
| Seven seats + biggest boot | Do not assert which Kodiaq range endpoint applies without supported seating conditions. |
| Lowest diesel consumption + big boot | Clarify objective priority where necessary; compare compatible reviewed facts, preserve ranges and disclose trade-offs. |
| Petrol, not electric | Clarify hybrid/PHEV acceptance rather than guessing. |
| Max EUR budget | Inclusive minor-unit boundary, currency/basis correctness, effective date checks, reviewed price required. |
| Required electric seats | Heated seats, lumbar-only adjustment and driver-only adjustment cannot silently satisfy both-front-seat adjustment. |
| Unknown/unreviewed fitment or price | Never upgrade to confirmed. Exclude only when the fact is necessary; show the relevant data gap. |
| Unmatched or conflicting feature name | Clarify instead of dropping the requirement or borrowing facts from another trim. |
| Empty result | No ranking network call; explicit no-match/data-gap result. |
| Prompt injection in user/catalog text | No paid routing, tools, system-prompt override or hard-constraint relaxation. |
| Model invents candidate, price or citation | Reject before rendering; numeric facts come from local data. |
| Large candidate set/context | Preserve objective winners, obey budget, disclose shortlist; no truncation of serialized JSON. |
| Free model removed/repriced | Fail closed before inference or use only another reverified free variant. |
| 429/5xx/timeouts/cancellation | Bounded attempts/deadlines, correct exit state, no hidden paid fallback. |

Release gates:

- Every displayed recommendation passes local hard constraints.
- Every displayed catalog fact/citation resolves to a reviewed applicable fact in the exact snapshot.
- Both inference stages pass captured-wire free-only and schema-policy tests.
- No normal test/CI invocation accesses OpenRouter or requires a key.
- Formatting and targeted tests pass before expanding validation.
- Suggested targeted commands after files exist:
  - `./gradlew spotlessCheck test --tests 'com.sps23.aiworkflow.catalog.*' --tests 'com.sps23.aiworkflow.recommendation.*' --tests 'com.sps23.aiworkflow.openrouter.*'`
  - `./gradlew build test` for final integration; note `build` runs `spotlessApply` and can modify sources.
- Do not claim recommendation accuracy from a successful HTTP response; evaluate intent fidelity, eligibility, provenance and objective consistency separately.

## Decisions and trade-offs (ADRs)

**ADR-1: Strictly free, fail closed.** Concrete verified `:free` models only; no paid router, paid fallback or billable plugins. Lower availability is accepted in exchange for predictable zero-inference-cost policy.

**ADR-2: Typed interpretation, not prompt beautification.** Stage 1 produces validated requirements; deterministic code builds the efficient stage-2 message. This avoids meaning drift and a third call.

**ADR-3: Catalog facts are authoritative.** Local filtering/comparison and locally rendered evidence remain authoritative. The LLM supplies bounded recommendation choices and supported reasons, not new vehicle data.

**ADR-4: Dedicated runtime agent prompt.** The recommender's packaged system instructions are independent of repository development/ingestion agents. No general tool loop or agent framework expansion is necessary.

**ADR-5: CLI first.** A reusable use case allows later Compose integration without coupling domain logic to UI state.

**ADR-6: Koog first, mandatory safeguards first.** Verify the declared version against wire contracts. Use existing Ktor only if needed for capabilities Koog cannot represent; do not weaken the free-only contract.

**ADR-7: Reviewed facts only.** Fewer confirmed matches are preferable to persuasive but unsupported answers. Catalog review remains a separate workstream.

## Glossary

- **Configuration:** Exact model/trim/powertrain/body/transmission variant identified by catalog ID.
- **Fact:** An applicable price, measurement, fitment or configuration attribute with provenance and review status.
- **Intent:** Validated structured representation of what the user actually asked.
- **Hard constraint:** Requirement a candidate must demonstrably satisfy.
- **Objective:** Preference used to compare eligible candidates; may need priority clarification.
- **Projection:** Compact, request-specific subset of catalog facts sent to the model.
- **Agent:** Versioned system prompt plus strict input/output contract, not a Copilot agent automatically executed by OpenRouter.
- **Free variant:** Actual `:free` model catalog entry, not an arbitrary suffix on any model.
- **Provider fallback:** Another endpoint serving the same allowed model.
- **Model fallback:** Another explicitly verified free model, using the same attempt budget.
- **Reviewed:** Catalog review status; distinct from date validity, homologation approval and comparison compatibility.

## Explicitly out of scope

Compose screens, persistent chat history, embeddings/vector databases, web browsing/car scraping, autonomous tools, model training, PDF re-extraction, bulk catalog correction, paid inference, production backend deployment and purchase/finance advice.

## External evidence and implementation cautions

Checked during planning; revalidate before implementation/live evaluation:

- [OpenRouter free variants](https://openrouter.ai/docs/guides/routing/model-variants/free): paid fallback entries can incur charges.
- [Free router](https://openrouter.ai/docs/guides/routing/routers/free-router): `openrouter/free` varies underlying models; not the chosen default.
- [Models metadata](https://openrouter.ai/docs/guides/overview/models): actual variants, context and supported parameters.
- [Structured output](https://openrouter.ai/docs/guides/features/structured-outputs): strict JSON schema plus endpoint support and `require_parameters`; still validate locally.
- [Provider preferences](https://openrouter.ai/docs/guides/routing/provider-selection): price ceilings, parameter requirements, data-use preferences and provider versus model routing.
- [Limits](https://openrouter.ai/docs/api/reference/limits): currently documents 20 requests/minute and 50/day below the qualifying purchased-credit tier, or 1,000/day at that tier. Two successful calls consume at least two requests; retries/clarifications reduce practical capacity. Do not encode these as guaranteed quotas or require purchasing credits.
- [Koog providers](https://docs.koog.ai/llm-providers/) and [LLM clients](https://docs.koog.ai/prompts/llm-clients/): concept/API guidance; installed 1.3.0 artifacts are authoritative for implementation compatibility.

Remaining implementation evidence gates are not permission to assume success: exact Koog wire support, a currently compatible free model, context estimation limits, and live free-endpoint availability must be established before declaring the adapter ready.
