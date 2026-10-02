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
        assertEquals(
            listOf("1.4.0", "1.3.0"),
            snapshot.sinceYourVersion.map(WhatsNewRelease::version),
        )
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
        val snapshot = WhatsNewSnapshotBuilder.build(
            releases = listOf(
                release("1.4.0"),
                release("1.3.0"),
                release("1.2.0"),
            ),
            currentVersion = "not-a-version",
        )

        assertFalse(snapshot.hasCompleteSinceVersion)
        assertTrue(snapshot.sinceYourVersion.isEmpty())
    }

    @Test
    fun emptyReleaseListProducesAnIncompleteSnapshotWithoutFalseUpdates() {
        val snapshot = WhatsNewSnapshotBuilder.build(
            releases = emptyList(),
            currentVersion = "1.2.0",
        )

        assertTrue(snapshot.releases.isEmpty())
        assertFalse(snapshot.hasCompleteSinceVersion)
        assertTrue(snapshot.sinceYourVersion.isEmpty())
    }

    @Test
    fun cleanReleaseNotesDecodesCommonHtmlEntitiesWithoutRewritingContributionText() {
        val cleaned = WhatsNewSnapshotBuilder.cleanReleaseNotes(
            "- fix(player): handle \"quoted\" title &amp; subtitle @alice",
        )

        assertEquals(1, cleaned.size)
        assertEquals(
            "fix(player): handle \"quoted\" title & subtitle",
            cleaned.single().text,
        )
        assertEquals("alice", cleaned.single().authorLogin)
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

        assertEquals(
            listOf("1.4.0", "1.3.0", "1.2.0"),
            snapshot.releases.map(WhatsNewRelease::version),
        )
        assertEquals(
            listOf("1.4.0", "1.3.0"),
            snapshot.sinceYourVersion.map(WhatsNewRelease::version),
        )
    }

    @Test
    fun cleanReleaseNotesPreservesAngleBracketTextThatIsNotHtml() {
        val cleaned = WhatsNewSnapshotBuilder.cleanReleaseNotes(
            "- fix(player): support <TV> channel names",
        )

        assertEquals(
            "fix(player): support <TV> channel names",
            cleaned.single().text,
        )
    }

    @Test
    fun cleanReleaseNotesDoesNotStripAHyphenThatIsPartOfTheText() {
        val cleaned = WhatsNewSnapshotBuilder.cleanReleaseNotes(
            "-not-a-list-item",
        )

        assertEquals(
            "-not-a-list-item",
            cleaned.single().text,
        )
    }

    @Test
    fun cleanReleaseNotesPreservesRepeatedContributionLinesInOrder() {
        val cleaned = WhatsNewSnapshotBuilder.cleanReleaseNotes(
            """
            - fix(player): update subtitle handling @alice
            - fix(player): update subtitle handling @alice
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                "fix(player): update subtitle handling",
                "fix(player): update subtitle handling",
            ),
            cleaned.map(WhatsNewNote::text),
        )
        assertEquals(listOf("alice", "alice"), cleaned.map(WhatsNewNote::authorLogin))
    }

    @Test
    fun cleanReleaseNotesPreservesExactContributionWordingAndCredits() {
        val cleaned = WhatsNewSnapshotBuilder.cleanReleaseNotes(
            """
            - feat(player): add smarter subtitle startup @alice
            - fix(home): fix hero alignment [details](https://github.com/example/project) @bob
            - perf(profile): reduce duplicate API work @carol
            - i18n(el): improve Greek wording @dora
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                WhatsNewNoteCategory.FEATURES,
                WhatsNewNoteCategory.FIXES,
                WhatsNewNoteCategory.PERFORMANCE,
                WhatsNewNoteCategory.LOCALIZATION,
            ),
            cleaned.map(WhatsNewNote::category),
        )
        assertEquals(
            "feat(player): add smarter subtitle startup",
            cleaned[0].text,
        )
        assertEquals("alice", cleaned[0].authorLogin)
        assertEquals(
            "fix(home): fix hero alignment details",
            cleaned[1].text,
        )
        assertEquals("bob", cleaned[1].authorLogin)
        assertEquals("perf(profile): reduce duplicate API work", cleaned[2].text)
        assertEquals("carol", cleaned[2].authorLogin)
    }

    private fun release(
        version: String,
        title: String = version,
        notes: List<WhatsNewNote> = listOf(
            WhatsNewNote(
                category = WhatsNewNoteCategory.OTHER,
                text = "Notes for " + version,
                authorLogin = null,
            ),
        ),
    ): WhatsNewRelease =
        WhatsNewRelease(
            version = version,
            title = title,
            notes = notes,
            publishedAt = null,
            releaseUrl = "https://github.com/luqmanfadlli/NuvioMobile-Enhanced/releases/tag/" + version,
        )
}
