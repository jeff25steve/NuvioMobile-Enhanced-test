package com.nuvio.app.core.auth

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.Job

/**
 * Tracks in-flight operations that can import a Supabase session.
 *
 * A server switch blocks new operations, invalidates their ownership tokens, and returns
 * the jobs that must finish cancellation before the old client's stored session is cleared.
 */
internal class AuthSessionOperationGate {
    private val lock = SynchronizedObject()
    private var nextOperationId = 0L
    private var switching = false
    private val operations = mutableMapOf<Long, Job>()

    fun beginOperation(job: Job): Long? = synchronized(lock) {
        if (switching) return@synchronized null
        (++nextOperationId).also { operations[it] = job }
    }

    fun isCurrent(operation: Long): Boolean = synchronized(lock) {
        !switching && operation in operations
    }

    fun runIfOpen(block: () -> Unit): Boolean = synchronized(lock) {
        if (switching) return@synchronized false
        block()
        true
    }

    fun finishOperation(operation: Long) {
        synchronized(lock) {
            operations.remove(operation)
        }
    }

    fun beginServerSwitch(): List<Job> = synchronized(lock) {
        switching = true
        operations.values.distinct()
    }

    fun endServerSwitch() {
        synchronized(lock) {
            switching = false
        }
    }
}
