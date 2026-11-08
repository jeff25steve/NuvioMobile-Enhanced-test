package com.nuvio.app.features.livetv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class LiveTvSourceSignatureTest {

    private fun signature(state: LiveTvUiState): String =
        liveTvChannelSourceSignature(state, accountUserId = "account-a", profileIndex = 1)

    @Test
    fun `account identity separates otherwise identical channel caches`() {
        val state = LiveTvUiState()
        assertNotEquals(
            liveTvChannelSourceSignature(state, "account-a", 1),
            liveTvChannelSourceSignature(state, "account-b", 1),
        )
    }

    @Test
    fun `profile identity separates otherwise identical channel caches`() {
        val state = LiveTvUiState()
        assertNotEquals(
            liveTvChannelSourceSignature(state, "account-a", 1),
            liveTvChannelSourceSignature(state, "account-a", 2),
        )
    }

    @Test
    fun `Stalker password contributes to the channel cache identity`() {
        val original = LiveTvUiState(
            stalkerSettings = LiveTvStalkerSettings(
                portalUrl = "https://portal.example",
                macAddress = "00:11:22:33:44:55",
                username = "provider-user",
                password = "first-password",
            ),
        )
        val changed = original.copy(
            stalkerSettings = original.stalkerSettings.copy(password = "second-password"),
        )

        assertNotEquals(
            signature(original),
            signature(changed),
        )
    }

    @Test
    fun `different playlist URLs with colliding String hashes have distinct cache identities`() {
        val firstUrl = "https://example.com/Aa"
        val secondUrl = "https://example.com/BB"
        assertEquals(firstUrl.length, secondUrl.length)
        assertEquals(firstUrl.hashCode(), secondUrl.hashCode())

        fun stateFor(source: String) = LiveTvUiState(
            playlists = listOf(
                LiveTvPlaylist(
                    id = "same-playlist-id",
                    name = "Example",
                    type = LiveTvPlaylistType.Url,
                    source = source,
                ),
            ),
        )

        assertNotEquals(
            signature(stateFor(firstUrl)),
            signature(stateFor(secondUrl)),
        )
    }

    @Test
    fun `playlist name changes invalidate cached channel metadata`() {
        val state = LiveTvUiState(
            playlists = listOf(
                LiveTvPlaylist(
                    id = "playlist-id",
                    name = "Old name",
                    type = LiveTvPlaylistType.Url,
                    source = "https://example.com/playlist.m3u",
                ),
            ),
        )
        val renamed = state.copy(
            playlists = state.playlists.map { it.copy(name = "New name") },
        )

        assertNotEquals(
            signature(state),
            signature(renamed),
        )
    }

    @Test
    fun `EPG cache identity includes each complete request key`() {
        val firstKey = "https://example.com/Aa"
        val secondKey = "https://example.com/BB"
        assertEquals(firstKey.hashCode(), secondKey.hashCode())

        assertNotEquals(
            liveTvEpgSourceSignature("channel-signature", listOf(firstKey)),
            liveTvEpgSourceSignature("channel-signature", listOf(secondKey)),
        )
    }
}
