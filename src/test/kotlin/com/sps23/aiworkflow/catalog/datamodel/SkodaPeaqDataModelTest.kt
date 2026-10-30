package com.sps23.aiworkflow.catalog.datamodel

import com.sps23.aiworkflow.catalog.datamodel.DataModelFixtures.euros
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkodaPeaqDataModelTest {
    private val car = DataModelFixtures.load(DataModelFixtures.SKODA_PEAQ)

    @Test
    fun `identity keeps diacritics while the id is ASCII`() {
        assertEquals("skoda-peaq", car.id)
        assertEquals("Škoda", car.make)
        assertEquals("Peaq", car.model)
        assertEquals(YearMonth.of(2026, 8), car.effectivePeriod)
    }

    @Test
    fun `variants carry RRP grants and converted consumption`() {
        assertEquals(listOf("Selection", "Sportline"), car.trims.map { it.name })
        assertEquals(5, car.variants.size)
        assertTrue(car.variants.all { it.powertrainType == PowertrainType.ELECTRIC })

        val selection60 = car.variants.single { it.name == "Selection 60" }
        assertEquals("PZ7DF2", selection60.modelCode)
        assertEquals(63.0, selection60.batteryCapacityKwh)
        assertEquals(150, selection60.powerKw)
        assertEquals(NumericRange(452.0), selection60.wltpRangeKm)
        assertEquals(15.01, selection60.electricConsumptionKwhPer100Km)
        assertEquals(euros(54_996), selection60.price(PriceBasis.RRP)?.amount)
        assertEquals(euros(54_996), selection60.price(PriceBasis.RRP_FROM)?.amount)
        assertEquals(Money(0), selection60.price(PriceBasis.SEAI_GRANT)?.amount)
        assertEquals(Money(0), selection60.price(PriceBasis.VRT_REBATE)?.amount)

        val sportline90x = car.variants.single { it.name == "Sportline 90X" }
        assertEquals("Sportline", sportline90x.trimName)
        assertEquals(euros(63_050), sportline90x.price(PriceBasis.RRP)?.amount)
    }

    @Test
    fun `Sportline equipment is additions over Selection`() {
        val sportline = car.trim("Sportline")!!
        val group = sportline.standardEquipment.single()
        assertEquals("additions_over_selection", group.category)
        assertTrue(group.isAddition)
        assertEquals(8, group.items.size)
        assertEquals(5, car.trim("Selection")!!.standardEquipment.size)
    }

    @Test
    fun `per-trim option prices map to trim scopes`() {
        assertEquals(7, car.options.count { it.category == OptionCategory.WHEELS })
        assertEquals(3, car.options.count { it.category == OptionCategory.INTERIOR })
        assertEquals(6, car.options.count { it.category == OptionCategory.PACKAGE })
        assertEquals(11, car.options.count { it.category == OptionCategory.SINGLE_OPTION })
        assertEquals(10, car.colours.size)

        val techPack = car.option("P1B")!!
        assertEquals(4, techPack.items.size)
        assertEquals(FitmentState.OPTIONAL, techPack.availabilityFor("Selection")!!.state)
        assertEquals(euros(1_806), techPack.availabilityFor("Selection")!!.price?.amount)
        assertEquals(FitmentState.STANDARD, techPack.availabilityFor("Sportline")!!.state)
        assertNull(techPack.availabilityFor("Sportline")!!.price)

        val whale = car.option("V95")!!
        assertEquals(FitmentState.STANDARD, whale.availabilityFor("Selection")!!.state)
        assertEquals(FitmentState.NOT_AVAILABLE, whale.availabilityFor("Sportline")!!.state)

        val gharial = car.option("F55")!!.availabilityFor("Selection")!!
        assertEquals(FitmentState.OPTIONAL, gharial.state)
        assertEquals(Money(0), gharial.price?.amount)

        val velvetRed = car.option("K1K1")!!
        assertEquals("Exclusive", velvetRed.type)
        assertEquals(euros(1_182), velvetRed.availabilityFor("Sportline")!!.price?.amount)

        val suiteBlack = car.option("ZH")!!
        assertEquals(FitmentState.STANDARD, suiteBlack.availability.single().state)
        assertEquals("Selection", suiteBlack.availability.single().scope)
        assertEquals("Black leather & Leatherette", suiteBlack.additional["upholstery"])
    }

    @Test
    fun `technical specifications include battery and charging data`() {
        val dimensions = car.technicalSpecifications.dimensions
        assertEquals(NumericRange(4874.0), dimensions.lengthMm)
        assertEquals(NumericRange(890.0), dimensions.maxBootCapacityLitres)
        assertEquals(NumericRange(35.0), dimensions.frontTrunkCapacityLitres)
        assertEquals(emptyMap(), dimensions.additional)

        val powertrains = car.technicalSpecifications.powertrains
        assertEquals(listOf("60", "90", "90X"), powertrains.map { it.name })
        assertTrue(powertrains.all { it.type == PowertrainType.ELECTRIC })

        val ninety = powertrains.single { it.name == "90" }
        assertEquals("4x2", ninety.drivetrain)
        assertEquals(86.0, ninety.batteryCapacityKwh)
        assertEquals(91.0, ninety.batteryGrossKwh)
        assertEquals(545.0, ninety.torqueNm)
        assertEquals(NumericRange(630.0, 637.0), ninety.wltpRangeKm)
        assertEquals(199.0, ninety.dcMaxChargingKw)
        assertEquals(2909.0, ninety.grossVehicleWeightKg)
        assertEquals("9 hours", ninety.charging["wallbox_time_0_100_percent"])

        val ninetyX = powertrains.single { it.name == "90X" }
        assertEquals("4x4", ninetyX.drivetrain)
        assertNull(ninetyX.torqueNm)
        assertEquals("Front: 162 / Rear: 350", ninetyX.additional["max_torque_nm"])
        assertEquals(2000.0, ninetyX.brakedTowingKg)
    }
}
