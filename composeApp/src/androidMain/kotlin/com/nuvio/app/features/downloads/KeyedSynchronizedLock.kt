package com.nuvio.app.features.downloads

/**
 * Runs blocking store operations under a per-key monitor while releasing idle entries.
 *
 * A reference is registered before waiting for the key monitor, so an entry cannot be
 * removed while another thread is queued on it. The registry monitor is never held while
 * the caller's operation runs.
 */
internal class KeyedSynchronizedLock {
    private class Entry {
        val monitor = Any()
        var users: Int = 0
    }

    private val registryMonitor = Any()
    private val entries = mutableMapOf<String, Entry>()

    internal val trackedKeyCount: Int
        get() = synchronized(registryMonitor) { entries.size }

    fun <T> withLock(key: String, action: () -> T): T {
        val entry = synchronized(registryMonitor) {
            entries.getOrPut(key) { Entry() }.also { it.users++ }
        }
        try {
            return synchronized(entry.monitor) { action() }
        } finally {
            synchronized(registryMonitor) {
                entry.users--
                check(entry.users >= 0) { "Keyed store lock reference count underflow for $key" }
                if (entry.users == 0 && entries[key] === entry) {
                    entries.remove(key)
                }
            }
        }
    }
}
