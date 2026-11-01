# 0005 – Failure handling: sealed outcome, auto-saved raw output, no retry

Status: accepted, 2026-10-31.

## Context
A paid response that fails validation still holds useful text; losing it wastes money and hides
the cause.

## Decision
- `PdfExtractionService.extract` returns `PdfExtractionOutcome`: `Written`, `Declined` or
  `Failed(kind, message, hardViolations, rawPath)`; it does not throw for expected failures.
- When the model returned text and the run fails, the text is saved automatically to
  `<stem>.raw.json` next to the target. The UI shows the path with Copy raw output and Open
  folder; there is no separate Save raw action.
- No automatic retry; the user decides whether to pay for another run.

## Consequences
- CLI exit 1 and the UI error panel list hard violations and the raw path.
- `<stem>.raw.json` files accumulate in the output folder until removed by hand. They have no
  `schemaVersion`, so promoting one by mistake fails in the source-shape parser.
