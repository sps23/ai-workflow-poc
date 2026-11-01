# 0002 – Gemini `gemini-3.8-flash` via native `generateContent` over Ktor

Status: accepted, 2026-10-31.

## Context
The existing `GoogleAiClient` uses the OpenAI-compatible endpoint, which is text-only. PDF input
and `responseJsonSchema` need the native API. Ktor CIO and kotlinx-serialization are already
dependencies.

## Decision
- `GeminiPdfClient` calls `POST /v1beta/models/{id}:generateContent` with the PDF as base64
  `inline_data`, the key only in the `x-goog-api-key` header, no SDK.
- CLI default model `gemini-3.8-flash` (`GEMINI_PDF_DEFAULT_MODEL`); override with `GOOGLE_AI_MODEL`.
  The desktop default and presets are defined in the
  [desktop plan](../openrouter-desktop-ui-plan.md).
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

## Approved extension: desktop providers, 2026-11-01

- Desktop PDF extraction supports the same providers as Recommender: Google,
  OpenAI, OpenRouter, LM Studio, llama.cpp, Ollama, and Manual API.
- Google retains native whole-PDF input and schema enforcement.
- Other providers receive PDFBox-extracted text plus the generated schema as prompt
  instructions through the existing `ChatBackend`; strict local validation remains
  authoritative. User approved `org.apache.pdfbox:pdfbox:3.0.6`.
- Text extraction omits images/visual layout; empty, encrypted, forbidden, or damaged
  PDFs fail explicitly. Image-only brochures require the Google option; no OCR/fallback.
- Existing provider auth, model discovery, context/output gates, telemetry, timeout,
  raw-output failure handling, overwrite confirmation, and manual promotion stay active.
  Non-Google output uses the backend's configured completion budget, capped at 65,536.
- User approved nullable provenance `outputTokens` when usage is unavailable.
  Existing numeric provenance remains compatible; schemaVersion stays 1.
- CLI provider/model defaults remain unchanged.
