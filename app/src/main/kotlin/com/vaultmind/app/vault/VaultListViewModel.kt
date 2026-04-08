package com.vaultmind.app.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vaultmind.app.rag.ModelLoadState
import com.vaultmind.app.rag.ModelSessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class VaultListUiState {
    data object Loading : VaultListUiState()
    data class Ready(val vaults: List<Vault>) : VaultListUiState()
    data class Error(val message: String) : VaultListUiState()
}

@HiltViewModel
class VaultListViewModel @Inject constructor(
    private val vaultRepository: VaultRepository,
    private val modelSessionManager: ModelSessionManager
) : ViewModel() {

    val vaults: StateFlow<List<Vault>> = vaultRepository.vaults
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _uiState = MutableStateFlow<VaultListUiState>(VaultListUiState.Loading)
    val uiState: StateFlow<VaultListUiState> = _uiState.asStateFlow()

    private val _showCreateDialog = MutableStateFlow(false)
    val showCreateDialog: StateFlow<Boolean> = _showCreateDialog.asStateFlow()

    private val _deleteTarget = MutableStateFlow<Vault?>(null)
    val deleteTarget: StateFlow<Vault?> = _deleteTarget.asStateFlow()

    val modelState: StateFlow<ModelLoadState> = modelSessionManager.state

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            try {
                vaultRepository.refreshVaultList()
                _uiState.value = VaultListUiState.Ready(vaultRepository.vaults.value)
            } catch (e: Exception) {
                _uiState.value = VaultListUiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun showCreateDialog() { _showCreateDialog.value = true }
    fun hideCreateDialog() { _showCreateDialog.value = false }

    fun createVault(name: String, embeddingDim: Int = 768) {
        viewModelScope.launch {
            try {
                vaultRepository.createVault(CreateVaultRequest(name.trim(), embeddingDim))
                _showCreateDialog.value = false
            } catch (e: Exception) {
                _uiState.value = VaultListUiState.Error("Failed to create vault: ${e.message}")
            }
        }
    }

    fun requestDelete(vault: Vault) { _deleteTarget.value = vault }
    fun cancelDelete() { _deleteTarget.value = null }

    fun confirmDelete() {
        val vault = _deleteTarget.value ?: return
        _deleteTarget.value = null
        viewModelScope.launch {
            try {
                vaultRepository.deleteVault(vault.id)
            } catch (e: Exception) {
                _uiState.value = VaultListUiState.Error("Failed to delete vault: ${e.message}")
            }
        }
    }

    fun renameVault(vaultId: String, newName: String) {
        viewModelScope.launch {
            vaultRepository.renameVault(vaultId, newName.trim())
        }
    }

    fun loadModels() {
        viewModelScope.launch {
            modelSessionManager.loadFromSettings()
        }
    }

    fun unloadModels() {
        viewModelScope.launch {
            modelSessionManager.unload()
        }
    }
}
