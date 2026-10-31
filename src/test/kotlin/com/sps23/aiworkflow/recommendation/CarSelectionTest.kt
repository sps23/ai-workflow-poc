package com.sps23.aiworkflow.recommendation

import com.sps23.aiworkflow.catalog.datamodel.FitmentState
import com.sps23.aiworkflow.openrouter.FailureKind
import com.sps23.aiworkflow.openrouter.FakeOpenRouterServer
import com.sps23.aiworkflow.openrouter.OpenRouterWorkflowException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CarSelectionTest {
    @Test
    fun `parses all existing fitment statuses and renders readable labels`() {
        FitmentState.entries.forEach { status ->
            val raw = FakeOpenRouterServer.CAR_OUTPUT.replace("OPTIONAL", status.name)
            val selection = CarSelectionCodec.decode(raw)
            assertEquals(
                status,
                selection.cars
                    .single()
                    .options
                    .single()
                    .status,
            )
            assertTrue(status.displayLabel() in selection.toReadableText())
            assertTrue("Requires comfort pack" in selection.toReadableText())
            assertTrue("Details\tSources" in selection.toReadableText())
            assertTrue("Requires comfort pack\tvw/Golf_MY27.pdf:8" in selection.toReadableText())
        }
    }

    @Test
    fun `empty cars unknown trim and empty options have explicit readable output`() {
        assertEquals("No cars returned.", CarSelectionCodec.decode("""{"cars":[]}""").toReadableText())
        val selection =
            CarSelectionCodec.decode(
                """{"cars":[{"configurationId":"car-a","car":"Example","trim":null,"options":[]}]}""",
            )
        assertEquals(
            "Car: Example\nTrim: Unknown\nConfiguration: car-a\nNo requested options returned.",
            selection.toReadableText(),
        )
    }

    @Test
    fun `malformed missing extra and invalid fields are rejected without exposing raw output in the error`() {
        val valid = FakeOpenRouterServer.CAR_OUTPUT
        val invalid =
            listOf(
                "not JSON",
                "```json\n$valid\n```",
                "{}",
                """{"cars":null}""",
                valid.replace("\"trim\":\"Comfort\",", ""),
                valid.replace(",\"sources\":[\"vw/Golf_MY27.pdf:8\"]", ""),
                valid.replace("vw/Golf_MY27.pdf:8", "vw/Golf_MY27.pdf:0"),
                valid.replace("vw/Golf_MY27.pdf:8", "vw/Golf_MY27.pdf:8,9"),
                valid.replace("\"Comfort\"", "42"),
                valid.replace("\"Example Family\"", "true"),
                valid.replace("\"Requires comfort pack\"", "12"),
                valid.replace("\"OPTIONAL\"", "\"AVAILABLE\""),
                valid.replace("\"Comfort\"", "\"\""),
                valid.replace("\"car-a\"", "\"\""),
                valid.replace("\"Electric seats\"", "\" \""),
                valid.replace("\"cars\":", "\"extra\":true,\"cars\":"),
                valid.replace("\"options\":", "\"unexpected\":1,\"options\":"),
                valid.replace("\"details\":", "\"unexpected\":1,\"details\":"),
                valid.replace("\"options\":[", "\"options\":null,\"unused\":["),
            )
        invalid.forEach { raw ->
            val error = assertFailsWith<OpenRouterWorkflowException> { CarSelectionCodec.decode(raw) }
            assertEquals(FailureKind.INVALID_RESPONSE, error.kind)
            assertEquals(raw, error.rawOutput)
            assertEquals("The model output does not match the required cars/trim/options JSON contract.", error.message)
        }
    }

    @Test
    fun `duplicate configurations and option names are rejected`() {
        val car = """{"configurationId":"car-a","car":"Example","trim":null,"options":[]}"""
        val option = """{"name":"Seats","status":"UNKNOWN","details":"","sources":[]}"""
        listOf(
            """{"cars":[$car,$car]}""",
            """{"cars":[${car.replace("[]", "[$option,$option]")}]}""",
        ).forEach { raw ->
            assertFailsWith<OpenRouterWorkflowException> { CarSelectionCodec.decode(raw) }
        }
    }
}
