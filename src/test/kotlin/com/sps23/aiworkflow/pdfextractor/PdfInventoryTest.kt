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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PdfInventoryTest {
    @Test
    fun `inventory detects text and image-only PDFs and creates unique stable output paths`() {
        val root = Files.createTempDirectory("pdf-inventory")
        try {
            val input = Files.createDirectories(root.resolve("car-pricelists"))
            val textFile = input.resolve("toyota/Example guide.pdf")
            val imageFile = input.resolve("peugeot/scan.pdf")
            createTextPdf(textFile)
            createImageOnlyPdf(imageFile)

            val first = PdfInventory.createManifest(input, root.resolve("car-pricelists-extracted"))
            val second = PdfInventory.createManifest(input, root.resolve("car-pricelists-extracted"))

            assertEquals(2, first.documents.size)
            assertEquals(PdfInventory.encodeManifest(first), PdfInventory.encodeManifest(second))
            assertEquals(
                listOf("peugeot/scan.pdf", "toyota/Example guide.pdf"),
                first.documents.map { it.relativePath },
            )
            val imageEntry = first.documents.first()
            assertEquals(PdfContentType.IMAGE_ONLY, imageEntry.contentType)
            assertEquals(PdfProcessingStatus.NEEDS_REVIEW, imageEntry.processingStatus)
            assertEquals(1, imageEntry.pageCount)
            val textEntry = first.documents.last()
            assertEquals(PdfContentType.TEXT_BASED, textEntry.contentType)
            assertEquals(PdfProcessingStatus.QUEUED, textEntry.processingStatus)
            assertEquals(2, textEntry.pageCount)
            assertNotNull(textEntry.sha256)
            assertEquals(64, textEntry.sha256.length)
            assertTrue(textEntry.outputRelativePath.startsWith("car-pricelists-extracted/toyota/"))
            assertNotEquals(imageEntry.outputRelativePath, textEntry.outputRelativePath)
            assertNull(textEntry.diagnostic)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a malformed PDF remains visible as a failed inventory entry`() {
        val root = Files.createTempDirectory("pdf-inventory-failed")
        try {
            val input = Files.createDirectories(root.resolve("car-pricelists/cupra"))
            val malformed = input.resolve("bad.pdf")
            Files.writeString(malformed, "not a PDF")

            val manifest = PdfInventory.createManifest(input.parent, root.resolve("car-pricelists-extracted"))

            val entry = manifest.documents.single()
            assertEquals(PdfProcessingStatus.FAILED, entry.processingStatus)
            assertEquals(PdfContentType.UNKNOWN, entry.contentType)
            assertNull(entry.pageCount)
            assertNotNull(entry.diagnostic)
            assertNotNull(entry.sha256)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun createTextPdf(path: Path) {
        Files.createDirectories(path.parent)
        PDDocument().use { document ->
            document.addPage(PDPage())
            val page = PDPage()
            document.addPage(page)
            PDPageContentStream(document, page).use { content ->
                content.beginText()
                content.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                content.newLineAtOffset(40f, 700f)
                content.showText("Extractable text")
                content.endText()
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
}
