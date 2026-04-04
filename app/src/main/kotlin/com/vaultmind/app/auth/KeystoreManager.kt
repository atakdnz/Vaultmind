package com.vaultmind.app.auth

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the hardware-backed master key in Android Keystore and the wrapped master secret.
 *
 * Architecture:
 *   - Android Keystore holds a non-exportable AES-256 key (the KEK — Key Encryption Key)
 *   - A random 32-byte "master secret" is generated at first launch
 *   - The master secret is encrypted with the KEK and stored in SharedPreferences
 *   - The KEK requires user authentication (biometric/PIN) before it can be used
 *   - After auth, the master secret is decrypted and used for HKDF vault key derivation
 *
 * The master secret lives in RAM only while the app is unlocked and is wiped on lock/background.
 */
@Singleton
class KeystoreManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val MASTER_KEY_ALIAS = "vaultmind_kek_v1"
        private const val PREFS_NAME = "vaultmind_keystore_prefs"
        private const val PREF_ENCRYPTED_SECRET = "enc_master_secret"
        private const val PREF_IV = "master_secret_iv"
        // Auth timeout: 30 seconds. App derives vault keys immediately after auth, so
        // this window is more than sufficient. Vault keys are wiped on app lock.
        private const val AUTH_TIMEOUT_SECONDS = 30
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** True if this is the first launch (no wrapped master secret stored yet). */
    fun isFirstLaunch(): Boolean = !prefs.contains(PREF_ENCRYPTED_SECRET)

    /** True if the Keystore KEK has been generated. */
    fun isKeystoreInitialized(): Boolean {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).also { it.load(null) }
        return ks.containsAlias(MASTER_KEY_ALIAS)
    }

    /**
     * Get the Keystore-backed KEK.
     * Generates it if it doesn't exist yet (first-launch path).
     * This key requires user auth to use (decrypt/encrypt).
     */
    fun getOrCreateKek(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).also { it.load(null) }
        return if (ks.containsAlias(MASTER_KEY_ALIAS)) {
            ks.getKey(MASTER_KEY_ALIAS, null) as SecretKey
        } else {
            generateKek()
        }
    }

    private fun generateKek(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER
        )
        val specBuilder = KeyGenParameterSpec.Builder(
            MASTER_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(
                AUTH_TIMEOUT_SECONDS,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
            )
            .setRandomizedEncryptionRequired(true)

        // Attempt to use StrongBox (hardware-isolated security chip, available on S23 Ultra).
        // Fall back to TEE if StrongBox is not available.
        try {
            specBuilder.setIsStrongBoxBacked(true)
            keyGenerator.init(specBuilder.build())
        } catch (e: Exception) {
            specBuilder.setIsStrongBoxBacked(false)
            keyGenerator.init(specBuilder.build())
        }

        return keyGenerator.generateKey()
    }

    /**
     * First-launch setup: generate the master secret and encrypt it with the Keystore KEK.
     *
     * This must be called after the user has authenticated for the first time,
     * because the KEK requires user auth before use.
     *
     * @return the plaintext master secret bytes (caller MUST wipe after use)
     */
    fun setupMasterSecret(): ByteArray {
        val masterSecret = ByteArray(32).also { SecureRandom().nextBytes(it) }

        val kek = getOrCreateKek()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, kek)
        val iv = cipher.iv
        val encryptedSecret = cipher.doFinal(masterSecret)

        prefs.edit()
            .putString(PREF_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
            .putString(PREF_ENCRYPTED_SECRET, Base64.encodeToString(encryptedSecret, Base64.NO_WRAP))
            .apply()

        return masterSecret
    }

    /**
     * Unlock path: decrypt and return the master secret.
     * Requires that the user has recently authenticated (within AUTH_TIMEOUT_SECONDS).
     *
     * @return the plaintext master secret bytes (caller MUST wipe after deriving vault keys)
     */
    fun decryptMasterSecret(): ByteArray {
        val ivB64 = prefs.getString(PREF_IV, null)
            ?: throw IllegalStateException("No master secret IV found — was setupMasterSecret() called?")
        val encB64 = prefs.getString(PREF_ENCRYPTED_SECRET, null)
            ?: throw IllegalStateException("No encrypted master secret found")

        val iv = Base64.decode(ivB64, Base64.NO_WRAP)
        val encryptedSecret = Base64.decode(encB64, Base64.NO_WRAP)

        val kek = getOrCreateKek()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, kek, GCMParameterSpec(128, iv))
        return cipher.doFinal(encryptedSecret)
    }

    /**
     * Wipe all stored key material. Called on vault data wipe or factory reset within the app.
     * Does NOT delete the Keystore key (which would require a separate deletion call).
     */
    fun clearStoredSecrets() {
        prefs.edit().clear().apply()
    }

    /** Delete the Keystore KEK. All vault data becomes permanently inaccessible. */
    fun deleteKeystoreKey() {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).also { it.load(null) }
        if (ks.containsAlias(MASTER_KEY_ALIAS)) {
            ks.deleteEntry(MASTER_KEY_ALIAS)
        }
    }
}
