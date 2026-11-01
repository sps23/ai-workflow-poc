# 0006 – Validation rules R1–R18: hard rules in production, reference checks in tests

Status: accepted, 2026-10-31.

## Context
LLM output is nondeterministic. The repo's hand-made reference JSON differs from faithful
extractions in known ways (Stage 0 evidence), so the reference cannot be ground truth.

## Decision
`ExtractionRequirements` (main) defines the rules; violations are **hard** (reject) or
**soft** (report).

- Hard, always: R1 finishReason `STOP`; R2 one bare JSON object; R3 strict decode; R4 no
  identity keys; R5 non-blank title/make/model; R6 `effectivePeriod` `YYYY-MM`; R7 unique
  non-empty trims with variants; R8 variants named, matching trim, priced; R9 price facts
  (rawText, ISO 4217, nonnegative amount, max ≥ amount; zero-priced grants/options are valid);
  R10 list prices EUR 5,000–250,000; R11 plausible
  ranges and dimensions; R12 named options with scopes; R13 maps to `CarDataModel` and
  round-trips; R18 variant names unique within a trim.
- Reference only (tests, when a reference model is given): R14 trim names match (hard); R15
  each trim's lowest reference price present (hard); R16 counts and values within ±20 % (soft);
  R17 dimensions match (soft).
- `validateVehicle` runs the content rules R5–R13 and R18 without a finishReason; the catalog
  generator uses it on promoted files.

## Consequences
- Anchors stay few and exact; everything else is soft.
- Known soft differences: the reference parser gives `UNKNOWN` powertrain where Gemini gives
  `PETROL`; a reference price range is one row where Gemini makes two variants; Gemini lists
  colours as separate options.
