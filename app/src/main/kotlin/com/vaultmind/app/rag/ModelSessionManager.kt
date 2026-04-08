package com.vaultmind.app.rag

import android.util.Log
import com.vaultmind.app.ingestion.EmbeddingEngine
import com.vaultmind.app.settings.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

sealed class ModelLoadState {
    data object NotLoaded : ModelLoadState()
    data object Loading : ModelLoadState()
    data object Ready : ModelLoadState()
    data class Error(val message: String) : ModelLoadState()
}

@Singleton
class ModelSessionManager @Inject constructor(
    private val llmEngine: LlmEngine,
    private val embeddingEngine: EmbeddingEngine,
    private val appPreferences: AppPreferences
) {
    private val loadMutex = Mutex()

    private val _state = MutableStateFlow<ModelLoadState>(ModelLoadState.NotLoaded)
    val state: StateFlow<ModelLoadState> = _state.asStateFlow()

    fun isLoaded(): Boolean = llmEngine.isLoaded() && embeddingEngine.isLoaded()

    suspend fun ensureLoaded(): Boolean = loadFromSettings()

    suspend fun loadFromSettings(): Boolean = loadMutex.withLock {
        if (isLoaded()) {
            _state.value = ModelLoadState.Ready
            return true
        }

        val settings = appPreferences.settings.first()
        if (settings.llmModelPath.isBlank() || settings.embeddingModelPath.isBlank()) {
            _state.value = ModelLoadState.Error(
                "Configure both model files in Settings first."
            )
            return false
        }

        _state.value = ModelLoadState.Loading

        return try {
            embeddingEngine.load(settings.embeddingModelPath)
            llmEngine.load(
                modelPath = settings.llmModelPath,
                temperature = settings.temperature,
                contextWindow = settings.contextWindow
            )
            _state.value = ModelLoadState.Ready
            true
        } catch (e: Throwable) {
            Log.e("VaultMind", "Model session load failed", e)
            unloadInternal()
            _state.value = ModelLoadState.Error(e.message ?: "Failed to load model")
            false
        }
    }

    suspend fun unload() = loadMutex.withLock {
        unloadInternal()
        _state.value = ModelLoadState.NotLoaded
    }

    private fun unloadInternal() {
        llmEngine.unload()
        embeddingEngine.close()
    }
}
