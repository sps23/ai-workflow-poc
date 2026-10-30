package com.sps23.aiworkflow.openrouter

import com.sps23.aiworkflow.recommendation.CarSelection
import com.sps23.aiworkflow.recommendation.LlmRequest
import com.sps23.aiworkflow.recommendation.LlmRequestCodec
import com.sps23.aiworkflow.recommendation.SelectedCar
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

data class CapturedRequest(
    val method: String,
    val path: String,
    val authorization: String?,
    val body: String,
)

/** Loopback fake of the two OpenRouter endpoints used by the workflow. No credentials or internet. */
class FakeOpenRouterServer : AutoCloseable {
    @Volatile var modelsStatus: Int = 200

    @Volatile var modelsBody: String = modelsJson()

    @Volatile var localModelsStatus: Int = 200

    @Volatile var localModelsBody: String = localModelsJson()

    @Volatile var chatStatus: Int = 200

    @Volatile var chatBody: String = chatResponse("Fake answer")

    @Volatile var chatDelayMillis: Long = 0

    @Volatile var translationBody: String? = null

    val requests = CopyOnWriteArrayList<CapturedRequest>()

    private val executor = Executors.newCachedThreadPool()
    private val server =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { exchange -> handle(exchange) }
            executor = this@FakeOpenRouterServer.executor
            start()
        }

    val baseUrl: String get() = "http://localhost:${server.address.port}"
    val chatRequests: List<CapturedRequest> get() = requests.filter { it.method == "POST" }

    private fun handle(exchange: HttpExchange) {
        exchange.use {
            val body = it.requestBody.readBytes().toString(Charsets.UTF_8)
            requests += CapturedRequest(it.requestMethod, it.requestURI.path, it.requestHeaders.getFirst("Authorization"), body)
            val (status, payload) =
                when (it.requestURI.path) {
                    "/api/v1/models" -> {
                        modelsStatus to modelsBody
                    }

                    "/api/v0/models" -> {
                        localModelsStatus to localModelsBody
                    }

                    "/api/v1/chat/completions", "/v1/chat/completions" -> {
                        if (chatDelayMillis > 0) Thread.sleep(chatDelayMillis)
                        val messages = Json.parseToJsonElement(body).jsonObject["messages"]?.jsonArray
                        val translating = messages?.firstOrNull()?.toString()?.contains("loss-preserving car-catalog query") == true
                        val payload =
                            if (translating && chatStatus == 200) {
                                translationBody ?: chatResponse(
                                    LlmRequestCodec.encode(
                                        LlmRequest(
                                            messages
                                                .last()
                                                .jsonObject
                                                .getValue("content")
                                                .jsonPrimitive.content,
                                            emptyList(),
                                            emptyList(),
                                            emptyList(),
                                            listOf(
                                                messages
                                                    .last()
                                                    .jsonObject
                                                    .getValue("content")
                                                    .jsonPrimitive.content,
                                            ),
                                        ),
                                    ),
                                )
                            } else {
                                chatBody
                            }
                        chatStatus to payload
                    }

                    else -> {
                        404 to "{}"
                    }
                }
            val bytes = payload.toByteArray()
            it.responseHeaders.add("Content-Type", "application/json")
            runCatching {
                it.sendResponseHeaders(status, bytes.size.toLong())
                it.responseBody.write(bytes)
            }
        }
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    companion object {
        const val LOCAL_MODEL = "local/test-model"
        const val FREE_MODEL = "vendor/model:free"
        const val CAR_OUTPUT =
            """{"cars":[{"configurationId":"car-a","car":"Example Family","trim":"Comfort","options":[{"name":"Electric seats","status":"OPTIONAL","details":"Requires comfort pack","sources":["vw/Golf_MY27.pdf:8"]}]}]}"""

        val MULTI_MODEL_OUTPUT =
            Json.encodeToString(
                CarSelection(
                    listOf(
                        SelectedCar("car-a-base", "Example Family", "Base", emptyList()),
                        SelectedCar("car-b-comfort", "Example SUV", "Comfort", emptyList()),
                        SelectedCar("car-a-comfort", "Example Family", "Comfort", emptyList()),
                        SelectedCar("car-c-sport", "Example Hatch", "Sport", emptyList()),
                        SelectedCar("car-a-sport", "Example Family", "Sport", emptyList()),
                    ),
                ),
            )

        fun localModelsJson(context: Long = 262_144): String =
            """{"data":[{"id":"$LOCAL_MODEL","type":"llm","state":"loaded",
                "max_context_length":1048576,"loaded_context_length":$context}]}"""

        fun modelsJson(
            id: String = FREE_MODEL,
            promptPrice: String = "0",
            completionPrice: String = "0",
            contextLength: Long = 100_000,
            outputs: String = "\"text\"",
            extraPricing: String = "",
            maxCompletionTokens: Long = 4096,
        ): String =
            """{"data":[
              {"id":"other/model","pricing":{"prompt":"1","completion":"1"}},
              {"id":"$id","context_length":$contextLength,
               "architecture":{"output_modalities":[$outputs]},
               "pricing":{"prompt":"$promptPrice","completion":"$completionPrice","request":"0"$extraPricing},
               "top_provider":{"context_length":$contextLength,"max_completion_tokens":$maxCompletionTokens}}]}"""

        fun chatResponse(
            content: String?,
            finishReason: String = "stop",
        ): String {
            val contentJson = JsonPrimitive(content).toString()
            return """{"id":"gen-1","model":"$FREE_MODEL","created":1,"choices":[
                {"index":0,"finish_reason":"$finishReason",
                 "message":{"role":"assistant","content":$contentJson}}],
                "usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}"""
        }
    }
}
