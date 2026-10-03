package com.nuvio.app.features.whatsnew

import com.nuvio.app.features.updater.VersionUtils

private val conventionalCommitPattern = Regex(
    """^(feat|fix|perf|refactor|docs|test|ci|build|chore|revert|i18n)(?:\(([^)]*)\))?(?:!)?(?::\s*|\s+).+$""",
    RegexOption.IGNORE_CASE,
)
private val authorSuffixPattern = Regex("""\s+@([A-Za-z0-9_.-]+)\s*$""")
private val markdownLinkPattern = Regex("""\[([^\]]+)]\([^)]*\)""")
private val htmlLineBreakPattern = Regex(
    """(?i)<(?:br\s*/?|/?(?:p|div|ul|ol)\b[^>]*>)""",
)
private val htmlListItemPattern = Regex("""(?i)<li\b[^>]*>""")
private val htmlListItemEndPattern = Regex("""(?i)</li>""")
private val htmlFormattingTagPattern = Regex(
    """(?i)</?(?:a|b|strong|i|em|code|del|s|u|small|sub|sup)\b[^>]*>""",
)
private val markdownHeadingPattern = Regex("""#{1,6}\s+.*""")
private val markdownBlockquotePattern = Regex("""^\s*>+\s?""")
private val markdownInlineCodePattern = Regex("""`+([^`]+)`+""")
private val markdownStrikePattern = Regex("""~~(.+?)~~""")
private val markdownRulePattern = Regex("""-{3,}\s*$""")
private val listMarkerPattern = Regex("""^[•*+-]\s+""")

internal object WhatsNewSnapshotBuilder {
    fun build(
        releases: List<WhatsNewRelease>,
        currentVersion: String,
    ): WhatsNewSnapshot {
        val normalizedReleases = releases
            .mapNotNull { release ->
                VersionUtils.parse(release.version)?.let { version ->
                    version to release.copy(version = VersionUtils.normalize(release.version))
                }
            }
            .sortedByDescending { it.first }
            .distinctBy { it.first }
            .map { it.second }

        val current = VersionUtils.parse(currentVersion)
        val currentIndex = current?.let { target ->
            normalizedReleases.indexOfFirst { release ->
                VersionUtils.parse(release.version) == target
            }
        } ?: -1

        return if (currentIndex >= 0) {
            WhatsNewSnapshot(
                releases = normalizedReleases,
                currentVersion = VersionUtils.normalize(currentVersion),
                sinceYourVersion = normalizedReleases.take(currentIndex),
                hasCompleteSinceVersion = true,
            )
        } else {
            WhatsNewSnapshot(
                releases = normalizedReleases,
                currentVersion = VersionUtils.normalize(currentVersion),
                sinceYourVersion = emptyList(),
                hasCompleteSinceVersion = false,
            )
        }
    }

    fun cleanReleaseNotes(raw: String): List<WhatsNewNote> {
        val lines = raw
            .replace(htmlLineBreakPattern, "\n")
            .replace(htmlListItemPattern, "\n• ")
            .replace(htmlListItemEndPattern, "\n")
            .replace(markdownLinkPattern, "$1")
            .replace(htmlFormattingTagPattern, "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("\r", "")
            .lines()
            .map(String::trim)
            .filter(String::isNotBlank)

        return lines.mapNotNull { line ->
            val candidate = line
                .replaceFirst(markdownBlockquotePattern, "")
                .replace(markdownInlineCodePattern, "$1")
                .replace(markdownStrikePattern, "$1")
                .replace("**", "")
                .replace("__", "")
                .trim()
                .replaceFirst(listMarkerPattern, "")
                .trim()

            if (candidate.isBlank() ||
                candidate.matches(markdownHeadingPattern) ||
                candidate.matches(markdownRulePattern)
            ) {
                return@mapNotNull null
            }

            val authorMatch = authorSuffixPattern.find(candidate)
            val authorLogin = authorMatch?.groupValues?.getOrNull(1)
            val exactWording = authorMatch
                ?.let { candidate.removeRange(it.range).trimEnd() }
                ?: candidate

            if (exactWording.isBlank()) return@mapNotNull null

            WhatsNewNote(
                category = categoryFor(exactWording),
                text = exactWording,
                authorLogin = authorLogin,
            )
        }
    }

    private fun categoryFor(text: String): WhatsNewNoteCategory {
        val type = conventionalCommitPattern.matchEntire(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.lowercase()

        return when (type) {
            "feat" -> WhatsNewNoteCategory.FEATURES
            "fix", "revert" -> WhatsNewNoteCategory.FIXES
            "perf" -> WhatsNewNoteCategory.PERFORMANCE
            "i18n" -> WhatsNewNoteCategory.LOCALIZATION
            else -> WhatsNewNoteCategory.OTHER
        }
    }
}
