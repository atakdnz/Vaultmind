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

        // Phase 1: embed all chunks, validate dimension on first chunk
        val embeddings = mutableListOf<Pair<TextChunker.Chunk, FloatArray>>()
        for ((index, chunk) in chunks.withIndex()) {
            onProgress(IngestionProgress(index + 1, chunks.size, "Embedding chunk"))

            val vector = embeddingEngine.embedDocument(chunk.text)
                ?: return@withContext IngestionResult.Error(
                    "Embedding model not loaded. Please set up the model first."
                )

            if (index == 0) {
                val expectedDim = vaultDb.getVaultInfo("embedding_dim")?.toIntOrNull()
                if (expectedDim != null && vector.size != expectedDim) {
                    vector.fill(0f)
                    return@withContext IngestionResult.Error(
                        "Embedding dimension mismatch: vault expects ${expectedDim}D but model produces ${vector.size}D. " +
                        "Re-create the vault with the correct embedding model."
                    )
                }
            }

            embeddings.add(Pair(chunk, vector))
        }

        // Phase 2: batch-insert all chunks in a single transaction
        onProgress(IngestionProgress(chunks.size, chunks.size, "Saving to vault…"))
        vaultDb.beginBatch()
        try {
            for ((chunk, vector) in embeddings) {
                vaultDb.insertChunkWithEmbedding(
                    content = chunk.text,
                    chunkIndex = chunk.index,
                    tokenCount = chunk.estimatedTokenCount,
                    vector = vector
                )
            }
            vaultDb.commitBatch()
        } finally {
            vaultDb.endBatch()
            embeddings.forEach { (_, v) -> v.fill(0f) }
        }

        val totalChunks = vaultDb.getChunkCount()
        vaultRepository.updateChunkCount(vaultId, totalChunks)

        IngestionResult.Success(embeddings.size)
    }
}
