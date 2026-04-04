package com.vaultmind.app.ingestion

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val packageImporter: PackageImporter
) : ViewModel() {

    private val _state = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    fun importTxt(fileUri: Uri, vaultId: String) {
        if (_state.value is ImportUiState.InProgress) return
        viewModelScope.launch {
            val result = onDeviceIngestion.ingest(fileUri, vaultId) { progress ->
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
}
