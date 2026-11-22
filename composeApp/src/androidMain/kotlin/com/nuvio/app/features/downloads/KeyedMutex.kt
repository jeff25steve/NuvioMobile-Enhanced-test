package com.nuvio.app.features.downloads

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes suspendable work by key without retaining every key for the scheduler's lifetime.
 *
 * A reference is registered before waiting for the mutex and released only after withLock has
 * returned (or cancelled). Therefore holders and queued callers share one entry, while an idle
 * entry can be removed without allowing a second mutex for the same key to overlap it.
 */
internal class KeyedMutex {
    private class Entry {
        val mutex = Mutex()
        var users = 0
    }

    private val entries = mutableMapOf<String, Entry>()

    suspend fun <T> withLock(key: String, block: suspend () -> T): T {
        val entry = synchronized(entries) {
            (entries[key] ?: Entry().also { entries[key] = it }).also { it.users += 1 }
        }

        return try {
            entry.mutex.withLock { block() }
        } finally {
            synchronized(entries) {
                check(entries[key] === entry) { "Keyed mutex entry changed while in use" }
                entry.users -= 1
                check(entry.users >= 0) { "Keyed mutex reference count became negative" }
                if (entry.users == 0) entries.remove(key)
            }
        }
    }

    internal fun activeKeyCount(): Int = synchronized(entries) { entries.size }

    internal fun activeUsers(key: String): Int = synchronized(entries) { entries[key]?.users ?: 0 }
}
