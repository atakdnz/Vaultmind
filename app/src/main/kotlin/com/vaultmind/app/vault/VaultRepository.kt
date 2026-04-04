package com.vaultmind.app.vault

import android.content.Context
import com.vaultmind.app.crypto.SecureWipe
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for vault lifecycle and in-memory session state.
 *
 * Holds the master secret in memory while the app is unlocked (needed to open vault DBs).
 * Wipes it on [lock].
 */
@Singleton
class VaultRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {

    // In-memory session state — wiped on lock
    private var masterSecret: ByteArray? = null
    private var masterDb: MasterDatabase? = null
    private val openVaultDbs = mutableMapOf<String, VaultDatabase>()

    private val _vaults = MutableStateFlow<List<Vault>>(emptyList())
    val vaults: StateFlow<List<Vault>> = _vaults.asStateFlow()

    /**
     * Full unlock sequence called after auth success.
     * Opens master DB and refreshes vault list.
     */
    suspend fun unlock(masterSecretBytes: ByteArray) = withContext(Dispatchers.IO) {
        masterSecret = masterSecretBytes.copyOf()
        masterDb?.close()
        masterDb = MasterDatabase(context, masterSecretBytes)
        refreshVaultList()
    }

    suspend fun refreshVaultList() = withContext(Dispatchers.IO) {
        val db = masterDb ?: return@withContext
        val vaultList = db.getAllVaults().map { vault ->
            val dbFile = VaultDatabase.getDatabasePath(context, vault.id)
            vault.copy(sizeBytes = if (dbFile.exists()) dbFile.length() else 0L)
        }
        _vaults.value = vaultList
    }

    /** Create a new vault, persist metadata, return the new Vault. */
    suspend fun createVault(request: CreateVaultRequest): Vault = withContext(Dispatchers.IO) {
        val db = requireMasterDb()
        val secret = requireMasterSecret()

        val vault = Vault(
            id = UUID.randomUUID().toString(),
            name = request.name,
            createdAt = System.currentTimeMillis(),
            chunkCount = 0,
            embeddingDim = request.embeddingDim,
            sourceMethod = "on_device"
        )
        db.insertVault(vault)

        // Create and immediately close the vault DB to ensure it's initialized
        val vaultDb = VaultDatabase(context, vault.id, secret)
        vaultDb.setVaultInfo("embedding_dim", request.embeddingDim.toString())
        vaultDb.setVaultInfo("embedding_model", "embeddinggemma-300m")
        vaultDb.setVaultInfo("chunk_size", "256")
        vaultDb.setVaultInfo("chunk_overlap", "40")
        vaultDb.close()

        refreshVaultList()
        vault
    }

    /** Delete a vault and its database file. Irreversible. */
    suspend fun deleteVault(vaultId: String) = withContext(Dispatchers.IO) {
        val db = requireMasterDb()

        // Close vault DB if open
        openVaultDbs[vaultId]?.close()
        openVaultDbs.remove(vaultId)

        // Delete database file
        val dbFile = VaultDatabase.getDatabasePath(context, vaultId)
        dbFile.delete()
        File(dbFile.absolutePath + "-shm").delete()
        File(dbFile.absolutePath + "-wal").delete()

        // Remove from master DB
        db.deleteVault(vaultId)
        refreshVaultList()
    }

    /** Rename a vault. */
    suspend fun renameVault(vaultId: String, newName: String) = withContext(Dispatchers.IO) {
        requireMasterDb().updateVaultName(vaultId, newName)
        refreshVaultList()
    }

    /**
     * Open (or get cached) a vault database.
     * The caller must not close this — managed by the repository.
     */
    fun openVaultDb(vaultId: String): VaultDatabase {
        openVaultDbs[vaultId]?.takeIf { it.isOpen() }?.let { return it }
        val secret = requireMasterSecret()
        val vaultDb = VaultDatabase(context, vaultId, secret)
        openVaultDbs[vaultId] = vaultDb
        return vaultDb
    }

    /** Update chunk count in master DB after ingestion. */
    suspend fun updateChunkCount(vaultId: String, count: Int) = withContext(Dispatchers.IO) {
        requireMasterDb().updateVaultChunkCount(vaultId, count)
        refreshVaultList()
    }

    /**
     * Lock — wipes master secret and closes all databases.
     * Called when app goes to background.
     */
    fun lock() {
        masterSecret?.let { SecureWipe.wipe(it) }
        masterSecret = null
        openVaultDbs.values.forEach { it.close() }
        openVaultDbs.clear()
        masterDb?.close()
        masterDb = null
        _vaults.value = emptyList()
    }

    fun isUnlocked(): Boolean = masterSecret != null && masterDb != null

    private fun requireMasterDb(): MasterDatabase =
        masterDb ?: throw IllegalStateException("App is locked — call unlock() first")

    private fun requireMasterSecret(): ByteArray =
        masterSecret ?: throw IllegalStateException("Master secret not available — app is locked")
}
