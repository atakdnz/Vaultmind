package com.vaultmind.app.ingestion

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer

/**
 * Pure-Kotlin SentencePiece tokenizer for EmbeddingGemma.
 *
 * Parses the binary .model protobuf (no protobuf library needed) and implements
 * Unigram Viterbi tokenization with byte fallback. Works on any Android device
 * without native libraries.
 *
 * Usage:
 *   val tokenizer = SentencePieceTokenizer.load(assets.open("tokenizer.model"))
 *   val ids: IntArray = tokenizer.encode("Hello world")
 */
class SentencePieceTokenizer private constructor(
    private val pieceToId: Map<String, Int>,
    private val scores: FloatArray,
    private val byteTokenIds: IntArray,   // 256 entries; -1 if no byte token for that value
    private val maxPieceLen: Int,
    private val unkId: Int
) {

    companion object {
        private const val SPACE_SYMBOL = '\u2581' // ▁ — SentencePiece word-boundary marker

        // SentencePiece piece types (from sentencepiece_model.proto)
        private const val TYPE_NORMAL       = 1
        private const val TYPE_UNKNOWN      = 2
        private const val TYPE_CONTROL      = 3
        private const val TYPE_USER_DEFINED = 4
        private const val TYPE_UNUSED       = 5
        private const val TYPE_BYTE         = 6

        /**
         * Load a SentencePiece model from an [InputStream] (e.g. assets.open("tokenizer.model")).
         * The stream is consumed and closed.
         */
        fun load(inputStream: InputStream): SentencePieceTokenizer {
            val data = inputStream.use { it.readBytes() }
            return parse(data)
        }

        // ---- Protobuf parsing (wire format, no generated code needed) ----------

        private fun parse(data: ByteArray): SentencePieceTokenizer {
            val rawPieces = mutableListOf<Triple<String, Float, Int>>() // (text, score, type)
            val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

            while (buf.hasRemaining()) {
                val tag = readVarint(buf).toInt()
                val fieldNum = tag ushr 3
                val wireType = tag and 0x7

                if (fieldNum == 1 && wireType == 2) {
                    // ModelProto.pieces — repeated SentencePiece (field 1, LEN)
                    val len = readVarint(buf).toInt()
                    val end = buf.position() + len
                    rawPieces.add(parsePiece(buf, end))
                    buf.position(end)
                } else {
                    skipField(buf, wireType)
                }
            }

            // Build lookup structures
            val pieceToId = HashMap<String, Int>(rawPieces.size * 2)
            val scores = FloatArray(rawPieces.size)
            val byteTokenIds = IntArray(256) { -1 }
            var unkId = 0
            var maxLen = 1

            for ((id, triple) in rawPieces.withIndex()) {
                val (piece, score, type) = triple
                scores[id] = score

                when (type) {
                    TYPE_UNKNOWN -> unkId = id
                    TYPE_BYTE -> {
                        // Byte fallback tokens are named <0x00> .. <0xFF>
                        if (piece.startsWith("<0x") && piece.endsWith(">") && piece.length == 6) {
                            val byteVal = piece.substring(3, 5).toIntOrNull(16)
                            if (byteVal != null) byteTokenIds[byteVal] = id
                        }
                    }
                    TYPE_CONTROL, TYPE_UNUSED -> { /* skip — not matchable in text */ }
                    else -> {
                        // NORMAL, USER_DEFINED, or default (0)
                        if (piece.isNotEmpty()) {
                            pieceToId[piece] = id
                            maxLen = maxOf(maxLen, piece.length)
                        }
                    }
                }
            }

            return SentencePieceTokenizer(pieceToId, scores, byteTokenIds, maxLen, unkId)
        }

        /** Parse a single SentencePiece sub-message from the buffer. */
        private fun parsePiece(buf: ByteBuffer, end: Int): Triple<String, Float, Int> {
            var piece = ""
            var score = 0f
            var type = TYPE_NORMAL

            while (buf.position() < end) {
                val tag = readVarint(buf).toInt()
                val fieldNum = tag ushr 3
                val wireType = tag and 0x7

                when {
                    fieldNum == 1 && wireType == 2 -> {           // piece (string)
                        val len = readVarint(buf).toInt()
                        val bytes = ByteArray(len)
                        buf.get(bytes)
                        piece = String(bytes, Charsets.UTF_8)
                    }
                    fieldNum == 2 && wireType == 5 -> {           // score (float, I32)
                        score = buf.float
                    }
                    fieldNum == 3 && wireType == 0 -> {           // type (enum, VARINT)
                        type = readVarint(buf).toInt()
                    }
                    else -> skipField(buf, wireType)
                }
            }

            return Triple(piece, score, type)
        }

        /** Read a protobuf varint from [buf]. */
        private fun readVarint(buf: ByteBuffer): Long {
            var result = 0L
            var shift = 0
            while (buf.hasRemaining()) {
                val b = buf.get().toLong() and 0xFF
                result = result or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0L) break
                shift += 7
            }
            return result
        }

        /** Skip an unknown protobuf field based on its [wireType]. */
        private fun skipField(buf: ByteBuffer, wireType: Int) {
            when (wireType) {
                0 -> readVarint(buf)                                  // VARINT
                1 -> buf.position(buf.position() + 8)                 // I64
                2 -> {                                                 // LEN
                    val len = readVarint(buf).toInt()
                    buf.position(buf.position() + len)
                }
                5 -> buf.position(buf.position() + 4)                 // I32
            }
        }
    }

    // ---- Unigram Viterbi tokenization ------------------------------------------

    /**
     * Tokenize [text] into SentencePiece token IDs.
     *
     * Steps:
     *  1. NFKC Unicode normalisation
     *  2. Prepend ▁ and replace all spaces with ▁ (SentencePiece convention)
     *  3. Viterbi forward pass — find highest-probability segmentation
     *  4. Byte fallback for characters not in the vocabulary
     *  5. Backtrack to collect IDs
     */
    fun encode(text: String): IntArray {
        if (text.isEmpty()) return intArrayOf()

        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
        val processed = buildString(normalized.length + 1) {
            append(SPACE_SYMBOL)
            for (ch in normalized) {
                append(if (ch == ' ') SPACE_SYMBOL else ch)
            }
        }

        val n = processed.length

        // bestScore[i] = best log-probability for processed[0 .. i-1]
        val bestScore = FloatArray(n + 1) { if (it == 0) 0f else Float.NEGATIVE_INFINITY }
        // bestLen[i] = length of the last token in the best path ending at i.
        //              Negative value (-charLen) means byte-fallback was used.
        val bestLen = IntArray(n + 1)

        // ---- Forward pass ----
        for (i in 0 until n) {
            if (bestScore[i] == Float.NEGATIVE_INFINITY) continue

            val end = minOf(i + maxPieceLen, n)
            for (len in 1..(end - i)) {
                val piece = processed.substring(i, i + len)
                val id = pieceToId[piece] ?: continue
                val newScore = bestScore[i] + scores[id]
                if (newScore > bestScore[i + len]) {
                    bestScore[i + len] = newScore
                    bestLen[i + len] = len
                }
            }

            // Byte fallback: if no piece starts with the character at position i,
            // advance by one character (handling surrogate pairs).
            val charLen = if (Character.isHighSurrogate(processed[i]) && i + 1 < n) 2 else 1
            if (bestScore[i + charLen] == Float.NEGATIVE_INFINITY) {
                bestScore[i + charLen] = bestScore[i]
                bestLen[i + charLen] = -charLen   // negative ⇒ byte fallback
            }
        }

        // ---- Backtrack ----
        val ids = mutableListOf<Int>()
        var pos = n
        while (pos > 0) {
            val len = bestLen[pos]
            when {
                len > 0 -> {
                    val piece = processed.substring(pos - len, pos)
                    ids.add(0, pieceToId[piece] ?: unkId)
                    pos -= len
                }
                len < 0 -> {
                    // Byte fallback: encode character(s) as UTF-8 bytes
                    val charLen = -len
                    val ch = processed.substring(pos - charLen, pos)
                    val bytes = ch.toByteArray(Charsets.UTF_8)
                    for (b in bytes.reversed()) {
                        val byteId = byteTokenIds[b.toInt() and 0xFF]
                        ids.add(0, if (byteId >= 0) byteId else unkId)
                    }
                    pos -= charLen
                }
                else -> pos-- // safety: should not happen
            }
        }

        return ids.toIntArray()
    }
}
