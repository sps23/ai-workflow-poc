# PDF extractor Stage 2: matrix and marker spike

## Result

Two manufacturer layouts were probed:

| Source / region | Columns | Cells | Coverage | Exactness | Review |
|---|---:|---:|---:|---:|---:|
| VW Golf MY27, page 8, Exterior Highlights | 4 trims | 96 | 100% | 100% | 0 |
| Peugeot 5008 spec sheet, page 17, Options | 3 trims | 12 | 91.7% | 100% | 1 |

Both references contain row ordinals, page coordinates, and raw marker glyphs,
without copied source row text:

- [VW Golf page 8 reference](evidence/vw-golf-my27-page-8-exterior-highlights.psv)
- [Peugeot 5008 page 17 reference](evidence/peugeot-5008-page-17-options.psv)

For VW, columns are Life, Edition 75, Style, and R-Line; the visible legend maps
`●` to standard, an open circle to optional, and `-` to unavailable. For Peugeot,
columns are Allure, GT, and GT Exclusive; the visible legend maps `⚫` to standard,
the open-circle private-use glyph to option, and `-` to not available. The final
Peugeot row's Allure cell is visually blank, so it remains unknown and goes to
review rather than being inferred as not available.

## Parser comparison and measurement

Reproduce the probes with `./gradlew probePdfMatrix probePeugeot5008Matrix`. They report:

| Evidence | VW Golf page 8 | Peugeot 5008 page 17 |
|---|---|---|
| Tika 3.3.3 | 14 pages; 26,949 document-text characters | 31 pages; 42,659 document-text characters |
| Producer | `Microsoft® PowerPoint® for Microsoft 365` | `Microsoft® PowerPoint® for Microsoft 365` |
| PDFBox 3.0.8 geometry | Media/crop `[0, 0, 960, 540]` pt; rotation 0 | Media/crop `[0, 0, 960, 540]` pt; rotation 0 |
| Marker glyphs | `●` U+25CF; optional mark extracted as `O` U+004F; unavailable `-` U+002D | `⚫` U+26AB; optional glyph U+F06D (Wingdings private use); unavailable `-` U+002D |
| Cell assignment | 96/96 markers exact | 11/12 markers assigned; all 11 exact; 1 blank routed to review |
| Automatic coverage / exactness | 100% / 100% | 91.7% / 100% |

PDFBox `TextPosition` coordinates are direction-adjusted points from the displayed
page's upper-left for both unrotated pages. Each page was rendered at 120 dpi
(1 PDF point = 1.667 rendered pixels) and visually compared with its matrix. VW's
optional mark is encoded as `O`; Peugeot uses a Wingdings private-use code point.
Both encodings are layout-specific and must not be treated as universal marker
semantics.

Tika returns document-level metadata and flattened text, including matrix symbols,
but no row/column geometry. PDFBox supplies text positions and glyph coordinates;
the spike's explicit table bounds, row anchors, and column centers then match the
manual references. This supports two layouts only; it is not a universal matrix
detector.

The Peugeot matrix uses nonuniform row spacing and the last row's two option marks
sit at different y-coordinates because one has an explanatory annotation beneath
it. The evaluated profile allows a wider row tolerance for this layout. A larger
column tolerance is also needed because glyph bounding boxes for its private-use
marker are offset from the visible circle centers. Those tolerances are specific to
this page and were checked against its rendered image.

## Corpus layout triage

A PDFBox text-signature scan covered all 22 PDFs (390 pages). Golf page 8, ID.7
page 6, Tayron page 8, and Tiguan page 8 expose multi-trim tables with marker
symbols; these four VW guides are a preliminary matrix-layout family. Their
column counts and table contents vary, so only Golf page 8 has measured accuracy.
The other 18 PDFs had no matching `Standard` / `Optional` / `Unavailable` legend
signature. That scan does not prove they lack equipment tables or graphic marks.

The corpus also contains per-trim equipment-list pages in Peugeot spec sheets,
technical data tables in model guides, and narrative Toyota brochures. These are
likely distinct content/layout families, not verified support claims. Stage 5
must visually review examples before extending the supported set.

## Boundaries and review path

- VW's 96 cells carry legend symbols, including explicit unavailable dashes.
- Peugeot's Allure cell for the final feature is visibly blank and remains unknown;
  the other 11 cells carry text glyph markers. The evaluator counts only assigned
  marker states toward automatic coverage and reports the blank as a review cell.
- A blank elsewhere remains missing evidence, not “unavailable”; the unit test
  confirms an empty glyph and unrelated `*` are not recognized marker glyphs, and
  that an explicitly unknown cell is excluded from coverage.
- Automated feature-label and header-text extraction was not evaluated. The
  reference uses manually checked row ordinals and trim columns, not copied row
  wording.
- Visible target markers are text glyphs in both selected regions. The spike does
  not establish extraction of vector-only or raster marks in other documents.
- A glyph outside the configured row/column anchors, an unsupported marker, a
  blank/illegible cell, or any untested layout must remain unknown/ambiguous and
  require review. No negative fact is inferred from absence.
- Tabula was not added or run: its published 1.0.5 release uses PDFBox 2.x, while
  this project uses PDFBox 3.x. The test focuses on the declared coordinate API.
- The probe uses no OCR, network, OpenRouter, or vision model.
- No production feature-matrix extractor is implemented in this stage. The
  coordinate probe and these two layout references are inputs to Stage 3 design.

The spike does not include feature-row wording in its checked-in reference. Source
terms still need review before committing extracted page text or broader derived
artifacts.
