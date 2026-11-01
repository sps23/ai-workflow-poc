package com.sps23.aiworkflow.catalog.extraction

import com.sps23.aiworkflow.catalog.datamodel.Dimensions
import com.sps23.aiworkflow.catalog.datamodel.EquipmentGroup
import com.sps23.aiworkflow.catalog.datamodel.FitmentState
import com.sps23.aiworkflow.catalog.datamodel.Money
import com.sps23.aiworkflow.catalog.datamodel.NumericRange
import com.sps23.aiworkflow.catalog.datamodel.OptionAvailability
import com.sps23.aiworkflow.catalog.datamodel.OptionCategory
import com.sps23.aiworkflow.catalog.datamodel.PowertrainType
import com.sps23.aiworkflow.catalog.datamodel.PriceBasis
import com.sps23.aiworkflow.catalog.datamodel.PriceFact
import com.sps23.aiworkflow.catalog.datamodel.TechnicalSpecifications
import com.sps23.aiworkflow.catalog.datamodel.Trim
import com.sps23.aiworkflow.catalog.datamodel.TrimVariant
import com.sps23.aiworkflow.catalog.datamodel.VehicleOption
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExtractionRequirementsTest {
    private val json = Json { encodeDefaults = true }

    private fun variant(
        trim: String,
        name: String = "TCe 110",
        price: PriceFact = PriceFact(PriceBasis.PRICE, Money(2_699_000), rawText = "€26,990"),
        type: PowertrainType = PowertrainType.PETROL,
    ) = TrimVariant(
        name = name,
        trimName = trim,
        modelCode = null,
        powertrainType = type,
        batteryCapacityKwh = null,
        powerHp = 110,
        powerKw = null,
        co2GPerKm = NumericRange(135.0),
        wltpRangeKm = null,
        electricConsumptionKwhPer100Km = null,
        taxBand = null,
        vrtRatePercent = null,
        prices = listOf(price),
    )

    private fun trim(
        name: String,
        vararg variants: TrimVariant = arrayOf(variant(name)),
    ) = Trim(name, variants.toList(), listOf(EquipmentGroup("safety", false, listOf("ABS", "ESC"))))

    private val valid =
        ExtractedVehicle(
            title = "Dacia Jogger",
            make = "Dacia",
            model = "Jogger",
            effectiveDate = "August 2026",
            effectivePeriod = "2026-08",
            trims = listOf(trim("Essential"), trim("Extreme")),
            options =
                listOf(
                    VehicleOption(
                        code = null,
                        name = "Metallic paint",
                        category = OptionCategory.PAINT,
                        categoryLabel = "Paint",
                        availability =
                            listOf(
                                OptionAvailability(
                                    "Essential",
                                    FitmentState.OPTIONAL,
                                    PriceFact(PriceBasis.OPTION, Money(60_000), rawText = "€600"),
                                ),
                            ),
                    ),
                ),
            technicalSpecifications =
                TechnicalSpecifications(
                    Dimensions(NumericRange(4547.0), NumericRange(1784.0), null, null, NumericRange(160.0), null, null, null),
                    emptyList(),
                ),
        )

    private fun encode(vehicle: ExtractedVehicle): String = json.encodeToString(ExtractedVehicle.serializer(), vehicle)

    private fun validate(
        vehicle: ExtractedVehicle,
        reference: ExtractedVehicle? = valid,
    ) = ExtractionRequirements.validate(encode(vehicle), "STOP", reference?.toCarDataModel("ref.json"))

    private fun rules(report: ValidationReport): Set<String> = report.violations.map { it.rule }.toSet()

    @Test
    fun `valid extraction has no violations`() {
        val report = validate(valid)
        assertEquals(emptyList(), report.violations)
        assertTrue(report.passed)
        assertEquals(valid, report.vehicle)
    }

    @Test
    fun `R1 truncated response is a hard violation`() {
        val report = ExtractionRequirements.validate(encode(valid), "MAX_TOKENS")
        assertEquals(setOf("R1"), rules(report))
        assertFalse(report.passed)
    }

    @Test
    fun `R2 fenced JSON is rejected`() {
        val report = ExtractionRequirements.validate("```json\n${encode(valid)}\n```", "STOP")
        assertEquals(setOf("R2"), rules(report))
        assertNull(report.vehicle)
    }

    @Test
    fun `R2 trailing prose is rejected`() {
        assertEquals(setOf("R2"), rules(ExtractionRequirements.validate(encode(valid) + "\nDone.", "STOP")))
    }

    @Test
    fun `R2 BOM and surrounding whitespace are tolerated`() {
        assertTrue(ExtractionRequirements.validate("\uFEFF  ${encode(valid)}\n", "STOP").passed)
    }

    @Test
    fun `R3 unknown enum value fails strict decode`() {
        val text = encode(valid).replace("\"PETROL\"", "\"GASOLINE\"")
        assertEquals(setOf("R3"), rules(ExtractionRequirements.validate(text, "STOP")))
    }

    @Test
    fun `R3 unknown key fails strict decode`() {
        val text = encode(valid).replaceFirst("{", "{\"colour\":\"red\",")
        assertEquals(setOf("R3"), rules(ExtractionRequirements.validate(text, "STOP")))
    }

    @Test
    fun `R3 missing required field fails strict decode`() {
        val root = Json.parseToJsonElement(encode(valid)).jsonObject
        val text = JsonObject(root - "trims").toString()
        assertEquals(setOf("R3"), rules(ExtractionRequirements.validate(text, "STOP")))
    }

    @Test
    fun `R4 forbidden identity keys are reported but the rest is still validated`() {
        val root = Json.parseToJsonElement(encode(valid)).jsonObject
        val text = JsonObject(root + ("id" to JsonPrimitive("x")) + ("sourcePdfPath" to JsonPrimitive("a.pdf"))).toString()
        val report = ExtractionRequirements.validate(text, "STOP")
        assertEquals(setOf("R4"), rules(report))
        assertEquals(valid, report.vehicle)
    }

    @Test
    fun `R5 blank or wrong make`() {
        assertEquals(setOf("R5"), rules(validate(valid.copy(model = " "))))
        assertEquals(setOf("R5"), rules(validate(valid.copy(make = "Renault"))))
    }

    @Test
    fun `R6 effective period must be YYYY-MM`() {
        assertEquals(setOf("R6"), rules(validate(valid.copy(effectivePeriod = "August 2026"))))
        assertEquals(setOf("R6"), rules(validate(valid.copy(effectivePeriod = "2026-13"))))
        assertTrue(validate(valid.copy(effectivePeriod = null)).passed)
    }

    @Test
    fun `R7 trims must exist, be unique and have variants`() {
        assertTrue("R7" in rules(validate(valid.copy(trims = emptyList()), reference = null)))
        assertEquals(setOf("R7"), rules(validate(valid.copy(trims = valid.trims + trim("essential ")), reference = null)))
        assertEquals(
            setOf("R7"),
            rules(validate(valid.copy(trims = listOf(trim("Essential"), Trim("Extreme", emptyList(), emptyList()))), reference = null)),
        )
    }

    @Test
    fun `R8 variant must point at its parent trim and have prices`() {
        assertEquals(setOf("R8"), rules(validate(valid.copy(trims = listOf(trim("Essential", variant("Extreme")), trim("Extreme"))))))
        val noPrices = variant("Extreme").copy(prices = emptyList())
        assertEquals(setOf("R8", "R15"), rules(validate(valid.copy(trims = listOf(trim("Essential"), trim("Extreme", noPrices))))))
    }

    @Test
    fun `R9 price facts need raw text, ISO currency and ordered ranges`() {
        fun with(price: PriceFact) = valid.copy(trims = listOf(trim("Essential"), trim("Extreme", variant("Extreme", price = price))))
        assertEquals(setOf("R9"), rules(validate(with(PriceFact(PriceBasis.PRICE, Money(2_699_000), rawText = " ")))))
        assertEquals(setOf("R9"), rules(validate(with(PriceFact(PriceBasis.PRICE, Money(2_699_000, "€"), rawText = "€26,990")))))
        assertEquals(
            setOf("R9"),
            rules(validate(with(PriceFact(PriceBasis.PRICE, Money(2_699_000), Money(2_600_000), "€26,990 - €26,000")))),
        )
        assertTrue(validate(with(PriceFact(PriceBasis.PRICE, null, rawText = "POA")), reference = null).passed)
    }

    @Test
    fun `R9 accepts zero-value grants and options but rejects negative amounts`() {
        val zeroGrant = PriceFact(PriceBasis.VRT_REBATE, Money(0), rawText = "€0")
        val zeroOption = PriceFact(PriceBasis.OPTION, Money(0), rawText = "€0")
        val pricedVariant =
            variant("Essential").copy(
                prices =
                    variant("Essential").prices +
                        zeroGrant +
                        PriceFact(PriceBasis.SEAI_GRANT, Money(0), rawText = "€0"),
            )
        val zeroPricedOption =
            valid.options.single().copy(
                name = "Zero-priced option",
                availability =
                    listOf(
                        OptionAvailability("Essential", FitmentState.OPTIONAL, zeroOption),
                    ),
            )
        val withZeroAmounts =
            valid.copy(
                trims = listOf(trim("Essential", pricedVariant), trim("Extreme")),
                options = valid.options + zeroPricedOption,
            )
        assertTrue(validate(withZeroAmounts, reference = null).passed)

        assertEquals(
            setOf("R9"),
            rules(
                validate(
                    withZeroAmounts.copy(
                        trims =
                            listOf(
                                trim(
                                    "Essential",
                                    pricedVariant.copy(
                                        prices = pricedVariant.prices + PriceFact(PriceBasis.VRT_REBATE, Money(-1), rawText = "-€0.01"),
                                    ),
                                ),
                                trim("Extreme"),
                            ),
                    ),
                    reference = null,
                ),
            ),
        )
    }

    @Test
    fun `R10 price without cents conversion is out of plausible range`() {
        val euros = PriceFact(PriceBasis.PRICE, Money(26_990), rawText = "€26,990")
        val report =
            validate(valid.copy(trims = listOf(trim("Essential", variant("Essential", price = euros)), trim("Extreme"))), reference = null)
        assertEquals(setOf("R10"), rules(report))
    }

    @Test
    fun `R11 ranges must be ordered and dimensions plausible`() {
        val bad = valid.technicalSpecifications.dimensions.copy(lengthMm = NumericRange(45_470.0))
        assertEquals(
            setOf("R11"),
            rules(validate(valid.copy(technicalSpecifications = TechnicalSpecifications(bad, emptyList())), reference = null)),
        )
        val inverted = variant("Essential").copy(co2GPerKm = NumericRange(140.0, 130.0))
        assertEquals(setOf("R11"), rules(validate(valid.copy(trims = listOf(trim("Essential", inverted), trim("Extreme"))))))
    }

    @Test
    fun `R12 options need names and scopes`() {
        val option = valid.options.single()
        val blankScope = option.copy(availability = listOf(OptionAvailability(" ", FitmentState.STANDARD)))
        assertEquals(setOf("R12"), rules(validate(valid.copy(options = listOf(blankScope)))))
        assertEquals(setOf("R12"), rules(validate(valid.copy(options = listOf(option.copy(name = ""))))))
    }

    @Test
    fun `R14 trim names must match reference`() {
        val reference = valid.copy(trims = valid.trims + trim("Expression"))
        val report = validate(valid, reference)
        assertTrue("R14" in rules(report))
        assertFalse(report.passed)
    }

    @Test
    fun `R14 trim matching ignores case and whitespace`() {
        val reference = valid.copy(trims = listOf(trim("ESSENTIAL"), trim(" Extreme ")))
        assertTrue(validate(valid, reference).passed)
    }

    @Test
    fun `R15 lowest reference price must appear in the extracted trim`() {
        val cheaper = PriceFact(PriceBasis.PRICE, Money(2_599_000), rawText = "€25,990")
        val reference = valid.copy(trims = listOf(trim("Essential", variant("Essential", price = cheaper)), trim("Extreme")))
        assertEquals(setOf("R15"), rules(validate(valid, reference)))
    }

    @Test
    fun `R15 accepts the reference minimum as an extracted maxAmount`() {
        val range = PriceFact(PriceBasis.PRICE, Money(2_500_000), Money(2_699_000), "€25,000 - €26,990")
        val extracted = valid.copy(trims = listOf(trim("Essential", variant("Essential", price = range)), trim("Extreme")))
        assertTrue(validate(extracted).passed)
    }

    @Test
    fun `R16 and R17 deviations are soft and do not fail`() {
        val hybrid = variant("Extreme", name = "HEV 155", type = PowertrainType.HYBRID)
        val longer = valid.technicalSpecifications.dimensions.copy(lengthMm = NumericRange(4550.0))
        val extracted =
            valid.copy(
                trims = listOf(trim("Essential"), trim("Extreme", variant("Extreme"), hybrid)),
                options = emptyList(),
                technicalSpecifications = TechnicalSpecifications(longer, emptyList()),
            )
        val report = validate(extracted)
        assertEquals(setOf("R16", "R17"), rules(report))
        assertTrue(report.violations.all { it.severity == Severity.SOFT })
        assertTrue(report.passed)
    }

    @Test
    fun `R18 variant names must be unique within a trim`() {
        val twin = variant("Expression", price = PriceFact(PriceBasis.PRICE, Money(2_849_000), rawText = "€28,490"))
        val expression = trim("Expression", variant("Expression"), twin)
        val report = validate(valid.copy(trims = valid.trims + expression), reference = null)
        assertEquals(setOf("R18"), rules(report))
        val renamed = trim("Expression", variant("Expression", name = "TCe 110 5 seats"), twin.copy(name = "TCe 110 7 seats"))
        assertTrue(validate(valid.copy(trims = valid.trims + renamed), reference = null).passed)
    }

    @Test
    fun `mapping computes identity and effective period`() {
        val car = valid.toCarDataModel("dacia/x.json", "dacia/x.pdf")
        assertEquals("dacia-jogger", car.id)
        assertEquals(YearMonth.of(2026, 8), car.effectivePeriod)
        assertEquals("dacia/x.pdf", car.sourcePdfPath)
        assertEquals(YearMonth.of(2026, 8), valid.copy(effectivePeriod = null).toCarDataModel("x").effectivePeriod)
    }

    @Test
    fun `model definition example is itself a valid extraction`() {
        val definition = PdfExtractionPrompts.modelDefinition()
        val example = definition.substring(definition.indexOf("{", definition.indexOf("## Example")), definition.lastIndexOf("}") + 1)
        val report = ExtractionRequirements.validate(example, "STOP")
        assertEquals(emptyList(), report.violations)
    }

    @Test
    fun `prompts load and user prompt carries the model definition`() {
        assertTrue(PdfExtractionPrompts.systemPrompt().contains("minorUnits"))
        val user = PdfExtractionPrompts.userPrompt()
        assertTrue(user.startsWith("# ExtractedVehicle model definition"))
        assertTrue(user.trimEnd().endsWith("Return only the JSON object."))
    }
}
