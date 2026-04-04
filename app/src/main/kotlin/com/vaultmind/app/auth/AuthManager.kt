package com.vaultmind.app.auth

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Result of an authentication attempt. */
sealed class AuthResult {
    data object Success : AuthResult()
    data class Error(val code: Int, val message: String) : AuthResult()
    data object Failed : AuthResult()
}

/** Reasons why authentication cannot proceed. */
enum class AuthAvailability {
    AVAILABLE,
    NO_HARDWARE,
    NOT_ENROLLED,  // User needs to set up biometric/PIN in device settings
    UNKNOWN_ERROR
}

/**
 * Wraps [BiometricPrompt] into a suspend-friendly API.
 *
 * We use BIOMETRIC_STRONG | DEVICE_CREDENTIAL so the user can fall back to their
 * PIN/pattern/password if biometric fails or is not enrolled, without any custom UI.
 */
@Singleton
class AuthManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun checkAvailability(): AuthAvailability {
        val biometricManager = BiometricManager.from(context)
        return when (biometricManager.canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)) {
            BiometricManager.BIOMETRIC_SUCCESS -> AuthAvailability.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> AuthAvailability.NO_HARDWARE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> AuthAvailability.NOT_ENROLLED
            else -> AuthAvailability.UNKNOWN_ERROR
        }
    }

    /**
     * Show the biometric/PIN prompt and suspend until the user completes (or fails) auth.
     *
     * Must be called from a coroutine launched in the Activity/Fragment scope.
     *
     * @param activity The current FragmentActivity (required by BiometricPrompt).
     * @param title Title shown in the auth dialog.
     * @param subtitle Subtitle shown in the auth dialog.
     */
    suspend fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String
    ): AuthResult = suspendCancellableCoroutine { continuation ->
        val executor = ContextCompat.getMainExecutor(activity)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (continuation.isActive) continuation.resume(AuthResult.Success)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (continuation.isActive) {
                    continuation.resume(AuthResult.Error(errorCode, errString.toString()))
                }
            }

            override fun onAuthenticationFailed() {
                // Biometric read but not recognised — prompt stays open, don't cancel coroutine.
                // BiometricPrompt handles retry counting and lockout automatically.
            }
        }

        val prompt = BiometricPrompt(activity, executor, callback)

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            // BIOMETRIC_STRONG | DEVICE_CREDENTIAL means the system automatically
            // shows PIN/pattern fallback. We cannot add a negative button in this mode.
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build()

        continuation.invokeOnCancellation { prompt.cancelAuthentication() }
        prompt.authenticate(promptInfo)
    }
}
