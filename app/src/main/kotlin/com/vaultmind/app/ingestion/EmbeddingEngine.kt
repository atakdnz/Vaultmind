package com.vaultmind.app.ingestion

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
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
 * Tokenizer: google/embeddinggemma-300m tokenizer.json (HuggingFace format)
 *            → bundled in app/src/main/assets/tokenizer.json
 *            → loaded via DJL HuggingFaceTokenizer (ai.djl.android:tokenizer-native)
 * Output: 768-dim float32 vectors, L2-normalised
 *
 * Embedding prompts follow the EmbeddingGemma specification:
 *  - Document (ingestion): "task: search result | text: {chunk}"
 *  - Query (retrieval): "task: search result | query: {question}"
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
    private var seqLength: Int = MAX_SEQUENCE_LENGTH
    private var tokenizer: HuggingFaceTokenizer? = null

    fun isLoaded(): Boolean = interpreter != null

    /**
     * Load the embedding model from [modelPath].
     * Must be called before any [embedDocument] / [embedQuery] calls.
     */
    fun load(modelPath: String) {
        interpreter?.close()

        val options = Interpreter.Options().apply {
            setNumThreads(4)
        }

        // For SAF content URIs (the common case from the Settings file picker),
        // read the file bytes into a direct ByteBuffer and pass that to the
        // Interpreter. This avoids both path-resolution failures and the
        // MappedByteBuffer lifetime issues that occur when the file descriptor
        // is closed after mapping.
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
            // Raw file path (e.g. from getDefaultModelPath())
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

        // Load HuggingFace tokenizer from bundled asset (tokenizer.json)
        tokenizer?.close()
        tokenizer = try {
            HuggingFaceTokenizer.newInstance(
                context.assets.open("tokenizer.json"),
                emptyMap()
            )
        } catch (_: Exception) {
            null  // tokenizer.json missing — embed() falls back to placeholder
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
     * TFLite/LiteRT Interpreter for EmbeddingGemma:
     *  - Input:  int32[1, seqLength] — token IDs from HuggingFace tokenizer, zero-padded
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
     *
     * Uses the DJL HuggingFaceTokenizer loaded from assets/tokenizer.json.
     * Falls back to Unicode codepoints if the tokenizer is unavailable.
     */
    private fun tokenize(text: String, maxLen: Int): IntArray {
        tokenizer?.let { tok ->
            val ids = tok.encode(text).ids   // LongArray from DJL
            return IntArray(maxLen) { i -> if (i < ids.size) ids[i].toInt() else 0 }
        }
        // Fallback: Unicode codepoints (poor quality — ensure tokenizer.json is in assets)
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

        if (pathOrUri.startsWith("/")) {
            return if (File(pathOrUri).exists()) pathOrUri else null
        }

        if (!pathOrUri.startsWith("content://")) return null

        val uri = Uri.parse(pathOrUri)

        val docResolved = try {
            val docId = DocumentsContract.getDocumentId(uri)
            when {
                docId.startsWith("raw:") -> {
                    val path = docId.removePrefix("raw:")
                    if (File(path).exists()) path else null
                }
                docId.startsWith("msf:") -> {
                    val id = docId.removePrefix("msf:").toLongOrNull() ?: return null
                    resolveMediaStoreId(id)
                }
                docId.contains(":") -> {
                    val (type, rel) = docId.split(":", limit = 2)
                    if (type.equals("primary", ignoreCase = true)) {
                        val path = "${Environment.getExternalStorageDirectory()}/$rel"
                        if (File(path).exists()) path else null
                    } else null
                }
                docId.all { it.isDigit() } -> {
                    val id = docId.toLongOrNull() ?: return null
                    resolveMediaStoreId(id)
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
        if (docResolved != null) return docResolved

        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val resolved = File("/proc/self/fd/${pfd.fd}").canonicalPath
                if (resolved.startsWith("/proc")) null else resolved
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveMediaStoreId(id: Long): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val downloadUri = ContentUris.withAppendedId(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, id
        )
        return context.contentResolver.query(
            downloadUri,
            arrayOf(MediaStore.MediaColumns.DATA),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val path = cursor.getString(0)
                if (!path.isNullOrBlank() && File(path).exists()) path else null
            } else null
        }
    }

    fun close() {
        tokenizer?.close()
        tokenizer = null
        interpreter?.close()
        interpreter = null
    }
}
