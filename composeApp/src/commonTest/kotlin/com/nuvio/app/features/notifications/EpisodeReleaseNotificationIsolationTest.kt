package com.nuvio.app.features.notifications

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EpisodeReleaseNotificationIsolationTest {

    @Test
    fun `profile operation requires matching profile and generation`() {
        val token = EpisodeReleaseNotificationProfileToken(profileId = 2, generation = 7)

        assertTrue(isEpisodeReleaseNotificationOperationCurrent(token, activeProfileId = 2, currentGeneration = 7))
        assertFalse(isEpisodeReleaseNotificationOperationCurrent(token, activeProfileId = 1, currentGeneration = 7))
        assertFalse(isEpisodeReleaseNotificationOperationCurrent(token, activeProfileId = 2, currentGeneration = 8))
    }

    @Test
    fun `scheduling requires current profile enabled state and no cleanup`() {
        val token = EpisodeReleaseNotificationProfileToken(profileId = 2, generation = 7)

        assertTrue(
            isEpisodeReleaseNotificationSchedulingAllowed(
                token = token,
                activeProfileId = 2,
                currentGeneration = 7,
                isEnabled = true,
                accountCleanupInProgress = false,
            ),
        )
        assertFalse(
            isEpisodeReleaseNotificationSchedulingAllowed(
                token = token,
                activeProfileId = 2,
                currentGeneration = 7,
                isEnabled = false,
                accountCleanupInProgress = false,
            ),
        )
        assertFalse(
            isEpisodeReleaseNotificationSchedulingAllowed(
                token = token,
                activeProfileId = 2,
                currentGeneration = 7,
                isEnabled = true,
                accountCleanupInProgress = true,
            ),
        )
    }

    @Test
    fun `notification identifiers keep Int minimum hashes non-negative`() {
        // This string has Java/Kotlin String.hashCode() == Int.MIN_VALUE.
        // abs(Int.MIN_VALUE) overflows and remains negative.
        val identifier = buildEpisodeReleaseNotificationId(
            profileId = 2,
            contentType = "series",
            contentId = "show-1",
            episodeId = "polygenelubricants",
            releaseDateIso = "2026-11-13",
        )

        assertFalse(identifier.contains("--"))
        assertTrue(identifier.endsWith("-2147483648-2026-11-13").not())
        assertTrue(identifier.endsWith("-2147483648-2026-11-13").not())
        assertTrue(identifier.endsWith("-2147483648-2026-11-13").not())
        assertTrue(identifier.contains("-2147483648"))
    }

    @Test
    fun `account cleanup recognises only episode release notification identifiers`() {
        assertTrue(isEpisodeReleaseNotificationIdentifier("episode-release-2-123-456-2026-11-08"))
        assertTrue(isEpisodeReleaseNotificationIdentifier("episode-release-test-2-123"))
        assertFalse(isEpisodeReleaseNotificationIdentifier("chat-message-123"))
        assertFalse(isEpisodeReleaseNotificationIdentifier("episode-release"))
    }
}
