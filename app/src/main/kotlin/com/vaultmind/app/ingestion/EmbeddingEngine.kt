package com.vaultmind.app.ingestion

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Runs EmbeddingGemma 300M via LiteRT (TFLite successor) to produce embedding vectors.
 *
 * Model: litert-community/embeddinggemma-300m on HuggingFace
 * Format: .tflite (LiteRT flat-buffer)
 * Runtime: com.google.ai.edge.litert:litert (classes still at org.tensorflow.lite.*)
 * Output: 768-dim (or 256-dim) float32 vectors, L2-normalised
 *
 * Embedding prompts follow the EmbeddingGemma specification:
 *  - Document (ingestion): "task: search result | text: {chunk}"
 *  - Query (retrieval): "task: search result | query: {question}"
 *
 * NOTE: The tokeniser in [simplePlaceholderTokenize] is a placeholder.
 * Replace with the SentencePiece tokeniser shipped with EmbeddingGemma
 * (tokenizer.model file from the HuggingFace litert-community package).
 */
@Singleton
class EmbeddingEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val DEFAULT_EMBEDDING_DIM = 768
        private const val MAX_SEQUENCE_LENGTH = 512

        const val DOCUMENT_PROMPT_PREFIX = "task: search result | text: "
        const val QUERY_PROMPT_PREFIX = "task: search result | query: "

        const val MODEL_FILENAME = "embeddinggemma-300m.tflite"
    }

    private var interpreter: Interpreter? = null
    private var embeddingDim: Int = DEFAULT_EMBEDDING_DIM

    fun isLoaded(): Boolean = interpreter != null

    /**
     * Load the embedding model from [modelPath].
     * Must be called before any [embedDocument] / [embedQuery] calls.
     */
    fun load(modelPath: String) {
        interpreter?.close()

        val resolvedPath = resolveModelPath(modelPath)
            ?: throw IllegalArgumentException(
                "Cannot access embedding model file. " +
                "Place ${MODEL_FILENAME} in your Downloads folder and re-select in Settings."
            )

        val options = Interpreter.Options().apply {
            setNumThreads(4)
            // GPU delegate: uncomment after verifying model GPU compatibility
            // addDelegate(GpuDelegate())
        }
        interpreter = Interpreter(File(resolvedPath), options)

        // Infer embedding dimension from output tensor shape: [1, dim]
        val outputShape = interpreter!!.getOutputTensor(0).shape()
        embeddingDim = if (outputShape != null && outputShape.size >= 2) outputShape[1]
                       else DEFAULT_EMBEDDING_DIM
    }

    fun getEmbeddingDim(): Int = embeddingDim

    /** Embed a document chunk (for storage during ingestion). */
    suspend fun embedDocument(text: String): FloatArray? = withContext(Dispatchers.Default) {
        embed("$DOCUMENT_PROMPT_PREFIX$text")
    }

    /** Embed a user query (for retrieval at chat time). */
    suspend fun embedQuery(text: String): FloatArray? = withContext(Dispatchers.Default) {
        embed("$QUERY_PROMPT_PREFIX$text")
    }

    /**
     * Core embedding inference.
     *
     * TFLite/LiteRT Interpreter for EmbeddingGemma:
     *  - Input:  int32[1, MAX_SEQUENCE_LENGTH] — token IDs (padded with 0)
     *  - Output: float32[1, embeddingDim] — raw embedding
     *
     * Token IDs are produced by SentencePiece. The placeholder below uses
     * Unicode codepoints — replace with real tokeniser before production use.
     */
    private fun embed(promptedText: String): FloatArray? {
        val interp = interpreter ?: return null

        val tokenIds = simplePlaceholderTokenize(promptedText, MAX_SEQUENCE_LENGTH)

        // Input: int32[1, MAX_SEQUENCE_LENGTH]
        val inputBuffer = ByteBuffer
            .allocateDirect(MAX_SEQUENCE_LENGTH * Integer.BYTES)
            .order(ByteOrder.nativeOrder())
        tokenIds.forEach { inputBuffer.putInt(it) }
        inputBuffer.rewind()

        // Output: float32[1, embeddingDim]
        val outputBuffer = ByteBuffer
            .allocateDirect(embeddingDim * java.lang.Float.BYTES)
            .order(ByteOrder.nativeOrder())

        interp.run(inputBuffer, outputBuffer)
        outputBuffer.rewind()

        val vector = FloatArray(embeddingDim) { outputBuffer.float }
        return l2Normalise(vector)
    }

    /**
     * Placeholder tokeniser — Unicode codepoints, padded/truncated to [maxLen].
     *
     * REPLACE with the SentencePiece tokeniser from EmbeddingGemma's model bundle:
     * 1. Add sentencepiece-android JNI dependency
     * 2. Load tokenizer.model from the EmbeddingGemma HuggingFace package
     * 3. Call `sentencePiece.encode(text)` → List<Int> of token IDs
     */
    private fun simplePlaceholderTokenize(text: String, maxLen: Int): IntArray {
        val codePoints = text.codePoints().limit(maxLen.toLong()).toArray()
        return IntArray(maxLen) { i -> if (i < codePoints.size) codePoints[i] else 0 }
    }

    private fun l2Normalise(vector: FloatArray): FloatArray {
        val norm = sqrt(vector.fold(0f) { acc, v -> acc + v * v })
        if (norm > 0f) for (i in vector.indices) vector[i] /= norm
        return vector
    }

    /**
     * Resolve a path string to a real filesystem path that File() can open.
     * Handles SAF content URIs from the Settings file picker in addition to raw paths.
     */
    private fun resolveModelPath(pathOrUri: String): String? {
        if (pathOrUri.isBlank()) return null
        if (pathOrUri.startsWith("/")) return if (File(pathOrUri).exists()) pathOrUri else null
        if (!pathOrUri.startsWith("content://")) return null
        return try {
            val uri = Uri.parse(pathOrUri)
            val docId = DocumentsContract.getDocumentId(uri)
            when {
                docId.startsWith("raw:") -> {
                    val path = docId.removePrefix("raw:")
                    if (File(path).exists()) path else null
                }
                docId.contains(":") -> {
                    val (type, rel) = docId.split(":", limit = 2)
                    if (type.equals("primary", ignoreCase = true)) {
                        val path = "${Environment.getExternalStorageDirectory()}/$rel"
                        if (File(path).exists()) path else null
                    } else null
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
