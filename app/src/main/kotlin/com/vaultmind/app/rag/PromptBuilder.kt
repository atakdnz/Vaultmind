package com.vaultmind.app.rag

import javax.inject.Inject

/**
 * Assembles the final prompt sent to Gemma 4 E4B.
 *
 * RAG prompt structure (following the plan):
 *   System: instructions about the task, language, and context constraints
 *   Context: retrieved chunks separated by markers
 *   History: last N turns of conversation
 *   User: current question
 *
 * Gemma 4 uses the standard chat template with special tokens.
 * Format based on Gemma 4 instruction-tuning template.
 */
class PromptBuilder @Inject constructor() {

    companion object {
        private const val MAX_CONTEXT_TOKENS = 3000
        private const val CHARS_PER_TOKEN = 4

        private val SYSTEM_PROMPT = """
            You are a helpful assistant answering questions about the user's personal notes.
            Answer ONLY based on the provided context below.
            The notes may be in Turkish. Respond in the same language the user uses to ask.
            If the context does not contain enough information to answer the question, say so clearly.
            Do not make up information. Be concise and accurate.
        """.trimIndent()
    }

    /**
     * Conversation turn (user message + optional assistant reply).
     */
    data class Turn(
        val userMessage: String,
        val assistantMessage: String? = null
    )

    /**
     * Build a complete prompt for Gemma 4 E4B.
     *
     * @param question         Current user question.
     * @param retrievedChunks  Chunks from vector search (ordered by relevance).
     * @param history          Previous conversation turns (up to N, newest last).
     * @param thinkingMode     Whether to append the `<|think|>` tag for CoT reasoning.
     * @param userInstructions Optional per-vault instructions from the user.
     */
    fun buildRagPrompt(
        question: String,
        retrievedChunks: List<VectorSearch.SearchResult>,
        history: List<Turn> = emptyList(),
        thinkingMode: Boolean = true,
        userInstructions: String? = null
    ): String {
        val contextBlock = buildContextBlock(retrievedChunks)

        val systemPrompt = if (!userInstructions.isNullOrBlank()) {
            SYSTEM_PROMPT + "\n\nUser instructions for this vault:\n" + userInstructions.trim()
        } else {
            SYSTEM_PROMPT
        }

        return buildGemmaPrompt(
            systemPrompt = systemPrompt,
            contextBlock = contextBlock,
            history = history,
            question = question,
            thinkingMode = thinkingMode
        )
    }

    /**
     * Build context block from retrieved chunks.
     * Chunks are truncated at MAX_CONTEXT_TOKENS to leave room for the rest of the prompt.
     */
    private fun buildContextBlock(chunks: List<VectorSearch.SearchResult>): String {
        if (chunks.isEmpty()) return "No relevant context found."

        val sb = StringBuilder()
        var tokenBudget = MAX_CONTEXT_TOKENS

        chunks.forEachIndexed { i, result ->
            val header = "[Context ${i + 1}]\n"
            val footer = "\n"
            val body = result.content
            val totalChars = (header + body + footer).length
            val estimatedTokens = totalChars / CHARS_PER_TOKEN

            if (tokenBudget <= 0) return@forEachIndexed
            if (estimatedTokens > tokenBudget) {
                // Truncate this chunk to fit remaining budget
                val maxChars = maxOf(0, tokenBudget * CHARS_PER_TOKEN)
                val available = maxOf(0, maxChars - header.length - footer.length - 10)
                if (available > 0) {
                    val truncated = body.take(available)
                    sb.append(header).append(truncated).append("…").append(footer)
                }
                tokenBudget = 0
            } else {
                sb.append(header).append(body).append(footer)
                tokenBudget -= estimatedTokens
            }
        }
        return sb.toString().trim()
    }

    /**
     * Assemble a Gemma 4 chat-format prompt with system, context, history, and user turn.
     *
     * Gemma 4 uses the following special tokens:
     *   <start_of_turn>user / model<end_of_turn>
     *
     * The system prompt is injected as the first user turn (Gemma style).
     */
    private fun buildGemmaPrompt(
        systemPrompt: String,
        contextBlock: String,
        history: List<Turn>,
        question: String,
        thinkingMode: Boolean
    ): String {
        val sb = StringBuilder()

        // System + context as the first user message
        sb.append("<start_of_turn>user\n")
        sb.append(systemPrompt)
        sb.append("\n\n--- CONTEXT ---\n")
        sb.append(contextBlock)
        sb.append("\n--- END CONTEXT ---")
        sb.append("<end_of_turn>\n")
        sb.append("<start_of_turn>model\nUnderstood. I will answer based only on the provided context.<end_of_turn>\n")

        // Conversation history
        for (turn in history) {
            sb.append("<start_of_turn>user\n")
            sb.append(turn.userMessage)
            sb.append("<end_of_turn>\n")
            if (turn.assistantMessage != null) {
                sb.append("<start_of_turn>model\n")
                sb.append(turn.assistantMessage)
                sb.append("<end_of_turn>\n")
            }
        }

        // Current question
        sb.append("<start_of_turn>user\n")
        sb.append(question)
        sb.append("<end_of_turn>\n")

        // Start model turn — add think tag if enabled
        sb.append("<start_of_turn>model\n")
        if (thinkingMode) sb.append("<|think|>")

        return sb.toString()
    }
}
