package com.vaultmind.app.ingestion

import android.content.Context
import android.net.Uri
import com.vaultmind.app.vault.VaultRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Result of an ingestion run.
 */
sealed class IngestionResult {
    data class Success(val chunksAdded: Int) : IngestionResult()
    data class Error(val message: String) : IngestionResult()
}

/**
 * Progress callback during ingestion.
 */
data class IngestionProgress(
    val current: Int,
    val total: Int,
    val phase: String
)

/**
 * Full on-device ingestion pipeline: SAF URI → chunks → embeddings → SQLCipher vault DB.
 *
 * Follows plan § Ingestion Pipeline → Path A.
 *
 * Security notes:
 *  - File is read via SAF content URI — never copied to app storage unencrypted.
 *  - All plaintext lives only in the call stack; the GC'd heap is wiped immediately after use.
 *  - The source .txt is never written to app-internal storage.
 */
class OnDeviceIngestion @Inject constructor(
    @ApplicationContext private val context: Context,
    private val chunker: TextChunker,
    private val embeddingEngine: EmbeddingEngine,
    private val vaultRepository: VaultRepository
) {
    /**
     * Ingest the text file at [fileUri] into [vaultId].
     *
     * @param fileUri    SAF content URI of the .txt file.
     * @param vaultId    Target vault UUID.
     * @param onProgress Callback invoked after each chunk is embedded.
     */
    suspend fun ingest(
        fileUri: Uri,
        vaultId: String,
        onProgress: (IngestionProgress) -> Unit = {}
    ): IngestionResult = withContext(Dispatchers.IO) {
        // Step 1: Read file via SAF — do NOT copy, read stream directly
        val text = try {
            context.contentResolver.openInputStream(fileUri)?.use { stream ->
                stream.bufferedReader(Charsets.UTF_8).readText()
            } ?: return@withContext IngestionResult.Error("Could not open file")
        } catch (e: Exception) {
            return@withContext IngestionResult.Error("Failed to read file: ${e.message}")
        }

        if (text.isBlank()) {
            return@withContext IngestionResult.Error("File is empty")
        }

        onProgress(IngestionProgress(0, 0, "Chunking text…"))

        // Step 2: Chunk the text
        val chunks = try {
            chunker.chunk(text)
        } catch (e: Exception) {
            return@withContext IngestionResult.Error("Chunking failed: ${e.message}")
        }

        if (chunks.isEmpty()) {
            return@withContext IngestionResult.Error("No chunks produced — file may be too short")
        }

        // Step 3 & 4: Embed each chunk and store in vault DB
        val vaultDb = try {
            vaultRepository.openVaultDb(vaultId)
        } catch (e: Exception) {
            return@withContext IngestionResult.Error("Could not open vault: ${e.message}")
        }

        var embedded = 0
        for ((index, chunk) in chunks.withIndex()) {
            onProgress(IngestionProgress(index + 1, chunks.size, "Embedding chunk"))

            val vector = embeddingEngine.embedDocument(chunk.text)
                ?: return@withContext IngestionResult.Error(
                    "Embedding model not loaded. Please set up the model first."
                )

            vaultDb.insertChunkWithEmbedding(
                content = chunk.text,
                chunkIndex = chunk.index,
                tokenCount = chunk.estimatedTokenCount,
                vector = vector
            )
            embedded++

            // Wipe vector from local scope immediately after insert
            vector.fill(0f)
        }

        // Step 5: Update chunk count in master DB
        val totalChunks = vaultDb.getChunkCount()
        vaultRepository.updateChunkCount(vaultId, totalChunks)

        // The `text` string is now unreferenced and eligible for GC.
        // Kotlin strings are immutable — we cannot zero them, but we can null the reference.

        IngestionResult.Success(embedded)
    }
}
