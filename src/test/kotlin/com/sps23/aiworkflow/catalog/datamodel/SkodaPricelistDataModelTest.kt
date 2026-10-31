package com.sps23.aiworkflow.catalog.datamodel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkodaPricelistDataModelTest {
    @Test
    fun `Skoda MY27 pricelists conform to the shared car data model`() {
        val expected =
            mapOf(
                "skoda/Superb_MY27_PG_Janury_CW01_2027.json" to ("skoda-superb" to 24),
                "skoda/Epiq_MY27_PG_September_CW48_2026.json" to ("skoda-epiq" to 4),
                "skoda/Peaq_MY27_PG_August_CW48_2026.json" to ("skoda-peaq" to 5),
                "skoda/Kodiaq_MY27_PG_January_CW01_2027.json" to ("skoda-kodiaq" to 12),
            )

        expected.forEach { (path, expectedVehicle) ->
            val car = DataModelFixtures.load(path)

            assertEquals(expectedVehicle.first, car.id, path)
            assertEquals(expectedVehicle.second, car.variants.size, path)
            assertTrue(car.variants.all { it.prices.isNotEmpty() }, path)
            assertTrue(car.technicalSpecifications.powertrains.isNotEmpty(), path)
        }
    }
}
