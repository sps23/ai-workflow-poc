package com.sps23.aiworkflow.recommendation

import com.sps23.aiworkflow.openrouter.FailureKind
import com.sps23.aiworkflow.openrouter.OpenRouterWorkflowException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LlmRequestTest {
    private val original = "  Seven seats, not electric; preferably electric seats. Show all matches under 50,000 EUR. "
    private val request =
        LlmRequest(
            original,
            listOf(
                RequestTerm(CatalogRequestField.SEATING_CAPACITY, RequestOperator.EQUALS, "Seven", null, "Seven seats"),
                RequestTerm(CatalogRequestField.POWERTRAIN, RequestOperator.EXCLUDES, "ELECTRIC", null, "not electric"),
                RequestTerm(CatalogRequestField.PRICE, RequestOperator.OTHER, "under 50,000", "EUR", "under 50,000 EUR"),
            ),
            listOf(RequestTerm(CatalogRequestField.EQUIPMENT, RequestOperator.EQUALS, "electric seats", null, "preferably electric seats")),
            listOf(RequestTerm(CatalogRequestField.OTHER, RequestOperator.DESCRIBE, "all matches", null, "Show all matches")),
            listOf("electric seats", "under 50,000 EUR"),
        )

    @Test
    fun `round trip preserves original wording units exclusions preferences and unresolved phrases`() {
        assertEquals(request, LlmRequestCodec.decode(LlmRequestCodec.encode(request), original))
    }

    @Test
    fun `malformed missing extra unknown enum wrong scalar and changed source wording fail validation`() {
        val valid = LlmRequestCodec.encode(request)
        val invalid =
            listOf(
                "not JSON",
                "{}",
                valid.replace("\"ELECTRIC\"", "\"electric\""),
                valid.replace("\"Seven\"", "7"),
                valid.replace("\"SEATING_CAPACITY\"", "\"SEATS\""),
                valid.replace("\"originalRequest\":", "\"unexpected\": [], \"originalRequest\":"),
                LlmRequestCodec.encode(request.copy(originalRequest = original.trim())),
                LlmRequestCodec.encode(request.copy(unresolvedPhrases = listOf("invented phrase"))),
                LlmRequestCodec.encode(
                    request.copy(requirements = listOf(request.requirements.first().copy(sourceText = "invented constraint"))),
                ),
                LlmRequestCodec.encode(
                    request.copy(
                        requirements = emptyList(),
                        preferences = emptyList(),
                        requestedAttributes = emptyList(),
                        unresolvedPhrases = emptyList(),
                    ),
                ),
            )
        invalid.forEach { raw ->
            val error = assertFailsWith<OpenRouterWorkflowException> { LlmRequestCodec.decode(raw, original) }
            assertEquals(FailureKind.INVALID_RESPONSE, error.kind)
            assertEquals(raw, error.rawOutput)
        }
    }
}
