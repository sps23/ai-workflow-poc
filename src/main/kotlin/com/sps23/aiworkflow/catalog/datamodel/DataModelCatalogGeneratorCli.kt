package com.sps23.aiworkflow.catalog.datamodel

import java.nio.file.Path
import kotlin.system.exitProcess

object DataModelCatalogGeneratorCli {
    private const val DEFAULT_SOURCE_ROOT = "src/main/resources/car-pricelists"
    private const val DEFAULT_OUTPUT =
        "src/main/resources/vehicle-catalog/data-model-catalog.json"

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size > 2) {
            System.err.println("Usage: generateDataModelCatalog [car-pricelists-directory] [output-json]")
            exitProcess(2)
        }
        try {
            DataModelCatalogGenerator.generate(
                sourceRoot = Path.of(args.getOrNull(0) ?: DEFAULT_SOURCE_ROOT),
                outputPath = Path.of(args.getOrNull(1) ?: DEFAULT_OUTPUT),
            )
        } catch (exception: Exception) {
            System.err.println("Catalog generation failed: ${exception.message ?: exception.javaClass.simpleName}")
            exitProcess(1)
        }
    }
}
