package com.nuvio.app.features.plugins

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

internal data class PluginProfileOperation(
    val profileId: Int,
    val generation: Long,
)

/**
 * Prevents repository refreshes from publishing or caching results after a profile transition.
 *
 * The transition callback runs under the same lock as result publication and scraper-code
 * cache writes, so cleanup cannot remove the plugin directory while a stale write is entering.
 */
internal class PluginProfileOperationGate(initialProfileId: Int = 1) {
    private val lock = SynchronizedObject()
    private var profileId = initialProfileId
    private var generation = 0L
    private var accountCleanupDepth = 0

    fun current(expectedProfileId: Int): PluginProfileOperation? = synchronized(lock) {
        if (accountCleanupDepth > 0 || profileId != expectedProfileId) {
            return@synchronized null
        }
        PluginProfileOperation(profileId, generation)
    }

    fun invalidate(
        nextProfileId: Int,
        block: () -> Unit = {},
    ): PluginProfileOperation = synchronized(lock) {
        profileId = nextProfileId
        generation += 1L
        block()
        PluginProfileOperation(profileId, generation)
    }

    fun beginAccountCleanup(block: () -> Unit = {}) {
        synchronized(lock) {
            accountCleanupDepth += 1
            generation += 1L
            block()
        }
    }

    fun endAccountCleanup() {
        synchronized(lock) {
            if (accountCleanupDepth > 0) accountCleanupDepth -= 1
        }
    }

    fun isCurrent(operation: PluginProfileOperation): Boolean = synchronized(lock) {
        accountCleanupDepth == 0 && profileId == operation.profileId && generation == operation.generation
    }

    fun runIfCurrent(
        operation: PluginProfileOperation,
        block: () -> Unit,
    ): Boolean = synchronized(lock) {
        if (accountCleanupDepth > 0 || profileId != operation.profileId || generation != operation.generation) {
            return@synchronized false
        }
        block()
        true
    }
}
