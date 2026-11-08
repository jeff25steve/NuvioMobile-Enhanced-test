package com.nuvio.app.features.addons

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

internal data class AddonProfileOperation(
    val profileId: Int,
    val generation: Long,
)

/**
 * Gives asynchronous add-on operations an ownership token for one effective profile.
 *
 * The generation changes on every explicit profile transition, including switching
 * away from a profile and later returning to the same profile index.
 */
internal class AddonProfileOperationGate(initialProfileId: Int = 1) {
    private val lock = SynchronizedObject()
    private var profileId = initialProfileId
    private var generation = 0L
    private var accountCleanupDepth = 0

    fun current(expectedProfileId: Int): AddonProfileOperation? = synchronized(lock) {
        if (accountCleanupDepth > 0 || profileId != expectedProfileId) {
            return@synchronized null
        }
        AddonProfileOperation(profileId, generation)
    }

    fun invalidate(
        nextProfileId: Int,
        block: () -> Unit = {},
    ): AddonProfileOperation = synchronized(lock) {
        profileId = nextProfileId
        generation += 1L
        block()
        AddonProfileOperation(profileId, generation)
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

    fun isCurrent(operation: AddonProfileOperation): Boolean = synchronized(lock) {
        accountCleanupDepth == 0 && profileId == operation.profileId && generation == operation.generation
    }

    fun runIfCurrent(
        operation: AddonProfileOperation,
        block: () -> Unit,
    ): Boolean = synchronized(lock) {
        if (accountCleanupDepth > 0 || profileId != operation.profileId || generation != operation.generation) {
            return@synchronized false
        }
        block()
        true
    }
}
