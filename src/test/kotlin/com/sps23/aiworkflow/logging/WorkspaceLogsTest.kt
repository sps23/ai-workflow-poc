package com.sps23.aiworkflow.logging

import com.sps23.aiworkflow.openrouter.ChatBackend
import com.sps23.aiworkflow.openrouter.Completion
import com.sps23.aiworkflow.openrouter.FailureKind
import com.sps23.aiworkflow.openrouter.ModelLimits
import com.sps23.aiworkflow.openrouter.OpenRouterCarWorkflow
import com.sps23.aiworkflow.openrouter.OpenRouterErrorDiagnostics
import com.sps23.aiworkflow.openrouter.OpenRouterWorkflowException
import com.sps23.aiworkflow.openrouter.RequestMetrics
import com.sps23.aiworkflow.openrouter.TokenUsage
import com.sps23.aiworkflow.recommendation.LlmRequest
import com.sps23.aiworkflow.recommendation.LlmRequestCodec
import com.sps23.aiworkflow.workspace.WorkspacePaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Comparator
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WorkspaceLogsTest {
    private val directory = Files.createTempDirectory("workspace-logs-test")
    private val paths = WorkspacePaths.at(directory)
    private val clock = Clock.fixed(Instant.parse("2026-11-01T23:28:55Z"), ZoneOffset.UTC)
    private val logs = WorkspaceLogs(paths, listOf("private-api-key"), clock)
    private val original = "parking sensors"
    private val translated = LlmRequest(original, emptyList(), emptyList(), emptyList(), listOf(original))
    private val metrics =
        RequestMetrics(
            "Test API",
            "requested-model",
            "actual-model",
            inputUtf8Bytes = 5000,
            estimatedInputTokens = 2500,
            requestedOutputTokens = 2048,
            dispatched = true,
            httpStatus = 200,
            elapsedMillis = 12,
            usage = TokenUsage(100, 20, 120),
        )

    @AfterTest
    fun cleanup() {
        Files.walk(directory).use { entries ->
            entries.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun `unique per-call JSONL files preserve full content redact credentials and append summaries`() =
        runBlocking {
            val full = "Prompt ".repeat(1000)
            repeat(2) {
                logs.record(
                    "assistant",
                    "test",
                    buildJsonObject {
                        put("systemPrompt", full)
                        put("message", "private-api-key")
                        put("authorization", "sensitive-header")
                    },
                ) { log ->
                    log.response("""{"answer":"private-api-key","apiKey":"unknown-secret"}""")
                }
            }
            val files = operationFiles("assistant")
            assertEquals(
                listOf("log_20261101232855_1_assistant.log", "log_20261101232855_assistant.log"),
                files.map { it.fileName.toString() },
            )
            files.forEach { path ->
                val text = Files.readString(path)
                assertTrue(
                    full in
                        events(path)
                            .first()
                            .getValue("details")
                            .jsonObject
                            .getValue("systemPrompt")
                            .jsonPrimitive.content,
                )
                assertFalse("private-api-key" in text)
                assertFalse("sensitive-header" in text)
                assertFalse("unknown-secret" in text)
                assertTrue("[REDACTED]" in text)
                assertEquals(listOf("STARTED", "RESPONSE", "COMPLETED"), events(path).map { it.getValue("event").jsonPrimitive.content })
            }
            assertEquals(4, events(paths.logs.resolve("application.log")).size)
            if (Files.getFileAttributeView(paths.logs, PosixFileAttributeView::class.java) != null) {
                assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(paths.logs))
                files.forEach { assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(it)) }
            }
        }

    @Test
    fun `concurrent logger instances do not overwrite calls or interleave general JSON lines`() =
        runBlocking {
            (1..16)
                .map { index ->
                    async(Dispatchers.IO) {
                        WorkspaceLogs(paths, clock = clock).record("assistant", "call-$index", buildJsonObject {}) { }
                    }
                }.awaitAll()
            assertEquals(16, operationFiles("assistant").size)
            assertEquals(32, events(paths.logs.resolve("application.log")).size)
            operationFiles("assistant").forEach { assertEquals(2, events(it).size) }
        }

    @Test
    fun `failures and cancellation retain raw model output diagnostic metadata and original exception`() =
        runBlocking {
            val failure =
                OpenRouterWorkflowException(
                    FailureKind.TRUNCATED_RESPONSE,
                    "private-api-key failed",
                    diagnostics = OpenRouterErrorDiagnostics("Chat completion", 429, providerMessage = "private-api-key"),
                    rawOutput = """{"broken":"private-api-key"""",
                    requestMetrics = metrics,
                )
            assertSame(
                failure,
                assertFailsWith<OpenRouterWorkflowException> {
                    logs.record("assistant", "test", buildJsonObject {}) { throw failure }
                },
            )
            val cancelled = CancellationException("cancelled")
            assertEquals(
                cancelled.message,
                assertFailsWith<CancellationException> {
                    logs.record("extractor", "test", buildJsonObject {}) { throw cancelled }
                }.message,
            )
            val failed = events(operationFiles("assistant").single()).last()
            assertEquals("FAILED", failed.getValue("event").jsonPrimitive.content)
            val details = failed.getValue("details").jsonObject
            assertEquals("TRUNCATED_RESPONSE", details.getValue("failureKind").jsonPrimitive.content)
            assertTrue("broken" in details.getValue("rawResponse").jsonPrimitive.content)
            assertTrue("requested-model" in details.getValue("requestMetrics").toString())
            assertFalse("private-api-key" in failed.toString())
            assertEquals(
                "CANCELLED",
                events(operationFiles("extractor").single())
                    .last()
                    .getValue("event")
                    .jsonPrimitive.content,
            )
            assertTrue(events(paths.logs.resolve("application.log")).any { it.getValue("level").jsonPrimitive.content == "ERROR" })
        }

    @Test
    fun `missing workspace is not silently recreated by logging`() {
        val missing = directory.resolve("removed-workspace")
        val logs = WorkspaceLogs(WorkspacePaths.at(missing))
        assertFailsWith<WorkspaceLoggingException> { logs.info("must fail") }
        assertFalse(Files.exists(missing))
    }

    @Test
    fun `logging failure stops dispatch and symbolic links are rejected`() =
        runBlocking {
            Files.createDirectory(paths.logs)
            Files.createDirectory(paths.logs.resolve("application.log"))
            var dispatched = false
            val error =
                assertFailsWith<WorkspaceLoggingException> {
                    logs.record("assistant", "test", buildJsonObject {}) { dispatched = true }
                }
            assertFalse(dispatched)
            assertTrue("Cannot persist workspace logs" in error.message.orEmpty())
            Files.delete(paths.logs.resolve("application.log"))
            Files.createSymbolicLink(paths.logs.resolve("application.log"), directory.resolve("outside.log"))
            assertFailsWith<WorkspaceLoggingException> { logs.info("must not follow link") }
            assertFalse(Files.exists(directory.resolve("outside.log")))
        }

    @Test
    fun `one Assistant file per inference includes validated performance metadata and cached runs make one file`() =
        runBlocking {
            val backend =
                backend { user ->
                    if (user == original) LlmRequestCodec.encode(translated) else """{"cars":[]}"""
                }
            val workflow = OpenRouterCarWorkflow(backend, """{"vehicles":[]}""", logs, "requested-model", "Test API")
            workflow.answer(original)
            assertEquals(2, operationFiles("assistant").size)
            operationFiles("assistant").forEach { path ->
                val records = events(path)
                assertEquals(
                    "COMPLETED",
                    records
                        .last()
                        .getValue("event")
                        .jsonPrimitive.content,
                )
                assertTrue(records.any { it.getValue("event").jsonPrimitive.content == "VALIDATED" })
                assertTrue("performanceMetadata" in Files.readString(path))
                assertTrue("actual-model" in Files.readString(path))
                assertTrue("totalTokens" in Files.readString(path))
            }
            workflow.answer(original, translated)
            assertEquals(3, operationFiles("assistant").size)
        }

    @Test
    fun `response validation and model lookup failures are logged without extra inference attempts`() =
        runBlocking {
            var calls = 0
            val invalid =
                backend {
                    calls++
                    "not JSON"
                }
            assertFailsWith<OpenRouterWorkflowException> {
                OpenRouterCarWorkflow(invalid, "{}", logs).answer(original)
            }
            assertEquals(1, calls)
            val file = operationFiles("assistant").single()
            assertTrue("not JSON" in Files.readString(file))
            assertEquals(
                "FAILED",
                events(file)
                    .last()
                    .getValue("event")
                    .jsonPrimitive.content,
            )
            val metadataFailure =
                object : ChatBackend by invalid {
                    override suspend fun fetchLimits(): ModelLimits? =
                        throw OpenRouterWorkflowException(FailureKind.MODEL_UNAVAILABLE, "No model")
                }
            assertFailsWith<OpenRouterWorkflowException> {
                OpenRouterCarWorkflow(metadataFailure, "{}", logs).answer(original)
            }
            assertEquals(2, operationFiles("assistant").size)
            assertEquals(1, calls)
        }

    private fun backend(answer: (String) -> String): ChatBackend =
        object : ChatBackend {
            override val maxCompletionTokens = 2048L

            override suspend fun fetchLimits(): ModelLimits? = null

            override suspend fun complete(
                userMessage: String,
                systemPrompt: String?,
                context: String?,
                limits: ModelLimits?,
                maxCompletionTokens: Long,
            ): Completion = Completion(answer(userMessage), "requested-model", "actual-model", metrics)
        }

    private fun operationFiles(component: String): List<Path> =
        Files.list(paths.logs).use { entries ->
            entries.filter { it.fileName.toString().endsWith("_$component.log") }.sorted().toList()
        }

    private fun events(path: Path): List<JsonObject> = Files.readAllLines(path).map { Json.parseToJsonElement(it).jsonObject }
}
