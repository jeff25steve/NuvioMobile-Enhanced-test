package com.nuvio.app.core.auth

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Serializes device-link attempts and prevents any new attempt from starting while the
 * active backend is being replaced.
 */
internal class DeviceLinkFlowGate {
    private val lock = SynchronizedObject()
    private var generation = 0L
    private var attemptActive = false
    private var serverSwitchInProgress = false

    fun beginAttempt(): Long? = synchronized(lock) {
        if (serverSwitchInProgress || attemptActive) return@synchronized null
        attemptActive = true
        generation += 1L
        generation
    }

    fun isCurrent(attempt: Long): Boolean = synchronized(lock) {
        !serverSwitchInProgress && attemptActive && generation == attempt
    }

    fun finishAttempt(attempt: Long) {
        synchronized(lock) {
            if (generation == attempt) attemptActive = false
        }
    }

    fun cancelAttempt() {
        synchronized(lock) {
            generation += 1L
            attemptActive = false
        }
    }

    fun beginServerSwitch() {
        synchronized(lock) {
            serverSwitchInProgress = true
            generation += 1L
            attemptActive = false
        }
    }

    fun endServerSwitch() {
        synchronized(lock) {
            serverSwitchInProgress = false
        }
    }
}
