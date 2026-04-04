package com.vaultmind.app.rag

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device LLM inference engine wrapping LiteRT-LM for Gemma 4 E4B.
 *
 * LiteRT-LM SDK: com.google.ai.edge.litert:litert-lm
 * Docs: https://ai.google.dev/edge/litert-lm
 * Reference implementation: github.com/google-ai-edge/gallery
 *
 * The Gemma 4 E4B model file (~4-5 GB) is loaded from the app's files directory.
 * Loading takes ~10-15 seconds — show a loading screen while this runs.
 * The engine is kept as a singleton and unloaded on app lock/background.
 *
 * NOTE: Import paths below use the LiteRT-LM Kotlin API.
 * If the API surface differs in the version you're using, check:
 *   - The Kotlin API reference at ai.google.dev/edge/litert-lm
 *   - The Gallery app source at github.com/google-ai-edge/gallery
 */
@Singleton
class LlmEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val MODEL_FILENAME = "gemma4-e4b-it-int4.task"
        private const val MAX_TOKENS = 1024
        private const val TEMPERATURE = 0.3f
        private const val TOP_K = 40
        private const val TOP_P = 0.95f
    }

    // LiteRT-LM inference object — null when not loaded
    // Type: com.google.ai.edge.litert.lm.LlmInference
    private var llmInference: Any? = null

    /** True if the model is loaded and ready for inference. */
    fun isLoaded(): Boolean = llmInference != null

    /**
     * Load the Gemma 4 E4B model from [modelPath].
     *
     * This is a blocking operation (~10-15 seconds on S23 Ultra).
     * Call from a background coroutine with a loading screen visible.
     *
     * LiteRT-LM API usage:
     * ```kotlin
     * import com.google.ai.edge.litert.lm.LlmInference
     * import com.google.ai.edge.litert.lm.LlmInferenceOptions
     *
     * val options = LlmInferenceOptions.builder()
     *     .setModelPath(modelPath)
     *     .setMaxTokens(MAX_TOKENS)
     *     .setTemperature(TEMPERATURE)
     *     .setTopK(TOP_K)
     *     .setRandomSeed(42)
     *     .build()
     * llmInference = LlmInference.createFromOptions(context, options)
     * ```
     *
     * Uncomment and adjust the imports once the dependency is resolved.
     */
    suspend fun load(modelPath: String) = withContext(Dispatchers.IO) {
        unload()

        // TODO: Uncomment once LiteRT-LM dependency is added and imports resolved:
        //
        // val options = LlmInferenceOptions.builder()
        //     .setModelPath(modelPath)
        //     .setMaxTokens(MAX_TOKENS)
        //     .setTemperature(TEMPERATURE)
        //     .setTopK(TOP_K)
        //     .setRandomSeed(42)
        //     .build()
        // llmInference = LlmInference.createFromOptions(context, options)

        // Placeholder: mark as loaded so the rest of the app flow works during development
        llmInference = "placeholder_loaded"
    }

    /**
     * Stream a response token by token.
     *
     * Returns a [Flow<String>] — each emission is one or more new tokens.
     * The flow completes when generation finishes.
     *
     * LiteRT-LM streaming API:
     * ```kotlin
     * callbackFlow {
     *     (llmInference as LlmInference).generateResponseAsync(prompt) { partial, done ->
     *         trySend(partial)
     *         if (done) close()
     *     }
     *     awaitClose { }
     * }.flowOn(Dispatchers.IO)
     * ```
     */
    fun generateStream(prompt: String): Flow<String> = callbackFlow {
        val inference = llmInference
        if (inference == null) {
            trySend("[Model not loaded]")
            close()
            return@callbackFlow
        }

        // TODO: Replace this placeholder with the real LiteRT-LM streaming call:
        //
        // (inference as LlmInference).generateResponseAsync(prompt) { partial, done ->
        //     trySend(partial)
        //     if (done) close()
        // }

        // Placeholder streaming for development/UI testing
        val demoResponse = "This is a placeholder response. " +
            "Load the Gemma 4 E4B model to get real answers. " +
            "The model should be placed at: ${getDefaultModelPath()}"
        demoResponse.split(" ").forEach { word ->
            trySend("$word ")
        }
        close()

        awaitClose { }
    }.flowOn(Dispatchers.IO)

    /**
     * Synchronous generation — use only for short outputs or testing.
     * Prefer [generateStream] for chat responses.
     */
    suspend fun generate(prompt: String): String = withContext(Dispatchers.IO) {
        val inference = llmInference ?: return@withContext "[Model not loaded]"

        // TODO: Replace with LiteRT-LM synchronous call:
        // return@withContext (inference as LlmInference).generateResponse(prompt)

        "Placeholder response — model not yet integrated."
    }

    /** Unload the model and free GPU/RAM. Called on app lock or background. */
    fun unload() {
        // TODO: Uncomment once LiteRT-LM is integrated:
        // (llmInference as? LlmInference)?.close()
        llmInference = null
    }

    /** Default path where the model file should be placed (in app's files dir). */
    fun getDefaultModelPath(): String =
        "${context.filesDir.absolutePath}/$MODEL_FILENAME"

    /** Check if the model file exists at the default path. */
    fun isModelFilePresent(): Boolean =
        java.io.File(getDefaultModelPath()).exists()
}
