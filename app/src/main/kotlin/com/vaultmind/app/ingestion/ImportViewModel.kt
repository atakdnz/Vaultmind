package com.vaultmind.app.ingestion

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vaultmind.app.settings.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class ImportUiState {
    data object Idle : ImportUiState()
    data class InProgress(val progress: IngestionProgress) : ImportUiState()
    data class Done(val chunksAdded: Int) : ImportUiState()
    data class Error(val message: String) : ImportUiState()
}

@HiltViewModel
class ImportViewModel @Inject constructor(
    private val onDeviceIngestion: OnDeviceIngestion,
    private val packageImporter: PackageImporter,
    private val embeddingEngine: EmbeddingEngine,
    private val appPreferences: AppPreferences
) : ViewModel() {

    private val _state = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    fun importTxt(fileUri: Uri, vaultId: String, chunkSize: Int = 128, chunkOverlap: Int = 20) {
        if (_state.value is ImportUiState.InProgress) return
        viewModelScope.launch {
            if (!ensureEmbeddingModelLoaded()) return@launch
            val result = onDeviceIngestion.ingest(
                fileUri, vaultId,
                chunkSize = chunkSize,
                chunkOverlap = chunkOverlap
            ) { progress ->
                _state.value = ImportUiState.InProgress(progress)
            }
            _state.value = when (result) {
                is IngestionResult.Success -> ImportUiState.Done(result.chunksAdded)
                is IngestionResult.Error -> ImportUiState.Error(result.message)
            }
        }
    }

    fun importRvault(fileUri: Uri, password: CharArray, vaultId: String) {
        if (_state.value is ImportUiState.InProgress) return
        viewModelScope.launch {
            val result = packageImporter.importPackage(fileUri, password, vaultId) { progress ->
                _state.value = ImportUiState.InProgress(progress)
            }
            _state.value = when (result) {
                is IngestionResult.Success -> ImportUiState.Done(result.chunksAdded)
                is IngestionResult.Error -> ImportUiState.Error(result.message)
            }
        }
    }

    fun reset() { _state.value = ImportUiState.Idle }

    /**
     * Ensure the embedding model is loaded before ingestion.
     * Returns true if ready, false (and sets error state) if the path is not configured or load fails.
     */
    private suspend fun ensureEmbeddingModelLoaded(): Boolean {
        if (embeddingEngine.isLoaded()) return true
        val path = appPreferences.settings.first().embeddingModelPath
        if (path.isBlank()) {
            _state.value = ImportUiState.Error(
                "Embedding model not configured. Go to Settings and locate the ${EmbeddingEngine.MODEL_FILENAME} file."
            )
            return false
        }
        return try {
            _state.value = ImportUiState.InProgress(IngestionProgress(0, 0, "Loading embedding model…"))
            embeddingEngine.load(path)
            true
        } catch (e: Exception) {
            _state.value = ImportUiState.Error("Failed to load embedding model: ${e.message}")
            false
        }
    }
}
