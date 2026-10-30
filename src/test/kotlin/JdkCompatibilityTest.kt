import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import androidx.compose.material.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.renderComposeScene
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.InetAddress
import java.net.ServerSocket
import javax.swing.SwingUtilities
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Ignore
class JdkCompatibilityTest {
    @Serializable
    data class Payload(
        val message: String,
    )

    @Test
    fun `serialization compiler and runtime work together`() {
        val payload = Payload("jdk compatibility")
        assertEquals(payload, Json.decodeFromString<Payload>(Json.encodeToString(payload)))
    }

    @Test
    fun `CIO and content negotiation handle a local HTTP response`() =
        runBlocking {
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
                server.soTimeout = 10_000
                val response =
                    async(Dispatchers.IO) {
                        server.accept().use { socket ->
                            socket.soTimeout = 10_000
                            val reader = socket.getInputStream().bufferedReader()
                            while (!reader.readLine().isNullOrEmpty()) {
                                // Consume the request headers before sending the response.
                            }
                            val body = """{"message":"jdk compatibility"}"""
                            socket.getOutputStream().write(
                                (
                                    "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                                        "Content-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n" +
                                        body
                                ).toByteArray(),
                            )
                        }
                    }
                val client =
                    HttpClient(CIO) {
                        install(ContentNegotiation) { json() }
                        engine { requestTimeout = 10_000 }
                    }
                try {
                    val payload = client.get("http://localhost:${server.localPort}").body<Payload>()
                    assertEquals(Payload("jdk compatibility"), payload)
                    response.await()
                } finally {
                    client.close()
                }
            }
        }

    @Test
    fun `coroutines dispatch to the Swing event thread`() =
        runBlocking {
            withContext(Dispatchers.Swing) {
                assertTrue(SwingUtilities.isEventDispatchThread())
            }
        }

    @Test
    fun `Compose compiler state and native text rendering work together`() =
        runBlocking {
            withContext(Dispatchers.Swing) {
                val state = mutableStateOf("initial")
                state.value = "updated"
                renderComposeScene(width = 128, height = 32) {
                    Text(state.value)
                }.use { image ->
                    assertEquals(128, image.width)
                    assertEquals(32, image.height)
                }
            }
        }

    @Test
    fun `Koog core and OpenRouter client initialize without credentials or network calls`() {
        assertEquals(
            "AIAgent",
            Class.forName("ai.koog.agents.core.agent.AIAgent").simpleName,
        )
        OpenRouterLLMClient(apiKey = "unused-local-smoke-test").close()
    }
}
