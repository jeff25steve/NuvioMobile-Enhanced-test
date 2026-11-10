package com.nuvio.app.core.ui

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Byte-bounded, access-ordered cache whose generation fences off writes from loads
 * that started before [clear] was called.
 *
 * All critical sections contain only map and counter operations. Callers perform
 * file and network I/O outside this lock.
 */
internal class GenerationBoundedByteLruCache(
    private val maxBytes: Long,
) {
    private val lock = SynchronizedObject()
    private val entries = LinkedHashMap<String, ByteArray>()
    private var cachedBytes = 0L
    private var generation = 0L

    init {
        require(maxBytes > 0L) { "Cache capacity must be positive" }
    }

    fun generationSnapshot(): Long = synchronized(lock) {
        generation
    }

    fun lookup(key: String): ByteArray? = synchronized(lock) {
        val bytes = entries.remove(key) ?: return@synchronized null
        entries[key] = bytes
        bytes
    }

    fun putIfCurrent(
        key: String,
        bytes: ByteArray,
        expectedGeneration: Long,
    ): Boolean = synchronized(lock) {
        if (expectedGeneration != generation || bytes.size.toLong() > maxBytes) {
            return@synchronized false
        }

        entries.remove(key)?.let { cachedBytes -= it.size.toLong() }
        entries[key] = bytes
        cachedBytes += bytes.size.toLong()

        while (cachedBytes > maxBytes && entries.isNotEmpty()) {
            val oldestKey = entries.keys.first()
            entries.remove(oldestKey)?.let { cachedBytes -= it.size.toLong() }
        }
        true
    }

    fun clear() {
        synchronized(lock) {
            generation += 1L
            entries.clear()
            cachedBytes = 0L
        }
    }
}
