package com.nuvio.app.features.whatsnew

import com.nuvio.app.features.updater.VersionUtils

private val conventionalCommitPattern = Regex(
    """^(feat|fix|perf|refactor|docs|test|ci|build|chore|revert|i18n)(?:\(([^)]*)\))?(?:!)?(?::\s*|\s+).+$""",
    RegexOption.IGNORE_CASE,
)
private val authorSuffixPattern = Regex("""\s+@([A-Za-z0-9_.-]+)\s*$""")
private val markdownLinkPattern = Regex("""\[([^\]]+)]\([^)]*\)""")
private val markdownTagPattern = Regex("""<[^>]*>""")
private val markdownHeadingPattern = Regex("""#{1,6}\s+.*""")
private val markdownRulePattern = Regex("""-{3,}""")

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
            .replace(Regex("""(?i)<br\s*/?>"""), "\n")
            .replace(Regex("""(?i)<li\b[^>]*>"""), "\n• ")
            .replace(Regex("""(?i)</li>"""), "\n")
            .replace(markdownLinkPattern, "$1")
            .replace(markdownTagPattern, "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("\r", "")
            .lines()
            .map(String::trim)
            .filter(String::isNotBlank)

        return lines.mapNotNull { line ->
            val candidate = line
                .removePrefix("•")
                .trim()
                .removePrefix("-")
                .trim()
                .removePrefix("*")
                .trim()
                .removePrefix("+")
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
        }.distinctBy { it.text to it.authorLogin }
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
