package com.sps23.aiworkflow.catalog.datamodel

import com.sps23.aiworkflow.catalog.extraction.ExtractionRequirements
import com.sps23.aiworkflow.catalog.extraction.Severity
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Peugeot5008DataModelTest {
    private val fixture = DataModelFixtures.frozen(DataModelFixtures.PEUGEOT_5008)
    private val car = fixture.toModel()
    private val trims = listOf("ALLURE", "GT", "GT EXCLUSIVE")

    @Test
    fun `fixture contains the published identity effective period and price exclusions`() {
        assertEquals("peugeot-5008", car.id)
        assertEquals("peugeot/5008-spec-sheet.json", car.sourcePath)
        assertEquals("PEUGEOT 5008", car.title)
        assertEquals("PEUGEOT", car.make)
        assertEquals("5008", car.model)
        assertEquals("November 2026", car.effectiveDate)
        assertEquals(YearMonth.of(2026, 11), car.effectivePeriod)
        assertNull(car.sourcePdfPath)
        assertEquals(
            mapOf("price_note" to "Dealer delivery related charges, metallic paint and tyre recycling levy not included."),
            car.additional,
        )
    }

    @Test
    fun `all three trims contain exact electric and hybrid pricing rows with tax ranges`() {
        assertEquals(trims, car.trims.map { it.name })
        assertEquals(6, car.variants.size)
        val electricPrices = listOf(52_995L, 55_995L, 57_995L)
        val hybridPrices = listOf(49_995L, 52_995L, 53_995L)
        car.trims.forEachIndexed { index, trim ->
            assertEquals(
                listOf(
                    TrimVariant(
                        name = "Electric 210 (73 kWh)",
                        trimName = trim.name,
                        modelCode = null,
                        powertrainType = PowertrainType.ELECTRIC,
                        batteryCapacityKwh = 73.0,
                        powerHp = 210,
                        powerKw = null,
                        co2GPerKm = NumericRange(0.0),
                        wltpRangeKm = null,
                        electricConsumptionKwhPer100Km = null,
                        taxBand = "A",
                        vrtRatePercent = null,
                        prices =
                            listOf(
                                price(PriceBasis.RRP, electricPrices[index]),
                                price(PriceBasis.ANNUAL_ROAD_TAX, 120),
                            ),
                    ),
                    TrimVariant(
                        name = "Hybrid 145 AUTO",
                        trimName = trim.name,
                        modelCode = null,
                        powertrainType = PowertrainType.HYBRID,
                        batteryCapacityKwh = null,
                        powerHp = 145,
                        powerKw = null,
                        co2GPerKm = NumericRange(128.0, 135.0),
                        wltpRangeKm = null,
                        electricConsumptionKwhPer100Km = null,
                        taxBand = "B1 - B2",
                        vrtRatePercent = null,
                        prices =
                            listOf(
                                price(PriceBasis.RRP, hybridPrices[index]),
                                PriceFact(PriceBasis.ANNUAL_ROAD_TAX, euros(200), euros(210), "€200 - €210"),
                            ),
                    ),
                ),
                trim.variants,
                trim.name,
            )
            assertEquals(emptyMap(), trim.additional, trim.name)
        }
    }

    @Test
    fun `equipment preserves every section count additions and important seating safety and comfort features`() {
        val expectedSections =
            listOf(
                linkedMapOf(
                    "safety" to 7,
                    "advanced_safety" to 9,
                    "exterior_aesthetic" to 10,
                    "wheels_and_rims" to 1,
                    "interior_aesthetic" to 6,
                    "comfort" to 11,
                    "upholstery_and_seats" to 4,
                    "multimedia_and_navigation" to 10,
                    "electric_specificities" to 3,
                ),
                linkedMapOf(
                    "safety" to 3,
                    "driving_and_manoeuvring_aids" to 2,
                    "wheels_and_rims" to 2,
                    "interior_aesthetics" to 7,
                    "comfort" to 5,
                    "upholstery_and_seats" to 5,
                    "multimedia_and_navigation" to 5,
                ),
                linkedMapOf(
                    "safety" to 3,
                    "driving_and_manoeuvring_aids" to 2,
                    "exterior_aesthetics" to 3,
                    "interior_aesthetics" to 6,
                    "comfort" to 5,
                    "upholstery_and_seats" to 3,
                    "multimedia_and_navigation" to 5,
                ),
            )
        car.trims.forEachIndexed { index, trim ->
            assertEquals(expectedSections[index].keys.toList(), trim.standardEquipment.map { it.category }, trim.name)
            assertEquals(expectedSections[index].values.toList(), trim.standardEquipment.map { it.items.size }, trim.name)
            assertTrue(trim.standardEquipment.all { it.isAddition == (index > 0) }, trim.name)
            assertTrue(trim.allEquipmentItems.all { it.isNotBlank() }, trim.name)
            assertTrue("Heated steering wheel" in trim.allEquipmentItems, trim.name)
        }
        val allure = car.trims.first().allEquipmentItems
        assertTrue("ISOFIX and Top Tether fasteners on row 2 rear side seats" in allure)
        assertTrue("Tri-zone automatic air conditioning" in allure)
        assertTrue("2 seats bench in the third row with 50/50 reclinable backrests" in allure)
        assertTrue("3-phase 11 kW on board charger" in allure)
        assertTrue("Powered tailgate with hands-free access" in car.trims[1].allEquipmentItems)
        assertTrue("Heated and massaging driver and passenger seats" in car.trims[2].allEquipmentItems)
    }

    @Test
    fun `all eight options retain exact per trim fitment prices and conditional notes`() {
        val codes = listOf("M0DP", "0MM0", "J6C0", "DX02", "J7EV", "4HFX", "AQ05", "AQ14")
        assertEquals(codes, car.options.map { it.code })
        assertEquals(2, car.colours.size)
        val standard = FitmentState.STANDARD
        val optional = FitmentState.OPTIONAL
        val unavailable = FitmentState.NOT_AVAILABLE
        val states =
            listOf(
                listOf(standard, standard, standard),
                listOf(optional, optional, optional),
                listOf(unavailable, optional, optional),
                listOf(unavailable, optional, optional),
                listOf(optional, unavailable, unavailable),
                listOf(unavailable, unavailable, optional),
                listOf(optional, optional, optional),
                listOf(unavailable, optional, optional),
            )
        val amounts =
            listOf(
                listOf(0L, 0L, 0L),
                listOf(900L, 900L, 900L),
                listOf(null, 1920L, 1920L),
                listOf(null, 430L, 430L),
                listOf(790L, null, null),
                listOf(null, null, 3330L),
                listOf(675L, 675L, 675L),
                listOf(null, 750L, 750L),
            )
        val notes =
            listOf(
                listOf(null, null, null),
                listOf(null, null, null),
                listOf(null, null, null),
                listOf(null, null, "Electric Version Only"),
                listOf(null, null, null),
                listOf(null, null, null),
                List(3) { "Available only on electric" },
                listOf(null, "Available only on Hybrid 145 Auto", "Available only on Hybrid 145 Auto"),
            )
        car.options.forEachIndexed { index, option ->
            assertEquals(
                trims.mapIndexed { trimIndex, trim ->
                    OptionAvailability(
                        scope = trim,
                        state = states[index][trimIndex],
                        price = amounts[index][trimIndex]?.let { price(PriceBasis.OPTION, it) },
                        note = notes[index][trimIndex],
                    )
                },
                option.availability,
                option.code,
            )
        }
    }

    @Test
    fun `paint packages upholstery and towing retain names contents categories and caveats`() {
        assertEquals(
            listOf(
                OptionCategory.PAINT,
                OptionCategory.PAINT,
                OptionCategory.PACKAGE,
                OptionCategory.SINGLE_OPTION,
                OptionCategory.PACKAGE,
                OptionCategory.INTERIOR,
                OptionCategory.SINGLE_OPTION,
                OptionCategory.SINGLE_OPTION,
            ),
            car.options.map { it.category },
        )
        assertEquals(
            listOf(
                "EXTERIOR PAINT",
                "EXTERIOR PAINT",
                "OPTION PACKS",
                "SAFETY AND SECURITY",
                "INTERIOR AMBIENCES",
                "INTERIOR AMBIENCES",
                "EXTERIOR FEATURES",
                "EXTERIOR FEATURES",
            ),
            car.options.map { it.categoryLabel },
        )
        assertEquals(listOf(0, 5, 2, 0, 6, 9, 0, 0), car.options.map { it.items.size })
        assertTrue(car.options.all { it.name.isNotBlank() && it.items.all(String::isNotBlank) })
        assertEquals("Obsession Blue", car.option("M0DP")!!.name)
        assertEquals("Metallic paint", car.option("M0DP")!!.type)
        val metallic = assertNotNull(car.option("0MM0"))
        assertEquals("Metallic paint", metallic.type)
        assertEquals(
            listOf("Nera Black (M09V)", "Titane Grey (M01J)", "Cumulus Grey (M0F4)", "Ingaro Blue (M07K)", "Okenite White (M0SU)"),
            metallic.items,
        )
        assertEquals(mapOf("stock_only_colours" to "Titane Grey (MO1J – Stock Only)"), metallic.additional)
        assertEquals("Sunroof Pack", car.option("J6C0")!!.name)
        assertTrue("Laminated acoustic front windows" in car.option("J6C0")!!.items)
        assertEquals("Heated front seats, Driver & Passenger Pack", car.option("J7EV")!!.name)
        assertTrue("Heated steering wheel" in car.option("J7EV")!!.items)
        assertEquals("Black perforated Nappa leather upholstery", car.option("4HFX")!!.name)
        assertTrue("Ventilated seats (D+P)" in car.option("4HFX")!!.items)
        assertTrue("Heated rear seats in addition to heated front seats" in car.option("4HFX")!!.items)
        assertEquals(mapOf("engine_availability" to "Available only on electric"), car.option("AQ05")!!.additional)
        assertEquals(mapOf("engine_availability" to "Available only on Hybrid 145 Auto"), car.option("AQ14")!!.additional)
    }

    @Test
    fun `dimensions preserve measurement ranges VDA versus water volumes and towing limits`() {
        assertEquals(
            Dimensions(
                lengthMm = NumericRange(4790.0, 4791.0),
                widthMm = NumericRange(1890.0, 1895.0),
                heightMm = NumericRange(1690.0, 1694.0),
                wheelbaseMm = NumericRange(2900.0, 2901.0),
                bootCapacityLitres = NumericRange(259.0, 348.0),
                maxBootCapacityLitres = NumericRange(1815.0, 2232.0),
                frontTrunkCapacityLitres = null,
                brakedTowingKg = NumericRange(750.0, 1000.0),
                additional =
                    mapOf(
                        "turning_diameter_m" to "11.20",
                        "trunk_volume_7_seater_behind_row_3_dm3_vda" to "259",
                        "trunk_volume_7_seater_behind_row_3_water_litres" to "348",
                        "trunk_volume_7_seater_behind_row_2_dm3_vda" to "748",
                        "trunk_volume_7_seater_behind_row_2_water_litres" to "916",
                        "trunk_volume_all_rear_seats_folded_dm3_vda" to "1815",
                        "trunk_volume_all_rear_seats_folded_water_litres" to "2232",
                        "cx_scx_electric_210_standard_range" to "0,28 / 0,77",
                        "cx_scx_electric_210_long_range" to "0,28 / 0,775",
                        "cx_scx_electric_325_dual_motor" to "0,28 / 0,77",
                        "cx_scx_hybrid_145" to "0,30 / 0,798",
                        "fuel_tank_capacity_l" to "55",
                    ),
            ),
            car.technicalSpecifications.dimensions,
        )
    }

    @Test
    fun `all four technical powertrains retain power performance consumption battery and weight attributes`() {
        val specs = car.technicalSpecifications.powertrains
        assertEquals(
            listOf(
                "Electric 210 73kWh Standard Range",
                "Electric 210 97kWh Long Range",
                "Electric 325 73kWh Dual Motor",
                "Hybrid 145 AUTO",
            ),
            specs.map { it.name },
        )
        assertEquals(List(3) { PowertrainType.ELECTRIC } + PowertrainType.MILD_HYBRID, specs.map { it.type })
        assertEquals(listOf("Front", "Front", "All wheel drive", "Front"), specs.map { it.drivetrain })
        assertEquals(List(3) { "Automatic 1 gear reductor" } + "Automatic 6", specs.map { it.transmission })
        assertEquals(listOf(157.0, 170.0, 239.0, 100.0), specs.map { it.powerKw })
        assertEquals(listOf(213.0, 231.0, 325.0, 136.0), specs.map { it.powerHp })
        assertEquals(listOf(343.0, 343.0, null, 230.0), specs.map { it.torqueNm })
        assertEquals(listOf(170.0, 170.0, 180.0, 200.0), specs.map { it.topSpeedKmh })
        assertEquals(listOf(9.7, 9.6, 6.5, 11.3).map(::NumericRange), specs.map { it.acceleration0To100S })
        assertEquals(
            listOf(NumericRange(489.0, 502.0), NumericRange(637.0, 668.0), NumericRange(465.0, 467.0), null),
            specs.map { it.wltpRangeKm },
        )
        assertEquals(
            listOf(NumericRange(17.7, 18.3), NumericRange(18.1, 19.1), NumericRange(18.8, 18.9), null),
            specs.map { it.electricConsumptionKwhPer100Km },
        )
        assertEquals(listOf(null, null, null, NumericRange(5.7, 5.9)), specs.map { it.fuelConsumptionLPer100Km })
        assertEquals(listOf(NumericRange(0.0), null, null, NumericRange(125.0, 133.0)), specs.map { it.co2GPerKm })
        assertEquals(listOf(73.0, 96.9, 73.0, 0.4), specs.map { it.batteryCapacityKwh })
        assertEquals(List(4) { "Lithium-ion NMC" }, specs.map { it.batteryType })
        assertEquals(listOf(null, null, null, 1199), specs.map { it.displacementCc })
        assertEquals(listOf(160.0, 160.0, 160.0, null), specs.map { it.dcMaxChargingKw })
        assertEquals(listOf(2900.0, 2940.0, 2940.0, 2330.0), specs.map { it.grossVehicleWeightKg })
        assertEquals(listOf(1000.0, 1000.0, 1000.0, 750.0), specs.map { it.brakedTowingKg })
        specs.forEach {
            assertNull(it.modelCode, it.name)
            assertNull(it.batteryGrossKwh, it.name)
            assertNull(it.unbrakedTowingKg, it.name)
        }
        assertEquals(listOf(trims, emptyList(), emptyList(), trims), specs.map { it.trimsAvailable })
        assertEquals(emptyMap(), car.technicalSpecifications.additional)
    }

    @Test
    fun `charging and unmapped source facts retain exact values without inventing hybrid charging`() {
        val specs = car.technicalSpecifications.powertrains
        specs.take(3).forEachIndexed { index, spec ->
            assertEquals(
                mapOf(
                    "maximum_ac_capacity_kw" to "11",
                    "maximum_dc_capacity_kw" to "160",
                    "charging_time_on_charging_station_7_4_kw_ac" to if (index == 1) "8h55" else "6h45",
                    "charging_time_on_charging_station_11_kw_ac" to if (index == 1) "6h30" else "4h30",
                    "charging_time_on_charging_station_22_kw_ac" to if (index == 1) "6h30" else "4h30",
                    "charging_time_on_fast_charging_dc_station" to if (index == 1) "27 min" else "30 min",
                ),
                spec.charging,
                spec.name,
            )
        }
        assertEquals(emptyMap(), specs.last().charging)
        assertEquals(listOf("520", "562", "520", "12.1"), specs.map { it.additional["battery_weight_kg"] })
        assertEquals(listOf("400", "400", "400", "48"), specs.map { it.additional["total_voltage_v"] })
        assertEquals(listOf("2218", "2241", "2331", "1625"), specs.map { it.additional["kerb_weight_kg"] })
        assertEquals(listOf("65", "65", "64", "67"), specs.map { it.additional["driving_sound_level_db"] })
        assertEquals(
            "Front : 343 @ 250 to 4 370; Rear : 166 @ 1 000 to 4 375",
            specs[2].additional["e_motor_maximum_torque"],
        )
        assertEquals("15,6 / 21 @ 4 264", specs.last().additional["e_motor_maximum_power"])
        assertEquals("1000", specs.last().additional["maximum_towable_weight_braked_with_oem_tow_hook_option_kg"])
        assertEquals("25 000 km or 2 years + annual control", specs.last().additional["service_interval"])
    }

    @Test
    fun `full fixture matches the current source and satisfies catalog generation rules`() {
        assertEquals(DataModelFixtures.load(DataModelFixtures.PEUGEOT_5008), car)
        val violations = ExtractionRequirements.validateVehicle(fixture.vehicle).filter { it.severity == Severity.HARD }
        assertTrue(violations.isEmpty(), violations.joinToString("\n") { "${it.rule}: ${it.message}" })
        val artifact = DataModelCatalogArtifact.from(DataModelCatalog(listOf(car.copy(sourcePdfPath = "peugeot/5008-spec-sheet.pdf"))))
        val encoded = DataModelFixtures.json.encodeToString(DataModelCatalogArtifact.serializer(), artifact)
        assertEquals(artifact, DataModelFixtures.json.decodeFromString(DataModelCatalogArtifact.serializer(), encoded))
    }

    private fun euros(amount: Long): Money = Money(amount * 100)

    private fun price(
        basis: PriceBasis,
        amount: Long,
    ): PriceFact = PriceFact(basis, euros(amount), rawText = "€" + "%,d".format(java.util.Locale.US, amount))
}
