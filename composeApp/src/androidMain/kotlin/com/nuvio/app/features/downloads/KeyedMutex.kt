package com.nuvio.app.features.downloads

import kotlinx.coroutines.sync.Mutex

/**
 * Serializes operations by key without retaining one Mutex for every key ever observed.
 *
 * References include both the current holder and suspended waiters, so an entry is removed
 * only after its final user has left the critical section or been cancelled while waiting.
 */
internal class KeyedMutex {
    private class Entry {
        val mutex = Mutex()
        var users: Int = 0
    }

    private val monitor = Any()
    private val entries = mutableMapOf<String, Entry>()

    internal val trackedKeyCount: Int
        get() = synchronized(monitor) { entries.size }

    internal fun trackedReferenceCount(key: String): Int =
        synchronized(monitor) { entries[key]?.users ?: 0 }

    suspend fun <T> withLock(key: String, action: suspend () -> T): T {
        val entry = synchronized(monitor) {
            entries.getOrPut(key) { Entry() }.also { it.users++ }
        }
        var acquired = false
        try {
            entry.mutex.lock()
            acquired = true
            return action()
        } finally {
            try {
                if (acquired) entry.mutex.unlock()
            } finally {
                synchronized(monitor) {
                    entry.users--
                    check(entry.users >= 0) { "Keyed mutex reference count underflow for $key" }
                    if (entry.users == 0 && entries[key] === entry) {
                        entries.remove(key)
                    }
                }
            }
        }
    }
}
