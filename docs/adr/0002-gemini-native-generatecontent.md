# 0002 – Gemini `gemini-3.8-flash` via native `generateContent` over Ktor

Status: accepted, 2026-10-31.

## Context
The existing `GoogleAiClient` uses the OpenAI-compatible endpoint, which is text-only. PDF input
and `responseJsonSchema` need the native API. Ktor CIO and kotlinx-serialization are already
dependencies.

## Decision
- `GeminiPdfClient` calls `POST /v1beta/models/{id}:generateContent` with the PDF as base64
  `inline_data`, the key only in the `x-goog-api-key` header, no SDK.
- Default model `gemini-3.8-flash` (`GEMINI_PDF_DEFAULT_MODEL`); override with `GOOGLE_AI_MODEL`
  (CLI) or the tab's model picker.
- Each run calls `models.list` and sets `maxOutputTokens` to the lower of 65,536 and the
  model's `outputTokenLimit`.
- PDFs over 50 MB or without a `%PDF-` header (Git LFS pointers) fail before the call.
- Timeout 10 minutes; no retries; HTTP errors map to `FailureKind`.

## Consequences
- A Jogger brochure costs about 15–18k tokens, about USD 0.05 at the promotional price
  ($0.75 in / $3.75 out per 1M until 2026-12-31, then double). The price table lives only in
  `PdfExtractionIntegrationTest`.
- Thinking tokens are 40–46 % of output and latency is 76–120 s, so the UI shows progress.
- Google also offers `/v1beta/interactions`; revisit if `generateContent` is deprecated.
- Every run uploads the whole PDF to Google; free-tier content may be used by Google.
