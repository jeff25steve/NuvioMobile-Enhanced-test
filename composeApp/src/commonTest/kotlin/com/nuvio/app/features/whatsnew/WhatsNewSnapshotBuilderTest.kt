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

    private fun release(version: String, title: String = version): WhatsNewRelease =
        WhatsNewRelease(
            version = version,
            title = title,
            notes = "Notes for $version",
            publishedAt = null,
            releaseUrl = "https://github.com/luqmanfadlli/NuvioMobile-Enhanced/releases/tag/$version",
        )
}
