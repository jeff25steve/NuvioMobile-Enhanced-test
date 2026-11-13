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
    private var resetting = false
    private val operations = mutableMapOf<Long, Job>()

    fun beginOperation(job: Job): Long? = synchronized(lock) {
        if (resetting) return@synchronized null
        (++nextOperationId).also { operations[it] = job }
    }

    fun isCurrent(operation: Long): Boolean = synchronized(lock) {
        !resetting && operation in operations
    }

    fun runIfOpen(block: () -> Unit): Boolean = synchronized(lock) {
        if (resetting) return@synchronized false
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
        try {
            resetMutex.lock()
        } catch (error: Throwable) {
            // Mutex.lock can hand ownership to a waiter and then throw cancellation before
            // that waiter's continuation runs. In that case the Mutex releases ownership
            // itself, so reconcile the gate after the cancelled acquisition has unwound.
            synchronized(lock) { resetting = resetMutex.isLocked }
            throw error
        }

        return try {
            synchronized(lock) {
                resetting = true
                operations.values.distinct()
            }
        } catch (error: Throwable) {
            resetMutex.unlock()
            synchronized(lock) { resetting = resetMutex.isLocked }
            throw error
        }
    }

    fun endReset() {
        resetMutex.unlock()
        synchronized(lock) {
            resetting = resetMutex.isLocked
        }
    }
}
