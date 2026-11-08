package com.nuvio.app.features.p2p

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class P2pTorrentErrorTest {
    @Test
    fun matchingActiveTorrentErrorIsReported() {
        assertEquals(
            P2pStreamingState.Error("file write failed"),
            p2pTorrentErrorForActiveTorrent(
                eventTorrentId = "active-torrent",
                activeTorrentId = "active-torrent",
                message = "file write failed",
                fallbackMessage = "unknown",
            ),
        )
    }

    @Test
    fun lateCommandScopedErrorIsCorrelatedByTorrentIdentity() {
        // Native torrent errors can retain the request ID from the completed add command.
        // The active torrent ID, rather than request-ID zero, is the stable playback owner.
        assertEquals(
            P2pStreamingState.Error("payload file disappeared"),
            p2pTorrentErrorForActiveTorrent(
                eventTorrentId = "active-torrent",
                activeTorrentId = "active-torrent",
                message = "payload file disappeared",
                fallbackMessage = "unknown",
            ),
        )
    }

    @Test
    fun errorForAnotherTorrentIsIgnored() {
        assertNull(
            p2pTorrentErrorForActiveTorrent(
                eventTorrentId = "previous-torrent",
                activeTorrentId = "active-torrent",
                message = "failed",
                fallbackMessage = "unknown",
            ),
        )
    }

    @Test
    fun errorWithoutTorrentIdentityIsIgnored() {
        assertNull(
            p2pTorrentErrorForActiveTorrent(
                eventTorrentId = null,
                activeTorrentId = "active-torrent",
                message = "disk cache budget is exceeded",
                fallbackMessage = "unknown",
            ),
        )
        assertNull(
            p2pTorrentErrorForActiveTorrent(
                eventTorrentId = "active-torrent",
                activeTorrentId = null,
                message = "failed",
                fallbackMessage = "unknown",
            ),
        )
    }

    @Test
    fun blankErrorMessageUsesFallback() {
        assertEquals(
            P2pStreamingState.Error("unknown"),
            p2pTorrentErrorForActiveTorrent(
                eventTorrentId = "active-torrent",
                activeTorrentId = "active-torrent",
                message = "  ",
                fallbackMessage = "unknown",
            ),
        )
    }
}
