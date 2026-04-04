package com.vaultmind.app.ingestion

import android.content.Context
import android.net.Uri
import com.vaultmind.app.vault.VaultRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.spec.KeySpec
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject

/**
 * .rvault package format (produced by vault_builder.py):
 *
 * [32 bytes: Argon2id salt]   — actually PBKDF2 salt (Argon2id requires JNI)
 * [12 bytes: AES-GCM nonce]
 * [N bytes:  AES-GCM ciphertext of JSON payload]
 * [16 bytes: AES-GCM auth tag — appended to ciphertext by JCE]
 *
 * NOTE: The Python script uses Argon2id. Android does not have Argon2 in the standard
 * JDK, so this implementation uses PBKDF2-HMAC-SHA256 (300000 iterations) as an
 * alternative. For full Argon2id support, add a JNI/Bouncy Castle dependency.
 */
class PackageImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vaultRepository: VaultRepository
) {
    companion object {
        private const val SALT_SIZE = 32
        private const val NONCE_SIZE = 12
        private const val GCM_TAG_BITS = 128
        // PBKDF2 params — deliberately slow for offline brute-force resistance
        private const val PBKDF2_ITERATIONS = 300_000
        private const val KEY_LENGTH_BITS = 256
    }

    @Serializable
    data class VaultPackage(
        val version: Int,
        val embedding_model: String,
        val embedding_dim: Int,
        val chunk_size: Int,
        val chunk_overlap: Int,
        val created_at: String,
        val chunks: List<ChunkPackage>
    )

    @Serializable
    data class ChunkPackage(
        val index: Int,
        val content: String,
        val token_count: Int,
        val vector: List<Float>
    )

    /**
     * Import a .rvault file into [vaultId].
     *
     * @param fileUri  SAF content URI of the .rvault file.
     * @param password Password used when building the package on the PC.
     * @param vaultId  Target vault UUID.
     * @param onProgress Progress callback.
     */
    suspend fun importPackage(
        fileUri: Uri,
        password: CharArray,
        vaultId: String,
        onProgress: (IngestionProgress) -> Unit = {}
    ): IngestionResult = withContext(Dispatchers.IO) {
        try {
            onProgress(IngestionProgress(0, 0, "Reading package…"))

            // Read the encrypted file
            val fileBytes = context.contentResolver.openInputStream(fileUri)?.use {
                it.readBytes()
            } ?: return@withContext IngestionResult.Error("Could not open file")

            if (fileBytes.size < SALT_SIZE + NONCE_SIZE + GCM_TAG_BITS / 8) {
                return@withContext IngestionResult.Error("File too small — not a valid .rvault package")
            }

            onProgress(IngestionProgress(0, 0, "Decrypting…"))

            // Parse file structure: salt || nonce || ciphertext+tag
            val salt = fileBytes.copyOf(SALT_SIZE)
            val nonce = fileBytes.copyOfRange(SALT_SIZE, SALT_SIZE + NONCE_SIZE)
            val ciphertextAndTag = fileBytes.copyOfRange(SALT_SIZE + NONCE_SIZE, fileBytes.size)

            // Derive key from password using PBKDF2-HMAC-SHA256
            val keyBytes = deriveKeyPbkdf2(password, salt)

            // Wipe password from memory immediately
            password.fill('\u0000')

            // Decrypt with AES-256-GCM
            val plaintext = try {
                val key = SecretKeySpec(keyBytes, "AES")
                keyBytes.fill(0)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
                cipher.doFinal(ciphertextAndTag)
            } catch (e: Exception) {
                return@withContext IngestionResult.Error(
                    "Decryption failed — check your password. (${e.message})"
                )
            }

            // Parse JSON payload
            val pkg = try {
                Json { ignoreUnknownKeys = true }.decodeFromString<VaultPackage>(
                    String(plaintext, Charsets.UTF_8)
                )
            } catch (e: Exception) {
                return@withContext IngestionResult.Error("Invalid package format: ${e.message}")
            }

            if (pkg.chunks.isEmpty()) {
                return@withContext IngestionResult.Error("Package contains no chunks")
            }

            onProgress(IngestionProgress(0, pkg.chunks.size, "Importing chunks…"))

            // Store chunks and vectors into the vault DB
            val vaultDb = vaultRepository.openVaultDb(vaultId)

            // Validate embedding dimension matches vault's configured dimension
            val vaultDim = vaultDb.getVaultInfo("embedding_dim")?.toIntOrNull()
            if (vaultDim != null && pkg.embedding_dim != vaultDim) {
                return@withContext IngestionResult.Error(
                    "Package embedding dimension (${pkg.embedding_dim}D) does not match vault (${vaultDim}D). Import rejected."
                )
            }

            vaultDb.beginBatch()
            try {
                pkg.chunks.forEachIndexed { i, chunk ->
                    onProgress(IngestionProgress(i + 1, pkg.chunks.size, "Importing chunks…"))
                    val vector = chunk.vector.toFloatArray()
                    vaultDb.insertChunkWithEmbedding(
                        content = chunk.content,
                        chunkIndex = chunk.index,
                        tokenCount = chunk.token_count,
                        vector = vector
                    )
                    vector.fill(0f)
                }
                vaultDb.commitBatch()
            } finally {
                vaultDb.endBatch()
            }

            val totalChunks = vaultDb.getChunkCount()
            vaultRepository.updateChunkCount(vaultId, totalChunks)

            IngestionResult.Success(pkg.chunks.size)
        } catch (e: Exception) {
            IngestionResult.Error("Import failed: ${e.message}")
        }
    }

    private fun deriveKeyPbkdf2(password: CharArray, salt: ByteArray): ByteArray {
        val spec: KeySpec = PBEKeySpec(password, salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }
}
