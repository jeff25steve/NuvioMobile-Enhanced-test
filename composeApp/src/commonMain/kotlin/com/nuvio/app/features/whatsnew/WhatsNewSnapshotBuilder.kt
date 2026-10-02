package com.nuvio.app.features.whatsnew

import com.nuvio.app.features.updater.VersionUtils

private val conventionalCommitPattern = Regex(
    """^(feat|fix|perf|refactor|docs|test|ci|build|chore|revert|i18n)(?:\\(([^)]*)\\))?(!)?(?::\\s*|\\s+)?(.+)$""",
    RegexOption.IGNORE_CASE,
)
private val authorSuffixPattern = Regex("""\\s+@[A-Za-z0-9_.-]+\\s*$""")
private val markdownLinkPattern = Regex("""\\[([^\\]]+)]\\([^)]*\\)""")
private val markdownTagPattern = Regex("""<[^>]*>""")
private val camelCasePattern = Regex("""([a-z])([A-Z])""")
private val markdownHeadingPattern = Regex("""#{1,6}\\s+.*""")
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
            .replace(Regex("""(?i)<br\\s*/?>"""), "\\n")
            .replace(Regex("""(?i)<li\\b[^>]*>"""), "\\n• ")
            .replace(Regex("""(?i)</li>"""), "\\n")
            .replace(markdownLinkPattern, "$1")
            .replace(markdownTagPattern, "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("\\r", "")
            .lines()
            .map(String::trim)
            .filter(String::isNotBlank)

        val structured = mutableListOf<WhatsNewNote>()
        val unstructured = mutableListOf<String>()

        lines.forEach { line ->
            val candidate = line
                .removePrefix("•")
                .trim()
                .removePrefix("-")
                .trim()
                .removePrefix("*")
                .trim()
                .removePrefix("+")
                .trim()

            if (candidate.isBlank() || candidate.matches(markdownHeadingPattern)) return@forEach
            if (candidate.matches(markdownRulePattern)) return@forEach

            val withoutAuthor = candidate.replace(authorSuffixPattern, "").trim()
            if (withoutAuthor.isBlank()) return@forEach

            val match = conventionalCommitPattern.matchEntire(withoutAuthor)
            if (match != null) {
                val type = match.groupValues[1].lowercase()
                val scope = match.groupValues[2].takeIf(String::isNotBlank)
                val message = match.groupValues[4].trim().ifBlank {
                    scope?.let(::humanizeIdentifier) ?: "Release update"
                }
                structured += WhatsNewNote(
                    category = categoryFor(type),
                    text = message.replaceFirstChar { it.uppercase() },
                )
            } else {
                unstructured += withoutAuthor
            }
        }

        val deduplicatedStructured = structured
            .distinctBy { it.category to it.text }
            .sortedBy { categoryOrder(it.category) }

        if (deduplicatedStructured.isEmpty()) {
            val fallback = unstructured.distinct().joinToString(separator = "\\n")
            return if (fallback.isBlank()) {
                emptyList()
            } else {
                listOf(WhatsNewNote(WhatsNewNoteCategory.OTHER, fallback))
            }
        }

        return buildList {
            addAll(deduplicatedStructured)
            unstructured.distinct().forEach { text ->
                add(WhatsNewNote(WhatsNewNoteCategory.OTHER, text))
            }
        }
    }

    private fun categoryFor(type: String): WhatsNewNoteCategory = when (type) {
        "feat" -> WhatsNewNoteCategory.FEATURES
        "fix", "revert" -> WhatsNewNoteCategory.FIXES
        "perf" -> WhatsNewNoteCategory.PERFORMANCE
        "i18n" -> WhatsNewNoteCategory.LOCALIZATION
        else -> WhatsNewNoteCategory.OTHER
    }

    private fun categoryOrder(category: WhatsNewNoteCategory): Int = when (category) {
        WhatsNewNoteCategory.FEATURES -> 0
        WhatsNewNoteCategory.FIXES -> 1
        WhatsNewNoteCategory.PERFORMANCE -> 2
        WhatsNewNoteCategory.LOCALIZATION -> 3
        WhatsNewNoteCategory.OTHER -> 4
    }

    private fun humanizeIdentifier(raw: String): String =
        raw
            .replace('-', ' ')
            .replace('_', ' ')
            .replace(camelCasePattern, "$1 $2")
            .trim()
            .replaceFirstChar { it.uppercase() }
}
