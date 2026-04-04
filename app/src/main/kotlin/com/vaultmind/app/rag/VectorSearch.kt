package com.vaultmind.app.rag

import com.vaultmind.app.vault.VaultDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.sqrt

/**
 * Brute-force cosine similarity search over in-memory embedding vectors.
 *
 * With ~2000 vectors at 768 dimensions, the full scan completes in <5ms on S23 Ultra.
 * No external library (FAISS, HNSW) is needed at this scale.
 *
 * Vectors are assumed to be L2-normalised (unit vectors), so cosine similarity
 * reduces to a simple dot product.
 */
class VectorSearch @Inject constructor() {

    /**
     * Search result — a single retrieved chunk with its similarity score.
     */
    data class SearchResult(
        val chunkId: Long,
        val content: String,
        val score: Float     // cosine similarity in [-1, 1]; higher = more relevant
    )

    /**
     * Find the top-K most similar chunks in [vaultDb] to [queryVector].
     *
     * @param vaultDb       The open, decrypted vault database.
     * @param queryVector   L2-normalised query embedding (same dimension as stored vectors).
     * @param topK          Number of results to return.
     * @param minSimilarity Discard results below this threshold (default: 0.3 per plan).
     */
    suspend fun search(
        vaultDb: VaultDatabase,
        queryVector: FloatArray,
        topK: Int = 5,
        minSimilarity: Float = 0.3f
    ): List<SearchResult> = withContext(Dispatchers.Default) {
        // Load all vectors into memory
        val allVectors = vaultDb.loadAllVectors()

        if (allVectors.isEmpty()) return@withContext emptyList()

        // Ensure query vector is normalised
        val normalisedQuery = l2Normalise(queryVector.copyOf())

        // Compute cosine similarity for every vector
        val scored = allVectors.mapNotNull { (chunkId, vector) ->
            val score = dotProduct(normalisedQuery, vector)
            if (score >= minSimilarity) Pair(chunkId, score) else null
        }

        // Sort by score descending, take top K
        val topKIds = scored
            .sortedByDescending { it.second }
            .take(topK)
            .map { it.first }

        if (topKIds.isEmpty()) return@withContext emptyList()

        // Fetch chunk text for the top results (preserving score order)
        val scoreMap = scored.associate { it.first to it.second }
        val chunks = vaultDb.getChunks(topKIds)

        chunks.map { (id, content) ->
            SearchResult(
                chunkId = id,
                content = content,
                score = scoreMap[id] ?: 0f
            )
        }
    }

    /** Dot product of two equal-length float arrays. */
    private fun dotProduct(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        val len = minOf(a.size, b.size)
        for (i in 0 until len) sum += a[i] * b[i]
        return sum
    }

    /** L2 normalise in-place and return. */
    private fun l2Normalise(v: FloatArray): FloatArray {
        val norm = sqrt(v.fold(0f) { acc, x -> acc + x * x })
        if (norm > 0f) for (i in v.indices) v[i] /= norm
        return v
    }
}
