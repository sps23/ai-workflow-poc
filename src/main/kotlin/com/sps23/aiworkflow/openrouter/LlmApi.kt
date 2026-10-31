package com.sps23.aiworkflow.openrouter

const val LOCAL_API_KEY_VARIABLE = "LOCAL_AI_API_KEY"

/** Chat API targets selectable in the desktop UI. Local presets use each server's default OpenAI-compatible URL. */
enum class LlmApi(
    val label: String,
    val baseUrl: String,
    val isLocal: Boolean,
) {
    OPENROUTER("OpenRouter", OPENROUTER_BASE_URL, false),
    OPENAI("OpenAI", OPENAI_BASE_URL, false),
    GOOGLE_AI("Google AI Studio", GOOGLE_AI_BASE_URL, false),
    LM_STUDIO("LM Studio (local)", "http://localhost:1234/v1", true),
    LLAMA_CPP("llama.cpp (local)", "http://localhost:8080/v1", true),
    OLLAMA("Ollama (local)", "http://localhost:11434/v1", true),
    MANUAL("Manual API (OpenAI-compatible)", "", false),
}

/** One chat-completion backend used by [OpenRouterCarWorkflow]. */
interface ChatBackend {
    val maxCompletionTokens: Long

    /** Model limits for the size gate, or null when the backend cannot report them. */
    suspend fun fetchLimits(): ModelLimits?

    suspend fun complete(
        userMessage: String,
        systemPrompt: String? = null,
        context: String? = null,
        limits: ModelLimits? = null,
        maxCompletionTokens: Long = this.maxCompletionTokens,
    ): Completion
}
