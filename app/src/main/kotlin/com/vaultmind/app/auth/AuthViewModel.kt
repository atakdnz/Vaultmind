package com.vaultmind.app.auth

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vaultmind.app.crypto.SecureWipe
import com.vaultmind.app.ingestion.EmbeddingEngine
import com.vaultmind.app.rag.LlmEngine
import com.vaultmind.app.vault.VaultRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class AuthUiState {
    data object Idle : AuthUiState()
    data object Authenticating : AuthUiState()
    data object Success : AuthUiState()
    data class Error(val message: String) : AuthUiState()
    data object NotEnrolled : AuthUiState()
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authManager: AuthManager,
    private val keystoreManager: KeystoreManager,
    private val vaultRepository: VaultRepository,
    private val llmEngine: LlmEngine,
    private val embeddingEngine: EmbeddingEngine
) : ViewModel() {

    private val _state = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    /** True once auth succeeded in this session — gates all other screens. */
    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    init {
        checkAuthAvailability()
    }

    private fun checkAuthAvailability() {
        when (authManager.checkAvailability()) {
            AuthAvailability.NOT_ENROLLED, AuthAvailability.NO_HARDWARE -> {
                _state.value = AuthUiState.NotEnrolled
            }
            else -> { /* available */ }
        }
    }

    /**
     * Trigger the biometric/PIN prompt.
     *
     * On success:
     * - First launch: generate and wrap the master secret, open the master DB.
     * - Subsequent launches: decrypt the master secret, open the master DB.
     */
    fun authenticate(activity: FragmentActivity) {
        if (_state.value == AuthUiState.Authenticating) return
        _state.value = AuthUiState.Authenticating

        viewModelScope.launch {
            val title = "Unlock VaultMind"
            val subtitle = "Authenticate to access your vaults"

            when (val result = authManager.authenticate(activity, title, subtitle)) {
                AuthResult.Success -> {
                    try {
                        unlockVaults()
                        _isUnlocked.value = true
                        _state.value = AuthUiState.Success
                    } catch (e: Exception) {
                        _state.value = AuthUiState.Error("Failed to unlock: ${e.message}")
                    }
                }
                is AuthResult.Error -> {
                    val isCancelled = result.code == androidx.biometric.BiometricPrompt.ERROR_USER_CANCELED
                            || result.code == androidx.biometric.BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    if (!isCancelled) {
                        _state.value = AuthUiState.Error(result.message)
                    } else {
                        _state.value = AuthUiState.Idle
                    }
                }
                AuthResult.Failed -> {
                    // Handled by BiometricPrompt internally (retries, lockout).
                    _state.value = AuthUiState.Idle
                }
            }
        }
    }

    private suspend fun unlockVaults() {
        val masterSecret: ByteArray = if (keystoreManager.isFirstLaunch()) {
            keystoreManager.setupMasterSecret()
        } else {
            keystoreManager.decryptMasterSecret()
        }
        try {
            // VaultRepository opens the master DB and keeps a copy of the master secret
            // for on-demand vault key derivation. It wipes its copy on lock().
            vaultRepository.unlock(masterSecret)
        } finally {
            SecureWipe.wipe(masterSecret)
        }
    }

    /**
     * Lock the app — wipes all in-memory keys and unloads models.
     * Called on background/stop. Order matters: unload models before
     * wiping vault keys so any in-flight inference completes cleanly.
     */
    fun lock() {
        llmEngine.unload()
        embeddingEngine.close()
        vaultRepository.lock()
        _isUnlocked.value = false
        _state.value = AuthUiState.Idle
    }
}
