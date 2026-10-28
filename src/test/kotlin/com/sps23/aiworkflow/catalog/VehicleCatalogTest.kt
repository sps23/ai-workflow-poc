package com.sps23.aiworkflow.catalog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        assertEquals(118, decoded.configurations.size)
        assertEquals(14, decoded.models.size)
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
            setOf("6 speed manual", "7 speed auto DSG", "6 speed auto DSG"),
            catalog.configurations
                .filter { it.modelId == "volkswagen-golf" }
                .mapNotNull { it.transmission }
                .toSet(),
        )
    }

    @Test
    fun `Leon variants preserve body style engine codes and published RRPs`() {
        val configurations = catalog.configurations.filter { it.modelId == "cupra-leon" }
        val configurationIds = configurations.map { it.id }.toSet()
        val v1 = configurations.filter { it.trim == "V1" }
        val vz = configurations.filter { it.trim == "VZ" }
        val tribe = configurations.filter { it.trim == "Tribe Edition" }
        val prices = catalog.prices.filter { price -> price.configurationIds.any { it in configurationIds } }

        assertEquals(14, configurations.size)
        assertEquals(8, v1.size)
        assertEquals(4, vz.size)
        assertEquals(2, tribe.size)
        assertEquals(4, v1.count { it.bodyStyle == "5D" })
        assertEquals(4, v1.count { it.bodyStyle == "SP" })
        assertEquals(
            setOf(Powertrain.PETROL, Powertrain.MILD_HYBRID, Powertrain.DIESEL, Powertrain.PLUG_IN_HYBRID),
            v1.map { it.powertrain }.toSet(),
        )
        assertTrue((v1 + vz).all { !it.manufacturerCode.isNullOrBlank() })
        assertTrue(configurations.filter { it.engine == "1.5 TSI 150hp" }.all { it.transmission == null })
        assertTrue(tribe.all { it.bodyStyle == null && it.reviewStatus == FactReviewStatus.NEEDS_REVIEW })
        val fourWheelDrive = vz.single { it.drivetrain == "4WD" }
        assertEquals("SP", fourWheelDrive.bodyStyle)
        assertEquals("2.0 TSI 333hp", fourWheelDrive.engine)
        assertEquals("KUDC3S", fourWheelDrive.manufacturerCode)
        assertEquals("KUGCIZ", vz.single { it.engine == "2.0 TSI 300hp" }.manufacturerCode)
        assertEquals(
            mapOf(
                "cupra-leon-v1-5d-15-tsi-150" to 3_499_500L,
                "cupra-leon-v1-5d-15-etsi-150-dsg" to 3_758_500L,
                "cupra-leon-v1-5d-20-tdi-150-dsg" to 3_980_000L,
                "cupra-leon-v1-5d-ehybrid-204-dsg" to 4_442_500L,
                "cupra-leon-v1-sp-15-tsi-150" to 3_712_000L,
                "cupra-leon-v1-sp-15-etsi-150-dsg" to 3_950_500L,
                "cupra-leon-v1-sp-20-tdi-150-dsg" to 4_179_500L,
                "cupra-leon-v1-sp-ehybrid-204-dsg" to 4_669_000L,
                "cupra-leon-tribe-edition-15-etsi-150-dsg" to 4_164_000L,
                "cupra-leon-tribe-edition-20-tdi-150-dsg" to 4_355_500L,
            ),
            prices.associate { it.configurationIds.single() to it.amountMinorUnits },
        )
        assertEquals(10, prices.size)
        assertTrue(prices.all { it.currency == "EUR" && it.basis == PriceBasis.MANUFACTURER_RRP })
        assertTrue(prices.all { it.effectiveFrom == null && it.effectiveTo == null })
    }

    @Test
    fun `Leon standard equipment preserves SP and Tribe engine restrictions`() {
        val configurations = catalog.configurations.filter { it.modelId == "cupra-leon" }.associateBy { it.id }
        val fitments = catalog.fitments.filter { fitment -> fitment.configurationIds.any { it in configurations } }
        val virtualPedal = fitments.single { it.featureId == "leon-virtual-pedal" }
        val tribeWheels = fitments.single { it.featureId == "leon-tribe-19-inch-alloy-wheels" }
        val windstorm = fitments.single { it.featureId == "leon-windstorm-18-inch-alloy-wheels" }
        val heatedSeats = fitments.single { it.featureId == "heated-front-seats" }

        assertEquals(11, fitments.size)
        assertTrue(fitments.all { it.status == FitmentStatus.STANDARD })
        assertEquals(2, virtualPedal.configurationIds.size)
        assertTrue(virtualPedal.configurationIds.all { configurations.getValue(it).bodyStyle == "SP" })
        assertEquals(listOf("cupra-leon-tribe-edition-15-etsi-150-dsg"), tribeWheels.configurationIds)
        assertEquals(listOf("cupra-leon-tribe-edition-20-tdi-150-dsg"), windstorm.configurationIds)
        assertEquals(
            configurations.values
                .filter { it.trim == "Tribe Edition" }
                .map { it.id }
                .toSet(),
            heatedSeats.configurationIds.toSet(),
        )
        val vzIds =
            configurations.values
                .filter { it.trim == "VZ" }
                .map { it.id }
                .toSet()
        fitments.filter { it.id.endsWith("-vz-standard") }.forEach {
            assertEquals(vzIds, it.configurationIds.toSet())
        }
    }

    @Test
    fun `every Leon citation preserves extracted source wording`() {
        val extracted =
            Json
                .parseToJsonElement(
                    checkNotNull(
                        javaClass.getResource(
                            "/car-pricelists-extracted/cupra/20260727_CUPRA_Ireland_Product_Guide_Leon_PA-982a472bb8a5.json",
                        ),
                    ).readText(),
                ).jsonObject
        val source = extracted.getValue("source").jsonObject
        val documentId = source.getValue("documentId").jsonPrimitive.content
        val sha256 = source.getValue("sha256").jsonPrimitive.content
        val pages =
            extracted.getValue("pages").jsonArray.associate { page ->
                val fields = page.jsonObject
                fields.getValue("pageNumber").jsonPrimitive.int to fields.getValue("text").jsonPrimitive.content
            }
        val citations =
            (
                catalog.configurations.flatMap { it.evidence } +
                    catalog.prices.flatMap { it.evidence } +
                    catalog.measurements.flatMap { it.evidence } +
                    catalog.fitments.flatMap { it.evidence }
            ).filter { it.documentId == documentId }
        val catalogSource = catalog.sources.single { it.documentId == documentId }
        val whitespace = Regex("\\s+")

        assertEquals(sha256, catalogSource.sha256)
        assertEquals(25, catalogSource.pageCount)
        assertEquals(null, catalogSource.effectivePeriod)
        assertTrue(citations.isNotEmpty())
        citations.forEach { citation ->
            assertEquals(sha256, citation.sha256)
            val page = pages.getValue(citation.pageNumber).replace(whitespace, " ").trim()
            val wording = citation.rawText.replace(whitespace, " ").trim()
            assertTrue(page.contains(wording), "Unsupported Leon citation on page ${citation.pageNumber}: $wording")
        }
    }

    @Test
    fun `Born variants preserve trims prices specifications and equipment conditions`() {
        val configurations = catalog.configurations.filter { it.modelId == "cupra-born" }.associateBy { it.id }
        val prices = catalog.prices.filter { it.id.startsWith("born-") }

        assertEquals(5, configurations.size)
        assertEquals(
            setOf("V1", "V2", "VZ"),
            configurations.values.mapNotNull { it.trim }.toSet(),
        )
        assertTrue(configurations.values.all { it.powertrain == Powertrain.ELECTRIC })
        assertEquals(
            setOf("K1GCDJ", "K1GCCH", "K1GD8H"),
            configurations.values.mapNotNull { it.manufacturerCode }.toSet(),
        )
        assertEquals(
            mapOf(
                "cupra-born-urban-v1-190hp-58kwh" to 3_656_500L,
                "cupra-born-urban-v2-190hp-58kwh" to 3_758_500L,
                "cupra-born-endurance-v1-231hp-79kwh" to 4_078_500L,
                "cupra-born-endurance-v2-231hp-79kwh" to 4_180_000L,
                "cupra-born-vz-326hp-79kwh" to 5_052_500L,
            ),
            prices.associate { it.configurationIds.single() to it.amountMinorUnits },
        )
        assertTrue(prices.all { it.basis == PriceBasis.RRP_AFTER_INCENTIVES && it.effectiveFrom == "2026-09-01" })
        assertEquals(FactReviewStatus.NEEDS_REVIEW, configurations.getValue("cupra-born-urban-v1-190hp-58kwh").reviewStatus)
        assertEquals(483.0, catalog.measurements.single { it.id == "born-urban-range" }.value)
        assertEquals(626.0, catalog.measurements.single { it.id == "born-endurance-range" }.value)
        assertEquals(629.0, catalog.measurements.single { it.id == "born-vz-range" }.value)
        assertEquals(2766.0, catalog.measurements.single { it.id == "born-wheelbase" }.value)
        assertEquals(385.0, catalog.measurements.single { it.id == "born-boot-capacity" }.value)
        assertTrue(
            listOf(
                "born-urban-acceleration",
                "born-endurance-acceleration",
                "born-vz-acceleration",
                "born-urban-top-speed",
                "born-endurance-vz-top-speed",
                "born-urban-maximum-torque",
                "born-endurance-vz-maximum-torque",
            ).all { id -> catalog.measurements.single { it.id == id }.reviewStatus == FactReviewStatus.NEEDS_REVIEW },
        )

        val v1Features =
            catalog.fitments.filter {
                it.id.startsWith("born-") && it.status == FitmentStatus.STANDARD &&
                    it.featureId != "born-heated-steering-wheel" &&
                    it.featureId !in
                    setOf(
                        "born-rear-view-camera",
                        "born-two-zone-climate-control",
                        "born-digital-key",
                        "born-keyless-advanced",
                        "born-vehicle-to-load",
                        "born-vehicle-to-home",
                    ) &&
                    !it.id.contains("-vz-standard")
            }
        assertTrue(v1Features.isNotEmpty())
        assertTrue(v1Features.all { it.configurationIds.toSet() == configurations.keys })
        assertEquals(
            setOf("cupra-born-endurance-v1-231hp-79kwh", "cupra-born-endurance-v2-231hp-79kwh", "cupra-born-vz-326hp-79kwh"),
            catalog.fitments
                .single { it.featureId == "born-heated-steering-wheel" }
                .configurationIds
                .toSet(),
        )
        val v2h = catalog.fitments.single { it.featureId == "born-vehicle-to-home" }
        assertEquals(FitmentStatus.STANDARD, v2h.status)
        assertTrue(v2h.configurationIds.all { configurations.getValue(it).engine?.contains("79kWh") == true })
        val unresolvedOptions =
            catalog.fitments.filter {
                it.id.startsWith("born-") && it.status == FitmentStatus.UNKNOWN
            }
        assertEquals(5, unresolvedOptions.size)
        assertTrue(unresolvedOptions.all { it.reviewStatus == FactReviewStatus.NEEDS_REVIEW })
    }

    @Test
    fun `every Born citation preserves extracted source wording`() {
        val extracted =
            Json
                .parseToJsonElement(
                    checkNotNull(
                        javaClass.getResource(
                            "/car-pricelists-extracted/cupra/20260820_CUPRA_Ireland_Product_Guide_New_Born_PA-cde7019de37c.json",
                        ),
                    ).readText(),
                ).jsonObject
        val source = extracted.getValue("source").jsonObject
        val documentId = source.getValue("documentId").jsonPrimitive.content
        val sha256 = source.getValue("sha256").jsonPrimitive.content
        val pages =
            extracted.getValue("pages").jsonArray.associate { page ->
                val fields = page.jsonObject
                fields.getValue("pageNumber").jsonPrimitive.int to fields.getValue("text").jsonPrimitive.content
            }
        val citations =
            (
                catalog.configurations.flatMap { it.evidence } +
                    catalog.prices.flatMap { it.evidence } +
                    catalog.measurements.flatMap { it.evidence } +
                    catalog.fitments.flatMap { it.evidence }
            ).filter { it.documentId == documentId }
        val catalogSource = catalog.sources.single { it.documentId == documentId }
        val whitespace = Regex("\\s+")

        assertEquals(sha256, catalogSource.sha256)
        assertEquals(18, catalogSource.pageCount)
        assertEquals("2026-09-01", catalogSource.effectivePeriod)
        assertTrue(citations.isNotEmpty())
        citations.forEach { citation ->
            assertEquals(sha256, citation.sha256)
            val page = pages.getValue(citation.pageNumber).replace(whitespace, " ").trim()
            val wording = citation.rawText.replace(whitespace, " ").trim()
            assertTrue(page.contains(wording), "Unsupported Born citation on page ${citation.pageNumber}: $wording")
        }
    }

    @Test
    fun `Raval variants preserve trims prices specifications and unresolved equipment`() {
        val configurations = catalog.configurations.filter { it.modelId == "cupra-raval" }.associateBy { it.id }
        val prices = catalog.prices.filter { it.id.startsWith("raval-") }

        assertEquals(5, configurations.size)
        assertEquals(
            setOf("V1", "V2", "VZ"),
            configurations.values.mapNotNull { it.trim }.toSet(),
        )
        assertTrue(configurations.values.all { it.powertrain == Powertrain.ELECTRIC })
        assertEquals(
            setOf("KS1AAF", "KS1A0F", "KS1B8E", "KS1C9E"),
            configurations.values.mapNotNull { it.manufacturerCode }.toSet(),
        )
        assertEquals(
            mapOf(
                "cupra-raval-urban-v1-116hp-37kwh" to 2_349_500L,
                "cupra-raval-urban-v2-135hp-37kwh" to 2_896_000L,
                "cupra-raval-endurance-v1-211hp-52kwh" to 3_212_500L,
                "cupra-raval-endurance-v2-211hp-52kwh" to 3_311_000L,
                "cupra-raval-vz-226hp-52kwh" to 3_682_500L,
            ),
            prices.associate { it.configurationIds.single() to it.amountMinorUnits },
        )
        assertTrue(prices.all { it.basis == PriceBasis.RRP_AFTER_INCENTIVES && it.effectiveFrom == "2026-09-01" })
        assertEquals(116.0, catalog.measurements.single { it.id == "raval-urban-v1-power" }.value)
        assertEquals(135.0, catalog.measurements.single { it.id == "raval-urban-v2-power" }.value)
        assertEquals(443.0, catalog.measurements.single { it.id == "raval-endurance-range" }.value)
        assertEquals(440.0, catalog.measurements.single { it.id == "raval-vz-range" }.value)
        assertEquals(37.0, catalog.measurements.single { it.id == "raval-urban-battery-capacity" }.value)
        assertEquals(52.0, catalog.measurements.single { it.id == "raval-endurance-vz-battery-capacity" }.value)
        assertEquals(4046.0, catalog.measurements.single { it.id == "raval-length" }.value)
        assertEquals(2599.0, catalog.measurements.single { it.id == "raval-wheelbase" }.value)
        assertEquals(441.0, catalog.measurements.single { it.id == "raval-boot-capacity" }.value)
        assertEquals(
            FactReviewStatus.NEEDS_REVIEW,
            configurations.getValue("cupra-raval-urban-v1-116hp-37kwh").reviewStatus,
        )
        assertEquals(
            FactReviewStatus.NEEDS_REVIEW,
            configurations.getValue("cupra-raval-urban-v2-135hp-37kwh").reviewStatus,
        )

        val fitments = catalog.fitments.filter { it.id.startsWith("raval-") }
        val vz = configurations.values.single { it.trim == "VZ" }
        assertTrue(
            fitments.single { it.featureId == "raval-19-inch-rebel-alloys" }.let {
                it.status == FitmentStatus.STANDARD && it.configurationIds == listOf(vz.id)
            },
        )
        assertEquals(
            FitmentStatus.STANDARD,
            fitments.single { it.featureId == "raval-adaptive-cruise-control" }.status,
        )
        assertEquals(12, fitments.count { it.status == FitmentStatus.UNKNOWN })
        assertTrue(
            fitments.filter { it.status == FitmentStatus.UNKNOWN }.all {
                it.reviewStatus == FactReviewStatus.NEEDS_REVIEW
            },
        )
    }

    @Test
    fun `every Raval citation preserves extracted source wording`() {
        val extracted =
            Json
                .parseToJsonElement(
                    checkNotNull(
                        javaClass.getResource(
                            "/car-pricelists-extracted/cupra/20260820_CUPRA_Ireland_Product_Guide_Raval-93785a566a1f.json",
                        ),
                    ).readText(),
                ).jsonObject
        val source = extracted.getValue("source").jsonObject
        val documentId = source.getValue("documentId").jsonPrimitive.content
        val sha256 = source.getValue("sha256").jsonPrimitive.content
        val pages =
            extracted.getValue("pages").jsonArray.associate { page ->
                val fields = page.jsonObject
                fields.getValue("pageNumber").jsonPrimitive.int to fields.getValue("text").jsonPrimitive.content
            }
        val citations =
            (
                catalog.configurations.flatMap { it.evidence } +
                    catalog.prices.flatMap { it.evidence } +
                    catalog.measurements.flatMap { it.evidence } +
                    catalog.fitments.flatMap { it.evidence }
            ).filter { it.documentId == documentId }
        val catalogSource = catalog.sources.single { it.documentId == documentId }
        val whitespace = Regex("\\s+")

        assertEquals(sha256, catalogSource.sha256)
        assertEquals(19, catalogSource.pageCount)
        assertEquals("2026-09-01", catalogSource.effectivePeriod)
        assertTrue(citations.isNotEmpty())
        citations.forEach { citation ->
            assertEquals(sha256, citation.sha256)
            val page = pages.getValue(citation.pageNumber).replace(whitespace, " ").trim()
            val wording = citation.rawText.replace(whitespace, " ").trim()
            assertTrue(page.contains(wording), "Unsupported Raval citation on page ${citation.pageNumber}: $wording")
        }
    }

    @Test
    fun `Formentor configurations preserve published prices and technical specifications`() {
        val configurations = catalog.configurations.filter { it.modelId == "cupra-formentor" }.associateBy { it.id }
        val prices = catalog.prices.filter { it.id.startsWith("formentor-") }

        assertEquals(11, configurations.size)
        assertEquals(
            mapOf("V1" to 4, "V2" to 4, "VZ" to 2, "Tribe Edition" to 1),
            configurations.values.groupingBy { it.trim!! }.eachCount(),
        )
        assertEquals(
            setOf(Powertrain.PETROL, Powertrain.MILD_HYBRID, Powertrain.DIESEL, Powertrain.PLUG_IN_HYBRID),
            configurations.values.map { it.powertrain }.toSet(),
        )
        assertEquals(
            mapOf(
                "cupra-formentor-v1-15-tsi-150" to 4_012_000L,
                "cupra-formentor-v1-15-etsi-150-dsg" to 4_381_500L,
                "cupra-formentor-v1-20-tdi-150-dsg" to 4_508_500L,
                "cupra-formentor-v1-ehybrid-204-dsg" to 4_573_500L,
                "cupra-formentor-v2-15-tsi-150" to 4_312_500L,
                "cupra-formentor-v2-15-etsi-150-dsg" to 4_682_000L,
                "cupra-formentor-v2-20-tdi-150-dsg" to 4_806_000L,
                "cupra-formentor-v2-ehybrid-204-dsg" to 4_836_000L,
                "cupra-formentor-vz-ehybrid-272-dsg" to 5_451_000L,
                "cupra-formentor-vz-20-tsi-333-dsg-4wd" to 7_512_000L,
                "cupra-formentor-tribe-edition-ehybrid-dsg" to 4_836_000L,
            ),
            prices.associate { it.configurationIds.single() to it.amountMinorUnits },
        )
        assertTrue(prices.all { it.basis == PriceBasis.MANUFACTURER_RRP && it.effectiveFrom == "2026-09-01" })
        assertEquals(
            126.0,
            catalog.measurements.single { it.id == "formentor-kmpb0y-electric-range" }.value,
        )
        assertEquals(
            118.0,
            catalog.measurements.single { it.id == "formentor-kmpc1y-electric-range" }.value,
        )
        assertEquals(
            2679.0,
            catalog.measurements.single { it.id == "formentor-kmpbnx-wheelbase" }.value,
        )
        assertEquals(
            204.0,
            catalog.measurements.single { it.id == "formentor-kmpb0y-power" }.value,
        )
        val tribe = configurations.getValue("cupra-formentor-tribe-edition-ehybrid-dsg")
        assertEquals(Powertrain.PLUG_IN_HYBRID, tribe.powertrain)
        assertEquals(null, tribe.engine)
        assertEquals(FactReviewStatus.NEEDS_REVIEW, tribe.reviewStatus)
        assertEquals(FactReviewStatus.NEEDS_REVIEW, prices.single { tribe.id in it.configurationIds }.reviewStatus)

        val fitments = catalog.fitments.filter { it.id.startsWith("formentor-") }
        val unresolvedOptions = fitments.filter { it.status == FitmentStatus.UNKNOWN }
        assertEquals(18, unresolvedOptions.size)
        assertTrue(unresolvedOptions.all { it.reviewStatus == FactReviewStatus.NEEDS_REVIEW })
        assertTrue(
            fitments.any {
                it.featureId == "formentor-adaptive-cruise-control" &&
                    it.status == FitmentStatus.STANDARD &&
                    it.configurationIds.containsAll(
                        configurations.values
                            .filter { config ->
                                config.trim == "V1"
                            }.map { config -> config.id },
                    )
            },
        )
        assertEquals(
            listOf("cupra-formentor-vz-20-tsi-333-dsg-4wd"),
            fitments.single { it.featureId == "formentor-4wd-vz" }.configurationIds,
        )
        assertTrue(
            fitments.filter { it.configurationIds.contains(tribe.id) }.all {
                it.reviewStatus == FactReviewStatus.NEEDS_REVIEW
            },
        )
    }

    @Test
    fun `every Formentor citation preserves extracted source wording`() {
        val extracted =
            Json
                .parseToJsonElement(
                    checkNotNull(
                        javaClass.getResource(
                            "/car-pricelists-extracted/cupra/20270727_CUPRA_Ireland_Product_Guide_Formentor_PA-0b17a160d4bf.json",
                        ),
                    ).readText(),
                ).jsonObject
        val source = extracted.getValue("source").jsonObject
        val documentId = source.getValue("documentId").jsonPrimitive.content
        val sha256 = source.getValue("sha256").jsonPrimitive.content
        val pages =
            extracted.getValue("pages").jsonArray.associate { page ->
                val fields = page.jsonObject
                fields.getValue("pageNumber").jsonPrimitive.int to fields.getValue("text").jsonPrimitive.content
            }
        val citations =
            (
                catalog.configurations.flatMap { it.evidence } +
                    catalog.prices.flatMap { it.evidence } +
                    catalog.measurements.flatMap { it.evidence } +
                    catalog.fitments.flatMap { it.evidence }
            ).filter { it.documentId == documentId }
        val catalogSource = catalog.sources.single { it.documentId == documentId }
        val whitespace = Regex("\\s+")

        assertEquals(sha256, catalogSource.sha256)
        assertEquals(20, catalogSource.pageCount)
        assertEquals("2026-09-01", catalogSource.effectivePeriod)
        assertTrue(citations.isNotEmpty())
        citations.forEach { citation ->
            assertEquals(sha256, citation.sha256)
            val page = pages.getValue(citation.pageNumber).replace(whitespace, " ").trim()
            val wording = citation.rawText.replace(whitespace, " ").trim()
            assertTrue(page.contains(wording), "Unsupported Formentor citation on page ${citation.pageNumber}: $wording")
        }
    }

    @Test
    fun `Peaq configurations preserve prices battery variants and technical specifications`() {
        val configurations = catalog.configurations.filter { it.modelId == "skoda-peaq" }.associateBy { it.id }
        val prices = catalog.prices.filter { it.id.startsWith("skoda-peaq-") }

        assertEquals(5, configurations.size)
        assertEquals(
            setOf("Selection 60", "Selection 90", "Selection 90X", "Sportline 90", "Sportline 90X"),
            configurations.values.mapNotNull { it.trim }.toSet(),
        )
        assertTrue(configurations.values.all { it.modelYear == 2027 && it.powertrain == Powertrain.ELECTRIC })
        assertEquals(
            setOf("PZ7DF2", "PZ7DP2", "PZ7DT4", "PZ7RP2", "PZ7RT4"),
            configurations.values.mapNotNull { it.manufacturerCode }.toSet(),
        )
        assertEquals(
            mapOf(
                "skoda-peaq-selection-60-150kw-63kwh" to 5_499_600L,
                "skoda-peaq-selection-90-210kw-91kwh" to 5_815_000L,
                "skoda-peaq-selection-90x-220kw-91kwh" to 5_965_000L,
                "skoda-peaq-sportline-90-210kw-91kwh" to 6_155_000L,
                "skoda-peaq-sportline-90x-220kw-91kwh" to 6_305_000L,
            ),
            prices.filter { it.featureId == null }.associate { it.configurationIds.single() to it.amountMinorUnits },
        )
        assertTrue(prices.filter { it.featureId == null }.all { it.basis == PriceBasis.MANUFACTURER_RRP })
        assertTrue(prices.filter { it.featureId == null }.all { it.effectiveFrom == "2026-08" })
        assertEquals(59.0, catalog.measurements.single { it.id == "skoda-peaq-60-battery-net" }.value)
        assertEquals(86.0, catalog.measurements.single { it.id == "skoda-peaq-90-battery-net" }.value)
        assertEquals(77.0, catalog.measurements.single { it.id == "skoda-peaq-90x-battery-net" }.value)
        assertEquals(452.0, catalog.measurements.single { it.id == "skoda-peaq-selection-60-range" }.value)
        assertEquals(637.0, catalog.measurements.single { it.id == "skoda-peaq-selection-90-range" }.value)
        assertEquals(
            FactReviewStatus.NEEDS_REVIEW,
            catalog.measurements.single { it.id == "skoda-peaq-selection-90x-range" }.reviewStatus,
        )
        assertEquals(
            FactReviewStatus.NEEDS_REVIEW,
            catalog.measurements.single { it.id == "skoda-peaq-sportline-90-range" }.reviewStatus,
        )
        assertEquals(596.0, catalog.measurements.single { it.id == "skoda-peaq-sportline-90x-range" }.value)
        assertEquals(4874.0, catalog.measurements.single { it.id == "skoda-peaq-length" }.value)
        assertEquals(2970.0, catalog.measurements.single { it.id == "skoda-peaq-wheelbase" }.value)
        assertEquals(890.0, catalog.measurements.single { it.id == "skoda-peaq-luggage-capacity" }.value)
        assertEquals("4x4", configurations.getValue("skoda-peaq-selection-90x-220kw-91kwh").drivetrain)
        assertEquals(2000.0, catalog.measurements.single { it.id == "skoda-peaq-90x-braked-towing-capacity" }.value)
        assertEquals(152.3, catalog.measurements.single { it.id == "skoda-peaq-selection-90-energy-consumption" }.value)

        val fitments = catalog.fitments.filter { it.id.startsWith("skoda-peaq-") }
        assertTrue(
            fitments.single { it.featureId == "skoda-peaq-whale-19-inch-alloy-wheels" }.let {
                it.status == FitmentStatus.STANDARD &&
                    it.configurationIds.toSet() ==
                    configurations.values
                        .filter { config -> config.trim?.startsWith("Selection") == true }
                        .map { config -> config.id }
                        .toSet()
            },
        )
        assertEquals(
            listOf("skoda-peaq-sportline-90-210kw-91kwh", "skoda-peaq-sportline-90x-220kw-91kwh"),
            fitments.single { it.featureId == "skoda-peaq-sportline-okapi-20-inch-alloy-wheels" }.configurationIds,
        )
        assertEquals(21, fitments.count { it.status == FitmentStatus.UNKNOWN })
        assertTrue(fitments.filter { it.status == FitmentStatus.UNKNOWN }.all { it.reviewStatus == FactReviewStatus.NEEDS_REVIEW })
    }

    @Test
    fun `Superb configurations preserve body styles powertrains prices and specifications`() {
        val configurations = catalog.configurations.filter { it.modelId == "skoda-superb" }
        val prices = catalog.prices.filter { it.id.startsWith("skoda-superb-") }

        assertEquals(24, configurations.size)
        assertEquals(setOf("Saloon", "Combi"), configurations.map { it.bodyStyle }.toSet())
        assertEquals(setOf("Selection", "Selection +", "Sportline", "L&K"), configurations.map { it.trim }.toSet())
        assertEquals(
            setOf(Powertrain.MILD_HYBRID, Powertrain.DIESEL, Powertrain.PLUG_IN_HYBRID),
            configurations.map { it.powertrain }.toSet(),
        )
        assertEquals(24, prices.size)
        assertEquals(
            4_997_500L,
            prices
                .single {
                    it.configurationIds.single() == "skoda-superb-saloon-selection-15tsi-158-mhev"
                }.amountMinorUnits,
        )
        assertEquals(
            6_109_000L,
            prices
                .single {
                    it.configurationIds.single() == "skoda-superb-saloon-lk-20tdi-150"
                }.amountMinorUnits,
        )
        assertTrue(prices.all { it.currency == "EUR" && it.effectiveFrom == "2027-01" })
        assertTrue(prices.all { it.basis == PriceBasis.MANUFACTURER_RRP && it.reviewStatus == FactReviewStatus.NEEDS_REVIEW })
        assertEquals(4912.0, catalog.measurements.single { it.id == "skoda-superb-saloon-15tsi-158-mhev-length" }.value)
        assertEquals(4902.0, catalog.measurements.single { it.id == "skoda-superb-combi-15tsi-158-mhev-length" }.value)
        assertEquals(130.0, catalog.measurements.single { it.id == "skoda-superb-saloon-phev-electric-range" }.minValue)
        assertEquals(125.0, catalog.measurements.single { it.id == "skoda-superb-combi-phev-electric-range" }.minValue)
        assertTrue(
            catalog.measurements
                .filter { it.id.contains("phev-battery-capacity") }
                .all { it.value == 25.0 && it.reviewStatus == FactReviewStatus.NEEDS_REVIEW },
        )
        assertEquals(20, catalog.fitments.count { it.id.startsWith("skoda-superb-") && it.status == FitmentStatus.UNKNOWN })
    }

    @Test
    fun `every Superb citation preserves extracted source wording`() {
        val extracted =
            Json
                .parseToJsonElement(
                    checkNotNull(
                        javaClass.getResource(
                            "/car-pricelists-extracted/skoda/Superb_MY27_PG_Janury_CW01_2027-6f0799c9f5c8.json",
                        ),
                    ).readText(),
                ).jsonObject
        val source = extracted.getValue("source").jsonObject
        val documentId = source.getValue("documentId").jsonPrimitive.content
        val sha256 = source.getValue("sha256").jsonPrimitive.content
        val pages =
            extracted.getValue("pages").jsonArray.associate { page ->
                val fields = page.jsonObject
                fields.getValue("pageNumber").jsonPrimitive.int to fields.getValue("text").jsonPrimitive.content
            }
        val citations =
            (
                catalog.configurations.flatMap { it.evidence } +
                    catalog.prices.flatMap { it.evidence } +
                    catalog.measurements.flatMap { it.evidence } +
                    catalog.fitments.flatMap { it.evidence }
            ).filter { it.documentId == documentId }
        val catalogSource = catalog.sources.single { it.documentId == documentId }
        val whitespace = Regex("\\s+")

        assertEquals(sha256, catalogSource.sha256)
        assertEquals(18, catalogSource.pageCount)
        assertEquals("2027-01", catalogSource.effectivePeriod)
        assertTrue(citations.isNotEmpty())
        citations.forEach { citation ->
            assertEquals(sha256, citation.sha256)
            val page = pages.getValue(citation.pageNumber).replace(whitespace, " ").trim()
            val wording = citation.rawText.replace(whitespace, " ").trim()
            assertTrue(page.contains(wording), "Unsupported Superb citation on page ${citation.pageNumber}: $wording")
        }
    }

    @Test
    fun `Kodiaq configurations preserve engine variants prices and technical specifications`() {
        val configurations = catalog.configurations.filter { it.modelId == "skoda-kodiaq" }.associateBy { it.id }
        val prices = catalog.prices.filter { it.id.startsWith("skoda-kodiaq-") }

        assertEquals(12, configurations.size)
        assertEquals(setOf("Selection", "Selection +", "Sportline"), configurations.values.map { it.trim }.toSet())
        assertEquals(
            setOf(Powertrain.MILD_HYBRID, Powertrain.PLUG_IN_HYBRID, Powertrain.DIESEL),
            configurations.values.map { it.powertrain }.toSet(),
        )
        assertEquals(3, configurations.values.count { it.seatingCapacity == 5 })
        assertEquals(9, configurations.values.count { it.seatingCapacity == 7 })
        assertEquals(12, prices.size)
        assertEquals(
            mapOf(
                "skoda-kodiaq-selection-15tsi-150-mhev" to 5_769_000L,
                "skoda-kodiaq-selection-plus-15tsi-150-mhev" to 6_159_000L,
                "skoda-kodiaq-sportline-15tsi-150-mhev" to 6_400_500L,
                "skoda-kodiaq-selection-15tsi-202-phev" to 5_423_500L,
                "skoda-kodiaq-selection-plus-15tsi-202-phev" to 5_859_500L,
                "skoda-kodiaq-sportline-15tsi-202-phev" to 5_899_500L,
                "skoda-kodiaq-selection-20tdi-150" to 5_698_000L,
                "skoda-kodiaq-selection-plus-20tdi-150" to 6_247_500L,
                "skoda-kodiaq-sportline-20tdi-150" to 6_274_000L,
                "skoda-kodiaq-selection-20tdi-190-4x4" to 6_536_000L,
                "skoda-kodiaq-selection-plus-20tdi-190-4x4" to 7_083_000L,
                "skoda-kodiaq-sportline-20tdi-190-4x4" to 7_317_000L,
            ),
            prices.associate { it.configurationIds.single() to it.amountMinorUnits },
        )
        assertTrue(prices.all { it.currency == "EUR" && it.effectiveFrom == "2027-01" })
        assertTrue(prices.all { it.basis == PriceBasis.MANUFACTURER_RRP })
        assertTrue(prices.all { it.reviewStatus == FactReviewStatus.NEEDS_REVIEW })
        assertEquals(4758.0, catalog.measurements.single { it.id == "skoda-kodiaq-length" }.value)
        assertEquals(1864.0, catalog.measurements.single { it.id == "skoda-kodiaq-width" }.value)
        assertEquals(142.0, catalog.measurements.single { it.id == "skoda-kodiaq-20tdi-190-4x4-power" }.value)
        assertEquals(116.0, catalog.measurements.single { it.id == "skoda-kodiaq-phev-range" }.minValue)
        assertEquals(
            setOf(19.7, 25.0),
            catalog.measurements
                .filter { it.id.startsWith("skoda-kodiaq-phev-battery-") }
                .mapNotNull { it.value }
                .toSet(),
        )
        assertTrue(
            catalog.measurements
                .filter { it.id.startsWith("skoda-kodiaq-phev-battery-") }
                .all { it.reviewStatus == FactReviewStatus.NEEDS_REVIEW },
        )
        val fitments = catalog.fitments.filter { it.id.startsWith("skoda-kodiaq-") }
        assertEquals(20, fitments.count { it.status == FitmentStatus.UNKNOWN })
        assertTrue(fitments.filter { it.status == FitmentStatus.UNKNOWN }.all { it.reviewStatus == FactReviewStatus.NEEDS_REVIEW })
        assertEquals(
            setOf(
                "skoda-kodiaq-selection-15tsi-150-mhev",
                "skoda-kodiaq-selection-15tsi-202-phev",
                "skoda-kodiaq-selection-20tdi-150",
                "skoda-kodiaq-selection-20tdi-190-4x4",
            ),
            fitments.single { it.featureId == "skoda-kodiaq-soira-18-inch-wheels" }.configurationIds.toSet(),
        )
    }

    @Test
    fun `every Kodiaq citation preserves extracted source wording`() {
        val extracted =
            Json
                .parseToJsonElement(
                    checkNotNull(
                        javaClass.getResource(
                            "/car-pricelists-extracted/skoda/Kodiaq_MY27_PG_January_CW01_2027-fe57b225bd7c.json",
                        ),
                    ).readText(),
                ).jsonObject
        val source = extracted.getValue("source").jsonObject
        val documentId = source.getValue("documentId").jsonPrimitive.content
        val sha256 = source.getValue("sha256").jsonPrimitive.content
        val pages =
            extracted.getValue("pages").jsonArray.associate { page ->
                val fields = page.jsonObject
                fields.getValue("pageNumber").jsonPrimitive.int to fields.getValue("text").jsonPrimitive.content
            }
        val citations =
            (
                catalog.configurations.flatMap { it.evidence } +
                    catalog.prices.flatMap { it.evidence } +
                    catalog.measurements.flatMap { it.evidence } +
                    catalog.fitments.flatMap { it.evidence }
            ).filter { it.documentId == documentId }
        val catalogSource = catalog.sources.single { it.documentId == documentId }
        val whitespace = Regex("\\s+")

        assertEquals(sha256, catalogSource.sha256)
        assertEquals(16, catalogSource.pageCount)
        assertEquals("2027-01", catalogSource.effectivePeriod)
        assertTrue(citations.isNotEmpty())
        citations.forEach { citation ->
            assertEquals(sha256, citation.sha256)
            val page = pages.getValue(citation.pageNumber).replace(whitespace, " ").trim()
            val wording = citation.rawText.replace(whitespace, " ").trim()
            assertTrue(page.contains(wording), "Unsupported Kodiaq citation on page ${citation.pageNumber}: $wording")
        }
    }

    @Test
    fun `every Peaq citation preserves extracted source wording`() {
        val extracted =
            Json
                .parseToJsonElement(
                    checkNotNull(
                        javaClass.getResource(
                            "/car-pricelists-extracted/skoda/Peaq_MY27_PG_August_CW48_2026-67ef26b49bd2.json",
                        ),
                    ).readText(),
                ).jsonObject
        val source = extracted.getValue("source").jsonObject
        val documentId = source.getValue("documentId").jsonPrimitive.content
        val sha256 = source.getValue("sha256").jsonPrimitive.content
        val pages =
            extracted.getValue("pages").jsonArray.associate { page ->
                val fields = page.jsonObject
                fields.getValue("pageNumber").jsonPrimitive.int to fields.getValue("text").jsonPrimitive.content
            }
        val citations =
            (
                catalog.configurations.flatMap { it.evidence } +
                    catalog.prices.flatMap { it.evidence } +
                    catalog.measurements.flatMap { it.evidence } +
                    catalog.fitments.flatMap { it.evidence }
            ).filter { it.documentId == documentId }
        val catalogSource = catalog.sources.single { it.documentId == documentId }
        val whitespace = Regex("\\s+")

        assertEquals(sha256, catalogSource.sha256)
        assertEquals(13, catalogSource.pageCount)
        assertEquals("2026-08", catalogSource.effectivePeriod)
        assertTrue(citations.isNotEmpty())
        citations.forEach { citation ->
            assertEquals(sha256, citation.sha256)
            val page = pages.getValue(citation.pageNumber).replace(whitespace, " ").trim()
            val wording = citation.rawText.replace(whitespace, " ").trim()
            assertTrue(page.contains(wording), "Unsupported Peaq citation on page ${citation.pageNumber}: $wording")
        }
    }

    @Test
    fun `Epiq covers four electric variants with prices specifications and reviewed equipment`() {
        val configurations = catalog.configurations.filter { it.modelId == "skoda-epiq" }.associateBy { it.id }
        val prices = catalog.prices.filter { price -> price.configurationIds.any { it in configurations } }
        val fitments = catalog.fitments.filter { fitment -> fitment.configurationIds.any { it in configurations } }

        assertEquals(
            setOf(
                "skoda-epiq-choice-35-85kw-38-5kwh",
                "skoda-epiq-essence-40-99kw-38-5kwh",
                "skoda-epiq-selection-40-99kw-38-5kwh",
                "skoda-epiq-selection-55-155kw-55kwh",
            ),
            configurations.keys,
        )
        assertTrue(configurations.values.all { it.modelYear == 2027 && it.powertrain == Powertrain.ELECTRIC })
        assertTrue(configurations.values.all { it.drivetrain == "4x2" })
        assertEquals(
            mapOf(
                "skoda-epiq-choice-35-85kw-38-5kwh" to 2_999_000L,
                "skoda-epiq-essence-40-99kw-38-5kwh" to 3_249_000L,
                "skoda-epiq-selection-40-99kw-38-5kwh" to 3_549_000L,
                "skoda-epiq-selection-55-155kw-55kwh" to 3_899_000L,
            ),
            prices.filter { it.featureId == null }.associate { it.configurationIds.single() to it.amountMinorUnits },
        )
        assertTrue(prices.filter { it.featureId == null }.all { it.basis == PriceBasis.MANUFACTURER_RRP })
        assertTrue(prices.filter { it.featureId == null }.all { it.effectiveFrom == "2026-09" })
        assertEquals(37.0, catalog.measurements.single { it.id == "skoda-epiq-40-battery-net" }.value)
        assertEquals(52.0, catalog.measurements.single { it.id == "skoda-epiq-55-battery-net" }.value)
        assertEquals(327.0, catalog.measurements.single { it.id == "skoda-epiq-essence-40-range" }.value)
        assertEquals(435.0, catalog.measurements.single { it.id == "skoda-epiq-selection-55-range" }.value)
        assertEquals(141.0, catalog.measurements.single { it.id == "skoda-epiq-selection-40-consumption" }.value)
        assertEquals(4171.0, catalog.measurements.single { it.id == "skoda-epiq-dimensions" }.value)
        assertEquals(475.0, catalog.measurements.single { it.id == "skoda-epiq-luggage-capacity" }.value)
        assertEquals(
            MeasurementApprovalStatus.PENDING_HOMOLOGATION,
            catalog.measurements.single { it.id == "skoda-epiq-choice-35-range" }.approvalStatus,
        )
        assertEquals(
            FactReviewStatus.NEEDS_REVIEW,
            catalog.measurements.single { it.id == "skoda-epiq-choice-35-consumption" }.reviewStatus,
        )

        val digitalKey = fitments.single { it.featureId == "skoda-epiq-mobile-digital-key" }
        assertEquals(FitmentStatus.UNKNOWN, digitalKey.status)
        assertEquals(FactReviewStatus.NEEDS_REVIEW, digitalKey.reviewStatus)
        val unresolvedOptions = fitments.filter { it.status == FitmentStatus.UNKNOWN }
        assertTrue(unresolvedOptions.isNotEmpty())
        assertTrue(unresolvedOptions.all { it.reviewStatus == FactReviewStatus.NEEDS_REVIEW })
        assertEquals(1, fitments.count { it.featureId == "skoda-epiq-ev-plan" && it.status == FitmentStatus.OPTIONAL })
        assertEquals(
            125_000L,
            prices.single { it.featureId == "skoda-epiq-ev-plan" }.amountMinorUnits,
        )
        assertEquals(
            setOf(3, 4, 5),
            fitments
                .filter { it.status == FitmentStatus.STANDARD }
                .flatMap { it.evidence }
                .map { it.pageNumber }
                .filter { it in 3..5 }
                .toSet(),
        )
    }

    @Test
    fun `every Epiq citation preserves extracted source wording`() {
        val extracted =
            Json
                .parseToJsonElement(
                    checkNotNull(
                        javaClass.getResource(
                            "/car-pricelists-extracted/skoda/Epiq_MY27_PG_September_CW48_2026-6c4deb69213e.json",
                        ),
                    ).readText(),
                ).jsonObject
        val source = extracted.getValue("source").jsonObject
        val documentId = source.getValue("documentId").jsonPrimitive.content
        val sha256 = source.getValue("sha256").jsonPrimitive.content
        val pages =
            extracted.getValue("pages").jsonArray.associate { page ->
                val fields = page.jsonObject
                fields.getValue("pageNumber").jsonPrimitive.int to fields.getValue("text").jsonPrimitive.content
            }
        val citations =
            (
                catalog.configurations.flatMap { it.evidence } +
                    catalog.prices.flatMap { it.evidence } +
                    catalog.measurements.flatMap { it.evidence } +
                    catalog.fitments.flatMap { it.evidence }
            ).filter { it.documentId == documentId }
        val catalogSource = catalog.sources.single { it.documentId == documentId }
        val whitespace = Regex("\\s+")

        assertEquals(sha256, catalogSource.sha256)
        assertEquals(14, catalogSource.pageCount)
        assertEquals("2026-09", catalogSource.effectivePeriod)
        assertTrue(citations.isNotEmpty())
        citations.forEach { citation ->
            assertEquals(sha256, citation.sha256)
            val page = pages.getValue(citation.pageNumber).replace(whitespace, " ").trim()
            val wording = citation.rawText.replace(whitespace, " ").trim()
            assertTrue(page.contains(wording), "Unsupported Epiq citation on page ${citation.pageNumber}: $wording")
        }
    }

    @Test
    fun `3008 electric variants preserve provisional prices and equipment mappings`() {
        val configurations = catalog.configurations.filter { it.modelId == "peugeot-3008" }
        val configurationIds = configurations.map { it.id }.toSet()
        val prices = catalog.prices.filter { price -> price.configurationIds.any { it in configurationIds } }
        val fitments = catalog.fitments.filter { fitment -> fitment.configurationIds.any { it in configurationIds } }

        assertEquals(4, configurations.size)
        assertEquals(setOf("Allure", "GT", "GT Exclusive"), configurations.map { it.trim }.toSet())
        assertTrue(configurations.all { it.powertrain == Powertrain.ELECTRIC })
        assertTrue(configurations.all { it.reviewStatus == FactReviewStatus.NEEDS_REVIEW })
        assertEquals(
            "GT Exclusive",
            configurations.single { it.engine == "Dual Motor Electric 73kWh 325" }.trim,
        )
        assertEquals(4, prices.size)
        assertEquals(setOf(4_075_000L, 4_559_500L, 5_109_500L, 5_999_500L), prices.map { it.amountMinorUnits }.toSet())
        assertTrue(prices.all { it.currency == "EUR" && it.effectiveFrom == "2026-11" })
        assertTrue(prices.all { it.basis == PriceBasis.OTHER && it.reviewStatus == FactReviewStatus.NEEDS_REVIEW })
        assertTrue(prices.all { !it.conditions.isNullOrBlank() })
        assertEquals(8, fitments.size)
        val upholstery = fitments.single { it.featureId == "peugeot-3008-rispy-fabric-upholstery" }
        assertEquals(FitmentStatus.STANDARD, upholstery.status)
        assertEquals(listOf("peugeot-3008-electric-210-73kwh-allure"), upholstery.configurationIds)
        assertTrue(
            fitments.filter { it.id != upholstery.id }.all {
                it.status == FitmentStatus.UNKNOWN && it.reviewStatus == FactReviewStatus.NEEDS_REVIEW
            },
        )
    }

    @Test
    fun `every 3008 citation preserves extracted source wording`() {
        val extracted =
            Json
                .parseToJsonElement(
                    checkNotNull(
                        javaClass.getResource(
                            "/car-pricelists-extracted/peugeot/new-3008-spec-sheet-e8f7dcdc6a18.json",
                        ),
                    ).readText(),
                ).jsonObject
        val source = extracted.getValue("source").jsonObject
        val documentId = source.getValue("documentId").jsonPrimitive.content
        val sha256 = source.getValue("sha256").jsonPrimitive.content
        val pages =
            extracted.getValue("pages").jsonArray.associate { page ->
                val fields = page.jsonObject
                fields.getValue("pageNumber").jsonPrimitive.int to fields.getValue("text").jsonPrimitive.content
            }
        val citations =
            (
                catalog.configurations.flatMap { it.evidence } +
                    catalog.prices.flatMap { it.evidence } +
                    catalog.fitments.flatMap { it.evidence }
            ).filter { it.documentId == documentId }
        val catalogSource = catalog.sources.single { it.documentId == documentId }
        val whitespace = Regex("\\s+")

        assertEquals(sha256, catalogSource.sha256)
        assertEquals(29, catalogSource.pageCount)
        assertEquals("2026-11", catalogSource.effectivePeriod)
        assertTrue(citations.isNotEmpty())
        citations.forEach { citation ->
            assertEquals(sha256, citation.sha256)
            val page = pages.getValue(citation.pageNumber).replace(whitespace, " ").trim()
            val wording = citation.rawText.replace(whitespace, " ").trim()
            assertTrue(page.contains(wording), "Unsupported 3008 citation on page ${citation.pageNumber}: $wording")
        }
    }

    @Test
    fun `5008 upholstery is standard on both Allure powertrains`() {
        val fitment = catalog.fitments.single { it.id == "5008-crispy-upholstery-allure-standard" }

        assertEquals(FitmentStatus.STANDARD, fitment.status)
        assertEquals(FactReviewStatus.REVIEWED, fitment.reviewStatus)
        assertEquals(
            setOf("peugeot-5008-electric-210-73kwh-allure", "peugeot-5008-hybrid-145-allure"),
            fitment.configurationIds.toSet(),
        )
        assertEquals(16, fitment.evidence.single().pageNumber)
    }

    @Test
    fun `5008 unresolved equipment preserves review state and powertrain conditions`() {
        val configurations = catalog.configurations.filter { it.modelId == "peugeot-5008" }
        val configurationsById = configurations.associateBy { it.id }
        val pending = catalog.fitments.filter { it.id.startsWith("5008-") && it.id.endsWith("-trim-review") }

        assertEquals(6, configurations.size)
        assertEquals(10, pending.size)
        assertTrue(pending.all { it.status == FitmentStatus.UNKNOWN && it.reviewStatus == FactReviewStatus.NEEDS_REVIEW })
        assertTrue(pending.all { !it.conditions.isNullOrBlank() })
        assertTrue(pending.all { fitment -> fitment.configurationIds.all { it in configurationsById } })
        assertEquals(
            setOf(Powertrain.HYBRID),
            pending
                .single { it.id == "5008-19-inch-wheels-hybrid-trim-review" }
                .configurationIds
                .map { configurationsById.getValue(it).powertrain }
                .toSet(),
        )
        assertEquals(
            setOf(Powertrain.ELECTRIC),
            pending
                .single { it.id == "5008-20-inch-wheels-electric-trim-review" }
                .configurationIds
                .map { configurationsById.getValue(it).powertrain }
                .toSet(),
        )
    }

    @Test
    fun `new 5008 equipment citations match the extracted source wording`() {
        val extracted =
            Json
                .parseToJsonElement(
                    checkNotNull(
                        javaClass.getResource(
                            "/car-pricelists-extracted/peugeot/5008-spec-sheet-42309ee8d008.json",
                        ),
                    ).readText(),
                ).jsonObject
        val source = extracted.getValue("source").jsonObject
        val pages =
            extracted.getValue("pages").jsonArray.associate { page ->
                val fields = page.jsonObject
                fields.getValue("pageNumber").jsonPrimitive.int to fields.getValue("text").jsonPrimitive.content
            }
        val newFitments =
            catalog.fitments.filter {
                it.id == "5008-crispy-upholstery-allure-standard" ||
                    (it.id.startsWith("5008-") && it.id.endsWith("-trim-review"))
            }
        val whitespace = Regex("\\s+")

        assertEquals(11, newFitments.size)
        newFitments.flatMap { it.evidence }.forEach { citation ->
            assertEquals(source.getValue("documentId").jsonPrimitive.content, citation.documentId)
            assertEquals(source.getValue("sha256").jsonPrimitive.content, citation.sha256)
            val page = pages.getValue(citation.pageNumber).replace(whitespace, " ").trim()
            val wording = citation.rawText.replace(whitespace, " ").trim()
            assertTrue(page.contains(wording), "Unsupported 5008 citation on page ${citation.pageNumber}: $wording")
        }
    }

    @Test
    fun `golf catalog covers all trims with configuration-specific equipment`() {
        val golfConfigurations = catalog.configurations.filter { it.modelId == "volkswagen-golf" }

        assertEquals(16, golfConfigurations.size)
        assertEquals(setOf("Life", "Edition 75", "Style", "R-Line"), golfConfigurations.mapNotNull { it.trim }.toSet())
        assertEquals(
            setOf(Powertrain.PETROL, Powertrain.MILD_HYBRID, Powertrain.DIESEL, Powertrain.PLUG_IN_HYBRID),
            golfConfigurations.map { it.powertrain }.toSet(),
        )

        val ledHeadlampFitments =
            catalog.fitments.filter { it.featureId == "golf-led-headlights-separate-drl" }
        val lifeConfigurationIds =
            golfConfigurations.filter { it.trim == "Life" }.map { it.id }.toSet()
        assertTrue(
            ledHeadlampFitments.any {
                it.status == FitmentStatus.STANDARD && it.configurationIds.containsAll(lifeConfigurationIds)
            },
        )

        val winterFitments = catalog.fitments.filter { it.featureId == "golf-winter-pack-heated-seats-wheel" }
        val lifeMhevId = "volkswagen-golf-life-1-5-tsi-mhev-116hp"
        val lifePetrolId = "volkswagen-golf-life-1-5-tsi-116hp"
        assertTrue(
            winterFitments.any {
                lifeMhevId in it.configurationIds && it.status == FitmentStatus.STANDARD
            },
        )
        assertTrue(
            winterFitments.any {
                lifePetrolId in it.configurationIds &&
                    it.status == FitmentStatus.UNKNOWN &&
                    it.reviewStatus == FactReviewStatus.NEEDS_REVIEW
            },
        )

        val unresolvedHeadUpFitment =
            catalog.fitments.single {
                it.featureId == "golf-head-up-display" &&
                    it.status == FitmentStatus.UNKNOWN
            }
        assertEquals(FactReviewStatus.NEEDS_REVIEW, unresolvedHeadUpFitment.reviewStatus)
        assertEquals(setOf(8, 11), unresolvedHeadUpFitment.evidence.map { it.pageNumber }.toSet())
    }

    @Test
    fun `yaris hybrid covers four trims with source-backed standard equipment`() {
        val configurations = catalog.configurations.filter { it.modelId == "toyota-yaris-hybrid" }
        val configurationIds = configurations.map { it.id }.toSet()
        val fitments = catalog.fitments.filter { fitment -> fitment.configurationIds.any { it in configurationIds } }

        assertEquals(4, configurations.size)
        assertEquals(setOf("Luna", "Luna Sport", "Platinum", "GR SPORT"), configurations.map { it.trim }.toSet())
        assertTrue(configurations.all { it.powertrain == Powertrain.HYBRID && it.modelYear == 2026 })
        assertTrue(fitments.all { it.status == FitmentStatus.STANDARD })
        assertEquals(
            configurationIds,
            fitments.single { it.featureId == "toyota-safety-sense-3" }.configurationIds.toSet(),
        )
        assertEquals(
            configurationIds,
            fitments.single { it.featureId == "yaris-luna-base-road-sign-assist" }.configurationIds.toSet(),
        )
        assertEquals(
            configurationIds,
            fitments.single { it.featureId == "yaris-luna-base-isofix" }.configurationIds.toSet(),
        )
        assertEquals(
            listOf("toyota-yaris-hybrid-luna-sport"),
            fitments.single { it.featureId == "yaris-led-headlamps" }.configurationIds,
        )
        assertEquals(
            listOf("toyota-yaris-hybrid-platinum"),
            fitments.single { it.featureId == "yaris-panoramic-roof" }.configurationIds,
        )
        assertEquals(
            listOf("toyota-yaris-hybrid-gr-sport"),
            fitments.single { it.featureId == "yaris-gr-sport-ultrasuede" }.configurationIds,
        )
        assertTrue(catalog.prices.none { price -> price.configurationIds.any { it in configurationIds } })
    }

    @Test
    fun `corolla cross maps equipment and technical data to four trims`() {
        val configurations = catalog.configurations.filter { it.modelId == "toyota-corolla-cross" }
        val configurationIds = configurations.map { it.id }.toSet()
        val fitments = catalog.fitments.filter { fitment -> fitment.configurationIds.any { it in configurationIds } }
        val luna = "toyota-corolla-cross-luna-18-hev"
        val sport = "toyota-corolla-cross-sport-18-hev"
        val sol = "toyota-corolla-cross-sol-18-hev"
        val grSport = "toyota-corolla-cross-gr-sport-20-hev"

        assertEquals(4, configurations.size)
        assertEquals(setOf("Luna", "Sport", "Sol", "GR SPORT"), configurations.map { it.trim }.toSet())
        assertTrue(configurations.all { it.modelYear == 2026 && it.powertrain == Powertrain.HYBRID })
        assertEquals(90, fitments.size)
        assertEquals(1, fitments.count { it.status == FitmentStatus.UNKNOWN })
        assertEquals(
            FactReviewStatus.NEEDS_REVIEW,
            fitments.single { it.featureId == "corolla-cross-digital-key" }.reviewStatus,
        )
        assertEquals(
            configurationIds,
            fitments.single { it.featureId == "toyota-safety-sense-3" }.configurationIds.toSet(),
        )
        assertEquals(
            listOf(luna),
            fitments.single { it.featureId == "corolla-cross-17-inch-silver-alloys" }.configurationIds,
        )
        assertEquals(
            setOf(sport, sol, grSport),
            fitments.single { it.featureId == "corolla-cross-18-inch-dark-grey-alloys" }.configurationIds.toSet(),
        )
        assertEquals(
            listOf(grSport),
            fitments.single { it.featureId == "corolla-cross-19-inch-alloy-wheels" }.configurationIds,
        )
        assertEquals(
            setOf(luna, sport),
            fitments.single { it.featureId == "corolla-cross-black-fabric-upholstery" }.configurationIds.toSet(),
        )
        assertEquals(
            listOf(sol),
            fitments.single { it.featureId == "corolla-cross-partial-synthetic-leather" }.configurationIds,
        )

        val grPower =
            catalog.measurements.filter {
                it.configurationIds.contains(grSport) && it.name.startsWith("Maximum power")
            }
        assertEquals(setOf(180.0, 197.0), grPower.mapNotNull { it.value }.toSet())
        assertTrue(grPower.all { it.reviewStatus == FactReviewStatus.NEEDS_REVIEW })
        val grConsumption =
            catalog.measurements.filter {
                it.configurationIds.contains(grSport) && it.name.startsWith("Combined fuel consumption")
            }
        assertEquals(setOf(5.1, 5.2), grConsumption.mapNotNull { it.value }.toSet())
        assertTrue(grConsumption.all { it.reviewStatus == FactReviewStatus.NEEDS_REVIEW })
        assertTrue(catalog.measurements.none { it.id.startsWith("corolla-cross-") && it.name.contains("NOx") })
        assertTrue(catalog.prices.none { price -> price.configurationIds.any { it in configurationIds } })
    }

    @Test
    fun `every Corolla Cross citation matches the extracted source page`() {
        val extracted =
            Json
                .parseToJsonElement(
                    checkNotNull(
                        javaClass.getResource(
                            "/car-pricelists-extracted/toyota/Toyota-Corolla-Cross---Brochure-f59fb0b1b918.json",
                        ),
                    ).readText(),
                ).jsonObject
        val source = extracted.getValue("source").jsonObject
        val documentId = source.getValue("documentId").jsonPrimitive.content
        val sha256 = source.getValue("sha256").jsonPrimitive.content
        val pages =
            extracted.getValue("pages").jsonArray.associate { page ->
                val fields = page.jsonObject
                fields.getValue("pageNumber").jsonPrimitive.int to fields.getValue("text").jsonPrimitive.content
            }
        val catalogSource = catalog.sources.single { it.documentId == documentId }
        val citations =
            (
                catalog.configurations.flatMap { it.evidence } +
                    catalog.fitments.flatMap { it.evidence } +
                    catalog.measurements.flatMap { it.evidence }
            ).filter { it.documentId == documentId }
        val whitespace = Regex("\\s+")

        assertEquals(sha256, catalogSource.sha256)
        assertEquals(18, catalogSource.pageCount)
        assertEquals("2026-02", catalogSource.effectivePeriod)
        assertTrue(citations.isNotEmpty())
        citations.forEach { citation ->
            assertEquals(sha256, citation.sha256)
            val page = pages.getValue(citation.pageNumber).replace(whitespace, " ").trim()
            val wording = citation.rawText.replace(whitespace, " ").trim()
            assertTrue(page.contains(wording), "Unsupported Corolla Cross citation on page ${citation.pageNumber}: $wording")
        }
    }

    @Test
    fun `yaris measurements retain trim-specific power emissions and consumption`() {
        assertEquals(116.0, catalog.measurements.single { it.id == "yaris-luna-platinum-power" }.value)
        assertEquals(130.0, catalog.measurements.single { it.id == "yaris-gr-sport-power" }.value)
        assertEquals(87.0, catalog.measurements.single { it.id == "yaris-luna-wltp-co2" }.value)
        assertEquals(97.0, catalog.measurements.single { it.id == "yaris-platinum-wltp-co2" }.value)
        assertEquals(99.0, catalog.measurements.single { it.id == "yaris-gr-sport-wltp-co2" }.value)
        assertEquals(3.8, catalog.measurements.single { it.id == "yaris-luna-wltp-fuel-consumption" }.value)
        assertEquals(97.0, catalog.measurements.single { it.id == "yaris-luna-sport-wltp-co2" }.value)
        assertEquals(4.3, catalog.measurements.single { it.id == "yaris-luna-sport-wltp-fuel-consumption" }.value)
        assertEquals(4.3, catalog.measurements.single { it.id == "yaris-platinum-wltp-fuel-consumption" }.value)
        assertEquals(4.4, catalog.measurements.single { it.id == "yaris-gr-sport-wltp-fuel-consumption" }.value)
        assertEquals(450.0, catalog.measurements.single { it.id == "yaris-braked-towing-capacity" }.value)
        assertEquals(
            setOf(
                "toyota-yaris-hybrid-luna",
                "toyota-yaris-hybrid-luna-sport",
                "toyota-yaris-hybrid-platinum",
                "toyota-yaris-hybrid-gr-sport",
            ),
            catalog.measurements
                .single { it.id == "yaris-braked-towing-capacity" }
                .configurationIds
                .toSet(),
        )
        assertEquals(3940.0, catalog.measurements.single { it.id == "yaris-length" }.value)
        assertEquals(286.0, catalog.measurements.single { it.id == "yaris-boot-capacity-seats-up" }.value)
        assertTrue(
            catalog.measurements.none {
                it.id.startsWith("yaris-") && it.name.contains("nox", ignoreCase = true)
            },
        )
    }

    @Test
    fun `every yaris citation preserves wording from the cited extracted page`() {
        val extracted =
            Json
                .parseToJsonElement(
                    checkNotNull(
                        javaClass.getResource(
                            "/car-pricelists-extracted/toyota/Toyota-Yaris---Hybrid-Brochure-7695a1115fb3.json",
                        ),
                    ).readText(),
                ).jsonObject
        val source = extracted.getValue("source").jsonObject
        val documentId = source.getValue("documentId").jsonPrimitive.content
        val sha256 = source.getValue("sha256").jsonPrimitive.content
        val pages =
            extracted.getValue("pages").jsonArray.associate { page ->
                val fields = page.jsonObject
                fields.getValue("pageNumber").jsonPrimitive.int to fields.getValue("text").jsonPrimitive.content
            }
        val catalogSource = catalog.sources.single { it.documentId == documentId }
        val citations =
            (
                catalog.configurations.flatMap { it.evidence } +
                    catalog.fitments.flatMap { it.evidence } +
                    catalog.measurements.flatMap { it.evidence }
            ).filter { it.documentId == documentId }
        val whitespace = Regex("\\s+")

        assertEquals(sha256, catalogSource.sha256)
        assertEquals(18, catalogSource.pageCount)
        assertEquals("2026-03", catalogSource.effectivePeriod)
        assertTrue(citations.isNotEmpty())
        citations.forEach { citation ->
            assertEquals(sha256, citation.sha256)
            val page = pages.getValue(citation.pageNumber).replace(whitespace, " ").trim()
            val wording = citation.rawText.replace(whitespace, " ").trim()
            assertTrue(page.contains(wording), "Unsupported Yaris citation on page ${citation.pageNumber}: $wording")
        }
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
    fun `RAV4 PHEV covers four trims with provisional GR SPORT specifications`() {
        val configurations = catalog.configurations.filter { it.modelId == "toyota-rav4-phev" }
        val configurationIds = configurations.map { it.id }.toSet()
        val fitments = catalog.fitments.filter { fitment -> fitment.configurationIds.any { it in configurationIds } }

        assertEquals(
            setOf(
                "toyota-rav4-phev-sol",
                "toyota-rav4-phev-sport",
                "toyota-rav4-phev-platinum",
                "toyota-rav4-phev-awd-gr-sport",
            ),
            configurationIds,
        )
        assertEquals(setOf("SOL", "SPORT", "PLATINUM", "GR SPORT"), configurations.map { it.trim }.toSet())
        assertTrue(configurations.all { it.powertrain == Powertrain.PLUG_IN_HYBRID })
        assertEquals(
            setOf("FWD", "AWD"),
            configurations.mapNotNull { it.drivetrain }.toSet(),
        )
        assertTrue(fitments.size > 70)
        assertEquals(
            configurationIds,
            fitments.single { it.featureId == "power-boot" }.configurationIds.toSet(),
        )
        assertEquals(
            setOf("toyota-rav4-phev-sol"),
            fitments.single { it.featureId == "rav4-phev-18-inch-alloy-wheels" }.configurationIds.toSet(),
        )
        assertEquals(
            setOf("toyota-rav4-phev-sport"),
            fitments.single { it.featureId == "rav4-phev-sport-20-inch-alloys" }.configurationIds.toSet(),
        )
        assertEquals(
            setOf("toyota-rav4-phev-platinum"),
            fitments.single { it.featureId == "rav4-phev-platinum-20-inch-alloy-wheels" }.configurationIds.toSet(),
        )
        assertEquals(
            setOf("toyota-rav4-phev-awd-gr-sport"),
            fitments.single { it.featureId == "rav4-phev-gr-sport-20-inch-alloy-wheels" }.configurationIds.toSet(),
        )
        assertEquals(137.0, catalog.measurements.single { it.id == "toyota-rav4-sol-ev-range" }.value)
        assertEquals(128.0, catalog.measurements.single { it.id == "toyota-rav4-sport-ev-range" }.value)
        assertEquals(125.0, catalog.measurements.single { it.id == "toyota-rav4-platinum-ev-range" }.value)
        assertEquals(121.0, catalog.measurements.single { it.id == "toyota-rav4-gr-sport-range-pending" }.value)
        assertEquals(
            MeasurementApprovalStatus.PENDING_HOMOLOGATION,
            catalog.measurements.single { it.id == "toyota-rav4-gr-sport-range-pending" }.approvalStatus,
        )
        assertEquals(
            MeasurementApprovalStatus.PENDING_HOMOLOGATION,
            catalog.measurements.single { it.id == "toyota-rav4-awd-acceleration-pending" }.approvalStatus,
        )
        assertEquals(
            FactReviewStatus.NEEDS_REVIEW,
            fitments.single { it.featureId == "rav4-phev-gr-sport-suspension" }.reviewStatus,
        )
        assertTrue(catalog.prices.none { price -> price.configurationIds.any { it in configurationIds } })
    }

    @Test
    fun `every RAV4 PHEV citation matches the extracted source page`() {
        val extracted =
            Json
                .parseToJsonElement(
                    checkNotNull(
                        javaClass.getResource(
                            "/car-pricelists-extracted/toyota/Toyota-RAV4---PHEV-Brochure-fb8cbadae7f0.json",
                        ),
                    ).readText(),
                ).jsonObject
        val source = extracted.getValue("source").jsonObject
        val documentId = source.getValue("documentId").jsonPrimitive.content
        val sha256 = source.getValue("sha256").jsonPrimitive.content
        val pages =
            extracted.getValue("pages").jsonArray.associate { page ->
                val fields = page.jsonObject
                fields.getValue("pageNumber").jsonPrimitive.int to fields.getValue("text").jsonPrimitive.content
            }
        val catalogSource = catalog.sources.single { it.documentId == documentId }
        val rav4Ids =
            catalog.configurations
                .filter { it.modelId == "toyota-rav4-phev" }
                .map { it.id }
                .toSet()
        val citations =
            (
                catalog.configurations.flatMap { it.evidence } +
                    catalog.fitments.flatMap { it.evidence } +
                    catalog.measurements.flatMap { it.evidence }
            ).filter { it.documentId == documentId }
        val whitespace = Regex("\\s+")

        assertEquals(sha256, catalogSource.sha256)
        assertEquals(18, catalogSource.pageCount)
        assertEquals("2026-09", catalogSource.effectivePeriod)
        assertTrue(citations.isNotEmpty())
        assertTrue(
            catalog.fitments
                .filter { it.configurationIds.any { id -> id in rav4Ids } }
                .all { fitment -> fitment.evidence.isNotEmpty() },
        )
        citations.forEach { citation ->
            assertEquals(sha256, citation.sha256)
            val page = pages.getValue(citation.pageNumber).replace(whitespace, " ").trim()
            val wording = citation.rawText.replace(whitespace, " ").trim()
            assertTrue(page.contains(wording), "Unsupported RAV4 citation on page ${citation.pageNumber}: $wording")
        }
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
                .first { it.approvalStatus == MeasurementApprovalStatus.PENDING_HOMOLOGATION }
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
