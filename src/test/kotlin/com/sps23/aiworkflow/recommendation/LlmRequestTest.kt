package com.sps23.aiworkflow.recommendation

import com.sps23.aiworkflow.openrouter.FailureKind
import com.sps23.aiworkflow.openrouter.OpenRouterWorkflowException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LlmRequestTest {
    private val original = "  Seven seats, not electric; preferably electric seats. Show all matches under 50,000 EUR. "
    private val request =
        LlmRequest(
            original,
            listOf(
                RequestTerm(CatalogRequestField.SEATING_CAPACITY, RequestOperator.EQUALS, "Seven", null, "Seven seats"),
                RequestTerm(CatalogRequestField.POWERTRAIN, RequestOperator.EXCLUDES, "ELECTRIC", null, "not electric"),
                RequestTerm(CatalogRequestField.PRICE, RequestOperator.OTHER, "under 50,000", "EUR", "under 50,000 EUR"),
            ),
            listOf(RequestTerm(CatalogRequestField.EQUIPMENT, RequestOperator.EQUALS, "electric seats", null, "preferably electric seats")),
            listOf(RequestTerm(CatalogRequestField.OTHER, RequestOperator.DESCRIBE, "all matches", null, "Show all matches")),
            listOf("electric seats", "under 50,000 EUR"),
        )

    @Test
    fun `translator example preserves powertrain alternatives and required features without softening them`() {
        val original = "electric or hybrid with parking sensors and automatic gearbox"
        val example =
            REQUEST_TRANSLATION_INSTRUCTIONS
                .substringAfter("Example translation:\n")
                .substringBefore("\nEnd example.")
        val translated = LlmRequestCodec.decode(example, original)

        assertEquals(
            listOf(
                RequestTerm(CatalogRequestField.POWERTRAIN, RequestOperator.OTHER, "OTHER", null, "electric or hybrid"),
                RequestTerm(
                    CatalogRequestField.EQUIPMENT,
                    RequestOperator.EQUALS,
                    "parking sensors",
                    null,
                    "parking sensors",
                    fitment = RequestedFitment.STANDARD_OR_OPTIONAL,
                ),
                RequestTerm(CatalogRequestField.TRANSMISSION, RequestOperator.EQUALS, "automatic gearbox", null, "automatic gearbox"),
            ),
            translated.requirements,
        )
        assertEquals(emptyList(), translated.preferences)
        assertEquals(emptyList(), translated.requestedAttributes)
        assertEquals(listOf("hybrid"), translated.unresolvedPhrases)
        assertEquals(translated, LlmRequestCodec.decode(LlmRequestCodec.encode(translated), original))
    }

    @Test
    fun `equipment example distinguishes required front heating from optional leather preference`() {
        val original = "parking sensors, heating seats and optional leather upholstery"
        val translated = example("equipment", original)
        assertEquals(listOf("parking sensors", "heated seats"), translated.requirements.map { it.value })
        assertNull(translated.requirements.first().scope)
        assertEquals(EquipmentScope.FRONT_SEATS, translated.requirements.last().scope)
        assertEquals("heating seats", translated.requirements.last().sourceText)
        val leather = translated.preferences.single()
        assertEquals("leather upholstery", leather.value)
        assertEquals("optional leather upholstery", leather.sourceText)
        assertEquals(RequestedFitment.STANDARD_OR_OPTIONAL, leather.fitment)
        assertTrue(translated.requirements.none { it.value.contains("leather") })
        assertEquals(translated, LlmRequestCodec.decode(LlmRequestCodec.encode(translated), original))
    }

    @Test
    fun `dimension example retains source typos but uses inclusive normalized measurements and all seats up`() {
        val translated = example("dimensions", "7 seats, lenght over 4.6 meters and boot space over 200 leters")
        val length = translated.requirements[1]
        assertEquals(RequestMetric.OVERALL_LENGTH, length.metric)
        assertEquals(RequestOperator.AT_LEAST, length.operator)
        assertEquals("4600", length.value)
        assertEquals("mm", length.unit)
        assertEquals("lenght over 4.6 meters", length.sourceText)
        val boot = translated.requirements[2]
        assertEquals(RequestMetric.BOOT_ALL_SEATS_UP, boot.metric)
        assertEquals(RequestOperator.AT_LEAST, boot.operator)
        assertEquals("200", boot.value)
        assertEquals("L", boot.unit)
        assertEquals("7", translated.requirements.first().value)
    }

    @Test
    fun `range and consumption examples identify metric WLTP basis units and bound direction`() {
        val range = example("range", "at least 300 km range").requirements.single()
        assertEquals(RequestMetric.ELECTRIC_RANGE, range.metric)
        assertEquals(RequestOperator.AT_LEAST, range.operator)
        assertEquals("300", range.value)
        assertEquals("km", range.unit)
        assertEquals(MeasurementBasis.WLTP_COMBINED, range.measurementBasis)
        val fuel = example("consumption", "fuel consumption not more than 8l/100km").requirements.single()
        assertEquals(RequestMetric.FUEL_CONSUMPTION, fuel.metric)
        assertEquals(RequestOperator.AT_MOST, fuel.operator)
        assertEquals("8", fuel.value)
        assertEquals("L/100km", fuel.unit)
        assertEquals(MeasurementBasis.WLTP_COMBINED, fuel.measurementBasis)
    }

    @Test
    fun `qualifiers survive round trip including strict bounds and distinct PHEV test bases`() {
        val original = "fuel consumption strictly below 8 L/100km with battery depleted"
        val term =
            RequestTerm(
                CatalogRequestField.MEASUREMENT,
                RequestOperator.LESS_THAN,
                "8",
                "L/100km",
                original,
                metric = RequestMetric.FUEL_CONSUMPTION,
                measurementBasis = MeasurementBasis.WLTP_DEPLETED_BATTERY,
            )
        MeasurementBasis.entries.forEach { basis ->
            val request = LlmRequest(original, listOf(term.copy(measurementBasis = basis)), emptyList(), emptyList(), emptyList())
            assertEquals(request, LlmRequestCodec.decode(LlmRequestCodec.encode(request), original))
        }
    }

    @Test
    fun `legacy terms without qualifiers remain compatible and exports include explicit null qualifiers`() {
        val original = "parking sensors"
        val raw =
            """{"originalRequest":"parking sensors","requirements":[{"field":"EQUIPMENT","operator":"EQUALS","value":"parking sensors","unit":null,"sourceText":"parking sensors"}],"preferences":[],"requestedAttributes":[],"unresolvedPhrases":[]}"""
        val decoded = LlmRequestCodec.decode(raw, original)
        val term = decoded.requirements.single()
        assertNull(term.metric)
        assertNull(term.scope)
        assertNull(term.fitment)
        assertNull(term.measurementBasis)
        val encoded = LlmRequestCodec.encode(decoded)
        listOf("metric", "scope", "fitment", "measurementBasis").forEach { assertTrue("\"$it\": null" in encoded) }
    }

    @Test
    fun `invalid qualifier enums fields units and numeric bounds fail explicitly`() {
        val original = "length over 4.6 meters"
        val valid =
            RequestTerm(
                CatalogRequestField.MEASUREMENT,
                RequestOperator.AT_LEAST,
                "4600",
                "mm",
                original,
                metric = RequestMetric.OVERALL_LENGTH,
            )
        val terms =
            listOf(
                valid.copy(field = CatalogRequestField.EQUIPMENT),
                valid.copy(unit = "m"),
                valid.copy(value = "four thousand"),
                valid.copy(value = "-1"),
                valid.copy(scope = EquipmentScope.FRONT_SEATS),
                valid.copy(fitment = RequestedFitment.STANDARD_ONLY),
                valid.copy(measurementBasis = MeasurementBasis.WLTP_COMBINED),
                valid.copy(metric = null, measurementBasis = MeasurementBasis.WLTP_COMBINED),
                valid.copy(metric = RequestMetric.ELECTRIC_RANGE, unit = "km", measurementBasis = MeasurementBasis.WLTP_WEIGHTED_COMBINED),
                valid.copy(metric = null, field = CatalogRequestField.TRANSMISSION, scope = EquipmentScope.FRONT),
                valid.copy(metric = null, field = CatalogRequestField.TRANSMISSION, fitment = RequestedFitment.OPTIONAL_ONLY),
            )
        val encoded = LlmRequestCodec.encode(LlmRequest(original, listOf(valid), emptyList(), emptyList(), emptyList()))
        val invalid =
            terms.map { LlmRequestCodec.encode(LlmRequest(original, listOf(it), emptyList(), emptyList(), emptyList())) } +
                listOf(
                    encoded.replace("\"OVERALL_LENGTH\"", "\"LENGTH\""),
                    encoded.replace("\"metric\": \"OVERALL_LENGTH\"", "\"metric\": 1"),
                    encoded.replace("\"scope\": null", "\"scope\": \"ALL_SEATS\""),
                    encoded.replace("\"fitment\": null", "\"fitment\": \"NICE_TO_HAVE\""),
                    encoded.replace("\"measurementBasis\": null", "\"measurementBasis\": \"NEDC\""),
                )
        invalid.forEach { raw ->
            val error = assertFailsWith<OpenRouterWorkflowException> { LlmRequestCodec.decode(raw, original) }
            assertEquals(FailureKind.INVALID_RESPONSE, error.kind)
            assertEquals(raw, error.rawOutput)
        }
    }

    private fun example(
        name: String,
        original: String,
    ): LlmRequest {
        val raw = REQUEST_TRANSLATION_INSTRUCTIONS.substringAfter("Example $name:\n").substringBefore("\nEnd example.")
        return LlmRequestCodec.decode(raw, original)
    }

    @Test
    fun `round trip preserves original wording units exclusions preferences and unresolved phrases`() {
        assertEquals(request, LlmRequestCodec.decode(LlmRequestCodec.encode(request), original))
    }

    @Test
    fun `malformed missing extra unknown enum wrong scalar and changed source wording fail validation`() {
        val valid = LlmRequestCodec.encode(request)
        val invalid =
            listOf(
                "not JSON",
                "{}",
                valid.replace("\"ELECTRIC\"", "\"electric\""),
                valid.replace("\"Seven\"", "7"),
                valid.replace("\"SEATING_CAPACITY\"", "\"SEATS\""),
                valid.replace("\"originalRequest\":", "\"unexpected\": [], \"originalRequest\":"),
                LlmRequestCodec.encode(request.copy(originalRequest = original.trim())),
                LlmRequestCodec.encode(request.copy(unresolvedPhrases = listOf("invented phrase"))),
                LlmRequestCodec.encode(
                    request.copy(requirements = listOf(request.requirements.first().copy(sourceText = "invented constraint"))),
                ),
                LlmRequestCodec.encode(
                    request.copy(
                        requirements = emptyList(),
                        preferences = emptyList(),
                        requestedAttributes = emptyList(),
                        unresolvedPhrases = emptyList(),
                    ),
                ),
            )
        invalid.forEach { raw ->
            val error = assertFailsWith<OpenRouterWorkflowException> { LlmRequestCodec.decode(raw, original) }
            assertEquals(FailureKind.INVALID_RESPONSE, error.kind)
            assertEquals(raw, error.rawOutput)
        }
    }
}
