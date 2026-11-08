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

    fun current(expectedProfileId: Int): PluginProfileOperation? = synchronized(lock) {
        if (profileId != expectedProfileId) {
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

    fun isCurrent(operation: PluginProfileOperation): Boolean = synchronized(lock) {
        profileId == operation.profileId && generation == operation.generation
    }

    fun runIfCurrent(
        operation: PluginProfileOperation,
        block: () -> Unit,
    ): Boolean = synchronized(lock) {
        if (profileId != operation.profileId || generation != operation.generation) {
            return@synchronized false
        }
        block()
        true
    }
}
