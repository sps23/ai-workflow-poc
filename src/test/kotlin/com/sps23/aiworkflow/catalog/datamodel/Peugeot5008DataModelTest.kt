package com.sps23.aiworkflow.catalog.datamodel

import com.sps23.aiworkflow.catalog.datamodel.DataModelFixtures.euros
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Peugeot5008DataModelTest {
    private val car = DataModelFixtures.load(DataModelFixtures.PEUGEOT_5008)

    @Test
    fun `identity is parsed despite the UTF-8 BOM`() {
        assertEquals("peugeot-5008", car.id)
        assertEquals("Peugeot", car.make)
        assertEquals("5008", car.model)
        assertEquals("Peugeot 5008 (Including Electric & Hybrid)", car.title)
        assertEquals(YearMonth.of(2026, 11), car.effectivePeriod)
    }

    @Test
    fun `same trim is priced separately per powertrain`() {
        assertEquals(listOf("Allure", "GT", "GT EXCLUSIVE"), car.trims.map { it.name })
        assertEquals(6, car.variants.size)

        val allure = car.trim("Allure")!!.variants
        val electric = allure.single { it.powertrainType == PowertrainType.ELECTRIC }
        val hybrid = allure.single { it.powertrainType == PowertrainType.HYBRID }
        assertEquals("Electric 210 (73 kWh)", electric.name)
        assertEquals(euros(51_995), electric.price(PriceBasis.PRICE)?.amount)
        assertEquals(NumericRange(0.0), electric.co2GPerKm)
        assertEquals("A", electric.taxBand)
        assertEquals(euros(49_995), hybrid.price(PriceBasis.PRICE)?.amount)
        assertEquals(NumericRange(128.0, 135.0), hybrid.co2GPerKm)
        assertEquals("B1-B2", hybrid.taxBand)
        val roadTax = hybrid.price(PriceBasis.ANNUAL_ROAD_TAX)!!
        assertEquals(euros(200), roadTax.amount)
        assertEquals(euros(210), roadTax.maxAmount)

        assertEquals(
            euros(57_995),
            car
                .trim("GT EXCLUSIVE")!!
                .variants
                .single { it.powertrainType == PowertrainType.ELECTRIC }
                .price(PriceBasis.PRICE)
                ?.amount,
        )
    }

    @Test
    fun `higher trims list equipment additions`() {
        val allure = car.trim("Allure")!!
        assertTrue(allure.standardEquipment.none { it.isAddition })
        assertTrue("Heated steering wheel" in allure.allEquipmentItems)

        val gtExclusive = car.trim("GT EXCLUSIVE")!!
        assertEquals(
            listOf("exterior_additions", "seating_and_upholstery_additions"),
            gtExclusive.standardEquipment.map { it.category },
        )
        assertTrue(gtExclusive.standardEquipment.all { it.isAddition })
    }

    @Test
    fun `optional equipment categories map to option categories`() {
        assertEquals(8, car.options.size)
        assertEquals(2, car.colours.size)

        val metallic = car.option("0MM0")!!
        assertEquals(OptionCategory.PAINT, metallic.category)
        assertEquals("Exterior Paint", metallic.categoryLabel)
        val allTrims = metallic.availability.single()
        assertEquals("All Trims", allTrims.scope)
        assertEquals(FitmentState.OPTIONAL, allTrims.state)
        assertEquals(euros(900), allTrims.price?.amount)

        val sunroof = car.option("J6C0")!!
        assertEquals(OptionCategory.PACKAGE, sunroof.category)
        assertEquals("GT, GT EXCLUSIVE", sunroof.availability.single().scope)
        assertEquals(
            euros(1_920),
            sunroof.availability
                .single()
                .price
                ?.amount,
        )

        assertEquals(OptionCategory.OTHER, car.option("DX02")!!.category)
        assertEquals(OptionCategory.INTERIOR, car.option("4HFX")!!.category)
        assertEquals(
            euros(3_330),
            car
                .option("4HFX")!!
                .availability
                .single()
                .price
                ?.amount,
        )
        assertEquals(
            "Hybrid 145 Auto Only",
            car
                .option("AQ14")!!
                .availability
                .single()
                .scope,
        )
    }

    @Test
    fun `metric dimensions are converted to millimetres`() {
        val dimensions = car.technicalSpecifications.dimensions
        assertEquals(NumericRange(4790.0), dimensions.lengthMm)
        assertEquals(NumericRange(1890.0), dimensions.widthMm)
        assertEquals(NumericRange(1690.0), dimensions.heightMm)
        assertEquals(NumericRange(2900.0), dimensions.wheelbaseMm)
        assertEquals(NumericRange(748.0), dimensions.bootCapacityLitres)
        assertEquals(NumericRange(1815.0), dimensions.maxBootCapacityLitres)
        assertEquals(
            mapOf("turning_diameter_m" to "11.20", "trunk_volume_7_seater_all_up_l" to "259"),
            dimensions.additional,
        )
    }

    @Test
    fun `electric and hybrid powertrain specifications are parsed`() {
        val powertrains = car.technicalSpecifications.powertrains
        assertEquals(4, powertrains.size)

        val longRange = powertrains.single { it.name == "Electric 210 97kWh Long Range" }
        assertEquals(PowertrainType.ELECTRIC, longRange.type)
        assertEquals(170.0, longRange.powerKw)
        assertEquals(231.0, longRange.powerHp)
        assertEquals(96.9, longRange.batteryCapacityKwh)
        assertEquals(NumericRange(637.0, 668.0), longRange.wltpRangeKm)
        assertEquals(NumericRange(18.1, 19.1), longRange.electricConsumptionKwhPer100Km)
        assertEquals(160.0, longRange.dcMaxChargingKw)
        assertEquals("27", longRange.additional["dc_fast_charge_20_to_80_percent_mins"])

        val dualMotor = powertrains.single { it.name == "Electric 325 73kWh Dual Motor" }
        assertEquals(null, dualMotor.torqueNm)
        assertEquals("343 (Front) / 166 (Rear)", dualMotor.additional["torque_nm"])

        val hybrid = powertrains.single { it.name == "Hybrid 145 AUTO" }
        assertEquals(PowertrainType.HYBRID, hybrid.type)
        assertEquals("6-Speed Automatic", hybrid.transmission)
        assertEquals(1199, hybrid.displacementCc)
        assertEquals(NumericRange(5.7, 5.9), hybrid.fuelConsumptionLPer100Km)
        assertEquals(NumericRange(125.0, 133.0), hybrid.co2GPerKm)
        assertEquals("136", hybrid.additional["ice_power_hp"])
    }
}
