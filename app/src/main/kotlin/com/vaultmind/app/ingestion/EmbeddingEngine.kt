package com.vaultmind.app.ingestion

import android.content.Context
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
 * Model: litert-community/embeddinggemma-300m on HuggingFace (.tflite)
 * Tokenizer: SentencePiece (tokenizer.model bundled in assets)
 * Output: 768-dim float32 vectors, L2-normalised
 *
 * Embedding prompts follow the EmbeddingGemma specification:
 *  - Document (ingestion): "title: none | text: {chunk}"
 *  - Query (retrieval): "task: search result | query: {question}"
 */
@Singleton
class EmbeddingEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val DEFAULT_EMBEDDING_DIM = 768
        private const val MAX_SEQUENCE_LENGTH = 512

        const val DOCUMENT_PROMPT_PREFIX = "title: none | text: "
        const val QUERY_PROMPT_PREFIX = "task: search result | query: "

        const val MODEL_FILENAME = "embeddinggemma-300m.tflite"
    }

    private var interpreter: Interpreter? = null
    private var embeddingDim: Int = DEFAULT_EMBEDDING_DIM
    private var seqLength: Int = MAX_SEQUENCE_LENGTH
    private var tokenizer: SentencePieceTokenizer? = null

    fun isLoaded(): Boolean = interpreter != null

    /**
     * Load the embedding model from [modelPath] and the SentencePiece tokenizer from assets.
     * Must be called before any [embedDocument] / [embedQuery] calls.
     *
     * This is a suspend function because it performs heavy I/O (~150 MB model read)
     * and must NOT run on the main thread.
     */
    suspend fun load(modelPath: String) = withContext(Dispatchers.IO) {
        interpreter?.close()

        val options = Interpreter.Options().apply {
            setNumThreads(4)
        }

        // For SAF content URIs (the common case from the Settings file picker),
        // read the file bytes into a direct ByteBuffer and pass that to the
        // Interpreter. This avoids path-resolution failures and MappedByteBuffer
        // lifetime issues when the file descriptor is closed after mapping.
        interpreter = if (modelPath.startsWith("content://")) {
            val uri = android.net.Uri.parse(modelPath)
            val stream = context.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException(
                    "Cannot open model file. Re-select it in Settings."
                )
            val bytes = stream.use { it.readBytes() }
            val buffer = ByteBuffer.allocateDirect(bytes.size)
            buffer.put(bytes)
            buffer.rewind()
            Interpreter(buffer, options)
        } else {
            val file = File(modelPath)
            if (!file.exists()) throw IllegalArgumentException(
                "Model file not found: $modelPath"
            )
            Interpreter(file, options)
        }

        // Infer embedding dimension from output tensor shape: [1, dim]
        val outputShape = interpreter!!.getOutputTensor(0).shape()
        embeddingDim = if (outputShape != null && outputShape.size >= 2) outputShape[1]
                       else DEFAULT_EMBEDDING_DIM

        // Infer sequence length from input tensor shape: [1, seqLen]
        val inputShape = interpreter!!.getInputTensor(0).shape()
        if (inputShape != null && inputShape.size >= 2) seqLength = inputShape[1]

        // Load SentencePiece tokenizer from bundled asset
        tokenizer = try {
            SentencePieceTokenizer.load(context.assets.open("tokenizer.model"))
        } catch (_: Exception) {
            null  // tokenizer.model missing — embed() falls back to codepoints
        }
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
     * LiteRT Interpreter for EmbeddingGemma:
     *  - Input:  int32[1, seqLength] — SentencePiece token IDs, zero-padded
     *  - Output: float32[1, embeddingDim] — raw embedding
     */
    private fun embed(promptedText: String): FloatArray? {
        val interp = interpreter ?: return null

        val tokenIds = tokenize(promptedText, seqLength)

        val inputBuffer = ByteBuffer
            .allocateDirect(seqLength * Integer.BYTES)
            .order(ByteOrder.nativeOrder())
        tokenIds.forEach { inputBuffer.putInt(it) }
        inputBuffer.rewind()

        val outputBuffer = ByteBuffer
            .allocateDirect(embeddingDim * java.lang.Float.BYTES)
            .order(ByteOrder.nativeOrder())

        interp.run(inputBuffer, outputBuffer)
        outputBuffer.rewind()

        val vector = FloatArray(embeddingDim) { outputBuffer.float }
        return l2Normalise(vector)
    }

    /**
     * Tokenize [text] to an [IntArray] of length [maxLen], zero-padded.
     * Uses the SentencePiece tokenizer loaded from assets/tokenizer.model.
     */
    private fun tokenize(text: String, maxLen: Int): IntArray {
        tokenizer?.let { tok ->
            val ids = tok.encode(text)
            return IntArray(maxLen) { i -> if (i < ids.size) ids[i] else 0 }
        }
        // Fallback: Unicode codepoints (poor quality — ensure tokenizer.model is in assets)
        val codePoints = text.codePoints().limit(maxLen.toLong()).toArray()
        return IntArray(maxLen) { i -> if (i < codePoints.size) codePoints[i] else 0 }
    }

    private fun l2Normalise(vector: FloatArray): FloatArray {
        val norm = sqrt(vector.fold(0f) { acc, v -> acc + v * v })
        if (norm > 0f) for (i in vector.indices) vector[i] /= norm
        return vector
    }

    fun close() {
        tokenizer = null
        interpreter?.close()
        interpreter = null
    }
}
