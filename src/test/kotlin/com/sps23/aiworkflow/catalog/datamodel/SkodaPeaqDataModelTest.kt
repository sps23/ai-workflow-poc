package com.sps23.aiworkflow.catalog.datamodel

import com.sps23.aiworkflow.catalog.extraction.ExtractionRequirements
import com.sps23.aiworkflow.catalog.extraction.Severity
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkodaPeaqDataModelTest {
    private val fixture = DataModelFixtures.frozen(DataModelFixtures.SKODA_PEAQ)
    private val car = fixture.toModel()
    private val trims = listOf("Selection", "Sportline")

    @Test
    fun `fixture contains the published identity and effective period`() {
        assertEquals("skoda-peaq", car.id)
        assertEquals("skoda/Peaq_MY27_PG_August_CW48_2026.json", car.sourcePath)
        assertEquals("Škoda Peaq", car.title)
        assertEquals("Škoda", car.make)
        assertEquals("Peaq", car.model)
        assertEquals("August 2026", car.effectiveDate)
        assertEquals(YearMonth.of(2026, 8), car.effectivePeriod)
        assertNull(car.sourcePdfPath)
        assertEquals(emptyMap(), car.additional)
    }

    @Test
    fun `all five variants retain model codes ranges prices grants and consumption text`() {
        assertEquals(trims, car.trims.map { it.name })
        assertEquals(
            listOf(
                variant("60", "Selection", "PZ7DF2", 63.0, 150, 452.0, 54_996, "150.1 Wh/km"),
                variant("90", "Selection", "PZ7DP2", 91.0, 210, 637.0, 58_150, "152.3 Wh/km"),
                variant("90X", "Selection", "PZ7DT4", 91.0, 220, 602.0, 59_650, "161.4 Wh/km"),
                variant("90", "Sportline", "PZ7RP2", 91.0, 210, 630.0, 61_550, "154.3 Wh/km"),
                variant("90X", "Sportline", "PZ7RT4", 91.0, 220, 596.0, 63_050, "163.1 Wh/km"),
            ),
            car.variants,
        )
        assertTrue(car.variants.all { it.powertrainType == PowertrainType.ELECTRIC })
        assertTrue(car.variants.all { it.electricConsumptionKwhPer100Km == null })
    }

    @Test
    fun `equipment preserves sections and trim specific standard items`() {
        val expectedSections =
            listOf(
                linkedMapOf("safety" to 12, "functional" to 21, "interior" to 11, "exterior" to 6),
                linkedMapOf("safety" to 12, "functional" to 21, "interior" to 16, "exterior" to 9),
            )
        car.trims.forEachIndexed { index, trim ->
            assertEquals(expectedSections[index].keys.toList(), trim.standardEquipment.map { it.category }, trim.name)
            assertEquals(expectedSections[index].values.toList(), trim.standardEquipment.map { it.items.size }, trim.name)
            assertTrue(trim.standardEquipment.none { it.isAddition }, trim.name)
            assertTrue(trim.allEquipmentItems.all(String::isNotBlank), trim.name)
        }
        assertTrue("Rear view camera" in car.trim("Selection")!!.allEquipmentItems)
        assertTrue("Area view 360 degree camera" in car.trim("Sportline")!!.allEquipmentItems)
        assertTrue("‘Suite’ design selection upholstery" in car.trim("Selection")!!.allEquipmentItems)
        assertTrue("Suedia & leatherette upholstery" in car.trim("Sportline")!!.allEquipmentItems)
    }

    @Test
    fun `options retain categories contents trim fitment and prices`() {
        assertEquals(
            listOf(
                "V95",
                "F55",
                "PV1",
                "PV2",
                "CM5",
                "PW1",
                "PW2",
                "ZH",
                "YE",
                "YM",
                "P1B",
                "P2C",
                "P1D",
                "P2D",
                "P1F",
                "P1E",
                "3FP",
                "W2B",
                "3CX",
                "1M6",
                "PHC",
                "PFD",
                "7AL",
                "PA2",
                "6NW",
                "EA9",
                "K4K4",
                "M3M3",
                null,
                null,
                null,
            ),
            car.options.map { it.code },
        )
        assertEquals(
            mapOf(
                OptionCategory.WHEELS to 7,
                OptionCategory.INTERIOR to 3,
                OptionCategory.PACKAGE to 6,
                OptionCategory.SINGLE_OPTION to 10,
                OptionCategory.PAINT to 4,
                OptionCategory.ACCESSORY_PACK to 1,
            ),
            car.options.groupingBy { it.category }.eachCount(),
        )
        assertEquals(4, car.colours.size)

        val whale = assertNotNull(car.option("V95"))
        assertEquals("19” ‘Whale’ alloy wheels", whale.name)
        assertEquals(FitmentState.STANDARD, whale.availabilityFor("Selection")!!.state)
        assertEquals(FitmentState.NOT_AVAILABLE, whale.availabilityFor("Sportline")!!.state)

        val gharial = assertNotNull(car.option("F55"))
        assertEquals(FitmentState.OPTIONAL, gharial.availabilityFor("Selection")!!.state)
        assertEquals(Money(0), gharial.availabilityFor("Selection")!!.price?.amount)

        val techPack = assertNotNull(car.option("P1B"))
        assertEquals(
            listOf("Matrix LED headlights", "LED taillights incl. dynamic indicators", "Crystal face 2.0", "Area view 360-degree camera"),
            techPack.items,
        )
        assertEquals(euros(1_806), techPack.availabilityFor("Selection")!!.price?.amount)
        assertEquals(FitmentState.STANDARD, techPack.availabilityFor("Sportline")!!.state)

        val chassisControl = assertNotNull(car.option("PFD"))
        assertEquals(euros(1_205), chassisControl.availabilityFor("Selection")!!.price?.amount)
        assertEquals(euros(944), chassisControl.availabilityFor("Sportline")!!.price?.amount)
        assertEquals(FitmentState.STANDARD, car.option("PA2")!!.availabilityFor("Sportline")!!.state)

        val evPlan = car.options.single { it.category == OptionCategory.ACCESSORY_PACK }
        assertEquals("Škoda EV Plan", evPlan.name)
        assertEquals("All Trims", evPlan.availability.single().scope)
        assertEquals(
            euros(1_250),
            evPlan.availability
                .single()
                .price
                ?.amount,
        )
        assertEquals("39%", evPlan.additional["saving"])
    }

    @Test
    fun `dimensions preserve published measurements and capacities`() {
        assertEquals(
            Dimensions(
                lengthMm = NumericRange(4874.0),
                widthMm = NumericRange(1867.0),
                heightMm = NumericRange(1674.0),
                wheelbaseMm = NumericRange(2970.0),
                bootCapacityLitres = null,
                maxBootCapacityLitres = NumericRange(890.0),
                frontTrunkCapacityLitres = NumericRange(35.0),
                brakedTowingKg = NumericRange(1800.0, 2000.0),
                additional =
                    mapOf(
                        "width_with_mirrors_mm" to "2119",
                        "height_with_roof_rails_mm" to "1664",
                        "front_overhang_mm" to "897",
                        "rear_overhang_mm" to "1012",
                        "front_track_mm" to "1587",
                        "rear_track_mm" to "1557",
                    ),
            ),
            car.technicalSpecifications.dimensions,
        )
    }

    @Test
    fun `technical powertrains preserve range battery charging and towing details`() {
        val powertrains = car.technicalSpecifications.powertrains
        assertEquals(listOf("60", "90", "90X"), powertrains.map { it.name })
        assertTrue(powertrains.all { it.type == PowertrainType.ELECTRIC })
        assertEquals(listOf("4x2", "4x2", "4x4"), powertrains.map { it.drivetrain })
        assertEquals(listOf(150.0, 210.0, 220.0), powertrains.map { it.powerKw })
        assertEquals(listOf(350.0, 545.0, null), powertrains.map { it.torqueNm })
        assertEquals(listOf(160.0, 180.0, 180.0), powertrains.map { it.topSpeedKmh })
        assertEquals(listOf(63.0, 91.0, 91.0), powertrains.map { it.batteryGrossKwh })
        assertEquals(listOf(59.0, 86.0, 77.0), powertrains.map { it.batteryCapacityKwh })
        assertEquals(
            listOf(NumericRange(452.0), NumericRange(630.0, 637.0), NumericRange(596.0, 602.0)),
            powertrains.map { it.wltpRangeKm },
        )
        assertEquals(listOf(160.0, 199.0, 199.0), powertrains.map { it.dcMaxChargingKw })
        assertEquals(listOf(1800.0, 1800.0, 2000.0), powertrains.map { it.brakedTowingKg })
        assertEquals("6hr 30 min", powertrains.first().charging["wallbox_3_phase_charging_time_0_100"])
        assertEquals("9 hours", powertrains[1].charging["wallbox_3_phase_charging_time_0_100"])
        assertEquals("162", powertrains.last().additional["max_torque_front_nm"])
        assertEquals("350", powertrains.last().additional["max_torque_rear_nm"])
        assertEquals(listOf(listOf("Selection"), trims, trims), powertrains.map { it.trimsAvailable })
        assertEquals(emptyMap(), car.technicalSpecifications.additional)
    }

    @Test
    fun `full fixture matches current source and satisfies catalog generation rules`() {
        assertEquals(DataModelFixtures.load(DataModelFixtures.SKODA_PEAQ), car)
        val violations = ExtractionRequirements.validateVehicle(fixture.vehicle).filter { it.severity == Severity.HARD }
        assertTrue(violations.isEmpty(), violations.joinToString("\n") { "${it.rule}: ${it.message}" })
        val artifact =
            DataModelCatalogArtifact.from(
                DataModelCatalog(listOf(car.copy(sourcePdfPath = "skoda/Peaq_MY27_PG_August_CW48_2026.pdf"))),
            )
        val encoded = DataModelFixtures.json.encodeToString(DataModelCatalogArtifact.serializer(), artifact)
        assertEquals(artifact, DataModelFixtures.json.decodeFromString(DataModelCatalogArtifact.serializer(), encoded))
    }

    private fun variant(
        name: String,
        trim: String,
        modelCode: String,
        batteryCapacityKwh: Double,
        powerKw: Int,
        rangeKm: Double,
        rrp: Long,
        consumption: String,
    ): TrimVariant =
        TrimVariant(
            name = name,
            trimName = trim,
            modelCode = modelCode,
            powertrainType = PowertrainType.ELECTRIC,
            batteryCapacityKwh = batteryCapacityKwh,
            powerHp = null,
            powerKw = powerKw,
            co2GPerKm = null,
            wltpRangeKm = NumericRange(rangeKm),
            electricConsumptionKwhPer100Km = null,
            taxBand = null,
            vrtRatePercent = null,
            prices =
                listOf(
                    price(PriceBasis.RRP, rrp),
                    price(PriceBasis.RRP_FROM, rrp),
                    price(PriceBasis.ANNUAL_ROAD_TAX, 120),
                    price(PriceBasis.VRT_REBATE, 0),
                    price(PriceBasis.SEAI_GRANT, 0),
                ),
            additional = mapOf("consumption_wltp" to consumption),
        )

    private fun euros(amount: Long): Money = Money(amount * 100)

    private fun price(
        basis: PriceBasis,
        amount: Long,
    ): PriceFact = PriceFact(basis, euros(amount), rawText = "€" + "%,d".format(java.util.Locale.US, amount))
}
