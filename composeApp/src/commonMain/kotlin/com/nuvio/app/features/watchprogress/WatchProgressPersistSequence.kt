package com.nuvio.app.features.watchprogress

/**
 * Tracks the newest accepted local snapshot independently for each profile.
 *
 * Repository callers serialize all operations with persistLock. The global
 * invalidation boundary remains separate so account cleanup can reject every
 * persist operation queued before the clear.
 */
internal class WatchProgressPersistSequence {
    private var invalidatedThroughSequence = 0L
    private val persistedByProfile = mutableMapOf<Int, Long>()

    fun isStale(profileId: Int, sequence: Long): Boolean =
        sequence <= invalidatedThroughSequence ||
            sequence <= (persistedByProfile[profileId] ?: 0L)

    fun markPersisted(profileId: Int, sequence: Long) {
        val previous = persistedByProfile[profileId] ?: 0L
        if (sequence > previous) {
            persistedByProfile[profileId] = sequence
        }
    }

    fun invalidateThrough(sequence: Long) {
        if (sequence > invalidatedThroughSequence) {
            invalidatedThroughSequence = sequence
        }
        persistedByProfile.clear()
    }
}
