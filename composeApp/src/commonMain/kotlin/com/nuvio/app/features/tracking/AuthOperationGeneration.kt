package com.nuvio.app.features.tracking

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Serializes credential/state commits with profile changes and authorization invalidation.
 * Network work happens outside this gate; only a response belonging to the current
 * generation may mutate state or persist credentials.
 */
internal class AuthOperationGeneration {
    private val lock = SynchronizedObject()
    private var generation = 0L

    fun advance(action: (Long) -> Unit = {}): Long = synchronized(lock) {
        generation += 1L
        action(generation)
        generation
    }

    fun current(): Long = synchronized(lock) { generation }

    fun isCurrent(expected: Long): Boolean = synchronized(lock) {
        generation == expected
    }

    fun <T> read(action: (Long) -> T): T = synchronized(lock) {
        action(generation)
    }

    fun commitIfCurrent(expected: Long, action: () -> Unit): Boolean = synchronized(lock) {
        if (generation != expected) return@synchronized false
        action()
        true
    }

    fun invalidateIfCurrent(expected: Long, action: (Long) -> Unit): Boolean = synchronized(lock) {
        if (generation != expected) return@synchronized false
        generation += 1L
        action(generation)
        true
    }
}
