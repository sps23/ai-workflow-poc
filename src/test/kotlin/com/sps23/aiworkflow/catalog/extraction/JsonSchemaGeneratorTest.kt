package com.sps23.aiworkflow.catalog.extraction

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JsonSchemaGeneratorTest {
    @Serializable
    enum class Colour { RED, GREEN }

    @Serializable
    data class Inner(
        val value: Double,
    )

    @Serializable
    data class Sample(
        val name: String,
        val note: String?,
        val count: Int,
        val flag: Boolean = false,
        val colour: Colour,
        val maybeColour: Colour? = null,
        val inner: Inner?,
        val tags: List<String>,
        val extras: Map<String, String> = emptyMap(),
    )

    @Serializable
    data class Node(
        val children: List<Node>,
    )

    @Serializable
    data class IntKeys(
        val values: Map<Int, String>,
    )

    private val schema = JsonSchemaGenerator.generate(Sample.serializer().descriptor)

    private fun JsonObject.prop(name: String): JsonObject = this["properties"]!!.jsonObject[name]!!.jsonObject

    private fun JsonObject.requiredNames(): Set<String> =
        this["required"]
            ?.jsonArray
            ?.map { it.jsonPrimitive.content }
            .orEmpty()
            .toSet()

    @Test
    fun `classes are closed objects with required non-default non-null fields`() {
        assertEquals("object", schema["type"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(false), schema["additionalProperties"])
        assertEquals(setOf("name", "count", "colour", "tags"), schema.requiredNames())
        assertEquals(
            listOf("name", "note", "count", "flag", "colour", "maybeColour", "inner", "tags", "extras"),
            schema["properties"]!!.jsonObject.keys.toList(),
        )
    }

    @Test
    fun `primitives, enums, lists and maps map to JSON schema types`() {
        assertEquals("string", schema.prop("name")["type"]!!.jsonPrimitive.content)
        assertEquals("integer", schema.prop("count")["type"]!!.jsonPrimitive.content)
        assertEquals("boolean", schema.prop("flag")["type"]!!.jsonPrimitive.content)
        assertEquals(listOf("RED", "GREEN"), schema.prop("colour")["enum"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("array", schema.prop("tags")["type"]!!.jsonPrimitive.content)
        assertEquals(
            "string",
            schema
                .prop("tags")["items"]!!
                .jsonObject["type"]!!
                .jsonPrimitive.content,
        )
        assertEquals("object", schema.prop("extras")["type"]!!.jsonPrimitive.content)
        assertEquals(
            "string",
            schema
                .prop("extras")["additionalProperties"]!!
                .jsonObject["type"]!!
                .jsonPrimitive.content,
        )
    }

    @Test
    fun `nullable primitives use type arrays and nullable structures use anyOf`() {
        assertEquals(listOf("string", "null"), schema.prop("note")["type"]!!.jsonArray.map { it.jsonPrimitive.content })
        val inner = schema.prop("inner")["anyOf"]!!.jsonArray
        assertEquals("object", inner[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(setOf("value"), inner[0].jsonObject.requiredNames())
        assertEquals(
            "number",
            inner[0]
                .jsonObject
                .prop("value")["type"]!!
                .jsonPrimitive.content,
        )
        assertEquals("null", inner[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(2, schema.prop("maybeColour")["anyOf"]!!.jsonArray.size)
    }

    @Test
    fun `recursive types and non-string map keys are rejected`() {
        assertFailsWith<IllegalArgumentException> { JsonSchemaGenerator.generate(Node.serializer().descriptor) }
        assertFailsWith<IllegalArgumentException> { JsonSchemaGenerator.generate(IntKeys.serializer().descriptor) }
    }

    @Test
    fun `ExtractedVehicle schema covers the full catalog model`() {
        val vehicle = ExtractedVehicle.jsonSchema
        assertEquals(setOf("title", "make", "model", "trims", "technicalSpecifications"), vehicle.requiredNames())
        val variant =
            vehicle
                .prop("trims")["items"]!!
                .jsonObject
                .prop("variants")["items"]!!
                .jsonObject
        assertEquals(setOf("name", "trimName", "powertrainType", "prices"), variant.requiredNames())
        val price = variant.prop("prices")["items"]!!.jsonObject
        assertEquals(setOf("basis", "rawText"), price.requiredNames())
        assertTrue(
            price
                .prop("basis")["enum"]!!
                .jsonArray
                .map { it.jsonPrimitive.content }
                .contains("RRP_EXCLUDING_GRANTS"),
        )
        assertTrue(vehicle.toString().length < 20_000, "schema stays compact: ${vehicle.toString().length} chars")
    }

    @Test
    fun `model definition example conforms to the generated schema`() {
        val definition = PdfExtractionPrompts.modelDefinition()
        val example = definition.substring(definition.indexOf("{", definition.indexOf("## Example")), definition.lastIndexOf("}") + 1)
        assertEquals(emptyList(), conformance(ExtractedVehicle.jsonSchema, Json.parseToJsonElement(example), "$"))
    }

    @Test
    fun `schema validator rejects what strict decoding rejects`() {
        val ok = Json.parseToJsonElement("""{"name":"a","count":1,"colour":"RED","tags":[],"inner":null}""")
        assertEquals(emptyList(), conformance(schema, ok, "$"))
        val bad = Json.parseToJsonElement("""{"name":"a","count":1.5,"colour":"BLUE","tags":[1],"extra":true,"inner":{}}""")
        val errors = conformance(schema, bad, "$")
        listOf("$.count", "$.colour", "$.tags[0]", "$.extra", "$.inner").forEach { path ->
            assertTrue(errors.any { it.startsWith(path) }, "expected error at $path in $errors")
        }
    }

    /** Minimal validator for the subset the generator emits; returns error messages. */
    private fun conformance(
        schema: JsonObject,
        value: JsonElement,
        path: String,
    ): List<String> {
        schema["anyOf"]?.let { options ->
            val results = options.jsonArray.map { conformance(it.jsonObject, value, path) }
            return if (results.any { it.isEmpty() }) emptyList() else results.minBy { it.size }
        }
        val types =
            when (val type = schema["type"]) {
                is JsonArray -> type.map { it.jsonPrimitive.content }
                is JsonPrimitive -> listOf(type.content)
                else -> return listOf("$path: schema without type")
            }
        if (value is JsonNull) return if ("null" in types) emptyList() else listOf("$path: null not allowed")
        val errors = mutableListOf<String>()
        when {
            "object" in types && value is JsonObject -> {
                val properties = schema["properties"]?.jsonObject
                schema.requiredNames().filterNot { it in value }.forEach { errors += "$path.$it: required" }
                value.forEach { (key, child) ->
                    val childSchema =
                        properties?.get(key)?.jsonObject
                            ?: (schema["additionalProperties"] as? JsonObject)
                    if (childSchema ==
                        null
                    ) {
                        errors += "$path.$key: not allowed"
                    } else {
                        errors += conformance(childSchema, child, "$path.$key")
                    }
                }
            }

            "array" in types && value is JsonArray -> {
                value.forEachIndexed { i, child -> errors += conformance(schema["items"]!!.jsonObject, child, "$path[$i]") }
            }

            value is JsonPrimitive -> {
                val ok =
                    when {
                        "string" in types && value.isString -> {
                            schema["enum"]?.jsonArray?.any { it.jsonPrimitive.content == value.content } ?: true
                        }

                        "integer" in types && !value.isString -> {
                            value.longOrNull != null
                        }

                        "number" in types && !value.isString -> {
                            value.doubleOrNull != null
                        }

                        "boolean" in types && !value.isString -> {
                            value.booleanOrNull != null
                        }

                        else -> {
                            false
                        }
                    }
                if (!ok) errors += "$path: '${value.content}' does not match $types"
            }

            else -> {
                errors += "$path: expected $types"
            }
        }
        return errors
    }
}
