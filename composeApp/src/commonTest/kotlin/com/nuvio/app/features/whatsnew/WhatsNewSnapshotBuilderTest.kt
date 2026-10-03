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
    fun futureStablePromotionAndBetaReleasesAreAllShownAsNewForCurrentBeta() {
        val snapshot = WhatsNewSnapshotBuilder.build(
            releases = listOf(
                release("0.5.7", title = "0.5.7"),
                release("0.5.7-beta.2", title = "0.5.7 Beta 2"),
                release("0.5.7-beta.1", title = "0.5.7 Beta 1"),
                release("0.5.6-beta.3", title = "0.5.6 Beta 3"),
            ),
            currentVersion = "0.5.6-beta.3",
        )

        assertTrue(snapshot.hasCompleteSinceVersion)
        assertEquals(
            listOf("0.5.7", "0.5.7-beta.2", "0.5.7-beta.1"),
            snapshot.sinceYourVersion.map(WhatsNewRelease::version),
        )
    }

    @Test
    fun currentBetaRemainsUpToDateWhenItIsNewestAvailableRelease() {
        val snapshot = WhatsNewSnapshotBuilder.build(
            releases = listOf(
                release("0.5.7-beta.2"),
                release("0.5.7-beta.1"),
                release("0.5.6"),
            ),
            currentVersion = "0.5.7-beta.2",
        )

        assertTrue(snapshot.hasCompleteSinceVersion)
        assertTrue(snapshot.sinceYourVersion.isEmpty())
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
    fun cleanReleaseNotesRemovesCommonMarkdownPresentationSyntax() {
        val cleaned = WhatsNewSnapshotBuilder.cleanReleaseNotes(
            """
            > **💡 Note**
            - fix(player): use `hardware acceleration` instead @alice
            - ~~old wording~~ replaced with the new wording
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                "💡 Note",
                "fix(player): use hardware acceleration instead",
                "old wording replaced with the new wording",
            ),
            cleaned.map(WhatsNewNote::text),
        )
        assertEquals("alice", cleaned[1].authorLogin)
    }

    @Test
    fun cleanReleaseNotesGroupsBlockquotesBeforeRemovingListMarkers() {
        val cleaned = WhatsNewSnapshotBuilder.cleanReleaseNotes(
            "> - feat(home): add a cleaner hero",
        )

        assertEquals("feat(home): add a cleaner hero", cleaned.single().text)
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

    @Test
    fun formatReleaseNoteForDisplayNormalizesSentenceStartWithoutRewritingMidSentenceAdd() {
        assertEquals(
            "Added mobile background selection",
            WhatsNewSnapshotBuilder.formatReleaseNoteForDisplay("feat(home): add mobile background selection"),
        )
        assertEquals(
            "Added TVDB anime ID preference to avoid per-season IMDB splits (#1952)",
            WhatsNewSnapshotBuilder.formatReleaseNoteForDisplay("Add TVDB anime ID preference to avoid per-season IMDB splits (#1952)"),
        )
        assertEquals(
            "Open iOS playback in fullscreen",
            WhatsNewSnapshotBuilder.formatReleaseNoteForDisplay("fix(trailer): open ios playback in fullscreen"),
        )
        assertEquals(
            "Allows users to add profiles",
            WhatsNewSnapshotBuilder.formatReleaseNoteForDisplay("Allows users to add profiles"),
        )
    }

    @Test
    fun cleanReleaseNotesPreservesAllItemsFromTheThirteenChangeRelease() {
        val body = """
            - fix(home): match continue watching badge colors to tv @tapframe
            - perf(navigation): reduce root tab switch stalls @tapframe
            - Add TVDB anime ID preference to avoid per-season IMDB splits (#1952) @skoruppa
            - Revert "perf(navigation): reduce tab switch animation stalls" @tapframe
            - fix(player): keep android landscape lock during exit @tapframe
            - fix(profiles): add active profile toast and back button @tapframe
            - fix(profiles): prevent selecting the active profile @tapframe
            - fix(player): hide addons with no streams @tapframe
            - fix(player): start playback without waiting for addon subtitles (#1949) @halibiram
            - fix(trailer): open ios playback in fullscreen @tapframe
            - Fix anime skip mapping, subtitle language detection, watched badges and Polish translations (#1943) @skoruppa
            - fix(library): restore LaunchedEffect import after the 0.4.21 merge @luqmanfadlli
            - feat(streams): add pinned stream sources @luqmanfadlli
        """.trimIndent()

        val cleaned = WhatsNewSnapshotBuilder.cleanReleaseNotes(body)

        assertEquals(13, cleaned.size)
        assertEquals(
            "Added pinned stream sources",
            WhatsNewSnapshotBuilder.formatReleaseNoteForDisplay(cleaned.last().text),
        )
        assertEquals(
            "Added TVDB anime ID preference to avoid per-season IMDB splits (#1952)",
            WhatsNewSnapshotBuilder.formatReleaseNoteForDisplay(cleaned[2].text),
        )
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
