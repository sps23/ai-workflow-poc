package com.sps23.aiworkflow.logging

import com.sps23.aiworkflow.openrouter.OpenRouterWorkflowException
import com.sps23.aiworkflow.workspace.WorkspacePaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class WorkspaceLoggingException(
    message: String,
    cause: IOException,
) : IOException(message, cause)

/** JSON Lines, intentionally separate from metadata-only exports and library console logging. */
class WorkspaceLogs(
    private val paths: WorkspacePaths,
    secrets: Collection<String?> = credentials(System.getenv()),
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val secrets = secrets.filterNotNull().filter { it.isNotBlank() }.sortedByDescending { it.length }

    fun info(message: String) = general("INFO", message)

    fun error(message: String) = general("ERROR", message)

    suspend fun <T> record(
        component: String,
        stage: String,
        request: JsonObject,
        action: suspend (Operation) -> T,
    ): T =
        withContext(Dispatchers.IO) {
            require(component in setOf("assistant", "extractor", "builder"))
            val operation = Operation(create(component), component, stage)
            operation.event("STARTED", request)
            info("$component / $stage started: ${operation.path.fileName}")
            try {
                action(operation).also {
                    operation.event(operation.outcome)
                    if (operation.outcome == "COMPLETED") info("$component / $stage completed: ${operation.path.fileName}")
                }
            } catch (exception: Exception) {
                try {
                    val failure = exception as? OpenRouterWorkflowException
                    operation.event(
                        if (exception is CancellationException) "CANCELLED" else "FAILED",
                        buildJsonObject {
                            put("exceptionType", exception.javaClass.name)
                            put("message", exception.message)
                            put("stackTrace", exception.stackTraceToString())
                            put("failureKind", failure?.kind?.name)
                            put("rawResponse", failure?.rawOutput)
                            put("responseJson", failure?.rawOutput?.let(::parseJson) ?: JsonNull)
                            put("requestMetrics", failure?.requestMetrics?.let { detailJson.encodeToJsonElement(it) } ?: JsonNull)
                            put("performanceMetadata", failure?.metadata?.let { detailJson.encodeToJsonElement(it) } ?: JsonNull)
                            put(
                                "diagnostics",
                                failure?.diagnostics?.let {
                                    buildJsonObject {
                                        put("stage", it.stage)
                                        put("httpStatus", it.httpStatus)
                                        put("providerCode", it.providerCode)
                                        put("providerMessage", it.providerMessage)
                                        put("providerName", it.providerName)
                                        put("requestId", it.requestId)
                                        put("upstreamCode", it.upstreamCode)
                                        put("upstreamMessage", it.upstreamMessage)
                                    }
                                } ?: JsonNull,
                            )
                        },
                    )
                    error("$component / $stage failed: ${exception.message}; ${operation.path.fileName}")
                } catch (loggingFailure: WorkspaceLoggingException) {
                    exception.addSuppressed(loggingFailure)
                    if (exception !is CancellationException) {
                        throw WorkspaceLoggingException(
                            "${loggingFailure.message}; original failure: ${redact(exception.message.orEmpty())}",
                            loggingFailure,
                        )
                    }
                }
                throw exception
            }
        }

    inner class Operation internal constructor(
        val path: Path,
        private val component: String,
        private val stage: String,
    ) {
        private val started = System.nanoTime()
        internal var outcome = "COMPLETED"

        fun event(
            event: String,
            details: JsonObject = buildJsonObject {},
        ) {
            append(
                path,
                buildJsonObject {
                    put("schemaVersion", 1)
                    put("timestamp", clock.instant().toString())
                    put("component", component)
                    put("stage", stage)
                    put("event", event)
                    put("elapsedMillis", (System.nanoTime() - started) / 1_000_000)
                    put("details", details)
                },
            )
        }

        fun response(raw: String) =
            event(
                "RESPONSE",
                buildJsonObject {
                    put("rawResponse", raw)
                    put("responseJson", parseJson(raw))
                },
            )

        fun failure(
            message: String,
            details: JsonObject,
        ) {
            outcome = "FAILED"
            event("VALIDATION_FAILED", details)
            error("$component / $stage: $message; ${path.fileName}")
        }
    }

    private fun general(
        level: String,
        message: String,
    ) = guarded {
        ensureDirectory()
        append(
            paths.logs.resolve("application.log"),
            buildJsonObject {
                put("timestamp", clock.instant().toString())
                put("level", level)
                put("message", message)
            },
        )
    }

    private fun create(component: String): Path =
        guarded {
            ensureDirectory()
            val date = LocalDateTime.now(clock).format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
            var collision = 0
            while (true) {
                val suffix = if (collision == 0) "" else "_$collision"
                val candidate = paths.logs.resolve("log_${date}${suffix}_$component.log")
                try {
                    Files.createFile(candidate, *privateAttributes(paths.logs))
                    return@guarded candidate
                } catch (_: FileAlreadyExistsException) {
                    collision++
                }
            }
            @Suppress("UNREACHABLE_CODE")
            kotlin.error("Unreachable")
        }

    private fun ensureDirectory() {
        if (!Files.isDirectory(paths.root)) throw IOException("Workspace root is unavailable: ${paths.root}")
        if (Files.isSymbolicLink(paths.logs)) throw IOException("Log directory must not be a symbolic link: ${paths.logs}")
        Files.createDirectories(paths.logs, *privateAttributes(paths.root, directory = true))
        if (!Files.isDirectory(paths.logs, LinkOption.NOFOLLOW_LINKS)) throw IOException("Log directory is unavailable: ${paths.logs}")
    }

    private fun privateAttributes(
        parent: Path,
        directory: Boolean = false,
    ) = if (Files.getFileAttributeView(parent, PosixFileAttributeView::class.java) != null) {
        arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(if (directory) "rwx------" else "rw-------")))
    } else {
        emptyArray()
    }

    private fun append(
        path: Path,
        entry: JsonObject,
    ) = guarded {
        synchronized(writeLock) {
            ensureDirectory()
            if (Files.isSymbolicLink(path)) throw IOException("Log file must not be a symbolic link: $path")
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.createFile(path, *privateAttributes(paths.logs))
                } catch (_: FileAlreadyExistsException) {
                    // Another process may have opened the shared application log.
                }
            }
            FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS).use { channel ->
                channel.lock().use {
                    val bytes = ByteBuffer.wrap((sanitize(entry).toString() + "\n").toByteArray(Charsets.UTF_8))
                    while (bytes.hasRemaining()) channel.write(bytes)
                    channel.force(false)
                }
            }
        }
    }

    private fun sanitize(value: JsonElement): JsonElement =
        when (value) {
            is JsonObject -> {
                JsonObject(
                    value.mapValues { (key, entry) ->
                        if (key.lowercase() in setOf("authorization", "api_key", "apikey", "x-goog-api-key", "password", "access_token")) {
                            JsonPrimitive("[REDACTED]")
                        } else {
                            sanitize(entry)
                        }
                    },
                )
            }

            is JsonArray -> {
                JsonArray(value.map(::sanitize))
            }

            is JsonPrimitive -> {
                if (value.isString) JsonPrimitive(redact(value.content)) else value
            }
        }

    private fun redact(value: String): String {
        var result = value
        secrets.forEach { result = result.replace(it, "[REDACTED]") }
        return result
            .replace(
                Regex("""(?i)("(?:api_?key|authorization|x-goog-api-key|password|access_token)"\s*:\s*")((?:\\.|[^"\\])*)(")"""),
            ) { "${it.groupValues[1]}[REDACTED]${it.groupValues[3]}" }
            .replace(Regex("(?i)Bearer\\s+[^\\s\"\\\\]+"), "Bearer [REDACTED]")
            .replace(Regex("sk-(?:or-)?[A-Za-z0-9_-]+"), "[REDACTED]")
    }

    private inline fun <T> guarded(action: () -> T): T =
        try {
            action()
        } catch (exception: WorkspaceLoggingException) {
            throw exception
        } catch (exception: IOException) {
            throw WorkspaceLoggingException(
                "Cannot persist workspace logs at ${paths.logs}: ${redact(exception.message.orEmpty())}. " +
                    "The operation may already have completed; check its artifacts before retrying.",
                exception,
            )
        }

    companion object {
        private val writeLock = Any()
        internal val detailJson = Json { encodeDefaults = true }

        fun credentials(
            environment: Map<String, String?>,
            override: String? = null,
        ): List<String?> = environment.filterKeys { it.endsWith("_API_KEY") }.values.toList() + override

        fun parseJson(raw: String): JsonElement =
            try {
                Json.parseToJsonElement(raw)
            } catch (_: SerializationException) {
                JsonNull
            } catch (_: IllegalArgumentException) {
                JsonNull
            }
    }
}
