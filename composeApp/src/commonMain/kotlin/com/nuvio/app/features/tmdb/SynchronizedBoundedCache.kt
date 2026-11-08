package com.nuvio.app.features.tmdb

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * A small, insertion-ordered in-memory cache for shared metadata results.
 *
 * All map operations are protected because callers can run on different coroutine
 * dispatchers and from parallel child coroutines. FIFO eviction keeps the retained
 * entry count bounded without changing cache keys or refreshing ordering on reads.
 */
internal class SynchronizedBoundedCache<K : Any, V : Any>(
    private val maxEntries: Int,
) {
    private val lock = SynchronizedObject()
    private val entries = linkedMapOf<K, V>()

    init {
        require(maxEntries > 0) { "Cache capacity must be positive" }
    }

    fun get(key: K): V? = synchronized(lock) {
        entries[key]
    }

    fun put(key: K, value: V) {
        synchronized(lock) {
            entries[key] = value
            if (entries.size > maxEntries) {
                entries.remove(entries.keys.first())
            }
        }
    }

    fun firstValueOrNull(predicate: (K) -> Boolean): V? = synchronized(lock) {
        entries.entries.firstOrNull { predicate(it.key) }?.value
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
        }
    }
}
