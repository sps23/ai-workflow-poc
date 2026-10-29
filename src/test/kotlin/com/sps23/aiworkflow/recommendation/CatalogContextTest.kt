package com.sps23.aiworkflow.recommendation

import com.sps23.aiworkflow.catalog.CatalogSource
import com.sps23.aiworkflow.catalog.EquipmentFitment
import com.sps23.aiworkflow.catalog.FactReviewStatus.NEEDS_REVIEW
import com.sps23.aiworkflow.catalog.FactReviewStatus.REVIEWED
import com.sps23.aiworkflow.catalog.FitmentStatus
import com.sps23.aiworkflow.catalog.MeasurementApprovalStatus
import com.sps23.aiworkflow.catalog.Powertrain
import com.sps23.aiworkflow.catalog.PriceBasis
import com.sps23.aiworkflow.catalog.SourceEvidence
import com.sps23.aiworkflow.catalog.VehicleCatalog
import com.sps23.aiworkflow.catalog.VehicleCatalogCodec
import com.sps23.aiworkflow.catalog.VehicleConfiguration
import com.sps23.aiworkflow.catalog.VehicleFeature
import com.sps23.aiworkflow.catalog.VehicleMeasurement
import com.sps23.aiworkflow.catalog.VehicleModel
import com.sps23.aiworkflow.catalog.VehiclePrice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CatalogContextTest {
    private val sha = "a".repeat(64)
    private val evidence = listOf(SourceEvidence("doc.pdf", sha, 3, "RAW SOURCE TEXT"))

    private fun configuration(
        id: String,
        status: com.sps23.aiworkflow.catalog.FactReviewStatus,
        trim: String,
    ) = VehicleConfiguration(
        id = id,
        modelId = "maker-one",
        market = "IE",
        trim = trim,
        powertrain = Powertrain.PETROL,
        seatingCapacity = if (id == "car-a") 7 else null,
        reviewStatus = status,
        evidence = evidence,
    )

    private val catalog =
        VehicleCatalog(
            sources = listOf(CatalogSource("doc.pdf", "doc.pdf", sha, "IE", 10)),
            models = listOf(VehicleModel("maker-one", "Maker", "One", "Mk2")),
            configurations =
                listOf(
                    configuration("car-a", REVIEWED, "A"),
                    configuration("car-b", NEEDS_REVIEW, "B"),
                    configuration("car-c", REVIEWED, "C"),
                ),
            features =
                listOf(
                    VehicleFeature("tow-bar", "Tow bar", "Towing"),
                    VehicleFeature("sunroof", "Sunroof"),
                    VehicleFeature("unused", "Unused"),
                ),
            prices =
                listOf(
                    VehiclePrice(
                        "price-shared",
                        listOf("car-a", "car-b", "car-c"),
                        null,
                        2_500_000,
                        "EUR",
                        PriceBasis.MANUFACTURER_RRP,
                        "2026-09",
                        null,
                        "Excl. delivery",
                        REVIEWED,
                        evidence,
                    ),
                    VehiclePrice(
                        "price-unreviewed",
                        listOf("car-a"),
                        null,
                        1,
                        "EUR",
                        PriceBasis.OTHER,
                        null,
                        null,
                        null,
                        NEEDS_REVIEW,
                        evidence,
                    ),
                    VehiclePrice(
                        "price-option",
                        listOf("car-a"),
                        "tow-bar",
                        90_000,
                        "EUR",
                        PriceBasis.OPTION_PRICE,
                        null,
                        null,
                        null,
                        REVIEWED,
                        evidence,
                    ),
                ),
            measurements =
                listOf(
                    VehicleMeasurement(
                        "m-boot",
                        "Boot volume",
                        listOf("car-a"),
                        minValue = 500.0,
                        maxValue = 600.0,
                        unit = "l",
                        approvalStatus = MeasurementApprovalStatus.CONFIRMED,
                        reviewStatus = REVIEWED,
                        evidence = evidence,
                    ),
                    VehicleMeasurement(
                        "m-bad",
                        "CO2",
                        listOf("car-a"),
                        value = 99.0,
                        unit = "g/km",
                        approvalStatus = MeasurementApprovalStatus.PENDING_HOMOLOGATION,
                        reviewStatus = NEEDS_REVIEW,
                        evidence = evidence,
                    ),
                ),
            fitments =
                listOf(
                    EquipmentFitment(
                        "fit-tow",
                        "tow-bar",
                        listOf("car-a", "car-b"),
                        FitmentStatus.OPTIONAL,
                        "With pack",
                        REVIEWED,
                        evidence,
                    ),
                    EquipmentFitment("fit-roof", "sunroof", listOf("car-a"), FitmentStatus.UNKNOWN, null, NEEDS_REVIEW, evidence),
                ),
        )

    private val compact = CatalogContext.compact(catalog.also { VehicleCatalogCodec.encode(it) })

    @Test
    fun `every reviewed configuration survives and unreviewed ones are excluded`() {
        assertEquals(listOf("car-a", "car-c"), compact.configurations.map { it.id })
    }

    @Test
    fun `reviewed facts join to the right configurations and unreviewed facts are dropped`() {
        val a = compact.configurations.single { it.id == "car-a" }
        val c = compact.configurations.single { it.id == "car-c" }

        assertEquals("Maker", a.make)
        assertEquals("Mk2", a.generation)
        assertEquals(7, a.seats)
        assertEquals(listOf(2_500_000L, 90_000L), a.prices.map { it.amountMinorUnits }.sortedDescending())
        assertEquals("Excl. delivery", a.prices.first { it.featureId == null }.conditions)
        assertEquals("2026-09", a.prices.first { it.featureId == null }.effectiveFrom)
        assertEquals(listOf("Boot volume"), a.measurements.map { it.name })
        assertEquals(500.0, a.measurements.single().min)
        assertEquals(600.0, a.measurements.single().max)
        assertEquals(MeasurementApprovalStatus.CONFIRMED, a.measurements.single().approval)
        assertEquals(listOf(FitmentStatus.OPTIONAL), a.equipment.map { it.status })
        assertEquals("With pack", a.equipment.single().conditions)

        assertEquals(listOf(2_500_000L), c.prices.map { it.amountMinorUnits })
        assertTrue(c.measurements.isEmpty() && c.equipment.isEmpty())
    }

    @Test
    fun `only referenced reviewed features are included`() {
        assertEquals(listOf("tow-bar"), compact.features.map { it.id })
    }

    @Test
    fun `JSON is compact without raw text hashes or nulls`() {
        val json = CatalogContext.toJson(compact)

        assertFalse("RAW SOURCE TEXT" in json)
        assertFalse(sha in json)
        assertFalse("\n" in json || "null" in json || "  " in json)
        assertFalse("car-b" in json || "price-unreviewed" in json)
        assertTrue("\"sources\":[\"doc.pdf:3\"]" in json)
        assertFalse("\"engine\"" in json)
    }

    @Test
    fun `bundled catalog keeps all reviewed configurations in smaller JSON`() {
        val bundled = CatalogContext.loadBundledCatalog()
        val result = CatalogContext.compact(bundled)

        assertEquals(bundled.configurations.count { it.reviewStatus == REVIEWED }, result.configurations.size)
        assertTrue(result.configurations.isNotEmpty())
        val json = CatalogContext.bundledJson()
        assertFalse("rawText" in json)
        assertTrue(json.length < VehicleCatalogCodec.encode(bundled).length)
    }
}
