package com.sps23.aiworkflow.recommendation

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SourcePdfStoreTest {
    private val sources = CatalogContext.loadBundledCatalog().sources
    private val golf = sources.single { it.documentId == "vw/Golf_MY27.pdf" }

    @Test
    fun `citation resolves through registry to original bundled PDF bytes`() {
        val source = golf.copy(documentId = "registered-document")
        val (filename, bytes) = SourcePdfStore(listOf(source)).read("registered-document:8")
        val expected =
            checkNotNull(javaClass.getResourceAsStream("/car-pricelists/${golf.relativePath}"))
                .use { it.readBytes() }
        assertEquals("Golf_MY27.pdf", filename)
        assertContentEquals(expected, bytes)
        assertEquals("%PDF-", bytes.take(5).toByteArray().toString(Charsets.US_ASCII))
    }

    @Test
    fun `unknown documents URLs and out of range pages cannot resolve`() {
        val store = SourcePdfStore(sources)
        listOf(
            "unknown.pdf:1",
            "https://example.com/Golf.pdf:8",
            "../vw/Golf_MY27.pdf:8",
            "${golf.documentId}:0",
            "${golf.documentId}:${golf.pageCount + 1}",
            "${golf.documentId}:8,9",
        ).forEach { reference ->
            assertFailsWith<IOException> { store.read(reference) }
        }
    }

    @Test
    fun `unsafe registry paths and missing PDFs are visible failures`() {
        listOf("../vw/Golf_MY27.pdf", "/vw/Golf_MY27.pdf", "vw\\Golf_MY27.pdf", "https://example.com/golf.pdf", "missing.pdf")
            .forEach { path ->
                val store = SourcePdfStore(listOf(golf.copy(relativePath = path)))
                assertFailsWith<IOException> { store.read("${golf.documentId}:8") }
            }
    }
}
