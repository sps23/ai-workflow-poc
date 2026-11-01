# PDF extraction stage 0 – live run evidence

Plan: [`../pdf-to-json-implementation-stages.md`](../pdf-to-json-implementation-stages.md) (Stage 0).

## Run 1 – 2026-10-31

| Item | Value |
|---|---|
| Resource | `dacia/dacia jogger.pdf` (1,687,863 bytes) |
| Model | `gemini-3.8-flash` (response `modelVersion` identical) |
| Config | `responseMimeType=application/json`, no schema, temperature = model default, `maxOutputTokens=65536` |
| Prompt sizes | system 3,065 chars, user (model definition + task) 5,480 chars |
| Finish reason | `STOP` |
| Latency | 121.6 s |
| Tokens | prompt 6,356; candidates 6,546; thoughts 5,549; total 18,451 |
| Estimated cost | USD 0.0501 (promo price $0.75 / $3.75 per 1M) |
| Extracted | 3 trims, 6 variants, 8 options, 3 powertrains |
| Result | **PASSED** – 0 hard, 5 soft |

Soft deviations (R16) and assessment:
- Powertrain types `PETROL` vs reference `UNKNOWN` (3 trims): the reference parser cannot infer
  petrol from "TCe 110"; extraction is more precise. Not an extraction defect.
- Expression variant count 3 vs 2: the reference stores "€27,890 - €28,490" as one ranged row;
  Gemini emitted two `TCe 110` rows (€27,890 and €28,490). Both are valid readings, but the two
  variants had **identical names** – a prompt rule requiring unique, attribute-qualified variant
  names was added after this run (not yet re-verified live).
- Option count 8 vs 2: the reference lists only two option groups; Gemini listed each paint colour.
  More granular, consistent with the model definition.

## Findings for stage 1+
- JSON mode without schema already produced a strictly decodable `ExtractedVehicle` on the first
  try; stage 1 schema work is about robustness, not a blocker.
- Thinking tokens ≈ 46 % of output; latency ~2 min → keep the 10-minute timeout, show progress in UI.
- Consider a hard rule "variant names unique within a trim" once the prompt change is verified.
  → Done in stage 1 as hard rule R18.

## Run 2 – 2026-10-31 (stage 1, responseJsonSchema)
`GeminiPdfClient`, JSON mode + `responseJsonSchema` generated from `ExtractedVehicle`
(schema saved as `schema.json`), prompt with the unique-variant-name rule, model default temperature.

| Metric | Run 1 (no schema) | Run 2 (schema) |
|---|---|---|
| Result | PASSED, 0 hard / 5 soft | PASSED, 0 hard / 5 soft (R18 passed) |
| Prompt tokens | — | 6,427 |
| Candidates / thoughts | — | 6,446 / 4,662 |
| Total tokens | 18,451 | 17,535 |
| Est. cost (USD) | 0.0501 | 0.0465 |
| Latency | 121.6 s | 96.3 s |
| Trims / variants / options | 3 / 6 / 8 | 3 / 6 / 8 |

Observations:
- Gemini accepted the generated schema (`additionalProperties: false`, `anyOf` nullables) without a 400.
- Expression `TCe 110` price range is now two distinctly named variants (`TCe 110 (999cc)`, `TCe 110 (998cc)`),
  so R18 holds; the reference still models it as one row → R16 variant-count deviation persists (expected).
- Powertrain `PETROL` vs. reference `UNKNOWN` and per-colour options unchanged (reference-parser limitations).
- Schema did not raise cost; slightly fewer tokens and faster on this single sample (n=1, not significant).

## Run 3 – 2026-10-31 (stage 3, `./gradlew extractPdf`)
End-to-end CLI on `dacia jogger.pdf` with the default output dir: exit 0, wrote
`extracted-catalog/dacia jogger.json` (27.6 kB envelope, `schemaVersion` 1, provenance with
sha256), 3 trims / 6 variants, tokens input 6,427 / output 8,202 (thinking 4,471) / total 14,629,
latency 76.0 s. Finding: the atomic write used `Files.createTempFile` → file mode 0600; fixed to
use a plain temp name so the file gets umask-default permissions (covered by `PdfExtractionServiceTest`).
