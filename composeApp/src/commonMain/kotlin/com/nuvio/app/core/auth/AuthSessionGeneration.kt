package com.nuvio.app.core.auth

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Identifies the AuthRepository session-status collector that currently owns auth state.
 * A generation check and its state publication can be performed under the same lock so a
 * collector invalidated by a backend switch cannot publish afterwards.
 */
internal class AuthSessionGeneration {
    private val lock = SynchronizedObject()
    private var value = 0L

    fun begin(): Long = synchronized(lock) {
        value += 1L
        value
    }

    fun current(): Long = synchronized(lock) { value }

    fun invalidate(): Long = synchronized(lock) {
        value += 1L
        value
    }

    fun isCurrent(candidate: Long): Boolean = synchronized(lock) {
        value == candidate
    }

    fun runIfCurrent(candidate: Long, block: () -> Unit): Boolean = synchronized(lock) {
        if (value != candidate) return@synchronized false
        block()
        true
    }
}
