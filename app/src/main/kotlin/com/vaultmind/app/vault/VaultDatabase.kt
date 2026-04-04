package com.vaultmind.app.vault

import android.content.ContentValues
import android.content.Context
import com.vaultmind.app.crypto.KeyDerivation
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SQLiteOpenHelper
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * SQLCipher-encrypted database for a single vault.
 *
 * Schema:
 *  - chunks(id, content, chunk_index, token_count, created_at)
 *  - embeddings(chunk_id, vector BLOB, dimension)
 *  - vault_info(key TEXT, value TEXT)
 *
 * The passphrase is derived from the master secret + vault ID using HKDF.
 * The raw 32-byte key is passed to SQLCipher using the "x'hex'" format.
 */
class VaultDatabase(
    context: Context,
    vaultId: String,
    masterSecret: ByteArray
) {
    companion object {
        private const val DB_VERSION = 1

        private const val TABLE_CHUNKS = "chunks"
        private const val TABLE_EMBEDDINGS = "embeddings"
        private const val TABLE_VAULT_INFO = "vault_info"

        private const val COL_ID = "id"
        private const val COL_CONTENT = "content"
        private const val COL_CHUNK_INDEX = "chunk_index"
        private const val COL_TOKEN_COUNT = "token_count"
        private const val COL_CREATED_AT = "created_at"
        private const val COL_CHUNK_ID = "chunk_id"
        private const val COL_VECTOR = "vector"
        private const val COL_DIMENSION = "dimension"
        private const val COL_KEY = "key"
        private const val COL_VALUE = "value"

        fun getDatabasePath(context: Context, vaultId: String): File {
            return File(context.getDatabasePath("vault_$vaultId.db").absolutePath)
        }

        /** Encode a float array as a little-endian BLOB for storage. */
        fun floatsToBlob(floats: FloatArray): ByteArray {
            val buf = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            floats.forEach { buf.putFloat(it) }
            return buf.array()
        }

        /** Decode a BLOB back to a float array. */
        fun blobToFloats(blob: ByteArray): FloatArray {
            val buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(blob.size / 4) { buf.float }
        }
    }

    private val db: SQLiteDatabase

    init {
        // Load SQLCipher native libraries
        SQLiteDatabase.loadLibs(context)

        // Derive the vault-specific key
        val vaultKeyBytes = KeyDerivation.deriveVaultKey(masterSecret, vaultId)
        val passphrase = KeyDerivation.toSqlCipherHexKey(vaultKeyBytes)
        vaultKeyBytes.fill(0)

        val dbFile = getDatabasePath(context, vaultId)
        dbFile.parentFile?.mkdirs()

        db = SQLiteDatabase.openOrCreateDatabase(
            dbFile,
            passphrase,
            null,
            null
        )

        onCreate(db)
    }

    private fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_CHUNKS (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_CONTENT TEXT NOT NULL,
                $COL_CHUNK_INDEX INTEGER NOT NULL,
                $COL_TOKEN_COUNT INTEGER NOT NULL,
                $COL_CREATED_AT INTEGER NOT NULL
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_EMBEDDINGS (
                $COL_CHUNK_ID INTEGER PRIMARY KEY,
                $COL_VECTOR BLOB NOT NULL,
                $COL_DIMENSION INTEGER NOT NULL,
                FOREIGN KEY ($COL_CHUNK_ID) REFERENCES $TABLE_CHUNKS($COL_ID)
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_VAULT_INFO (
                $COL_KEY TEXT PRIMARY KEY,
                $COL_VALUE TEXT NOT NULL
            )
        """.trimIndent())

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_chunks_index ON $TABLE_CHUNKS($COL_CHUNK_INDEX)")
    }

    /** Insert a chunk and its embedding vector in a single transaction. */
    fun insertChunkWithEmbedding(
        content: String,
        chunkIndex: Int,
        tokenCount: Int,
        vector: FloatArray
    ): Long {
        db.beginTransaction()
        return try {
            val chunkValues = ContentValues().apply {
                put(COL_CONTENT, content)
                put(COL_CHUNK_INDEX, chunkIndex)
                put(COL_TOKEN_COUNT, tokenCount)
                put(COL_CREATED_AT, System.currentTimeMillis())
            }
            val chunkId = db.insert(TABLE_CHUNKS, null, chunkValues)

            val embValues = ContentValues().apply {
                put(COL_CHUNK_ID, chunkId)
                put(COL_VECTOR, floatsToBlob(vector))
                put(COL_DIMENSION, vector.size)
            }
            db.insert(TABLE_EMBEDDINGS, null, embValues)

            db.setTransactionSuccessful()
            chunkId
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Load all (chunkId, vector) pairs into memory for brute-force cosine search.
     * With ~2000 vectors this completes in milliseconds.
     */
    fun loadAllVectors(): List<Pair<Long, FloatArray>> {
        val result = mutableListOf<Pair<Long, FloatArray>>()
        db.rawQuery("SELECT $COL_CHUNK_ID, $COL_VECTOR FROM $TABLE_EMBEDDINGS", null).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val blob = cursor.getBlob(1)
                result.add(Pair(id, blobToFloats(blob)))
            }
        }
        return result
    }

    /** Fetch a chunk's text content by its ID. */
    fun getChunkContent(chunkId: Long): String? {
        db.rawQuery(
            "SELECT $COL_CONTENT FROM $TABLE_CHUNKS WHERE $COL_ID = ?",
            arrayOf(chunkId.toString())
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    /** Fetch multiple chunks by ID, returned in the given order. */
    fun getChunks(chunkIds: List<Long>): List<Pair<Long, String>> {
        if (chunkIds.isEmpty()) return emptyList()
        val placeholders = chunkIds.joinToString(",") { "?" }
        val result = mutableListOf<Pair<Long, String>>()
        db.rawQuery(
            "SELECT $COL_ID, $COL_CONTENT FROM $TABLE_CHUNKS WHERE $COL_ID IN ($placeholders)",
            chunkIds.map { it.toString() }.toTypedArray()
        ).use { cursor ->
            val idIndex = cursor.getColumnIndex(COL_ID)
            val contentIndex = cursor.getColumnIndex(COL_CONTENT)
            while (cursor.moveToNext()) {
                result.add(Pair(cursor.getLong(idIndex), cursor.getString(contentIndex)))
            }
        }
        // Return in the requested order
        val byId = result.toMap()
        return chunkIds.mapNotNull { id -> byId[id]?.let { Pair(id, it) } }
    }

    fun getChunkCount(): Int {
        db.rawQuery("SELECT COUNT(*) FROM $TABLE_CHUNKS", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    fun setVaultInfo(key: String, value: String) {
        val cv = ContentValues().apply {
            put(COL_KEY, key)
            put(COL_VALUE, value)
        }
        db.insertWithOnConflict(TABLE_VAULT_INFO, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getVaultInfo(key: String): String? {
        db.rawQuery(
            "SELECT $COL_VALUE FROM $TABLE_VAULT_INFO WHERE $COL_KEY = ?",
            arrayOf(key)
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    fun close() {
        if (db.isOpen) db.close()
    }

    fun isOpen(): Boolean = db.isOpen
}
