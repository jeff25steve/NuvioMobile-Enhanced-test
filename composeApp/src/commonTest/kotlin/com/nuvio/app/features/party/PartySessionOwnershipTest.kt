package com.nuvio.app.features.party

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PartySessionOwnershipTest {
    @Test
    fun currentSessionAcceptsItsOwnMessagesAndResponses() {
        assertTrue(isPartySessionCurrent("party-a", "party-a"))
        assertTrue(isPartySnapshotCurrent("party-a", "party-a", "party-a"))
        // Error snapshots may not include party details, so the request owner
        // and still-active local session are the authority in that case.
        assertTrue(isPartySnapshotCurrent("party-a", "party-a", null))
    }

    @Test
    fun previousSessionCannotPublishIntoTheNextParty() {
        assertFalse(isPartySessionCurrent("party-a", "party-b"))
        assertFalse(isPartySnapshotCurrent("party-a", "party-b", null))
        assertFalse(isPartySnapshotCurrent("party-a", "party-b", "party-a"))
    }

    @Test
    fun mismatchedResponsePartyIsRejectedEvenWhenLocalSessionHasNotChanged() {
        assertFalse(isPartySnapshotCurrent("party-a", "party-a", "party-b"))
    }

    @Test
    fun completedSessionCannotPublishAfterPartyHasEnded() {
        assertFalse(isPartySessionCurrent("party-a", null))
        assertFalse(isPartySnapshotCurrent("party-a", null, null))
    }
}
