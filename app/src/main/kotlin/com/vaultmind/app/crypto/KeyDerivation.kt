package com.vaultmind.app.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HKDF-SHA256 key derivation for per-vault SQLCipher keys.
 *
 * RFC 5869 HKDF — two-step: Extract then Expand.
 * Input key material (IKM) is the master secret (32 bytes, held in RAM after auth).
 * The salt and info differentiate each vault.
 */
object KeyDerivation {

    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val HASH_LEN = 32 // SHA-256 output = 32 bytes

    /**
     * Derive a [length]-byte key from [ikm] using HKDF-SHA256.
     *
     * @param ikm   Input key material (the master secret). Caller is responsible for wiping this.
     * @param salt  Should be the vault UUID bytes (or "master" for the master DB key).
     * @param info  Context string bytes, e.g. "vaultmind-vault-key".
     * @param length Desired output key length in bytes (≤ 255 * 32 = 8160, practically ≤ 64).
     */
    fun hkdf(
        ikm: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int = 32
    ): ByteArray {
        require(length > 0) { "Key length must be positive" }
        require(length <= 255 * HASH_LEN) { "Requested key length exceeds HKDF limit" }

        // Step 1: Extract — PRK = HMAC-SHA256(salt, IKM)
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(salt, HMAC_ALGORITHM))
        val prk = mac.doFinal(ikm)

        // Step 2: Expand — produce [length] bytes
        val n = (length + HASH_LEN - 1) / HASH_LEN  // number of blocks needed
        val okm = ByteArray(n * HASH_LEN)
        var t = ByteArray(0)  // T(0) = empty

        mac.init(SecretKeySpec(prk, HMAC_ALGORITHM))
        for (i in 1..n) {
            mac.update(t)
            mac.update(info)
            mac.update(i.toByte())
            t = mac.doFinal()
            t.copyInto(okm, (i - 1) * HASH_LEN)
        }

        prk.fill(0)
        return okm.copyOf(length).also { okm.fill(0) }
    }

    /**
     * Derive the SQLCipher passphrase bytes for a vault.
     *
     * The passphrase is a 32-byte key derived from the master secret using the vault UUID as salt.
     */
    fun deriveVaultKey(masterSecret: ByteArray, vaultId: String): ByteArray {
        return hkdf(
            ikm = masterSecret,
            salt = vaultId.toByteArray(Charsets.UTF_8),
            info = "vaultmind-vault-key-v1".toByteArray(Charsets.UTF_8),
            length = 32
        )
    }

    /**
     * Derive the SQLCipher passphrase bytes for the master metadata database.
     */
    fun deriveMasterDbKey(masterSecret: ByteArray): ByteArray {
        return hkdf(
            ikm = masterSecret,
            salt = "master".toByteArray(Charsets.UTF_8),
            info = "vaultmind-master-db-key-v1".toByteArray(Charsets.UTF_8),
            length = 32
        )
    }

    /**
     * Convert raw key bytes to the hex string that SQLCipher uses as its passphrase.
     * SQLCipher 4.x accepts raw key bytes directly when passed as "x'hex_string'".
     */
    fun toSqlCipherHexKey(keyBytes: ByteArray): String {
        return "x'" + keyBytes.joinToString("") { "%02x".format(it) } + "'"
    }
}
