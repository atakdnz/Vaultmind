package com.vaultmind.app.vault

import android.content.ContentValues
import android.content.Context
import com.vaultmind.app.crypto.KeyDerivation
import net.sqlcipher.database.SQLiteDatabase
import java.io.File

/**
 * Encrypted master database — stores vault metadata only (no content, no vectors).
 *
 * Lives at: /data/data/com.vaultmind.app/databases/master.db (SQLCipher encrypted).
 * Key derived from master secret using HKDF with salt = "master".
 */
class MasterDatabase(
    context: Context,
    masterSecret: ByteArray
) {
    companion object {
        private const val DB_NAME = "master.db"
        private const val TABLE_VAULTS = "vaults"

        private const val COL_ID = "id"
        private const val COL_NAME = "name"
        private const val COL_CREATED_AT = "created_at"
        private const val COL_CHUNK_COUNT = "chunk_count"
        private const val COL_EMBEDDING_DIM = "embedding_dim"
        private const val COL_SOURCE_METHOD = "source_method"

        fun getDatabasePath(context: Context): File =
            File(context.getDatabasePath(DB_NAME).absolutePath)
    }

    private val db: SQLiteDatabase

    init {
        SQLiteDatabase.loadLibs(context)

        val dbKeyBytes = KeyDerivation.deriveMasterDbKey(masterSecret)
        val passphrase = KeyDerivation.toSqlCipherHexKey(dbKeyBytes)
        dbKeyBytes.fill(0)

        val dbFile = getDatabasePath(context)
        dbFile.parentFile?.mkdirs()

        db = SQLiteDatabase.openOrCreateDatabase(dbFile, passphrase, null, null)
        createTables()
    }

    private fun createTables() {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_VAULTS (
                $COL_ID TEXT PRIMARY KEY,
                $COL_NAME TEXT NOT NULL,
                $COL_CREATED_AT INTEGER NOT NULL,
                $COL_CHUNK_COUNT INTEGER NOT NULL DEFAULT 0,
                $COL_EMBEDDING_DIM INTEGER NOT NULL DEFAULT 768,
                $COL_SOURCE_METHOD TEXT NOT NULL DEFAULT 'on_device'
            )
        """.trimIndent())
    }

    fun insertVault(vault: Vault) {
        val cv = ContentValues().apply {
            put(COL_ID, vault.id)
            put(COL_NAME, vault.name)
            put(COL_CREATED_AT, vault.createdAt)
            put(COL_CHUNK_COUNT, vault.chunkCount)
            put(COL_EMBEDDING_DIM, vault.embeddingDim)
            put(COL_SOURCE_METHOD, vault.sourceMethod)
        }
        db.insertWithOnConflict(TABLE_VAULTS, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun updateVaultChunkCount(vaultId: String, count: Int) {
        db.execSQL(
            "UPDATE $TABLE_VAULTS SET $COL_CHUNK_COUNT = ? WHERE $COL_ID = ?",
            arrayOf(count, vaultId)
        )
    }

    fun updateVaultName(vaultId: String, newName: String) {
        val cv = ContentValues().apply { put(COL_NAME, newName) }
        db.update(TABLE_VAULTS, cv, "$COL_ID = ?", arrayOf(vaultId))
    }

    fun deleteVault(vaultId: String) {
        db.delete(TABLE_VAULTS, "$COL_ID = ?", arrayOf(vaultId))
    }

    fun getAllVaults(): List<Vault> {
        val result = mutableListOf<Vault>()
        db.rawQuery("SELECT * FROM $TABLE_VAULTS ORDER BY $COL_CREATED_AT DESC", null).use { cursor ->
            val idIdx = cursor.getColumnIndex(COL_ID)
            val nameIdx = cursor.getColumnIndex(COL_NAME)
            val createdIdx = cursor.getColumnIndex(COL_CREATED_AT)
            val chunkIdx = cursor.getColumnIndex(COL_CHUNK_COUNT)
            val dimIdx = cursor.getColumnIndex(COL_EMBEDDING_DIM)
            val srcIdx = cursor.getColumnIndex(COL_SOURCE_METHOD)

            while (cursor.moveToNext()) {
                result.add(
                    Vault(
                        id = cursor.getString(idIdx),
                        name = cursor.getString(nameIdx),
                        createdAt = cursor.getLong(createdIdx),
                        chunkCount = cursor.getInt(chunkIdx),
                        embeddingDim = cursor.getInt(dimIdx),
                        sourceMethod = cursor.getString(srcIdx)
                    )
                )
            }
        }
        return result
    }

    fun close() {
        if (db.isOpen) db.close()
    }
}
