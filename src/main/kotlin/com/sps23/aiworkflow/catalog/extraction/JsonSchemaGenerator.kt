package com.sps23.aiworkflow.catalog.extraction

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Builds the JSON Schema subset Gemini accepts in `generationConfig.responseJsonSchema`
 * from a kotlinx-serialization [SerialDescriptor].
 *
 * Mirrors [ExtractedVehicle.strictJson] decoding: classes reject unknown keys, `required` lists
 * only properties that decoding cannot fill (no default value and not nullable), and nullable
 * values accept JSON `null`.
 */
@OptIn(ExperimentalSerializationApi::class)
object JsonSchemaGenerator {
    fun generate(descriptor: SerialDescriptor): JsonObject = schema(descriptor, emptySet())

    private fun schema(
        descriptor: SerialDescriptor,
        path: Set<String>,
    ): JsonObject {
        val base = nonNullSchema(descriptor, path)
        if (!descriptor.isNullable) return base
        val type = base["type"]
        return if (type is JsonPrimitive && descriptor.kind is PrimitiveKind) {
            JsonObject(
                base + (
                    "type" to
                        buildJsonArray {
                            add(type)
                            add(JsonPrimitive("null"))
                        }
                ),
            )
        } else {
            buildJsonObject {
                put(
                    "anyOf",
                    buildJsonArray {
                        add(base)
                        add(buildJsonObject { put("type", "null") })
                    },
                )
            }
        }
    }

    private fun nonNullSchema(
        descriptor: SerialDescriptor,
        path: Set<String>,
    ): JsonObject =
        when (val kind = descriptor.kind) {
            PrimitiveKind.STRING, PrimitiveKind.CHAR -> {
                type("string")
            }

            PrimitiveKind.BOOLEAN -> {
                type("boolean")
            }

            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> {
                type("integer")
            }

            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> {
                type("number")
            }

            SerialKind.ENUM -> {
                enumSchema(descriptor)
            }

            StructureKind.LIST -> {
                buildJsonObject {
                    put("type", "array")
                    put("items", schema(descriptor.getElementDescriptor(0), path))
                }
            }

            StructureKind.MAP -> {
                mapSchema(descriptor, path)
            }

            StructureKind.CLASS, StructureKind.OBJECT -> {
                classSchema(descriptor, path)
            }

            is PolymorphicKind, SerialKind.CONTEXTUAL -> {
                throw IllegalArgumentException("Unsupported kind $kind for ${descriptor.serialName}.")
            }
        }

    private fun type(name: String): JsonObject = buildJsonObject { put("type", name) }

    private fun enumSchema(descriptor: SerialDescriptor): JsonObject =
        buildJsonObject {
            put("type", "string")
            put("enum", buildJsonArray { repeat(descriptor.elementsCount) { add(JsonPrimitive(descriptor.getElementName(it))) } })
        }

    private fun mapSchema(
        descriptor: SerialDescriptor,
        path: Set<String>,
    ): JsonObject {
        val key = descriptor.getElementDescriptor(0)
        require(key.kind == PrimitiveKind.STRING && !key.isNullable) {
            "Map keys must be non-null strings in ${descriptor.serialName}."
        }
        return buildJsonObject {
            put("type", "object")
            put("additionalProperties", schema(descriptor.getElementDescriptor(1), path))
        }
    }

    private fun classSchema(
        descriptor: SerialDescriptor,
        path: Set<String>,
    ): JsonObject {
        val name = descriptor.serialName.removeSuffix("?")
        require(name !in path) { "Recursive type $name is not supported." }
        val nested = path + name
        val properties = mutableMapOf<String, JsonElement>()
        val required = mutableListOf<JsonElement>()
        repeat(descriptor.elementsCount) { index ->
            val elementName = descriptor.getElementName(index)
            val element = descriptor.getElementDescriptor(index)
            properties[elementName] = schema(element, nested)
            if (!descriptor.isElementOptional(index) && !element.isNullable) required += JsonPrimitive(elementName)
        }
        return buildJsonObject {
            put("type", "object")
            put("properties", JsonObject(properties))
            if (required.isNotEmpty()) put("required", JsonArray(required))
            put("additionalProperties", false)
        }
    }
}
