package com.sps23.aiworkflow.catalog.datamodel

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

internal object GenerateDataModelTestFixtures {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "Expected source directory and test fixture directory." }
        val root = Path.of(args[0])
        val output = Path.of(args[1])
        val files = DataModelCatalogLoader.findDataModelFiles(root)
        require(files.isNotEmpty()) { "No model JSON files found in $root." }
        val fixtures =
            files.map { file ->
                val relative = DataModelCatalogLoader.relativePath(root, file)
                val car = CatalogSourceParser.parse(file.readText(), relative)
                relative to DataModelFixtures.json.encodeToString(FrozenDataModel.serializer(), FrozenDataModel.from(car))
            }
        fixtures.forEach { (relative, content) ->
            val target = output.resolve(relative)
            target.parent.createDirectories()
            target.writeText("$content\n")
            println(relative)
        }
        println("Generated ${fixtures.size} normalized fixtures. Review the complete diff before accepting a new baseline.")
    }
}
