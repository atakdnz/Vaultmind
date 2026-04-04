package com.vaultmind.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Hilt application class.
 *
 * This is intentionally minimal — all application-level setup is handled
 * by component lifecycle (e.g. SQLCipher is loaded lazily when first vault is opened).
 */
@HiltAndroidApp
class VaultMindApp : Application()
