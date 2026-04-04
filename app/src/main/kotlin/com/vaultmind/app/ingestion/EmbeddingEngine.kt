package com.vaultmind.app.ingestion

import android.content.Context
import com.google.ai.edge.litert.Interpreter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Runs EmbeddingGemma 300M via LiteRT (TFLite) to produce embedding vectors.
 *
 * Model: litert-community/embeddinggemma-300m on HuggingFace
 * Format: .tflite (LiteRT flat-buffer)
 * Output: 768-dim (or 256-dim) float32 vectors, L2-normalised
 *
 * Embedding prompts follow the EmbeddingGemma specification:
 *  - Document (ingestion): "task: search result | text: {chunk}"
 *  - Query (retrieval): "task: search result | query: {question}"
 *
 * NOTE: LiteRT Interpreter input expects tokenised integer IDs, not raw text.
 * This class assumes a pre-tokenised int32 input tensor.
 * If the model uses a text input (e.g. via TFLite NLP TaskLib), adapt accordingly.
 *
 * For the full integration:
 *  1. Download embeddinggemma-300m.tflite from HuggingFace litert-community
 *  2. Place it in app's files directory (sideloaded) or assets (bundled)
 *  3. The Google AI Edge RAG Library (com.google.ai.edge:android-ai-edge-rag) provides
 *     a higher-level wrapper if available on Maven — check at integration time.
 */
@Singleton
class EmbeddingEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val DEFAULT_EMBEDDING_DIM = 768
        private const val MAX_SEQUENCE_LENGTH = 512  // EmbeddingGemma max context

        // Prompt prefixes per EmbeddingGemma spec
        const val DOCUMENT_PROMPT_PREFIX = "task: search result | text: "
        const val QUERY_PROMPT_PREFIX = "task: search result | query: "

        // Model file name — user places this in app's files dir or it's sideloaded
        const val MODEL_FILENAME = "embeddinggemma-300m.tflite"
    }

    private var interpreter: Interpreter? = null
    private var embeddingDim: Int = DEFAULT_EMBEDDING_DIM

    /** Returns true if the embedding model is loaded and ready. */
    fun isLoaded(): Boolean = interpreter != null

    /**
     * Load the embedding model from [modelPath].
     * Must be called before any [embed] calls.
     *
     * @param modelPath Absolute path to the .tflite model file.
     */
    fun load(modelPath: String) {
        interpreter?.close()

        val options = Interpreter.Options().apply {
            numThreads = 4
            // GPU delegate: enable if available (provides 2-4x speedup on S23 Ultra)
            // addDelegate(GpuDelegate()) — uncomment after verifying model GPU compatibility
        }
        interpreter = Interpreter(File(modelPath), options)

        // Infer embedding dimension from output tensor shape
        val outputShape = interpreter!!.getOutputTensor(0).shape()
        embeddingDim = if (outputShape.size >= 2) outputShape[1] else DEFAULT_EMBEDDING_DIM
    }

    /** Return the embedding dimension of the loaded model. */
    fun getEmbeddingDim(): Int = embeddingDim

    /**
     * Embed a document chunk for storage.
     * Prepends the document prompt prefix as per EmbeddingGemma spec.
     *
     * @return L2-normalised float array of size [embeddingDim], or null if model not loaded.
     */
    suspend fun embedDocument(text: String): FloatArray? = withContext(Dispatchers.Default) {
        embed("$DOCUMENT_PROMPT_PREFIX$text")
    }

    /**
     * Embed a user query for retrieval.
     * Prepends the query prompt prefix.
     *
     * @return L2-normalised float array of size [embeddingDim], or null if model not loaded.
     */
    suspend fun embedQuery(text: String): FloatArray? = withContext(Dispatchers.Default) {
        embed("$QUERY_PROMPT_PREFIX$text")
    }

    /**
     * Core embedding inference.
     *
     * The LiteRT Interpreter for EmbeddingGemma expects:
     *  - Input tensor 0: int32[1, MAX_SEQUENCE_LENGTH] — token IDs (padded)
     *  - Output tensor 0: float32[1, embeddingDim] — raw embedding
     *
     * Note: This implementation uses a simple whitespace tokenizer as a placeholder.
     * Replace with the actual SentencePiece tokenizer shipped with EmbeddingGemma.
     * The tokenizer vocabulary file (.model) is included in the HuggingFace model package.
     */
    private fun embed(promptedText: String): FloatArray? {
        val interp = interpreter ?: return null

        // Tokenise — PLACEHOLDER: replace with SentencePiece tokenizer
        val tokenIds = simplePlaceholderTokenize(promptedText, MAX_SEQUENCE_LENGTH)

        // Prepare input: int32[1, MAX_SEQUENCE_LENGTH]
        val inputBuffer = ByteBuffer
            .allocateDirect(1 * MAX_SEQUENCE_LENGTH * 4)
            .order(ByteOrder.nativeOrder())
        tokenIds.forEach { inputBuffer.putInt(it) }
        inputBuffer.rewind()

        // Prepare output: float32[1, embeddingDim]
        val outputBuffer = ByteBuffer
            .allocateDirect(1 * embeddingDim * 4)
            .order(ByteOrder.nativeOrder())

        interp.run(inputBuffer, outputBuffer)
        outputBuffer.rewind()

        val vector = FloatArray(embeddingDim) { outputBuffer.float }
        return l2Normalise(vector)
    }

    /**
     * Placeholder tokenizer — converts text to character codepoints truncated/padded to [maxLen].
     *
     * IMPORTANT: Replace this with the real SentencePiece tokenizer before using in production.
     * The EmbeddingGemma tokenizer vocabulary is in the `tokenizer.model` file from HuggingFace.
     *
     * Integration path:
     *  1. Add sentencepiece-android dependency (or use JNI wrapper)
     *  2. Load the .model file from assets or files dir
     *  3. Encode text → List<Int> token IDs
     *  4. Truncate/pad to MAX_SEQUENCE_LENGTH
     */
    private fun simplePlaceholderTokenize(text: String, maxLen: Int): IntArray {
        val codepoints = text.codePoints().limit(maxLen.toLong()).toArray()
        return IntArray(maxLen) { i -> if (i < codepoints.size) codepoints[i] else 0 }
    }

    /** L2 normalise a vector in-place, return it. */
    private fun l2Normalise(vector: FloatArray): FloatArray {
        val norm = sqrt(vector.fold(0f) { acc, v -> acc + v * v })
        if (norm > 0f) {
            for (i in vector.indices) vector[i] /= norm
        }
        return vector
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
