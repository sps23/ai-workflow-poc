package com.sps23.aiworkflow.catalog.datamodel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CarDataModelParserTest {
    @Test
    fun `money is parsed to minor units including ranges and no cost`() {
        assertEquals(Money(2_349_500) to null, DataModelValues.parseMoney("€23,495"))
        assertEquals(Money(1_999) to null, DataModelValues.parseMoney("€19.99"))
        assertEquals(Money(20_000) to Money(21_000), DataModelValues.parseMoney("€200 - €210"))
        assertEquals(Money(0) to null, DataModelValues.parseMoney("No Cost"))
        assertNull(DataModelValues.parseMoney("€19.99 / month"))
        assertNull(DataModelValues.parseMoney("Standard"))
    }

    @Test
    fun `numeric ranges distinguish lossless and qualified values`() {
        assertEquals(ParsedRange(NumericRange(413.0, 446.0), true), DataModelValues.parseRange("446 - 413"))
        assertEquals(ParsedRange(NumericRange(0.0), true), DataModelValues.parseRange("0-0"))
        assertEquals(ParsedRange(NumericRange(13.5, 14.5), true), DataModelValues.parseRange("13.5 - 14.5 kWh/100km"))
        assertEquals(ParsedRange(NumericRange(7.0), true), DataModelValues.parseRange("7.00%"))
        assertEquals(
            ParsedRange(NumericRange(4460.0, 4465.0), false),
            DataModelValues.parseRange("4460 (1.8 HEV) / 4465 (2.0 HEV)"),
        )
        assertNull(DataModelValues.parseRange("Front: 162 / Rear: 350"))
    }

    @Test
    fun `fitment text maps to fitment states with notes`() {
        assertEquals(FitmentState.STANDARD to "Selection", DataModelValues.fitment("STANDARD (Selection)"))
        assertEquals(FitmentState.OPTIONAL to "Bi-Colour Roof", DataModelValues.fitment("Optional (Bi-Colour Roof)"))
        assertEquals(FitmentState.NOT_AVAILABLE to null, DataModelValues.fitment("N/A"))
        assertEquals(FitmentState.OPTIONAL to null, DataModelValues.fitment("€599"))
        assertEquals(FitmentState.UNKNOWN to null, DataModelValues.fitment("Ask dealer"))
    }

    @Test
    fun `powertrain type is inferred from published wording`() {
        assertEquals(PowertrainType.HYBRID, DataModelValues.inferPowertrain("1.8 HEV FWD"))
        assertEquals(PowertrainType.PLUG_IN_HYBRID, DataModelValues.inferPowertrain("2.5 PHEV AWD"))
        assertEquals(PowertrainType.ELECTRIC, DataModelValues.inferPowertrain("Electric 210 (73 kWh)"))
        assertEquals(PowertrainType.HYBRID, DataModelValues.inferPowertrain("Hybrid 145 AUTO"))
        assertEquals(PowertrainType.UNKNOWN, DataModelValues.inferPowertrain("Selection 60"))
    }

    @Test
    fun `unknown fields are preserved as raw attributes`() {
        val car =
            CarDataModelParser.parse(
                """
                {
                  "model": "Acme Roadster",
                  "effective_date": "Spring 2026",
                  "launch_notes": "pending",
                  "trims": [{
                    "trim_level": "Base",
                    "pricing": [{"powertrain": "1.5 Petrol", "price": "€30,000", "doors": 3}],
                    "standard_equipment": {"comfort": ["Air conditioning"]},
                    "badge": "B"
                  }]
                }
                """.trimIndent(),
                "acme.json",
            )

        assertEquals("acme-roadster", car.id)
        assertNull(car.effectivePeriod)
        assertEquals("Spring 2026", car.effectiveDate)
        assertEquals(mapOf("launch_notes" to "pending"), car.additional)
        assertEquals(mapOf("badge" to "B"), car.trims.single().additional)
        val variant = car.variants.single()
        assertEquals(PowertrainType.PETROL, variant.powertrainType)
        assertEquals(mapOf("doors" to "3"), variant.additional)
        assertTrue(car.options.isEmpty())
    }

    @Test
    fun `invalid input reports the source`() {
        val invalid = assertFailsWith<DataModelParseException> { CarDataModelParser.parse("{", "broken.json") }
        assertTrue(invalid.message!!.startsWith("broken.json"))

        val missingModel = assertFailsWith<DataModelParseException> { CarDataModelParser.parse("""{"trims": []}""", "nomodel.json") }
        assertTrue("model" in missingModel.message!!)
        assertTrue(missingModel.message!!.startsWith("nomodel.json"))
    }
}
