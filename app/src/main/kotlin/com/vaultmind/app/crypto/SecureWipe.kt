package com.vaultmind.app.crypto

import java.util.Arrays

/**
 * Utilities for wiping sensitive data from memory.
 *
 * Always call these after using any cryptographic material (keys, passwords, plaintext).
 * Note: JVM makes zero guarantees about when objects are GC'd, but wiping byte arrays
 * at least clears the data as soon as we're done with it, minimising the exposure window.
 */
object SecureWipe {

    /** Zero out a byte array. */
    fun wipe(data: ByteArray) {
        Arrays.fill(data, 0.toByte())
    }

    /** Zero out a char array (passwords). */
    fun wipe(data: CharArray) {
        Arrays.fill(data, '\u0000')
    }

    /** Zero out a float array (embedding vectors). */
    fun wipe(data: FloatArray) {
        Arrays.fill(data, 0f)
    }

    /**
     * Execute [block] with [data], then wipe [data] regardless of success or failure.
     * Use this for key material: key bytes are wiped as soon as the block finishes.
     */
    inline fun <T> withWipe(data: ByteArray, block: (ByteArray) -> T): T {
        return try {
            block(data)
        } finally {
            wipe(data)
        }
    }

    inline fun <T> withWipe(data: CharArray, block: (CharArray) -> T): T {
        return try {
            block(data)
        } finally {
            wipe(data)
        }
    }
}
