package com.vaultmind.app.rag

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.concurrent.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device LLM inference engine wrapping LiteRT-LM for Gemma 4 E4B.
 *
 * SDK: com.google.ai.edge.litertlm:litertlm-android:0.10.0
 * Reference: github.com/google-ai-edge/gallery (Android/src/app)
 *
 * API shape (from Gallery's LlmChatModelHelper):
 *   Engine(EngineConfig) → engine.initialize()
 *   engine.createConversation(ConversationConfig(SamplerConfig))
 *   conversation.sendMessageAsync(Contents.of(listOf(Content.Text(prompt))), MessageCallback)
 *   MessageCallback.onMessage(message) → message.toString() = token text
 *   MessageCallback.onDone() → generation complete
 *   conversation.cancelProcess() → stop generation
 *   conversation.close() + engine.close() → release resources
 *
 * Each RAG query creates a fresh conversation. VaultMind manages its own conversation
 * history in PromptBuilder (the full context is injected into each prompt string),
 * so we don't accumulate context inside the Engine across turns.
 */
@Singleton
class LlmEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val MODEL_FILENAME = "gemma4-e4b-it-int4.litertlm"
        private const val MAX_TOKENS = 1024
        private const val DEFAULT_TOP_K = 40
        private const val DEFAULT_TOP_P = 0.95
    }

    private var engine: Engine? = null
    private var temperature: Double = 0.3

    // Keeps the SAF file descriptor open for as long as the Engine is alive.
    // EngineConfig needs a real path; /proc/self/fd/{fd} stays valid only while
    // this PFD is open, so we must not close it until unload().
    private var modelPfd: ParcelFileDescriptor? = null

    // The conversation active during the current generateStream() call.
    // Closed and replaced for each new query.
    private var activeConversation: Conversation? = null

    fun isLoaded(): Boolean = engine != null

    /**
     * Load the Gemma 4 E4B model from [modelPath].
     *
     * Blocking (~10-15 s on S23 Ultra). Call from a background coroutine with
     * a loading screen visible. Accepts either a real file path or a SAF content URI.
     *
     * @param modelPath  Real filesystem path (e.g. /storage/.../model.task) or
     *                   SAF content URI string (resolved internally).
     * @param temperature Sampling temperature (0.0–1.0). Default 0.3 for factual RAG.
     */
    suspend fun load(modelPath: String, temperature: Float = 0.3f) = withContext(Dispatchers.IO) {
        unload()

        this@LlmEngine.temperature = temperature.toDouble()

        // For SAF content URIs, open a ParcelFileDescriptor and keep it alive
        // for the lifetime of the Engine. /proc/self/fd/{fd} gives LiteRT-LM a
        // valid path as long as the descriptor stays open.
        val actualPath = if (modelPath.startsWith("content://")) {
            val uri = Uri.parse(modelPath)
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalArgumentException(
                    "Cannot open model file. Re-select it in Settings."
                )
            modelPfd = pfd   // kept open until unload()
            "/proc/self/fd/${pfd.fd}"
        } else {
            if (!java.io.File(modelPath).exists()) throw IllegalArgumentException(
                "Model file not found. Re-select it in Settings."
            )
            modelPath
        }

        val engineConfig = EngineConfig(
            modelPath = actualPath,
            backend = Backend.GPU(),
            maxNumTokens = MAX_TOKENS
        )
        val newEngine = Engine(engineConfig)
        newEngine.initialize()
        engine = newEngine
    }

    /**
     * Stream a response token by token for the given [prompt].
     *
     * Each call starts a fresh conversation so that the Engine does not accumulate
     * context — VaultMind's PromptBuilder already embeds full history and retrieved
     * chunks in every prompt.
     *
     * Thinking content (from Gemma 4's reasoning step) arrives via
     * [Message.channels]["thought"] and is not emitted into this flow.
     * The main response text comes from [Message.toString()].
     *
     * The flow completes when generation finishes or is cancelled.
     */
    fun generateStream(prompt: String): Flow<String> = callbackFlow {
        val eng = engine ?: run {
            trySend("[Model not loaded]")
            close()
            return@callbackFlow
        }

        // Fresh conversation per query (full context is in the prompt)
        activeConversation?.close()
        val conv = eng.createConversation(
            ConversationConfig(
                samplerConfig = SamplerConfig(
                    topK = DEFAULT_TOP_K,
                    topP = DEFAULT_TOP_P,
                    temperature = temperature
                )
            )
        )
        activeConversation = conv

        conv.sendMessageAsync(
            Contents.of(listOf(Content.Text(prompt))),
            object : MessageCallback {
                override fun onMessage(message: Message) {
                    trySend(message.toString())
                }

                override fun onDone() {
                    close()
                }

                override fun onError(throwable: Throwable) {
                    if (throwable is CancellationException) {
                        close()
                    } else {
                        close(throwable)
                    }
                }
            }
        )

        awaitClose {
            conv.cancelProcess()
        }
    }.flowOn(Dispatchers.IO)

    /** Unload the model and free GPU/RAM. Call on app lock or background. */
    fun unload() {
        try { activeConversation?.close() } catch (_: Exception) {}
        activeConversation = null
        try { engine?.close() } catch (_: Exception) {}
        engine = null
        try { modelPfd?.close() } catch (_: Exception) {}
        modelPfd = null
    }

    /** Default path where the model file should be placed (in app's files dir). */
    fun getDefaultModelPath(): String =
        "${context.filesDir.absolutePath}/$MODEL_FILENAME"

    /** Check if the model file exists at the default path. */
    fun isModelFilePresent(): Boolean =
        java.io.File(getDefaultModelPath()).exists()

    /**
     * Resolve a path string to a real filesystem path the Engine can open.
     *
     * Handles:
     *  - Real file paths (starting with /) — returned as-is after existence check
     *  - SAF content URIs — resolved via DocumentsContract:
     *    - "raw:/storage/..." → strips prefix
     *    - "primary:Download/..." → prepends external storage root
     *    - "msf:<id>" or plain id → resolved via MediaStore
     *
     * Returns null if the path cannot be resolved to an accessible file.
     */
    private fun resolveModelPath(pathOrUri: String): String? {
        if (pathOrUri.isBlank()) return null

        if (pathOrUri.startsWith("/")) {
            return if (java.io.File(pathOrUri).exists()) pathOrUri else null
        }

        if (!pathOrUri.startsWith("content://")) return null

        val uri = Uri.parse(pathOrUri)

        // Try document ID patterns first
        val docResolved = try {
            val docId = DocumentsContract.getDocumentId(uri)
            when {
                docId.startsWith("raw:") -> {
                    val path = docId.removePrefix("raw:")
                    if (java.io.File(path).exists()) path else null
                }
                docId.startsWith("msf:") -> {
                    val id = docId.removePrefix("msf:").toLongOrNull() ?: return null
                    resolveMediaStoreId(id)
                }
                docId.contains(":") -> {
                    val (type, rel) = docId.split(":", limit = 2)
                    if (type.equals("primary", ignoreCase = true)) {
                        val path = "${Environment.getExternalStorageDirectory()}/$rel"
                        if (java.io.File(path).exists()) path else null
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

        // Universal fallback: resolve via file descriptor symlink in /proc/self/fd
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val resolved = java.io.File("/proc/self/fd/${pfd.fd}").canonicalPath
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
            arrayOf(android.provider.MediaStore.MediaColumns.DATA),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val path = cursor.getString(0)
                if (!path.isNullOrBlank() && java.io.File(path).exists()) path else null
            } else null
        }
    }
}
