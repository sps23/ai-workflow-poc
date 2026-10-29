package com.sps23.aiworkflow.openrouter

import com.sps23.aiworkflow.recommendation.CatalogContext
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Live test against https://openrouter.ai. Requires OPENROUTER_API_KEY and a `:free` OPENROUTER_MODEL.
 * Add `@Ignore` to the class when done so `./gradlew test` and CI do not call the real API.
 */
class OpenRouterClientIntegrationTest {
    private val config = OpenRouterConfig.fromEnvironment(System.getenv())

    @Test
    fun `configured free model is listed with a positive context length`() {
        val limits = runBlocking { OpenRouterClient(config).fetchLimits() }

        assertTrue(limits.contextLength > 0, "context length should be positive")
    }

    @Test
    fun `simple prompt returns a non-empty answer`() {
        val completion =
            runBlocking {
                OpenRouterClient(config).complete(
                    userMessage = "Reply with one short sentence: what colour is a clear daytime sky?",
                    maxCompletionTokens = 256,
                )
            }

        println("Model ${completion.requestedModelId} answered: ${completion.text}")
        assertTrue(completion.text.isNotBlank(), "answer should not be empty")
        assertEquals(config.modelId, completion.requestedModelId)
    }

    @Test
    fun `catalog question with the full compact catalog returns a non-empty answer`() {
        val catalogJson = CatalogContext.bundledJson()
        // A ~512k-token prompt on a free model can take minutes; the 120s default is too short.
        val slowConfig =
            OpenRouterConfig(
                apiKey = config.apiKey,
                modelId = config.modelId,
                baseUrl = config.baseUrl,
                requestTimeoutMillis = 600_000,
            )

        val completion =
            runBlocking {
                OpenRouterClient(slowConfig).complete(
                    userMessage = "Show me all hybrid cars under 50,000 EUR.",
                    systemPrompt = SYSTEM_INSTRUCTIONS,
                    context = catalogJson,
                    maxCompletionTokens = 8192,
                )
            }

        println("Model ${completion.requestedModelId} answered from ${catalogJson.toByteArray().size}-byte catalog:\n${completion.text}")
        assertTrue(completion.text.isNotBlank(), "answer should not be empty")
        assertEquals(config.modelId, completion.requestedModelId)
        /*
        Model nvidia/nemotron-3-ultra-550b-a55b:free answered from 1024558-byte catalog:
**Hybrid vehicles (MILD_HYBRID, HYBRID, PLUG_IN_HYBRID) with a listed price under 50 000 EUR**
*Prices are taken from the first price entry for each configuration; amounts are in minor units (cents) and converted to EUR. Only configurations that have a price field in the catalog are included.*

| Configuration ID | Make | Model | Trim | Powertrain | Engine | Price (EUR) | Price basis | Effective from | Source (documentId:page) |
|------------------|------|-------|------|------------|--------|-------------|-------------|----------------|--------------------------|
| cupra-formentor-v1-15-etsi-150-dsg | CUPRA | Formentor | V1 | MILD_HYBRID | 1.5 eTSI 150hp | 43 815 | MANUFACTURER_RRP | 2026-09-01 | cupra/20270727_CUPRA_Ireland_Product_Guide_Formentor_PA.pdf:3,7 |
| cupra-formentor-v1-ehybrid-204-dsg | CUPRA | Formentor | V1 | PLUG_IN_HYBRID | e-Hybrid 204hp | 45 735 | MANUFACTURER_RRP | 2026-09-01 | cupra/20270727_CUPRA_Ireland_Product_Guide_Formentor_PA.pdf:3,7 |
| cupra-formentor-v2-15-etsi-150-dsg | CUPRA | Formentor | V2 | MILD_HYBRID | 1.5 eTSI 150hp | 46 820 | MANUFACTURER_RRP | 2026-09-01 | cupra/20270727_CUPRA_Ireland_Product_Guide_Formentor_PA.pdf:3,8 |
| cupra-formentor-v2-ehybrid-204-dsg | CUPRA | Formentor | V2 | PLUG_IN_HYBRID | e-Hybrid 204hp | 48 360 | MANUFACTURER_RRP | 2026-09-01 | cupra/20270727_CUPRA_Ireland_Product_Guide_Formentor_PA.pdf:3,8 |
| cupra-leon-v1-5d-ehybrid-204-dsg | CUPRA | Leon | V1 | PLUG_IN_HYBRID | e-Hybrid 204hp | 44 425 | MANUFACTURER_RRP | – | cupra/20260727_CUPRA_Ireland_Product_Guide_Leon_PA.pdf:9 |
| cupra-leon-v1-sp-ehybrid-204-dsg | CUPRA | Leon | V1 | PLUG_IN_HYBRID | e-Hybrid 204hp | 46 690 | MANUFACTURER_RRP | – | cupra/20260727_CUPRA_Ireland_Product_Guide_Leon_PA.pdf:9 |
| volkswagen-golf-life-1-5-tsi-mhev-116hp | Volkswagen | Golf | Life | MILD_HYBRID | 1.5 TSI MHEV 116HP | 37 255 | MANUFACTURER_RRP | – | vw/Golf_MY27.pdf:4 |
| peugeot-5008-hybrid-145-allure | Peugeot | 5008 | Allure | HYBRID | Hybrid 145 | 49 995 | MANUFACTURER_RRP | 2026-11 | peugeot/5008-spec-sheet.pdf:3 |

**Notes**
- The CUPRA Formentor VZ e-Hybrid 272hp (54 510 EUR) and Peugeot 5008 Hybrid 145 GT/GT Exclusive (52 995 EUR / 53 995 EUR) exceed the 50 000 EUR threshold and are not listed.
- Several hybrid configurations (e.g., Volkswagen Golf Edition 75 eHybrid, Škoda Kodiaq/Superb PHEV, Toyota Corolla Cross/RAV4/Yaris hybrids) do not have a price field in the provided catalog, so their pricing cannot be verified here.
- All prices are manufacturer‑recommended retail prices (RRP) or on‑the‑road totals as indicated; delivery charges, metallic paint, or other fees may be extra per the source conditions.

         */
    }
}
