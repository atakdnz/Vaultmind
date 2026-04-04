package com.vaultmind.app.vault

/**
 * Vault data model — one vault = one independently-encrypted knowledge base.
 *
 * Stored in the encrypted master.db (SQLCipher).
 */
data class Vault(
    val id: String,           // UUID, used as salt for HKDF vault key derivation
    val name: String,         // User-chosen name (e.g. "Journal", "Work Notes")
    val createdAt: Long,      // Unix timestamp (ms)
    val chunkCount: Int,      // Number of text chunks stored
    val embeddingDim: Int,    // 768 (full) or 256 (fast) — set at creation time
    val sourceMethod: String, // "on_device" or "pc_import"
    val sizeBytes: Long = 0   // SQLCipher DB file size (for display)
)

/** Vault creation request. */
data class CreateVaultRequest(
    val name: String,
    val embeddingDim: Int = 768
)
