package com.vaultmind.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.vaultmind.app.auth.AuthViewModel
import com.vaultmind.app.navigation.VaultMindNavGraph
import com.vaultmind.app.settings.AppPreferences
import com.vaultmind.app.settings.AutoLockDelay
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Single activity — all navigation is handled by Compose NavHost.
 *
 * Security measures applied here:
 *  - FLAG_SECURE: prevents screenshots and screen recording of vault content
 *  - Auto-lock on onStop (background): wipes in-memory keys
 *  - Uses FragmentActivity (required by BiometricPrompt)
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    private val authViewModel: AuthViewModel by viewModels()

    @Inject
    lateinit var appPreferences: AppPreferences

    private var autoLockJob: kotlinx.coroutines.Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // SECURITY: Prevent screenshots, recent apps previews, and screen recording
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        enableEdgeToEdge()

        setContent {
            VaultMindTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    VaultMindNavGraph(
                        activity = this@MainActivity,
                        authViewModel = authViewModel
                    )
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Schedule auto-lock based on user setting
        scheduleAutoLock()
    }

    override fun onStart() {
        super.onStart()
        // Cancel any pending auto-lock if app comes back to foreground
        autoLockJob?.cancel()
        autoLockJob = null
    }

    private fun scheduleAutoLock() {
        autoLockJob?.cancel()
        autoLockJob = lifecycleScope.launch {
            val settings = appPreferences.settings.first()
            val delayMs = when (settings.autoLock) {
                AutoLockDelay.IMMEDIATE -> 0L
                AutoLockDelay.THIRTY_SECONDS -> 30_000L
                AutoLockDelay.ONE_MINUTE -> 60_000L
            }
            if (delayMs > 0) delay(delayMs)
            authViewModel.lock()
        }
    }
}

/**
 * VaultMind Material 3 dark theme.
 *
 * Deep indigo/violet palette for a secure, premium feel.
 * Uses pure dark background — easier on AMOLED screens (S23 Ultra has AMOLED).
 */
@androidx.compose.runtime.Composable
fun VaultMindTheme(content: @androidx.compose.runtime.Composable () -> Unit) {
    val colorScheme = darkColorScheme(
        primary = Color(0xFF7B68D4),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFF4A3F8F),
        onPrimaryContainer = Color(0xFFE0DCFF),
        secondary = Color(0xFF9F8FE8),
        onSecondary = Color(0xFF1A1040),
        background = Color(0xFF0F0E1A),
        onBackground = Color(0xFFE8E6F0),
        surface = Color(0xFF1A1828),
        onSurface = Color(0xFFE8E6F0),
        surfaceVariant = Color(0xFF252238),
        onSurfaceVariant = Color(0xFFC5C0DC),
        error = Color(0xFFCF6679),
        onError = Color(0xFF2D0011)
    )

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
