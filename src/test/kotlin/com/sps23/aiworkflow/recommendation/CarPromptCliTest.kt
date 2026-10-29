package com.sps23.aiworkflow.recommendation

import com.sps23.aiworkflow.openrouter.API_KEY_VARIABLE
import com.sps23.aiworkflow.openrouter.FakeOpenRouterServer
import com.sps23.aiworkflow.openrouter.MODEL_VARIABLE
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CarPromptCliTest {
    private val key = "cli-secret-key"
    private val env = mapOf(API_KEY_VARIABLE to key, MODEL_VARIABLE to FakeOpenRouterServer.FREE_MODEL)

    private class Result(
        val code: Int,
        val out: String,
        val err: String,
    )

    private fun run(
        args: List<String>,
        environment: Map<String, String?>,
        baseUrl: String = "http://localhost:1",
    ): Result {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code =
            runCarPrompt(
                args,
                environment,
                PrintStream(out, true),
                PrintStream(err, true),
                baseUrl,
                catalogJson = { """{"configurations":[],"features":[]}""" },
            )
        return Result(code, out.toString(), err.toString())
    }

    @Test
    fun `prints answer model and disclaimer on success`() {
        FakeOpenRouterServer().use { server ->
            server.chatBody = FakeOpenRouterServer.chatResponse(FakeOpenRouterServer.CAR_OUTPUT)
            val result = run(listOf("Suggest", "a", "seven-seat", "car"), env, server.baseUrl)

            assertEquals(0, result.code)
            assertTrue("Car: Example Family" in result.out)
            assertTrue("Trim: Comfort" in result.out)
            assertTrue("Electric seats\tOptional\tRequires comfort pack" in result.out)
            assertTrue(FakeOpenRouterServer.FREE_MODEL in result.out)
            assertTrue(PROTOTYPE_DISCLAIMER in result.out)
            assertEquals("", result.err)
            assertEquals(2, server.chatRequests.size)
            assertTrue("Suggest a seven-seat car" in server.chatRequests.last().body)
        }
    }

    @Test
    fun `provider failure exits nonzero with an actionable stderr message`() {
        FakeOpenRouterServer().use { server ->
            server.chatStatus = 401
            server.chatBody = """{"error":{"message":"bad key"}}"""
            val result = run(listOf("hello"), env, server.baseUrl)

            assertEquals(1, result.code)
            assertEquals("", result.out)
            assertTrue("AUTHENTICATION" in result.err && API_KEY_VARIABLE in result.err)
            assertFalse(key in result.err)
        }
    }

    @Test
    fun `blank prompt and missing configuration are rejected without network calls`() {
        val blank = run(listOf("  ", ""), env)
        assertEquals(2, blank.code)
        assertTrue(blank.err.isNotBlank())

        val missing = run(listOf("hello"), emptyMap())
        assertEquals(1, missing.code)
        assertTrue(API_KEY_VARIABLE in missing.err)
    }
}
