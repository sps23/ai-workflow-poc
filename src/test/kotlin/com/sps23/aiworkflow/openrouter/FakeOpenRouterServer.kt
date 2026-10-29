package com.sps23.aiworkflow.openrouter

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
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

    @Volatile var chatStatus: Int = 200

    @Volatile var chatBody: String = chatResponse("Fake answer")

    @Volatile var chatDelayMillis: Long = 0
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

                    "/api/v1/chat/completions" -> {
                        if (chatDelayMillis > 0) Thread.sleep(chatDelayMillis)
                        chatStatus to chatBody
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
        const val FREE_MODEL = "vendor/model:free"

        fun modelsJson(
            id: String = FREE_MODEL,
            promptPrice: String = "0",
            completionPrice: String = "0",
            contextLength: Long = 100_000,
            outputs: String = "\"text\"",
            extraPricing: String = "",
        ): String =
            """{"data":[
              {"id":"other/model","pricing":{"prompt":"1","completion":"1"}},
              {"id":"$id","context_length":$contextLength,
               "architecture":{"output_modalities":[$outputs]},
               "pricing":{"prompt":"$promptPrice","completion":"$completionPrice","request":"0"$extraPricing},
               "top_provider":{"context_length":$contextLength,"max_completion_tokens":4096}}]}"""

        fun chatResponse(
            content: String?,
            finishReason: String = "stop",
        ): String {
            val contentJson = if (content == null) "null" else "\"$content\""
            return """{"id":"gen-1","model":"$FREE_MODEL","created":1,"choices":[
                {"index":0,"finish_reason":"$finishReason",
                 "message":{"role":"assistant","content":$contentJson}}],
                "usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}"""
        }
    }
}
