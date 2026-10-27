# Kotlin PDF Extraction Implementation Plan

## Purpose and recommendation

The next step in the car-recommendation workflow is a local, repeatable Kotlin batch extractor. It should convert each source PDF into an inspectable artifact that preserves document identity, page text, table geometry, feature-row/trim-column relationships, marker state, and extraction warnings. Raw text alone is not sufficient: the workflow must determine whether a mark such as a tick or dot belongs to “Electric seats” under “Spec 1” or “Spec B.” The extractor resolves table membership and legend-backed marker status; later catalog interpretation may normalize feature wording but cannot alter that evidence.

The repository currently has 22 PDF files across CUPRA, Peugeot, Škoda, Toyota, and Volkswagen under `src/main/resources/car-pricelists/`. The general POC plan sets the pilot at about 2–3 models per brand; the current folder contains more than that for some makes. The extractor can inventory/process all present PDFs, while the later curated catalog can select the representative model/trim subset.

**Tika alone cannot meet this requirement.** Tika's official `PDFParser` documentation says it extracts text inside tables but does not compute table row or cell boundaries ([Tika 3.3.3 PDFParser](https://tika.apache.org/3.3.3/api/org/apache/tika/parser/pdf/PDFParser.html)). Keep Tika for document metadata and general text, but do not treat its plain text/XHTML as a structured feature matrix or as evidence that a mark maps to the correct trim.

Start with a table-focused technical spike. Prototype coordinate-aware text/glyph extraction using PDFBox's `PDFTextStripper`/`TextPosition` APIs ([PDFTextStripper](https://javadoc.io/doc/org.apache.pdfbox/pdfbox/3.0.5/org/apache/pdfbox/text/PDFTextStripper.html), [TextPosition](https://javadoc.io/doc/org.apache.pdfbox/pdfbox/3.0.5/org/apache/pdfbox/text/TextPosition.html)); compare a table extractor such as Tabula for cell reconstruction. Neither approach should be assumed correct until checked against annotated real pages. Identify whether marks are text glyphs, vector shapes, or raster/scanned content. The approved extractor stays local: unresolved graphic/scanned marks receive an explicit review status.

**Keep AI interpretation separate.** The extraction step should make no OpenRouter calls. A later interpretation use case can consume the raw evidence, propose normalized vehicle/model/trim facts, and require validation/review with source-page citations. A dedicated autonomous agent is unnecessary for deterministic file processing; use a batch CLI/task with an injectable parser and explicit outputs.

## Scope and outputs

### Input corpus

- Discover PDFs recursively under `src/main/resources/car-pricelists/`.
- Preserve their relative paths and make/model mapping in a generated manifest.
- Process each document independently; one malformed/unsupported file must not suppress the rest of the batch.
- Keep original PDFs unchanged.
- Prioritize pages containing equipment/specification matrices, with feature names in rows and trim/grade names in columns.

### Output location

The selected output location is:

```text
src/main/resources/car-pricelists-extracted/<safe-make>/<safe-source-stem>-<relative-path-hash>.json
```

The inventory manifest is `manifest.json`; the batch run writes its per-document outcomes to `batch-manifest.json`. Commit generated JSON artifacts as requested only after checking source terms. This is convenient for inspection and version history, but resources under `src/main/resources` can be bundled into the application distribution. Before packaging or distributing the app, review resource size and source-document terms; if generated raw data should not ship with the application, move it to a non-runtime data directory in a later decision.

### Raw JSON contract

Use a versioned JSON format; one artifact per PDF, with a collision-safe stable output name. Include:

- `schemaVersion`.
- Source relative path, source filename, make, known/guessed model coverage, SHA-256, file size, and source metadata.
- Parser name/version, extraction timestamp if retained, page count, processing status, and warnings/errors.
- Ordered page records with page number and extracted text.
- Ordered structural blocks or parser XHTML when useful and stable.
- Detected table regions with page number, coordinate system, and table bounding box.
- Ordered row and column records with bounds, raw labels/header text, and cell records keyed by row and column.
- For each cell, raw text, detected marker(s), marker kind (text glyph/vector/raster/unknown), bounding box or coordinates, extraction method, state, and review status.
- The table legend/notation text and its source page/region when available; the meaning of symbols depends on that legend.

Use explicit cell states such as `marked`, `unmarked`, `unknown`, and `ambiguous`; preserve the raw marker and evidence separately from any interpreted meaning such as `included`. Map a mark to `included` only when it is spatially assigned to that cell and the document's legend/notation confirms the meaning. Interpret a blank as not included only when the document establishes that convention. Do not coerce missing, illegible, unsupported, or ambiguous marks to false. Keep raw parser output and derived cell structure visibly distinct. Do not have the extractor infer canonical catalog facts from prose or guesses.

Example shape (draft, to refine after the extraction spike):

```json
{
  "schemaVersion": 1,
  "source": {
    "relativePath": "car-pricelists/toyota/example.pdf",
    "sha256": "...",
    "make": "toyota"
  },
  "extraction": {
    "parser": "Apache Tika",
    "parserVersion": "...",
    "pageCount": 1,
    "status": "needs_review",
    "warnings": []
  },
  "pages": [
    {
      "pageNumber": 1,
      "text": "...",
      "blocks": [],
      "tables": [
        {
          "bounds": {},
          "columns": [],
          "rows": [
            {
              "label": "Electric seats",
              "cells": [
                {
                  "columnId": "spec-1",
                  "rawMarker": "✓",
                  "markerKind": "text_glyph",
                  "bounds": {},
                  "state": "marked",
                  "reviewStatus": "needs_review"
                }
              ]
            }
          ],
          "legendReference": {},
          "reviewStatus": "needs_review"
        }
      ]
    }
  ]
}
```

## Proposed Kotlin design

Keep the first implementation small:

1. **`PdfSource` / manifest:** Input path, relative path, make, SHA-256, output path, and processing state.
2. **`PdfParser` interface:** Accept a PDF stream and source identity; return raw evidence or a typed error. Keep it independent of the batch runner and test it separately.
3. **`TikaDocumentParser`:** Extract document metadata and general page/text content. Treat output as text evidence, not cell structure.
4. **`FeatureMatrixExtractor`:** Use the spike-selected coordinate-aware parser to locate table regions, align row labels and trim headers, assign glyphs/shapes to cells, and retain bounds and evidence. Use layout-specific configuration where templates differ rather than pretending one heuristic is universal.
5. **`ExtractionResult` model:** Versioned serializable metadata, pages, blocks, tables, rows, columns, cells, marker evidence, legend references, and review status. Keep detected state separate from semantic interpretation.
6. **`PdfBatchExtractor`:** Discover inputs, hash each file, skip unchanged inputs, process changed/new files, write output atomically, continue on per-file failures, and create a final summary.
7. **Kotlin CLI + Gradle task:** Provide a reproducible task to run the batch without requiring the Compose UI. The repository has no application entry point yet, so register a focused Gradle `JavaExec` task or equivalent rather than coupling extraction to desktop startup.

Use Kotlin serialization already configured in `build.gradle.kts`. PDFBox 3.0.8 is declared directly for coordinate access, matching the version required by Tika 3.3.3. Tika's dependency on PDFBox does not make Tika's table extraction structured.

Tabula is deferred: the published `technology.tabula:tabula:1.0.5` depends on PDFBox 2.0.24, while this application uses PDFBox 3.x. Do not put that Tabula release on the application's runtime classpath without a verified compatibility solution. Revisit an isolated CLI evaluation or compatible integration if the matrix spike justifies it. Neither library eliminates annotation, legend interpretation, or verification.

## Implementation stages

### Stage 1: Inventory and corpus baseline

- Produce a manifest for every current PDF with relative path, make, file size, SHA-256, detected page count, detected text/image-only status, and initial processing status.
- Identify representative documents: one spec sheet, one price list, one brochure, and one model/product guide, with samples from different makes.
- Confirm filename handling, output-name uniqueness, document terms, and whether source-derived extracted text may be committed.
- Keep the initial inventory as a deterministic command output or checked-in manifest, not informal assumptions based on filenames.

**Done when:** every input has a stable identity, a unique output path, and an inventory status.

### Stage 2: Feature-matrix and marker extraction spike

- **Status: complete for two verified layouts.** Results, limits, and annotated references are in [`pdf-extractor-stage-2-spike.md`](pdf-extractor-stage-2-spike.md). Run `./gradlew probePdfMatrix probePeugeot5008Matrix` to reproduce both comparisons.
- Agreed release target: 100% exactness for auto-published cells and at least 90% automatic coverage on each supported layout. Route remaining cells to review; report coverage separately from exactness.
- [x] Annotated all 96 cells in the VW Golf MY27 page 8 matrix against the visible legend; measured marker-to-row-ordinal/trim assignment at 100% coverage and exactness.
- [x] Annotated all 12 cells in the Peugeot 5008 page 17 options matrix; measured 91.7% coverage and 100% exactness, with one visually blank cell explicitly routed to review.
- [x] Compared Tika 3.3.3 document text/metadata with PDFBox 3.0.8 text-glyph coordinates. Tabula was not added because its published release depends on PDFBox 2.x.
- [x] Confirmed marker glyph encoding and checked page number, media/crop box, rotation, and rendered coordinate orientation for both selected pages.
- [x] Scanned the 22-document corpus for matrix legend/layout signatures. Four VW guides are preliminary members of one matrix family; the other 18 had no matching text legend signature. See the spike report for limits.
- [x] Defined review routing: unassigned/unsupported glyphs, blank or illegible cells, and untested layouts remain unknown/ambiguous. The VW matrix has no blank cells; the Peugeot reference includes one visually blank cell routed to review. Unit tests reject empty and unrelated glyphs as markers.
- Row-label text recognition was not measured or implemented; row identity in each spike is a manually checked top-to-bottom ordinal. Verify label extraction and the remaining corpus layouts during Stage 5 before claiming support.

**Done when:** representative feature matrices have manually checked row/column/cell maps, marker encoding is known, parser choice and limitations are documented, and ambiguous cases have a defined review path. The current spike verifies only the two documented layouts; it does not claim general table extraction.

### Stage 3: Define and serialize raw artifacts

- **Status: complete.** The schema-v1 Kotlin contract and validator are in
  `PdfExtractionArtifact.kt`; JSON encoding is pretty-printed, deterministic,
  metadata keys are sorted, and generation timestamps are omitted. Matrix
  cells keep raw marker evidence and explicit state separate from
  legend-backed interpretation. Tests round-trip an annotated multi-trim
  matrix and reject invalid provenance, ordering, schema versions, and cell
  references. No extracted production-PDF artifacts are generated or checked
  in at this stage; Stage 2's manually annotated pages are evaluation evidence,
  not an automatic extractor.
- [x] Add serializable Kotlin types for source identity, parser result, pages, ordered blocks, table bounds, rows, columns, cells, marker evidence, legend references, warnings, and review states.
- [x] Set a schema version from the first output.
- [x] Store explicit provenance and geometry for every extracted cell/marker: document ID/hash, page number, coordinate bounds, row label, column header, raw symbol, and extraction method.
- [x] Choose stable formatting and deterministic ordering; omit generated timestamps from per-document artifacts.
- [x] Add validation for source path/hash, page ordering, schema version/status values, unique row/column IDs, and cell-to-row/column references.

**Done when:** an annotated matrix serializes with correct feature-to-trim cells and traceable source/page/geometry evidence; uncertain cells stay reviewable. **Met for schema and serialization tests only**; automated extraction and production artifact generation remain out of scope until later stages.

### Stage 4: Batch runner and change detection

- **Status: implemented.** Run `./gradlew extractPdfs` to invoke the local PDFBox batch with the source/output directories configured in `build.gradle.kts`. It writes one JSON artifact per PDF plus `batch-manifest.json`; it does not call external services. The current parser extracts ordered page text and PDF metadata only, so successful artifacts are marked `NEEDS_REVIEW` and explicitly warn that table/cell geometry is not extracted. Scanned/image-only documents also carry a review diagnostic. No production corpus artifacts were generated during implementation; check source terms before running/committing them.
- [x] Walk the configured input directory recursively and accept PDF extensions case-insensitively.
- [x] Derive output paths from sanitized relative-path segments, validate containment, and detect case-insensitive collisions instead of overwriting.
- [x] Compute SHA-256 before parsing. Skip only when source hash, artifact schema, and parser name/version match.
- [x] On changed input, write to a temporary file and replace the old artifact only after serialization and artifact validation succeed; a failed re-extraction leaves the previous artifact untouched.
- [x] Continue to the next file after a document-level error; report status and diagnostics for every source in `batch-manifest.json`.
- [x] Return failure from the Gradle task if any document failed, while keeping successful outputs available for review.
- [x] Print a concise summary: discovered, extracted, unchanged, failed, and unsupported/scanned counts.

**Done when:** running the batch twice is idempotent, changed inputs are detected, and partial failures are visible without losing successful results. **Met by offline fixtures and fake-parser tests.** Real-corpus output generation remains pending source-terms review; feature-matrix extraction remains unsupported and review-only.

### Stage 5: Tests and corpus review

- **Status: complete for offline regression coverage and current evidence; layout support remains intentionally narrow.** Tests use generated PDFs and fake parsers for orchestration; they require no network, API keys, or full production corpus. The suite covers text and multipage extraction, metadata, empty/malformed/scanned-like PDFs, artifact serialization and provenance, matrix markers/legend interpretation/unknown blanks, repeat runs, changed hashes, atomic replacement, and partial failures.
- [x] Generate small PDF fixtures in tests; keep the matrix references as manually annotated evidence under `docs/evidence/`.
- [x] Compare reference-backed matrix artifacts to both checked-in annotations. VW Golf page 8: 96/96 cells assigned, 100% coverage and exactness. Peugeot 5008 page 17: 11/12 assigned, 91.7% coverage and 100% exactness; its visually blank cell remains unknown and review-routed.
- [x] Run real-page probes and visually inspect representative pages: VW Golf p8, ID.7 p6, Tayron p8, Tiguan p8; Peugeot 5008 p17 and 408 p16; CUPRA Raval p3/p5; Škoda Kodiaq p3/p5; Toyota RAV4 PHEV p4; Peugeot 208 p2.
- [x] Record layout boundaries: VW and Peugeot pages use distinct marker-table layouts; CUPRA includes version/availability badges and pricing tables; Škoda and Toyota examples use grouped equipment lists and/or technical/pricing tables; Peugeot 208 uses side-by-side trim lists. These pages are visual triage, not additional annotated support.
- [x] Keep unsupported layouts and ambiguous cells in review. Only the Golf p8 and Peugeot 5008 p17 annotated matrix pages have measured references; no general matrix/table support is claimed, and text-only batch artifacts remain `NEEDS_REVIEW`.

**Done when:** offline tests pass, annotated comparisons pass for the two measured pages, and unsupported/ambiguous content remains review-only. Broader corpus layout support requires new annotated references and measured extraction evidence.

### Stage 6: Handoff to interpretation

- Update `docs/car-recommendation-poc-plan.md` to link here and record the implemented command, artifact schema, and known parser limitations.
- Later add a separate interpretation step: input raw artifact -> normalize feature labels and propose structured model/trim/price/specification records -> validate required fields and citations -> human review -> publish canonical catalog.
- Interpretation may normalize wording, but must consume already-established cell/trim assignments. It cannot change a cell's marker state or turn `unknown` into included/not-included without new source evidence.
- Only after this boundary works should the POC consider LLM-assisted interpretation. The model must cite supplied document/page evidence, return structured output, and leave unsupported facts unknown.

**Done when:** the extraction contract is stable enough for another stage to consume without depending on Tika internals.

## Dedicated AI agent decision

Do **not** create a separate autonomous agent for PDF extraction. Parsing files is deterministic infrastructure: explicit inputs, parser configuration, outputs, errors, and repeatability are more valuable than agent autonomy here. A later AI interpretation component may be useful for mapping varied source wording into a shared car schema, but implement it as a bounded service/use case with:

- A fixed set of raw pages/blocks supplied as context.
- A typed response schema for model/trim facts.
- Per-field document/page evidence.
- Validation against known candidate/source IDs.
- Human review before facts become recommendation data.
- Clear timeout, rate-limit, invalid-output, and missing-data behavior.

This separation allows raw artifacts to be regenerated without paying for or varying model interpretation.

## Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| Tika extracts table text without row/cell boundaries | Treat Tika as general text/metadata only; require coordinate-aware table extraction and annotated feature-matrix tests |
| A tick/dot is assigned to the wrong spec column | Preserve cell/marker geometry, verify against annotated rows/headers, and require review near boundaries or on unsupported layouts |
| A blank cell is misread as a negative fact | Preserve `unmarked` separately; interpret as not-included only when the document's legend/notation confirms it |
| Marker is vector art or raster content rather than text | Detect representation during the spike; support only verified local extraction paths and route unresolved cells to manual review |
| Legend meaning is missed or varies by document | Extract and cite legend/notation; marker meaning stays unknown until the legend is verified |
| Page boundaries or numbering are wrong | Verify against visible PDF pages; require page-citation tests before publishing artifacts |
| Input filenames collide or are renamed | Use relative path plus content hash as identity; detect output collisions |
| Reprocessing creates noisy diffs | Hash inputs, use stable JSON ordering, and skip unchanged parser/schema-compatible results |
| One bad file aborts the corpus | Process files independently, record per-file errors, and fail only the aggregate task after all files have been attempted |
| Raw extracted text is redistributed without permission | Check source terms before committing/packaging; preserve attribution and source identity |
| Resources inflate the app package | Measure artifact size and reconsider the chosen resource location before distribution |
| LLM turns ambiguous evidence into false facts | Keep interpretation separate, validate output and citations, require review, and represent unknowns explicitly |

## Acceptance criteria

- All 22 current PDFs are discovered, with unique output destinations and per-file outcomes.
- Each successful artifact includes source relative path, SHA-256, parser identity/version, page count, ordered page text, and warnings/status.
- For supported equipment matrices, output maps each target feature row to the correct trim/spec column and preserves each cell's marker state and evidence. Raw text without the cell mapping does not meet this criterion.
- A manually annotated matrix evaluation set is used. Every automatically published cell on supported layouts must exactly match the reference for row, column, marker state, and legend-backed meaning; false inclusion is a release-blocking error. Require 100% exactness and at least 90% automatic coverage on each supported layout; report both measures so marking every cell unknown cannot count as success.
- Uncertain, blank-with-unclear-semantics, graphic, scanned, or unsupported cells retain `unknown`/`ambiguous`/`unmarked` status and are not published as negative facts.
- Page number and coordinate mapping are verified against rendered source pages; each cell can be traced to source PDF, page, bounds, row label, column header, and raw marker evidence.
- Tika's output is not treated as sufficient for table boundaries. Parser selection is supported by results on real matrix pages.
- Re-running unchanged inputs creates no semantic artifact changes; a changed source triggers reprocessing.
- Failed/unsupported files remain visible and do not prevent other inputs from being extracted.
- Tests run offline and cover parser fixtures, serialization, batch idempotency, changed input, collisions, and partial failures.
- Generated artifacts are committed only after source terms are checked; packaging implications are documented.
- No OpenRouter or other external vision-model calls occur in the extractor.

## Repository starting point

`build.gradle.kts` already declares Tika core and standard parsers 3.3.3, PDFBox 3.0.8, Kotlin serialization, Kotlin 2.3.21, and JDK 25. Tabula is not included because its published PDFBox 2.x dependency needs a separate compatibility decision. The 22 source PDFs are currently under `src/main/resources/car-pricelists/`. There is no application entry point yet, so invoke extraction through a focused Gradle task and keep it independent of Compose startup.

See [`car-recommendation-poc-plan.md`](car-recommendation-poc-plan.md) for the broader recommendation workflow and catalog plan.

## References

- [Apache Tika 3.3.3 `PDFParser`](https://tika.apache.org/3.3.3/api/org/apache/tika/parser/pdf/PDFParser.html): table text is extracted, but PDFParser does not compute row or cell boundaries.
- [Apache PDFBox 3.0.5 `PDFTextStripper`](https://javadoc.io/doc/org.apache.pdfbox/pdfbox/3.0.5/org/apache/pdfbox/text/PDFTextStripper.html) and [`TextPosition`](https://javadoc.io/doc/org.apache.pdfbox/pdfbox/3.0.5/org/apache/pdfbox/text/TextPosition.html): coordinate-aware text extraction building blocks.
- [Tabula](https://tabula.technology/): candidate table extraction tool for text-based PDFs; it does not support scanned PDFs and still requires checking output against source pages.
