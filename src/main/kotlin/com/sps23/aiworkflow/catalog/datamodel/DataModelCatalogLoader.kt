package com.sps23.aiworkflow.catalog.datamodel

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.text.Normalizer
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.streams.asSequence

/** Builds a [DataModelCatalog] from every `*.json` data model file below a directory. */
object DataModelCatalogLoader {
    const val DEFAULT_RESOURCE_ROOT = "/car-pricelists"

    fun loadDirectory(root: Path): DataModelCatalog {
        val vehicles =
            findDataModelFiles(root).map { file ->
                val relative = relativePath(root, file)
                CarDataModelParser.parse(file.readText(), relative)
            }
        val duplicates = vehicles.groupBy { it.id }.filterValues { it.size > 1 }
        if (duplicates.isNotEmpty()) {
            throw DataModelParseException(
                "Duplicate vehicle ids: " + duplicates.entries.joinToString { (id, cars) -> "$id in ${cars.map { it.sourcePath }}" },
            )
        }
        return DataModelCatalog(vehicles)
    }

    /** Root-relative path with "/" separators, NFC-normalized (macOS stores names decomposed). */
    fun relativePath(
        root: Path,
        file: Path,
    ): String = Normalizer.normalize(root.relativize(file).joinToString("/"), Normalizer.Form.NFC)

    fun findDataModelFiles(root: Path): List<Path> =
        Files.walk(root).use { paths ->
            paths
                .asSequence()
                .filter { it.isRegularFile() && it.extension.equals("json", ignoreCase = true) }
                .sortedBy { root.relativize(it).toString() }
                .toList()
        }

    fun loadFromClasspath(resourceRoot: String = DEFAULT_RESOURCE_ROOT): DataModelCatalog {
        val uri =
            checkNotNull(DataModelCatalogLoader::class.java.getResource(resourceRoot)) {
                "Classpath resource $resourceRoot not found"
            }.toURI()
        return if (uri.scheme == "jar") {
            FileSystems.newFileSystem(uri, emptyMap<String, Any>()).use { loadDirectory(it.getPath(resourceRoot)) }
        } else {
            loadDirectory(Path.of(uri))
        }
    }
}
