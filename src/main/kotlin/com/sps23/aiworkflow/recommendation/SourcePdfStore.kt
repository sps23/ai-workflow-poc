package com.sps23.aiworkflow.recommendation

import com.sps23.aiworkflow.catalog.CatalogSource
import java.io.IOException

/** Resolves model citations through the local catalog, never through model-authored paths or URLs. */
class SourcePdfStore(
    private val sources: List<CatalogSource>,
) {
    fun read(reference: String): Pair<String, ByteArray> {
        val documentId = reference.substringBeforeLast(':', "")
        val page = reference.substringAfterLast(':').toIntOrNull()
        val source =
            sources.singleOrNull { it.documentId == documentId }
                ?: throw IOException("The cited source is not registered in the bundled catalog.")
        if (page == null || page !in 1..source.pageCount) {
            throw IOException("The cited page is outside the source document.")
        }
        val path = source.relativePath
        if (!path.endsWith(".pdf", ignoreCase = true) ||
            path.split('/').any { it.isBlank() || it == "." || it == ".." || '\\' in it || ':' in it }
        ) {
            throw IOException("The catalog source has an invalid PDF resource path.")
        }
        val bytes =
            SourcePdfStore::class.java.getResourceAsStream("/car-pricelists/$path")?.use { it.readBytes() }
                ?: throw IOException("The source PDF is missing from the application resources.")
        if (!bytes.take(5).toByteArray().contentEquals("%PDF-".toByteArray())) {
            throw IOException("The bundled source is not a PDF. Restore the original PDF with Git LFS and rebuild the app.")
        }
        return path.substringAfterLast('/') to bytes
    }
}
