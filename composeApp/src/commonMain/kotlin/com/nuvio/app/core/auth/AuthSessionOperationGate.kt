package com.nuvio.app.core.auth

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex

/**
 * Tracks in-flight operations that can import a Supabase session.
 *
 * A server switch blocks new operations, invalidates their ownership tokens, and returns
 * the jobs that must finish cancellation before the old client's stored session is cleared.
 */
internal class AuthSessionOperationGate {
    private val lock = SynchronizedObject()
    private val resetMutex = Mutex()
    private var nextOperationId = 0L
    private var pendingResets = 0
    private val operations = mutableMapOf<Long, Job>()

    fun beginOperation(job: Job): Long? = synchronized(lock) {
        if (pendingResets > 0) return@synchronized null
        (++nextOperationId).also { operations[it] = job }
    }

    fun isCurrent(operation: Long): Boolean = synchronized(lock) {
        pendingResets == 0 && operation in operations
    }

    fun runIfOpen(block: () -> Unit): Boolean = synchronized(lock) {
        if (pendingResets > 0) return@synchronized false
        block()
        true
    }

    fun finishOperation(operation: Long) {
        synchronized(lock) {
            operations.remove(operation)
        }
    }

    /**
     * Acquires exclusive ownership of an account/session reset.
     *
     * The mutex serializes server switches, sign-outs and account deletion. The gate remains
     * closed while ownership is handed to another queued reset.
     */
    suspend fun beginReset(): List<Job> {
        // Register the reset request before suspending on the mutex. This reservation is
        // the source of truth for the operation gate; Mutex.isLocked is only a snapshot and
        // cannot safely reconstruct gate state across a concurrent unlock/lock handoff.
        synchronized(lock) {
            pendingResets += 1
        }

        try {
            resetMutex.lock()
        } catch (error: Throwable) {
            synchronized(lock) {
                pendingResets -= 1
            }
            throw error
        }

        return try {
            synchronized(lock) {
                operations.values.distinct()
            }
        } catch (error: Throwable) {
            resetMutex.unlock()
            synchronized(lock) {
                pendingResets -= 1
            }
            throw error
        }
    }

    fun endReset() {
        resetMutex.unlock()
        synchronized(lock) {
            pendingResets -= 1
        }
    }
}
