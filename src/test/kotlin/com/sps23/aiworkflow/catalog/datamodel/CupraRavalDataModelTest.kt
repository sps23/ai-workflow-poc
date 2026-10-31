package com.sps23.aiworkflow.catalog.datamodel

import com.sps23.aiworkflow.catalog.datamodel.DataModelFixtures.euros
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CupraRavalDataModelTest {
    private val car = DataModelFixtures.load(DataModelFixtures.CUPRA_RAVAL)

    @Test
    fun `identity and effective period are parsed`() {
        assertEquals("cupra-raval", car.id)
        assertEquals("CUPRA", car.make)
        assertEquals("Raval", car.model)
        assertEquals(YearMonth.of(2026, 9), car.effectivePeriod)
        assertEquals(emptyMap(), car.additional)
    }

    @Test
    fun `trims and variants carry typed prices and electric figures`() {
        assertEquals(listOf("Urban", "Endurance", "VZ"), car.trims.map { it.name })
        assertEquals(5, car.variants.size)
        assertTrue(car.variants.all { it.powertrainType == PowertrainType.ELECTRIC && it.additional.isEmpty() })

        val urbanV1 = car.variants.single { it.name == "Raval Urban 116hp V1" }
        assertEquals("Urban", urbanV1.trimName)
        assertEquals("KS1AAF", urbanV1.modelCode)
        assertEquals(37.0, urbanV1.batteryCapacityKwh)
        assertEquals(116, urbanV1.powerHp)
        assertEquals(NumericRange(0.0), urbanV1.co2GPerKm)
        assertEquals(NumericRange(311.0), urbanV1.wltpRangeKm)
        assertEquals(13.5, urbanV1.electricConsumptionKwhPer100Km)
        assertEquals(7.0, urbanV1.vrtRatePercent)
        assertEquals(euros(23_495), urbanV1.price(PriceBasis.RRP_EXCLUDING_GRANTS)?.amount)
        assertEquals(euros(28_810), urbanV1.price(PriceBasis.RRP_INCLUDING_GRANTS)?.amount)
        assertEquals(euros(24_775), urbanV1.price(PriceBasis.ON_THE_ROAD)?.amount)
        assertEquals(euros(120), urbanV1.price(PriceBasis.ANNUAL_ROAD_TAX)?.amount)

        val vz = car.trim("VZ")!!.variants.single()
        assertEquals(euros(38_105), vz.price(PriceBasis.ON_THE_ROAD)?.amount)
    }

    @Test
    fun `nested V1 base and V2 additions equipment is flattened`() {
        val urban = car.trim("Urban")!!
        assertEquals(
            listOf("v1_base/exterior", "v1_base/interior", "v1_base/safety", "v1_base/technology", "v2_additions"),
            urban.standardEquipment.map { it.category },
        )
        assertEquals(listOf(false, false, false, false, true), urban.standardEquipment.map { it.isAddition })
        assertTrue("Vehicle-To-Load (V2L)" in urban.standardEquipment.last().items)
        assertEquals(
            listOf("exterior", "interior", "safety", "technology"),
            car.trim("VZ")!!.standardEquipment.map { it.category },
        )
    }

    @Test
    fun `options are normalized with per-scope availability`() {
        assertEquals(8, car.options.count { it.category == OptionCategory.WHEELS })
        assertEquals(4, car.options.count { it.category == OptionCategory.INTERIOR })
        assertEquals(7, car.options.count { it.category == OptionCategory.PAINT })
        assertEquals(2, car.options.count { it.category == OptionCategory.ROOF_COLOUR })
        assertEquals(7, car.options.count { it.category == OptionCategory.PACKAGE })
        assertEquals(3, car.options.count { it.category == OptionCategory.SINGLE_OPTION })
        assertEquals(9, car.colours.size)

        val winterPack = car.option("PW2/PW4")!!
        assertEquals(listOf("Heat Pump", "Heated Steering Wheel"), winterPack.items)
        assertEquals(listOf("V1", "V2", "VZ"), winterPack.availability.map { it.scope })
        assertTrue(winterPack.availability.all { it.state == FitmentState.OPTIONAL && it.price?.amount == euros(1_119) })

        val sulphurRebel = car.option("SULPHUR_REBEL")!!
        assertEquals(FitmentState.NOT_AVAILABLE, sulphurRebel.availabilityFor("V1")!!.state)
        assertNull(sulphurRebel.availabilityFor("V1")!!.price)
        val vzSulphur = sulphurRebel.availabilityFor("VZ")!!
        assertEquals(FitmentState.OPTIONAL, vzSulphur.state)
        assertEquals("Ahead Pack", vzSulphur.note)
        assertNull(vzSulphur.price!!.amount)
        assertEquals("Part of Ahead Pack", vzSulphur.price.rawText)

        val endurance = car.option("STANDARD_ENDURANCE")!!.availabilityFor("V2")!!
        assertEquals(FitmentState.STANDARD, endurance.state)
        assertEquals("Endurance", endurance.note)

        val fiordBlue = car.colours.single { it.name == "Fiord Blue" }
        assertEquals(Money(0), fiordBlue.availabilityFor("V1")!!.price!!.amount)

        val carePack = car.option("CUPRA_CARE")!!
        assertNull(carePack.availabilityFor("V1")!!.price!!.amount)
        assertEquals("€19.99 / month", carePack.availabilityFor("V1")!!.price!!.rawText)
    }

    @Test
    fun `technical specifications are typed and keep unparsed text`() {
        val dimensions = car.technicalSpecifications.dimensions
        assertEquals(NumericRange(4046.0), dimensions.lengthMm)
        assertEquals(NumericRange(441.0), dimensions.bootCapacityLitres)
        assertEquals(mapOf("front_track_mm" to "1538", "rear_track_mm" to "1536"), dimensions.additional)

        val powertrains = car.technicalSpecifications.powertrains
        assertEquals(4, powertrains.size)
        assertTrue(powertrains.all { it.type == PowertrainType.ELECTRIC })

        val urbanV1 = powertrains.single { it.name == "Urban V1 116hp" }
        assertEquals("KS1EAF", urbanV1.modelCode)
        assertEquals(85.0, urbanV1.powerKw)
        assertEquals(116.0, urbanV1.powerHp)
        assertEquals(264.0, urbanV1.torqueNm)
        assertEquals(NumericRange(13.5, 14.5), urbanV1.electricConsumptionKwhPer100Km)
        assertEquals(500.0, urbanV1.brakedTowingKg)
        assertEquals(mapOf("ac_11kw_0_100_percent" to "4 hrs", "dc_50kw_10_80_percent" to "23 min"), urbanV1.charging)
        assertEquals("85 kW (116 HP) / 3075-7000 rpm", urbanV1.additional["max_power"])

        val vz = powertrains.single { it.name == "VZ 226hp" }
        assertEquals(NumericRange(379.0, 440.0), vz.wltpRangeKm)
        assertEquals("NMC", vz.batteryType)
        assertEquals("240-408", vz.additional["battery_voltage"])
    }
}
