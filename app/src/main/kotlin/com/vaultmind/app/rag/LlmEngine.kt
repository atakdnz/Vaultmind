package com.vaultmind.app.rag

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.util.Log
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
     * Tries GPU backend first — if the GPU accelerator plugin isn't bundled
     * (libLiteRtGpuAccelerator.so) or OpenCL isn't available, falls back to CPU
     * (XNNPACK, still fast on Snapdragon 8 Gen 2).
     *
     * @param modelPath  Real filesystem path (e.g. /storage/.../model.task) or
     *                   SAF content URI string (resolved internally).
     * @param temperature Sampling temperature (0.0–1.0). Default 0.3 for factual RAG.
     * @param contextWindow Maximum context size in tokens. Defaults to 3072.
     */
    suspend fun load(modelPath: String, temperature: Float = 0.3f, contextWindow: Int = 3072) = withContext(Dispatchers.IO) {
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
            // Resolve the proc fd symlink to the real path so LiteRT-LM
            // can detect the .litertlm extension for format detection.
            // LiteRT-LM's native code can't access SAF files via /proc/self/fd
            // (scoped storage blocks path resolution). Copy to internal storage
            // on first use; subsequent loads use the cached copy instantly.
            val cached = java.io.File(context.filesDir, MODEL_FILENAME)
            if (!cached.exists() || cached.length() == 0L) {
                Log.d("VaultMind", "LLM: copying model to internal storage…")
                java.io.FileInputStream(pfd.fileDescriptor).use { input ->
                    cached.outputStream().use { output ->
                        input.copyTo(output, bufferSize = 8 * 1024 * 1024)
                    }
                }
                Log.d("VaultMind", "LLM: copy done, size=${cached.length()}")
            }
            pfd.close()
            modelPfd = null  // no longer needed after copy
            cached.absolutePath
        } else {
            if (!java.io.File(modelPath).exists()) throw IllegalArgumentException(
                "Model file not found. Re-select it in Settings."
            )
            modelPath
        }

        // Try GPU first — falls back to CPU if GPU accelerator plugin is missing
        // or OpenCL is unsupported on this device.
        try {
            val gpuConfig = EngineConfig(
                modelPath = actualPath,
                backend = Backend.GPU(),
                maxNumTokens = contextWindow
            )
            val gpuEngine = Engine(gpuConfig)
            gpuEngine.initialize()
            engine = gpuEngine
            Log.i("VaultMind", "LLM: loaded with GPU backend")
            return@withContext
        } catch (e: Exception) {
            Log.w("VaultMind", "LLM: GPU backend failed (${e.message}), falling back to CPU")
        }

        // CPU fallback — XNNPACK, multi-core, reliable on all devices
        val cpuConfig = EngineConfig(
            modelPath = actualPath,
            backend = Backend.CPU(),
            maxNumTokens = contextWindow
        )
        val cpuEngine = Engine(cpuConfig)
        cpuEngine.initialize()
        engine = cpuEngine
        Log.i("VaultMind", "LLM: loaded with CPU backend")
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

}
