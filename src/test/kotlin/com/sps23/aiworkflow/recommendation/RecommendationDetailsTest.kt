package com.sps23.aiworkflow.recommendation

import com.sps23.aiworkflow.catalog.datamodel.DataModelCatalogArtifact
import com.sps23.aiworkflow.catalog.datamodel.DataModelFixtures
import com.sps23.aiworkflow.catalog.datamodel.FitmentState
import com.sps23.aiworkflow.catalog.datamodel.Money
import com.sps23.aiworkflow.catalog.datamodel.PowertrainType
import com.sps23.aiworkflow.catalog.datamodel.PriceBasis
import com.sps23.aiworkflow.catalog.datamodel.PriceFact
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecommendationDetailsTest {
    private val artifact = DataModelFixtures.artifact()
    private val context = DataModelCatalogContext.fromArtifact(artifact)

    @Test
    fun `electric details resolve battery horsepower and all wheel drive from catalog`() {
        val skoda = context.details(selected("skoda-peaq-selection-90x"))
        assertTrue("77 kWh battery" in skoda.engine)
        assertTrue("91 kWh gross battery" in skoda.engine)
        assertTrue("220 kW (horsepower unavailable)" in skoda.engine)
        assertTrue("All-wheel drive (4x4)" in skoda.engine)

        val peugeot = context.details(selected("peugeot-5008-gt-electric-210-73-kwh"))
        assertTrue("73 kWh battery" in peugeot.engine)
        assertTrue("210 hp" in peugeot.engine)
        assertTrue("Front" in peugeot.engine)
    }

    @Test
    fun `hybrid details resolve displacement and power without inventing missing facts`() {
        val toyota = context.details(selected("toyota-yaris-gr-sport-gr-sport-hybrid-130"))
        assertTrue("Hybrid" in toyota.engine)
        assertTrue("1490 cc" in toyota.engine)
        assertTrue("130 hp" in toyota.engine)
        assertTrue("drivetrain unavailable" in toyota.engine)
        assertEquals("Base price: unavailable in catalog", toyota.priceLines.first())

        val peugeot = context.details(selected("peugeot-5008-allure-hybrid-145-auto"))
        // The row says HYBRID while technical specs say MILD_HYBRID; incompatible specs must not be guessed.
        assertTrue("engine size unavailable" in peugeot.engine)
        assertTrue("145 hp" in peugeot.engine)
        assertTrue("drivetrain unavailable" in peugeot.engine)
    }

    @Test
    fun `on the road price wins and returned optional equipment is itemized and added once`() {
        val vehicle = artifact.vehicles.single { it.id == "peugeot-5008" }
        val trim = vehicle.trims.single { it.name == "GT" }
        val variant = trim.variants.first()
        val onRoad = PriceFact(PriceBasis.ON_THE_ROAD, Money(5700000), rawText = "EUR 57000")
        val changed =
            vehicle.copy(
                trims =
                    vehicle.trims.map {
                        if (it == trim) it.copy(variants = listOf(variant.copy(prices = variant.prices + onRoad))) else it
                    },
            )
        val pricedContext = DataModelCatalogContext.fromArtifact(DataModelCatalogArtifact(vehicles = listOf(changed)))
        val car = selected("peugeot-5008-gt-electric-210-73-kwh", option("J6C0"), option("0MM0"))
        val lines = pricedContext.details(car).priceLines
        assertEquals("Base price (on-the-road): EUR 57000", lines.first())
        assertTrue(lines.any { it == "Option: J6C0: €1,920" })
        assertTrue(lines.any { it == "Option: 0MM0: €900" })
        assertEquals("Total with returned options (on-the-road): EUR 59820.00", lines.last())
        val readable = CarSelection(listOf(car)).toReadableText(pricedContext)
        assertTrue(pricedContext.details(car).engine in readable)
        lines.forEach { assertTrue(it in readable) }
    }

    @Test
    fun `list price and comma separated trim availability are respected`() {
        val vehicle = artifact.vehicles.single { it.id == "peugeot-5008" }
        val sunroof = vehicle.options.single { it.code == "J6C0" }
        val gtPrice = sunroof.availability.single { it.scope == "GT" }
        val joinedScope = sunroof.copy(availability = listOf(gtPrice.copy(scope = "GT, GT EXCLUSIVE")))
        val joinedContext =
            DataModelCatalogContext.fromArtifact(
                artifact.copy(
                    vehicles = listOf(vehicle.copy(options = vehicle.options.map { if (it == sunroof) joinedScope else it })),
                ),
            )
        val details = joinedContext.details(selected("peugeot-5008-gt-electric-210-73-kwh", option(sunroof.name)))
        assertEquals("Base price (RRP): €55,995", details.priceLines.first())
        assertEquals("Total with returned options (RRP): EUR 57915.00", details.priceLines.last())
        val wrongTrim = joinedContext.details(selected("peugeot-5008-allure-electric-210-73-kwh", option(sunroof.name)))
        assertTrue(wrongTrim.priceLines.last().startsWith("Total with returned options: unavailable"))
        val conditional = context.details(selected("peugeot-5008-gt-exclusive-hybrid-145-auto", option("DX02")))
        assertTrue(conditional.priceLines.last().startsWith("Total with returned options: unavailable"))
    }

    @Test
    fun `standard options do not increase price and missing options do not produce partial totals`() {
        val standard = context.details(selected("peugeot-5008-gt-electric-210-73-kwh", option("J6C0", FitmentState.STANDARD)))
        assertEquals(2, standard.priceLines.size)
        assertTrue("no additional paid options returned" in standard.priceLines.last())

        listOf(
            listOf(option("J6C0"), option("not in catalog")),
            listOf(option("J6C0"), option("Sunroof Pack")),
        ).forEach { options ->
            val details = context.details(selected("peugeot-5008-gt-electric-210-73-kwh", *options.toTypedArray()))
            assertFalse(details.priceLines.any { it.startsWith("Total with returned options (") })
        }
    }

    @Test
    fun `conditional and range prices withhold totals`() {
        val car = artifact.vehicles.single { it.id == "peugeot-5008" }
        val trim = car.trims.single { it.name == "GT" }
        val wheel = car.options.single { it.code == "J6C0" }
        val availability = wheel.availability.single { it.scope == "GT" }
        val cases =
            listOf(
                wheel.copy(availability = listOf(availability.copy(note = "Requires another package"))),
                wheel.copy(
                    availability =
                        listOf(
                            availability.copy(
                                price = PriceFact(PriceBasis.OPTION, Money(28000), Money(50000), "EUR 280-500"),
                            ),
                        ),
                ),
            )
        cases.forEach { changed ->
            val details =
                CatalogConfigurationDetails(car.copy(options = listOf(changed)), trim.name, trim.variants.first())
                    .describe(listOf(option("J6C0")))
            assertTrue(details.priceLines.last().startsWith("Total with returned options: unavailable"))
        }
    }

    @Test
    fun `unknown base from prices and mixed currencies withhold totals`() {
        val vehicle = artifact.vehicles.single { it.id == "peugeot-5008" }
        val trim = vehicle.trims.single { it.name == "GT" }
        val variant = trim.variants.first()
        listOf(
            emptyList(),
            listOf(PriceFact(PriceBasis.RRP_FROM, Money(2000000), rawText = "From EUR 20000")),
            listOf(PriceFact(PriceBasis.RRP, Money(2000000, "USD"), rawText = "USD 20000")),
        ).forEach { prices ->
            val details =
                CatalogConfigurationDetails(vehicle, trim.name, variant.copy(prices = prices))
                    .describe(listOf(option("J6C0")))
            assertTrue(details.priceLines.last().startsWith("Total with returned options: unavailable"))
        }
    }

    @Test
    fun `ambiguous technical specs are not guessed from shared power figures`() {
        val vehicle = artifact.vehicles.single { it.id == "peugeot-5008" }
        val trim = vehicle.trims.first()
        val spec = vehicle.technicalSpecifications.powertrains.first()
        val changed =
            vehicle.copy(
                technicalSpecifications =
                    vehicle.technicalSpecifications.copy(
                        powertrains = listOf(spec, spec.copy(name = "Other variant", drivetrain = "AWD")),
                    ),
            )
        val variant =
            trim.variants.first().copy(
                name = "Unknown variant",
                modelCode = null,
                powertrainType = PowertrainType.ELECTRIC,
                powerHp = null,
            )
        val details = CatalogConfigurationDetails(changed, trim.name, variant).describe(emptyList())
        assertTrue("drivetrain unavailable" in details.engine)
        assertFalse("All-wheel drive" in details.engine)
    }

    @Test
    fun `unknown configurations show explicit unavailable data without trusting model labels`() {
        val details = context.details(selected("invented-configuration"))
        assertTrue("configuration not found" in details.engine)
        assertTrue("configuration not found" in details.priceLines.single())
    }

    private fun selected(
        id: String,
        vararg options: RequestedOption,
    ): SelectedCar = SelectedCar(id, "Model-authored label", "Model-authored trim", options.toList())

    private fun option(
        name: String,
        state: FitmentState = FitmentState.OPTIONAL,
    ): RequestedOption = RequestedOption(name, state, "", emptyList())
}
