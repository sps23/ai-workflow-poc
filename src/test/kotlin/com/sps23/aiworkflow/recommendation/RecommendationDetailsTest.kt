package com.sps23.aiworkflow.recommendation

import com.sps23.aiworkflow.catalog.datamodel.DataModelCatalogArtifact
import com.sps23.aiworkflow.catalog.datamodel.FitmentState
import com.sps23.aiworkflow.catalog.datamodel.Money
import com.sps23.aiworkflow.catalog.datamodel.PriceBasis
import com.sps23.aiworkflow.catalog.datamodel.PriceFact
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecommendationDetailsTest {
    private val context = DataModelCatalogContext.loadBundled()
    private val artifact =
        Json.decodeFromString<DataModelCatalogArtifact>(
            checkNotNull(javaClass.getResource(DataModelCatalogContext.BUNDLED_CATALOG_RESOURCE)).readText(),
        )

    @Test
    fun `electric details resolve battery horsepower and all wheel drive from catalog`() {
        val cupra = context.details(selected("cupra-raval-urban-raval-urban-116hp-v1"))
        assertTrue("37 kWh battery" in cupra.engine)
        assertTrue("116 hp" in cupra.engine)
        assertTrue("Front Wheel Drive" in cupra.engine)

        val skoda = context.details(selected("skoda-peaq-selection-selection-90x"))
        assertTrue("77 kWh battery" in skoda.engine)
        assertTrue("91 kWh gross battery" in skoda.engine)
        assertTrue("220 kW (horsepower unavailable)" in skoda.engine)
        assertTrue("All-wheel drive (4x4)" in skoda.engine)

        val peugeot = context.details(selected("peugeot-5008-gt-electric-210-73-kwh"))
        assertTrue("73 kWh battery" in peugeot.engine)
        assertTrue("213 hp" in peugeot.engine)
    }

    @Test
    fun `hybrid details resolve displacement and power without inventing missing facts`() {
        val toyota = context.details(selected("toyota-corolla-cross-gr-sport-2-0-hev-fwd"))
        assertTrue("Hybrid" in toyota.engine)
        assertTrue("1987 cc" in toyota.engine)
        assertTrue("197 hp" in toyota.engine)
        assertTrue("FWD" in toyota.engine)
        assertTrue("Not explicitly stated in brochure" in toyota.priceLines.first())

        val peugeot = context.details(selected("peugeot-5008-allure-hybrid-145-auto"))
        assertTrue("1199 cc" in peugeot.engine)
        assertTrue("horsepower unavailable" in peugeot.engine)
        assertTrue("drivetrain unavailable" in peugeot.engine)
    }

    @Test
    fun `on the road price wins and returned optional equipment is itemized and added once`() {
        val car = selected("cupra-raval-urban-raval-urban-116hp-v1", option("R8A"), option("R9A"))
        val lines = context.details(car).priceLines
        assertEquals("Base price (on-the-road): €24,775", lines.first())
        assertTrue(lines.any { it == "Option: R8A: €280" })
        assertTrue(lines.any { it == "Option: R9A: €700" })
        assertEquals("Total with returned options (on-the-road): EUR 25755.00", lines.last())
        val readable = CarSelection(listOf(car)).toReadableText(context)
        assertTrue(context.details(car).engine in readable)
        lines.forEach { assertTrue(it in readable) }
    }

    @Test
    fun `list price and comma separated trim availability are respected`() {
        val vehicle = artifact.vehicles.single { it.id == "peugeot-5008" }
        val sunroof = vehicle.options.single { it.code == "J6C0" }
        val details = context.details(selected("peugeot-5008-gt-electric-210-73-kwh", option(sunroof.name)))
        assertEquals("Base price (catalog list price): €55,995", details.priceLines.first())
        assertEquals("Total with returned options (catalog list price): EUR 57915.00", details.priceLines.last())
        val wrongTrim = context.details(selected("peugeot-5008-allure-electric-210-73-kwh", option(sunroof.name)))
        assertTrue(wrongTrim.priceLines.last().startsWith("Total with returned options: unavailable"))
        val conditional = context.details(selected("peugeot-5008-gt-hybrid-145-auto", option("DX02")))
        assertTrue(conditional.priceLines.last().startsWith("Total with returned options: unavailable"))
    }

    @Test
    fun `standard options do not increase price and missing options do not produce partial totals`() {
        val standard = context.details(selected("cupra-raval-urban-raval-urban-116hp-v1", option("R8A", FitmentState.STANDARD)))
        assertEquals(2, standard.priceLines.size)
        assertTrue("no additional paid options returned" in standard.priceLines.last())

        listOf(
            listOf(option("R8A"), option("not in catalog")),
            listOf(option("R8A"), option("DEFIER 18\" Alloy wheels Machined in Sport Black/Silver")),
        ).forEach { options ->
            val details = context.details(selected("cupra-raval-urban-raval-urban-116hp-v1", *options.toTypedArray()))
            assertFalse(details.priceLines.any { it.startsWith("Total with returned options (") })
        }
    }

    @Test
    fun `conditional and range prices withhold totals`() {
        val car = artifact.vehicles.single { it.id == "cupra-raval" }
        val trim = car.trims.first()
        val wheel = car.options.single { it.code == "R8A" }
        val availability = wheel.availability.first()
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
                    .describe(listOf(option("R8A")))
            assertTrue(details.priceLines.last().startsWith("Total with returned options: unavailable"))
        }
    }

    @Test
    fun `unknown base from prices and mixed currencies withhold totals`() {
        val vehicle = artifact.vehicles.single { it.id == "cupra-raval" }
        val trim = vehicle.trims.first()
        val variant = trim.variants.first()
        listOf(
            emptyList(),
            listOf(PriceFact(PriceBasis.RRP_FROM, Money(2000000), rawText = "From EUR 20000")),
            listOf(PriceFact(PriceBasis.RRP, Money(2000000, "USD"), rawText = "USD 20000")),
        ).forEach { prices ->
            val details =
                CatalogConfigurationDetails(vehicle, trim.name, variant.copy(prices = prices))
                    .describe(listOf(option("R8A")))
            assertTrue(details.priceLines.last().startsWith("Total with returned options: unavailable"))
        }
    }

    @Test
    fun `ambiguous technical specs are not guessed from shared power figures`() {
        val vehicle = artifact.vehicles.single { it.id == "cupra-raval" }
        val trim = vehicle.trims.first()
        val spec = vehicle.technicalSpecifications.powertrains.first()
        val changed =
            vehicle.copy(
                technicalSpecifications =
                    vehicle.technicalSpecifications.copy(
                        powertrains = listOf(spec, spec.copy(name = "Other variant", drivetrain = "AWD")),
                    ),
            )
        val variant = trim.variants.first().copy(name = "Unknown variant", modelCode = null)
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
