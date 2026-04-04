package com.vaultmind.app.crypto

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AES-256-GCM encrypt/decrypt for wrapping the master secret and
 * for the PC-imported .rvault package decryption.
 *
 * Note: The Keystore-backed master *encryption key* (KEK) is managed by [KeystoreManager].
 * This class handles the actual cipher operations using any provided key.
 */
@Singleton
class CryptoManager @Inject constructor() {

    companion object {
        private const val AES_GCM = "AES/GCM/NoPadding"
        const val GCM_IV_LENGTH = 12   // 96-bit nonce (GCM recommended)
        const val GCM_TAG_LENGTH = 128  // 128-bit authentication tag
    }

    /**
     * Encrypt [plaintext] with [keyBytes] (32 bytes = AES-256).
     *
     * @return iv (12 bytes) + ciphertext + tag (together as a single ByteArray).
     */
    fun encrypt(plaintext: ByteArray, keyBytes: ByteArray): ByteArray {
        val key = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        // Return iv || ciphertext (GCM tag is appended to ciphertext by JCE)
        return iv + ciphertext
    }

    /**
     * Decrypt [ivAndCiphertext] (produced by [encrypt]) with [keyBytes].
     *
     * @throws javax.crypto.AEADBadTagException if authentication fails (wrong key or tampered data).
     */
    fun decrypt(ivAndCiphertext: ByteArray, keyBytes: ByteArray): ByteArray {
        require(ivAndCiphertext.size > GCM_IV_LENGTH) { "Ciphertext too short" }
        val iv = ivAndCiphertext.copyOf(GCM_IV_LENGTH)
        val ciphertext = ivAndCiphertext.copyOfRange(GCM_IV_LENGTH, ivAndCiphertext.size)

        val key = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        return cipher.doFinal(ciphertext)
    }

    /**
     * Encrypt [plaintext] using an already-initialised [Cipher] (from BiometricPrompt CryptoObject).
     * The Cipher must have been initialised with ENCRYPT_MODE.
     *
     * @return iv (12 bytes) + ciphertext.
     */
    fun encryptWithCipher(plaintext: ByteArray, cipher: Cipher): ByteArray {
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        return iv + ciphertext
    }

    /**
     * Create a decryption [Cipher] initialised with the given [keyBytes] and [iv].
     * Used to prepare a CryptoObject for BiometricPrompt.
     */
    fun createDecryptCipherForKeystore(keyBytes: ByteArray? = null, iv: ByteArray): Cipher {
        val cipher = Cipher.getInstance(AES_GCM)
        if (keyBytes != null) {
            val key = SecretKeySpec(keyBytes, "AES")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        }
        return cipher
    }
}
