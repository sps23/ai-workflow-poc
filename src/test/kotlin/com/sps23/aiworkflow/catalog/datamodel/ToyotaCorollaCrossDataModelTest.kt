package com.sps23.aiworkflow.catalog.datamodel

import com.sps23.aiworkflow.catalog.datamodel.DataModelFixtures.euros
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToyotaCorollaCrossDataModelTest {
    private val car = DataModelFixtures.load(DataModelFixtures.TOYOTA_COROLLA_CROSS)

    @Test
    fun `identity and effective period are parsed`() {
        assertEquals("toyota-corolla-cross", car.id)
        assertEquals("Toyota", car.make)
        assertEquals("Corolla Cross", car.model)
        assertEquals(YearMonth.of(2026, 2), car.effectivePeriod)
    }

    @Test
    fun `missing prices keep the source wording`() {
        assertEquals(listOf("Luna", "Sport", "Sol", "GR SPORT"), car.trims.map { it.name })
        assertEquals(4, car.variants.size)
        assertTrue(car.variants.all { it.powertrainType == PowertrainType.HYBRID })

        val luna = car.trim("Luna")!!.variants.single()
        assertEquals("1.8 HEV FWD", luna.name)
        assertEquals(NumericRange(112.0), luna.co2GPerKm)
        val price = luna.price(PriceBasis.PRICE)!!
        assertNull(price.amount)
        assertEquals("Not explicitly stated in brochure", price.rawText)
        assertEquals(euros(190), luna.price(PriceBasis.ANNUAL_ROAD_TAX)?.amount)

        assertEquals(
            "2.0 HEV FWD",
            car
                .trim("GR SPORT")!!
                .variants
                .single()
                .name,
        )
    }

    @Test
    fun `equipment additions are flagged per trim`() {
        val luna = car.trim("Luna")!!
        assertEquals(7, luna.standardEquipment.size)
        assertTrue(luna.standardEquipment.none { it.isAddition })
        assertEquals(
            21,
            luna.standardEquipment
                .single { it.category == "safety_and_security" }
                .items.size,
        )

        val sport = car.trim("Sport")!!
        assertTrue(sport.standardEquipment.all { it.isAddition })
        assertTrue("Blind Spot Monitor (BSM)" in sport.allEquipmentItems)
    }

    @Test
    fun `colours and accessory packs are options`() {
        assertEquals(12, car.colours.size)
        assertEquals(8, car.colours.count { it.categoryLabel == "monotone" })
        assertEquals(4, car.colours.count { it.categoryLabel == "bitone" })

        val pearlRed = car.option("305")!!
        assertEquals("Pearlescent", pearlRed.type)
        assertEquals("Sport, Sol (Not available on Luna)", pearlRed.availability.single().scope)

        val packs = car.options.filter { it.category == OptionCategory.ACCESSORY_PACK }
        assertEquals(listOf("Corolla Cross Care Pack", "Corolla Cross Protection Pack"), packs.map { it.name })
        assertEquals(listOf("Boot Liner", "Front & Rear Mud Guards", "Lock Nuts"), packs.first().items)
        assertTrue(packs.all { it.code == null && it.availability.isEmpty() })
    }

    @Test
    fun `engine-qualified dimensions become ranges and keep raw text`() {
        val dimensions = car.technicalSpecifications.dimensions
        assertEquals(NumericRange(4460.0, 4465.0), dimensions.lengthMm)
        assertEquals(NumericRange(1605.0, 1620.0), dimensions.heightMm)
        assertEquals(NumericRange(1825.0), dimensions.widthMm)
        assertEquals(NumericRange(425.0, 473.0), dimensions.maxBootCapacityLitres)
        assertEquals(NumericRange(750.0), dimensions.brakedTowingKg)
        assertEquals("4460 (1.8 HEV) / 4465 (2.0 HEV)", dimensions.additional["length_mm"])
    }

    @Test
    fun `hybrid powertrain specifications are parsed`() {
        val powertrains = car.technicalSpecifications.powertrains
        assertEquals(2, powertrains.size)
        assertTrue(powertrains.all { it.type == PowertrainType.HYBRID })

        val small = powertrains.single { it.name == "1.8 HEV FWD" }
        assertEquals(1798, small.displacementCc)
        assertEquals(140.0, small.powerHp)
        assertEquals(103.0, small.powerKw)
        assertEquals(142.0, small.torqueNm)
        assertEquals(NumericRange(9.9, 10.0), small.acceleration0To100S)
        assertEquals(NumericRange(4.9, 5.0), small.fuelConsumptionLPer100Km)
        assertEquals(NumericRange(112.0, 114.0), small.co2GPerKm)
        assertEquals("Lithium-ion / 56 cells", small.batteryType)
        assertEquals(listOf("Luna", "Sport", "Sol"), small.trimsAvailable)
        assertEquals(mapOf("nitrogen_oxides_nox_mg_km" to "2.4"), small.additional)

        val large = powertrains.single { it.name == "2.0 HEV FWD" }
        assertEquals(145.0, large.powerKw)
        assertEquals(NumericRange(7.7), large.acceleration0To100S)
        assertEquals(listOf("GR SPORT"), large.trimsAvailable)
    }
}
