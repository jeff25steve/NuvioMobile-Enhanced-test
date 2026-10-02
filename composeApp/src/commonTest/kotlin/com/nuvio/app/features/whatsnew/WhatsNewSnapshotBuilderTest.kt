package com.nuvio.app.features.whatsnew

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WhatsNewSnapshotBuilderTest {
    @Test
    fun sinceYourVersionContainsOnlyNewerReleasesWhenCurrentVersionIsPresent() {
        val releases = listOf(
            release("1.4.0"),
            release("1.3.0"),
            release("1.2.0"),
            release("1.1.0"),
        )

        val snapshot = WhatsNewSnapshotBuilder.build(
            releases = releases,
            currentVersion = "1.2.0",
        )

        assertTrue(snapshot.hasCompleteSinceVersion)
        assertEquals(listOf("1.4.0", "1.3.0"), snapshot.sinceYourVersion.map(WhatsNewRelease::version))
        assertEquals("1.2.0", snapshot.currentVersion)
    }

    @Test
    fun sinceYourVersionIsNotClaimedWhenCurrentVersionIsOutsideRecentHistory() {
        val releases = listOf(
            release("1.4.0"),
            release("1.3.0"),
        )

        val snapshot = WhatsNewSnapshotBuilder.build(
            releases = releases,
            currentVersion = "1.2.0",
        )

        assertFalse(snapshot.hasCompleteSinceVersion)
        assertTrue(snapshot.sinceYourVersion.isEmpty())
        assertEquals("1.2.0", snapshot.currentVersion)
    }

    @Test
    fun currentVersionAtLatestReleaseReportsNoNewReleases() {
        val snapshot = WhatsNewSnapshotBuilder.build(
            releases = listOf(
                release("1.4.0"),
                release("1.3.0"),
            ),
            currentVersion = "1.4.0",
        )

        assertTrue(snapshot.hasCompleteSinceVersion)
        assertTrue(snapshot.sinceYourVersion.isEmpty())
    }

    @Test
    fun anInvalidCurrentVersionDoesNotProduceAFalseSinceCount() {
        val releases = listOf(
            release("1.4.0"),
            release("1.3.0"),
            release("1.2.0"),
        )

        val snapshot = WhatsNewSnapshotBuilder.build(
            releases = releases,
            currentVersion = "not-a-version",
        )

        assertFalse(snapshot.hasCompleteSinceVersion)
        assertTrue(snapshot.sinceYourVersion.isEmpty())
    }

    @Test
    fun releaseOrderIsNormalizedNewestFirstWithoutDuplicatingVersions() {
        val releases = listOf(
            release("1.2.0"),
            release("1.4.0"),
            release("1.3.0"),
            release("1.4.0", title = "Duplicate"),
        )

        val snapshot = WhatsNewSnapshotBuilder.build(
            releases = releases,
            currentVersion = "1.2.0",
        )

        assertEquals(listOf("1.4.0", "1.3.0", "1.2.0"), snapshot.releases.map(WhatsNewRelease::version))
        assertEquals(listOf("1.4.0", "1.3.0"), snapshot.sinceYourVersion.map(WhatsNewRelease::version))
    }

    @Test
    fun prereleaseVersionsAreOrderedBeforeStableAndReleaseNotesAreCleaned() {
        val releases = listOf(
            release("1.0.0", notes = "1.0.0"),
            release(
                "1.0.0-beta.2",
                notes = "<h2>Fixes</h2><ul><li><strong>Fixed</strong> [playback](https://example.com)</li><li>Removed `debug`.</li></ul>",
            ),
            release("1.0.0-beta.1", notes = "Older"),
        )

        val snapshot = WhatsNewSnapshotBuilder.build(
            releases = releases,
            currentVersion = "1.0.0-beta.1",
        )

        assertEquals(
            listOf("1.0.0", "1.0.0-beta.2", "1.0.0-beta.1"),
            snapshot.releases.map(WhatsNewRelease::version),
        )
        assertEquals(listOf("1.0.0", "1.0.0-beta.2"), snapshot.sinceYourVersion.map(WhatsNewRelease::version))
        val cleaned = WhatsNewSnapshotBuilder.cleanReleaseNotes(releases[1].notes)
        assertFalse(cleaned.contains("`"))
        assertFalse(cleaned.contains("<"))
        assertTrue(cleaned.contains("• Fixed playback"))
        assertTrue(cleaned.contains("• Removed debug"))
    }

    private fun release(version: String, title: String = version, notes: String = "Notes for $version"): WhatsNewRelease =
        WhatsNewRelease(
            version = version,
            title = title,
            notes = notes,
            publishedAt = null,
            releaseUrl = "https://github.com/luqmanfadlli/NuvioMobile-Enhanced/releases/tag/$version",
        )
}
