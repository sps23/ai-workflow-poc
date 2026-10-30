package com.sps23.aiworkflow.recommendation

import com.sps23.aiworkflow.openrouter.DEFAULT_LOCAL_MAX_COMPLETION_TOKENS
import com.sps23.aiworkflow.openrouter.DEFAULT_OPENROUTER_MAX_COMPLETION_TOKENS
import com.sps23.aiworkflow.openrouter.SYSTEM_INSTRUCTIONS
import com.sps23.aiworkflow.openrouter.buildUserMessage
import com.sps23.aiworkflow.openrouter.estimateInputTokens
import kotlinx.serialization.json.Json

object CatalogSizeCli {
    @JvmStatic
    fun main(args: Array<String>) {
        val compact = CatalogContext.compact(CatalogContext.loadBundledCatalog())
        val inlineBytes = Json.encodeToString(CompactCatalog.serializer(), compact).toByteArray().size
        val indexed = CatalogContext.toJson(compact)
        val bytes = indexed.toByteArray().size
        val estimate = estimateInputTokens(SYSTEM_INSTRUCTIONS, buildUserMessage("{}", indexed))
        println("Reviewed configurations: ${compact.configurations.size}")
        println("Inline compact catalog: $inlineBytes UTF-8 bytes")
        println("Indexed catalog: $bytes UTF-8 bytes (${100L * (inlineBytes - bytes) / inlineBytes}% smaller)")
        println("Catalog-only estimate: ${(bytes + 1L) / 2} tokens")
        println("Recommendation baseline: $estimate estimated input tokens")
        println("Default output targets: OpenRouter $DEFAULT_OPENROUTER_MAX_COMPLETION_TOKENS; local $DEFAULT_LOCAL_MAX_COMPLETION_TOKENS")
        println("LM Studio output is capped to loaded context minus estimated input for each request.")
        println("Baseline uses an empty request object; the translated user request adds tokens.")
        println("Estimates use 1 token per 2 UTF-8 bytes plus message overhead, not the model tokenizer.")
    }
}
