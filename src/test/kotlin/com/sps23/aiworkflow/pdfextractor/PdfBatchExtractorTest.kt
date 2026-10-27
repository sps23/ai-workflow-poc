package com.sps23.aiworkflow.pdfextractor

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PdfBatchExtractorTest {
    @Test
    fun `PDFBox parser preserves ordered page text and flags matrix limits for review`() {
        withWorkspace { inputRoot, outputRoot ->
            val sourceFile = inputRoot.resolve("vw/multipage.pdf")
            createTextPdf(sourceFile, listOf("Page one", "Page two"))

            val result = PdfBatchExtractor(inputRoot, outputRoot, PdfBoxDocumentParser()).run()

            val artifact =
                PdfExtractionArtifactCodec.decode(
                    Files.readString(
                        outputRoot.resolve(
                            result.manifest.documents
                                .single()
                                .outputRelativePath,
                        ),
                    ),
                )
            assertEquals(listOf(1, 2), artifact.pages.map { it.pageNumber })
            assertTrue(artifact.pages[0].text.contains("Page one"))
            assertTrue(artifact.pages[1].text.contains("Page two"))
            assertTrue(artifact.pages.all { it.tables.isEmpty() })
            assertEquals("Fixture title", artifact.source.metadata["title"])
            assertEquals("Fixture author", artifact.source.metadata["author"])
            assertEquals(PdfExtractionStatus.NEEDS_REVIEW, artifact.extraction.status)
            assertTrue(artifact.extraction.diagnostics.any { it.message.contains("feature-matrix cells") })
        }
    }

    @Test
    fun `empty and malformed PDFs are reported as per-document failures`() {
        withWorkspace { inputRoot, outputRoot ->
            Files.createDirectories(inputRoot.resolve("make"))
            PDDocument().use { document -> document.save(inputRoot.resolve("make/empty.pdf").toFile()) }
            Files.writeString(inputRoot.resolve("make/malformed.pdf"), "not a PDF")

            val result = PdfBatchExtractor(inputRoot, outputRoot, PdfBoxDocumentParser()).run()

            assertEquals(2, result.summary.discovered)
            assertEquals(0, result.summary.extracted)
            assertEquals(2, result.summary.failed)
            assertTrue(result.manifest.documents.all { !it.diagnostic.isNullOrBlank() })
            assertTrue(Files.exists(result.summary.manifestPath))
        }
    }

    @Test
    fun `batch skips unchanged compatible artifacts and detects changed parser versions`() {
        withWorkspace { inputRoot, outputRoot ->
            val sourceFile = inputRoot.resolve("VW makes/Guide ?!.PDF")
            createTextPdf(sourceFile, "Initial source")
            val parser = RecordingParser()

            val first = PdfBatchExtractor(inputRoot, outputRoot, parser).run()
            val artifactPath =
                outputRoot.resolve(
                    first.manifest.documents
                        .single()
                        .outputRelativePath,
                )
            val firstArtifact = Files.readString(artifactPath)
            val second = PdfBatchExtractor(inputRoot, outputRoot, parser).run()

            assertEquals(1, parser.calls)
            assertEquals(
                PdfBatchStatus.EXTRACTED,
                first.manifest.documents
                    .single()
                    .status,
            )
            assertEquals(
                PdfBatchStatus.UNCHANGED,
                second.manifest.documents
                    .single()
                    .status,
            )
            assertEquals(1, second.summary.unchanged)
            assertEquals(firstArtifact, Files.readString(artifactPath))
            assertTrue(
                first.manifest.documents
                    .single()
                    .outputRelativePath
                    .startsWith("VW-makes/"),
            )
            assertFalse(artifactPath.normalize().startsWith(inputRoot.normalize()))

            val newerParser = RecordingParser(version = "2")
            val third = PdfBatchExtractor(inputRoot, outputRoot, newerParser).run()

            assertEquals(1, newerParser.calls)
            assertEquals(
                PdfBatchStatus.EXTRACTED,
                third.manifest.documents
                    .single()
                    .status,
            )
            assertEquals("2", PdfExtractionArtifactCodec.decode(Files.readString(artifactPath)).extraction.parserVersion)
        }
    }

    @Test
    fun `changed input is reprocessed and receives a new content hash`() {
        withWorkspace { inputRoot, outputRoot ->
            val sourceFile = inputRoot.resolve("toyota/guide.pdf")
            createTextPdf(sourceFile, "First revision")
            val parser = RecordingParser()
            val first = PdfBatchExtractor(inputRoot, outputRoot, parser).run()

            createTextPdf(sourceFile, "Changed revision")
            val second = PdfBatchExtractor(inputRoot, outputRoot, parser).run()

            assertEquals(2, parser.calls)
            assertEquals(
                PdfBatchStatus.EXTRACTED,
                second.manifest.documents
                    .single()
                    .status,
            )
            assertNotEquals(
                first.manifest.documents
                    .single()
                    .sha256,
                second.manifest.documents
                    .single()
                    .sha256,
            )
            val artifact =
                PdfExtractionArtifactCodec.decode(
                    outputRoot
                        .resolve(
                            second.manifest.documents
                                .single()
                                .outputRelativePath,
                        ).toFile()
                        .readText(),
                )
            assertEquals(
                second.manifest.documents
                    .single()
                    .sha256,
                artifact.source.sha256,
            )
        }
    }

    @Test
    fun `document failure does not suppress successful or scanned documents`() {
        withWorkspace { inputRoot, outputRoot ->
            createTextPdf(inputRoot.resolve("vw/good.pdf"), "Good source")
            createImageOnlyPdf(inputRoot.resolve("peugeot/scanned.pdf"))
            Files.createDirectories(inputRoot.resolve("cupra"))
            Files.writeString(inputRoot.resolve("cupra/bad.pdf"), "not a PDF")

            val result =
                PdfBatchExtractor(
                    inputRoot,
                    outputRoot,
                    PdfBoxDocumentParser(),
                ).run()

            assertEquals(3, result.summary.discovered)
            assertEquals(2, result.summary.extracted)
            assertEquals(1, result.summary.failed)
            assertEquals(1, result.summary.unsupportedOrScanned)
            assertEquals(
                listOf(PdfBatchStatus.FAILED, PdfBatchStatus.NEEDS_REVIEW, PdfBatchStatus.NEEDS_REVIEW),
                result.manifest.documents.map { it.status },
            )
            assertTrue(Files.exists(result.summary.manifestPath))
            assertTrue(
                result.manifest.documents
                    .first()
                    .diagnostic
                    .orEmpty()
                    .isNotBlank(),
            )
            val scannedEntry = result.manifest.documents.single { it.relativePath == "peugeot/scanned.pdf" }
            val scannedOutput = outputRoot.resolve(scannedEntry.outputRelativePath)
            val scannedArtifact = PdfExtractionArtifactCodec.decode(Files.readString(scannedOutput))
            assertTrue(
                scannedArtifact.pages
                    .single()
                    .text
                    .isBlank(),
            )
            assertTrue(scannedArtifact.extraction.diagnostics.any { it.message.contains("scanned or image-only") })
        }
    }

    @Test
    fun `failed re-extraction preserves the previous successful artifact`() {
        withWorkspace { inputRoot, outputRoot ->
            val sourceFile = inputRoot.resolve("skoda/model.pdf")
            createTextPdf(sourceFile, "First source")
            val first = PdfBatchExtractor(inputRoot, outputRoot, RecordingParser()).run()
            val relativeOutput =
                first.manifest.documents
                    .single()
                    .outputRelativePath
            val artifactPath = outputRoot.resolve(relativeOutput)
            val previousArtifact = Files.readString(artifactPath)

            createTextPdf(sourceFile, "Changed source")
            val result =
                PdfBatchExtractor(
                    inputRoot,
                    outputRoot,
                    RecordingParser(fail = true),
                ).run()

            assertEquals(
                PdfBatchStatus.FAILED,
                result.manifest.documents
                    .single()
                    .status,
            )
            assertEquals(previousArtifact, Files.readString(artifactPath))
            assertTrue(
                result.manifest.documents
                    .single()
                    .diagnostic
                    .orEmpty()
                    .contains("Parser failure"),
            )
        }
    }

    @Test
    fun `sanitized filenames remain unique and output paths stay relative`() {
        withWorkspace { inputRoot, outputRoot ->
            createTextPdf(inputRoot.resolve("same/Guide ?.pdf"), "One")
            createTextPdf(inputRoot.resolve("same/Guide !.pdf"), "Two")
            createTextPdf(inputRoot.resolve("Top-level.PDF"), "Root document")

            val manifest = PdfInventory.createManifest(inputRoot, outputRoot)
            val outputs = manifest.documents.map { it.outputRelativePath }

            assertEquals(3, outputs.distinct().size)
            assertTrue(outputs.all { !it.contains("../") && !it.startsWith("/") })
            assertEquals("unknown", manifest.documents.single { it.fileName == "Top-level.PDF" }.make)
        }
    }

    private fun withWorkspace(block: (Path, Path) -> Unit) {
        val root = Files.createTempDirectory("pdf-batch")
        try {
            val inputRoot = Files.createDirectories(root.resolve("source-pdfs"))
            val outputRoot = root.resolve("extracted")
            block(inputRoot, outputRoot)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun createTextPdf(
        path: Path,
        text: String,
    ) = createTextPdf(path, listOf(text))

    private fun createTextPdf(
        path: Path,
        texts: List<String>,
    ) {
        Files.createDirectories(path.parent)
        PDDocument().use { document ->
            document.documentInformation.title = "Fixture title"
            document.documentInformation.author = "Fixture author"
            texts.forEach { text ->
                val page = PDPage()
                document.addPage(page)
                PDPageContentStream(document, page).use { content ->
                    content.beginText()
                    content.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                    content.newLineAtOffset(40f, 700f)
                    content.showText(text)
                    content.endText()
                }
            }
            document.save(path.toFile())
        }
    }

    private fun createImageOnlyPdf(path: Path) {
        Files.createDirectories(path.parent)
        val image =
            BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB).apply {
                setRGB(0, 0, Color.BLACK.rgb)
            }
        PDDocument().use { document ->
            val page = PDPage(PDRectangle.A4)
            document.addPage(page)
            val imageObject = LosslessFactory.createFromImage(document, image)
            PDPageContentStream(document, page).use { content -> content.drawImage(imageObject, 20f, 20f) }
            document.save(path.toFile())
        }
    }

    private class RecordingParser(
        override val version: String = "1",
        private val fail: Boolean = false,
    ) : PdfDocumentParser {
        override val name: String = "Test parser"
        var calls: Int = 0
            private set

        override fun parse(source: PdfSource): PdfExtractionArtifact {
            calls++
            check(!fail) { "Parser failure requested by test" }
            val bounds = PdfArtifactBounds(0.0, 0.0, 0.0, 0.0)
            return PdfExtractionArtifact(
                source =
                    PdfArtifactSource(
                        documentId = source.relativePath,
                        relativePath = source.relativePath,
                        fileName = source.fileName,
                        make = source.make,
                        modelCoverage = emptyList(),
                        sha256 = source.sha256,
                        fileSizeBytes = source.fileSizeBytes,
                    ),
                extraction =
                    PdfExtractionSummary(
                        parserName = name,
                        parserVersion = version,
                        pageCount = 1,
                        status = PdfExtractionStatus.COMPLETE,
                    ),
                pages =
                    listOf(
                        PdfArtifactPage(
                            pageNumber = 1,
                            text = "Extracted ${source.sha256}",
                            blocks =
                                listOf(
                                    PdfArtifactBlock(
                                        kind = PdfBlockKind.TEXT,
                                        text = "Extracted ${source.sha256}",
                                        bounds = bounds,
                                        extractionMethod = name,
                                    ),
                                ),
                        ),
                    ),
            )
        }
    }
}
