package com.sps23.aiworkflow.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VehicleCatalogTest {
    private val catalog =
        VehicleCatalogCodec.decode(
            checkNotNull(javaClass.getResource("/vehicle-catalog/catalog.json")).readText(),
        )

    @Test
    fun `versioned catalog decodes and encodes deterministically`() {
        val firstEncoding = VehicleCatalogCodec.encode(catalog)
        val decoded = VehicleCatalogCodec.decode(firstEncoding)

        assertEquals(firstEncoding, VehicleCatalogCodec.encode(decoded))
        assertTrue(firstEncoding.endsWith("\n"))
        assertEquals(12, decoded.configurations.size)
        assertEquals(3, decoded.models.size)
    }

    @Test
    fun `same trim remains distinct by powertrain and technical configuration`() {
        val allureConfigurations =
            catalog.configurations.filter {
                it.modelId == "peugeot-5008" && it.trim == "Allure"
            }

        assertEquals(setOf(Powertrain.ELECTRIC, Powertrain.HYBRID), allureConfigurations.map { it.powertrain }.toSet())
        assertEquals(
            setOf("peugeot-5008-electric-210-73kwh-allure", "peugeot-5008-hybrid-145-allure"),
            allureConfigurations.map { it.id }.toSet(),
        )
        assertEquals(
            setOf("6 speed manual", "7 speed auto DSG"),
            catalog.configurations
                .filter { it.modelId == "volkswagen-golf" }
                .mapNotNull { it.transmission }
                .toSet(),
        )
    }

    @Test
    fun `price facts retain currency basis and source effective period`() {
        val electricAllurePrice = catalog.prices.single { it.id == "5008-electric-allure-rrp" }
        val golfPrices = catalog.prices.filter { it.configurationIds.singleOrNull() == "volkswagen-golf-life-1-5-tsi-116hp" }

        assertEquals(5_299_500, electricAllurePrice.amountMinorUnits)
        assertEquals("EUR", electricAllurePrice.currency)
        assertEquals(PriceBasis.MANUFACTURER_RRP, electricAllurePrice.basis)
        assertEquals("2026-11", electricAllurePrice.effectiveFrom)
        assertEquals(
            setOf(PriceBasis.MANUFACTURER_RRP, PriceBasis.DELIVERY_SERVICE_CHARGE, PriceBasis.ON_THE_ROAD_TOTAL),
            golfPrices.map { it.basis }.toSet(),
        )
    }

    @Test
    fun `equipment fitment distinguishes standard optional unavailable and unknown`() {
        val optionalAllure =
            catalog.fitments.single { it.id == "5008-heated-seat-pack-allure-optional" }
        val unavailableGt =
            catalog.fitments.single { it.id == "5008-heated-seat-pack-unavailable-gt" }
        val unknownFitment =
            optionalAllure.copy(
                status = FitmentStatus.UNKNOWN,
                reviewStatus = FactReviewStatus.NEEDS_REVIEW,
            )
        val catalogWithUnknown =
            catalog.copy(
                fitments = catalog.fitments.map { if (it.id == optionalAllure.id) unknownFitment else it },
            )

        assertEquals(FitmentStatus.OPTIONAL, optionalAllure.status)
        assertEquals(FitmentStatus.NOT_AVAILABLE, unavailableGt.status)
        assertTrue(unavailableGt.evidence.any { it.pageNumber == 17 })
        assertEquals(FitmentStatus.STANDARD, catalog.fitments.single { it.id == "rav4-sol-power-boot-standard" }.status)
        assertEquals(FitmentStatus.UNKNOWN, catalogWithUnknown.fitments.single { it.id == optionalAllure.id }.status)
        VehicleCatalogValidator.validate(catalogWithUnknown)
    }

    @Test
    fun `measurements preserve ranges conditions and homologation state`() {
        val toyotaRange = catalog.measurements.single { it.id == "toyota-rav4-fwd-ev-range" }
        val pendingToyotaValue = catalog.measurements.single { it.id == "toyota-rav4-awd-acceleration-pending" }
        val bootVolumes = catalog.measurements.filter { it.name == "Boot volume" }

        assertEquals(125.0, toyotaRange.minValue)
        assertEquals(137.0, toyotaRange.maxValue)
        assertEquals("WLTP", toyotaRange.testCycleOrCondition)
        assertEquals(MeasurementApprovalStatus.PENDING_HOMOLOGATION, pendingToyotaValue.approvalStatus)
        assertEquals(FactReviewStatus.NEEDS_REVIEW, pendingToyotaValue.reviewStatus)
        assertEquals(
            setOf("Five-seat configuration", "Seven-seat configuration"),
            bootVolumes.mapNotNull { it.testCycleOrCondition }.toSet(),
        )
    }

    @Test
    fun `validation rejects duplicate configuration identities and unsupported schema versions`() {
        val configuration = catalog.configurations.first()
        val duplicate =
            configuration.copy(
                id = "duplicate-configuration",
                evidence = configuration.evidence,
            )

        assertFailsWith<IllegalArgumentException> {
            VehicleCatalogValidator.validate(catalog.copy(schemaVersion = 2))
        }
        assertFailsWith<IllegalArgumentException> {
            VehicleCatalogValidator.validate(catalog.copy(configurations = catalog.configurations + duplicate))
        }
    }

    @Test
    fun `validation rejects missing and invalid source evidence`() {
        val sourceEvidence =
            catalog.prices
                .first()
                .evidence
                .first()
        val badEvidence = sourceEvidence.copy(pageNumber = 32)
        val badPageCatalog =
            catalog.copy(
                prices =
                    catalog.prices.map { price ->
                        if (price.id == catalog.prices.first().id) {
                            price.copy(evidence = listOf(badEvidence))
                        } else {
                            price
                        }
                    },
            )
        val badHashCatalog =
            catalog.copy(
                configurations =
                    catalog.configurations.map { configuration ->
                        if (configuration.evidence.first().documentId == sourceEvidence.documentId) {
                            configuration.copy(
                                evidence =
                                    listOf(
                                        configuration.evidence.first().copy(sha256 = "0".repeat(64)),
                                    ),
                            )
                        } else {
                            configuration
                        }
                    },
            )

        assertFailsWith<IllegalArgumentException> {
            VehicleCatalogValidator.validate(badPageCatalog)
        }
        assertFailsWith<IllegalArgumentException> {
            VehicleCatalogValidator.validate(badHashCatalog)
        }
        assertFailsWith<IllegalArgumentException> {
            VehicleCatalogValidator.validate(catalog.copy(prices = catalog.prices.map { it.copy(evidence = emptyList()) }))
        }
    }

    @Test
    fun `validation keeps unknown and provisional facts out of reviewed state`() {
        val unknownFitment =
            catalog.fitments.first().copy(
                status = FitmentStatus.UNKNOWN,
                reviewStatus = FactReviewStatus.REVIEWED,
            )
        val pendingMeasurement =
            catalog.measurements
                .single { it.approvalStatus == MeasurementApprovalStatus.PENDING_HOMOLOGATION }
                .copy(reviewStatus = FactReviewStatus.REVIEWED)

        assertFailsWith<IllegalArgumentException> {
            VehicleCatalogValidator.validate(
                catalog.copy(
                    fitments =
                        catalog.fitments.map {
                            if (it.id == unknownFitment.id) unknownFitment else it
                        },
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            VehicleCatalogValidator.validate(
                catalog.copy(
                    measurements =
                        catalog.measurements.map {
                            if (it.id == pendingMeasurement.id) pendingMeasurement else it
                        },
                ),
            )
        }
    }

    @Test
    fun `validation rejects malformed ranges and reversed price periods`() {
        val incompleteRange =
            catalog.measurements.first().copy(
                value = null,
                minValue = 2.0,
                maxValue = null,
            )
        val reversedPeriod =
            catalog.prices.first().copy(
                effectiveFrom = "2027-01",
                effectiveTo = "2026-11",
            )
        val invalidMonth = catalog.prices.first().copy(effectiveFrom = "2026-13")

        assertFailsWith<IllegalArgumentException> {
            VehicleCatalogValidator.validate(
                catalog.copy(
                    measurements =
                        catalog.measurements.map {
                            if (it.id == incompleteRange.id) incompleteRange else it
                        },
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            VehicleCatalogValidator.validate(
                catalog.copy(prices = catalog.prices.map { if (it.id == reversedPeriod.id) reversedPeriod else it }),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            VehicleCatalogValidator.validate(
                catalog.copy(prices = catalog.prices.map { if (it.id == invalidMonth.id) invalidMonth else it }),
            )
        }
    }
}
