package com.vaultmind.app.ingestion

/**
 * Recursive text splitter that respects paragraph and sentence boundaries.
 *
 * Chunking strategy (matching the plan):
 *  - Target chunk size: 256 tokens (~1024 characters for Turkish/multilingual text)
 *  - Overlap: 40 tokens (~160 characters)
 *  - Priority: split on double-newline (paragraphs) → newline → ". " → " "
 *
 * Token count approximation: 1 token ≈ 4 characters for Turkish text.
 * For production, replace [estimateTokenCount] with the actual SentencePiece tokenizer
 * bundled with EmbeddingGemma (see: plan § Notes for Coding Agent, point 3).
 */
class TextChunker(
    private val targetTokens: Int = 256,
    private val overlapTokens: Int = 40
) {
    companion object {
        // 1 token ≈ 4 characters (conservative estimate for Turkish/multilingual)
        private const val CHARS_PER_TOKEN = 4

        // Split priority: try largest units first, fall back to smaller
        private val SEPARATORS = listOf("\n\n", "\n", ". ", "! ", "? ", " ")
    }

    data class Chunk(
        val text: String,
        val index: Int,
        val estimatedTokenCount: Int
    )

    /**
     * Split [text] into overlapping chunks, returning a list of [Chunk].
     *
     * The text is preprocessed to normalize whitespace before chunking.
     */
    fun chunk(text: String): List<Chunk> {
        val normalised = text
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .trim()

        if (normalised.isEmpty()) return emptyList()

        val targetChars = targetTokens * CHARS_PER_TOKEN
        val overlapChars = overlapTokens * CHARS_PER_TOKEN

        val rawChunks = splitRecursive(normalised, targetChars, SEPARATORS)

        // Merge small chunks and apply overlap
        return mergeAndOverlap(rawChunks, targetChars, overlapChars)
    }

    /**
     * Recursively split [text] using [separators] until each piece is ≤ [maxChars].
     */
    private fun splitRecursive(text: String, maxChars: Int, separators: List<String>): List<String> {
        if (text.length <= maxChars) return listOf(text)
        if (separators.isEmpty()) {
            // Last resort: hard split
            return text.chunked(maxChars)
        }

        val separator = separators.first()
        val remaining = separators.drop(1)

        val parts = text.split(separator)
        val result = mutableListOf<String>()

        for (part in parts) {
            val trimmed = part.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.length <= maxChars) {
                result.add(trimmed)
            } else {
                result.addAll(splitRecursive(trimmed, maxChars, remaining))
            }
        }
        return result
    }

    /**
     * Merge adjacent small pieces into chunks of size ≤ [maxChars],
     * then slide a window with [overlapChars] overlap.
     */
    private fun mergeAndOverlap(pieces: List<String>, maxChars: Int, overlapChars: Int): List<Chunk> {
        // First pass: merge consecutive small pieces
        val merged = mutableListOf<String>()
        var current = StringBuilder()

        for (piece in pieces) {
            if (current.isEmpty()) {
                current.append(piece)
            } else if (current.length + piece.length + 1 <= maxChars) {
                current.append("\n\n").append(piece)
            } else {
                merged.add(current.toString())
                current = StringBuilder(piece)
            }
        }
        if (current.isNotEmpty()) merged.add(current.toString())

        if (merged.size <= 1) {
            return merged.mapIndexed { i, text ->
                Chunk(text, i, estimateTokenCount(text))
            }
        }

        // Second pass: add overlap by including trailing text from previous chunk
        val result = mutableListOf<Chunk>()
        for (i in merged.indices) {
            val chunkText = if (i == 0 || overlapChars == 0) {
                merged[i]
            } else {
                // Prepend the last [overlapChars] characters of the previous chunk
                val prevChunk = merged[i - 1]
                val overlapText = prevChunk.takeLast(overlapChars).let {
                    // Don't start mid-word
                    val spaceIdx = it.indexOf(' ')
                    if (spaceIdx > 0) it.substring(spaceIdx + 1) else it
                }
                if (overlapText.isNotBlank()) "$overlapText\n\n${merged[i]}"
                else merged[i]
            }
            result.add(Chunk(chunkText, i, estimateTokenCount(chunkText)))
        }
        return result
    }

    /**
     * Approximate token count: 1 token ≈ 4 characters.
     *
     * Replace this with the actual SentencePiece tokenizer when integrating
     * the EmbeddingGemma model bundle. The tokenizer .model file ships with
     * the EmbeddingGemma .tflite package from litert-community on HuggingFace.
     */
    private fun estimateTokenCount(text: String): Int = (text.length / CHARS_PER_TOKEN).coerceAtLeast(1)

    fun estimateTokenCount(text: String, dummy: Unit = Unit): Int = estimateTokenCount(text)
}
