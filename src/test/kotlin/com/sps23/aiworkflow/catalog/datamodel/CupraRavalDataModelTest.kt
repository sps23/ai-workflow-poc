package com.sps23.aiworkflow.catalog.datamodel

import com.sps23.aiworkflow.catalog.extraction.ExtractionRequirements
import com.sps23.aiworkflow.catalog.extraction.Severity
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CupraRavalDataModelTest {
    private val fixture = DataModelFixtures.frozen(DataModelFixtures.CUPRA_RAVAL)
    private val car = fixture.toModel()
    private val trims = listOf("Urban V1", "Urban V2", "Endurance V1 / V2", "VZ")

    @Test
    fun `fixture contains published identity and effective period`() {
        assertEquals("cupra-raval", car.id)
        assertEquals("cupra/20260820_CUPRA_Ireland_Product_Guide_Raval.json", car.sourcePath)
        assertEquals("CUPRA Raval", car.title)
        assertEquals("CUPRA", car.make)
        assertEquals("Raval", car.model)
        assertEquals("September 2026", car.effectiveDate)
        assertEquals(YearMonth.of(2026, 9), car.effectivePeriod)
        assertNull(car.sourcePdfPath)
        assertEquals(emptyMap(), car.additional)
    }

    @Test
    fun `all five variants retain current types prices and source emissions`() {
        assertEquals(trims, car.trims.map { it.name })
        assertEquals(
            listOf(
                variant("Raval Urban 37kWh 116hp", "Urban V1", 23_495, 28_810),
                variant("Raval Urban 37kWh 135hp", "Urban V2", 28_960, 34_645),
                variant("Raval Endurance 52kWh 211hp (V1)", "Endurance V1 / V2", 32_125, 38_020),
                variant("Raval Endurance 52kWh 211hp (V2)", "Endurance V1 / V2", 33_110, 39_070),
                variant("Raval VZ 52kWh 226hp", "VZ", 36_825, 43_035),
            ),
            car.variants,
        )
        assertTrue(car.variants.all { it.powertrainType == PowertrainType.UNKNOWN })
        assertTrue(car.variants.all { it.modelCode == null && it.additional.isEmpty() })
    }

    @Test
    fun `equipment preserves sections additions and trim specific features`() {
        val expectedSections =
            listOf(
                linkedMapOf("exterior" to 4, "interior_and_comfort" to 4, "multimedia" to 4),
                linkedMapOf("exterior_additions" to 2, "interior_and_technology_additions" to 6),
                linkedMapOf("exterior_additions" to 1),
                linkedMapOf("exterior_additions" to 1, "interior_and_technology_additions" to 3),
            )
        car.trims.forEachIndexed { index, trim ->
            assertEquals(expectedSections[index].keys.toList(), trim.standardEquipment.map { it.category }, trim.name)
            assertEquals(expectedSections[index].values.toList(), trim.standardEquipment.map { it.items.size }, trim.name)
            assertTrue(trim.standardEquipment.all { it.isAddition == (index > 0) }, trim.name)
            assertTrue(trim.allEquipmentItems.all(String::isNotBlank), trim.name)
        }
        assertTrue("Full LED Headlamps" in car.trim("Urban V1")!!.allEquipmentItems)
        assertTrue("Vehicle-To-Load (V2L)" in car.trim("Urban V2")!!.allEquipmentItems)
        assertTrue("18\" 'DEFIER' Alloy Wheel in Matt Black" in car.trim("Endurance V1 / V2")!!.allEquipmentItems)
        assertTrue("Dynamic Chassis Control (DCC)" in car.trim("VZ")!!.allEquipmentItems)
    }

    @Test
    fun `latest fixture has no normalized options or colours`() {
        assertTrue(car.options.isEmpty())
        assertTrue(car.colours.isEmpty())
    }

    @Test
    fun `dimensions preserve measurements and retain unmapped boot capacity`() {
        assertEquals(
            Dimensions(
                lengthMm = NumericRange(4046.0),
                widthMm = NumericRange(1784.0),
                heightMm = NumericRange(1514.0),
                wheelbaseMm = NumericRange(2599.0),
                bootCapacityLitres = null,
                maxBootCapacityLitres = null,
                frontTrunkCapacityLitres = null,
                brakedTowingKg = null,
                additional = mapOf("boot_capacity_l" to "441"),
            ),
            car.technicalSpecifications.dimensions,
        )
    }

    @Test
    fun `technical powertrains preserve typed electric specifications`() {
        val powertrains = car.technicalSpecifications.powertrains
        assertEquals(listOf("Urban 37kWh 116hp", "Endurance 52kWh 211hp"), powertrains.map { it.name })
        assertTrue(powertrains.all { it.type == PowertrainType.ELECTRIC })
        assertEquals(listOf(85.0, 155.0), powertrains.map { it.powerKw })
        assertEquals(listOf(116.0, 211.0), powertrains.map { it.powerHp })
        assertEquals(listOf(264.0, 290.0), powertrains.map { it.torqueNm })
        assertEquals(listOf(160.0, 160.0), powertrains.map { it.topSpeedKmh })
        assertEquals(listOf(NumericRange(10.7), NumericRange(7.1)), powertrains.map { it.acceleration0To100S })
        assertEquals(listOf(NumericRange(311.0), NumericRange(443.0)), powertrains.map { it.wltpRangeKm })
        assertEquals(listOf(37.0, 52.0), powertrains.map { it.batteryCapacityKwh })
        assertTrue(powertrains.all { it.electricConsumptionKwhPer100Km == null && it.charging.isEmpty() })
        assertEquals(emptyList(), powertrains.map { it.trimsAvailable }.flatten())
        assertEquals(emptyMap(), car.technicalSpecifications.additional)
    }

    @Test
    fun `full fixture matches current source and satisfies catalog generation rules`() {
        assertEquals(DataModelFixtures.load(DataModelFixtures.CUPRA_RAVAL), car)
        val violations = ExtractionRequirements.validateVehicle(fixture.vehicle).filter { it.severity == Severity.HARD }
        assertTrue(violations.isEmpty(), violations.joinToString("\n") { "${it.rule}: ${it.message}" })
        val artifact =
            DataModelCatalogArtifact.from(
                DataModelCatalog(listOf(car.copy(sourcePdfPath = "cupra/20260820_CUPRA_Ireland_Product_Guide_Raval.pdf"))),
            )
        val encoded = DataModelFixtures.json.encodeToString(DataModelCatalogArtifact.serializer(), artifact)
        assertEquals(artifact, DataModelFixtures.json.decodeFromString(DataModelCatalogArtifact.serializer(), encoded))
    }

    private fun variant(
        name: String,
        trim: String,
        price: Long,
        rrpExcludingGrants: Long,
    ): TrimVariant =
        TrimVariant(
            name = name,
            trimName = trim,
            modelCode = null,
            powertrainType = PowertrainType.UNKNOWN,
            batteryCapacityKwh = null,
            powerHp = null,
            powerKw = null,
            co2GPerKm = NumericRange(0.0),
            wltpRangeKm = null,
            electricConsumptionKwhPer100Km = null,
            taxBand = null,
            vrtRatePercent = null,
            prices =
                listOf(
                    price(PriceBasis.PRICE, price),
                    price(PriceBasis.RRP_EXCLUDING_GRANTS, rrpExcludingGrants),
                    price(PriceBasis.ANNUAL_ROAD_TAX, 120),
                ),
        )

    private fun euros(amount: Long): Money = Money(amount * 100)

    private fun price(
        basis: PriceBasis,
        amount: Long,
    ): PriceFact = PriceFact(basis, euros(amount), rawText = "€" + "%,d".format(java.util.Locale.US, amount))
}
