package com.nuvio.app.features.whatsnew

import com.nuvio.app.features.updater.VersionUtils

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

    fun cleanReleaseNotes(raw: String): String =
        raw
            .replace(Regex("""(?i)<br\s*/?>"""), "\n")
            .replace(Regex("""(?i)<li\b[^>]*>"""), "\n• ")
            .replace(Regex("""(?i)</li>"""), "\n")
            .replace(Regex("""(?i)</?(?:p|div|h[1-6]|ul|ol|blockquote|pre|table|thead|tbody|tr|th|td)\b[^>]*>"""), "\n")
            .replace(Regex("<[^>]*>"), "")
            .replace(Regex("""(?m)^[ \t]*#{1,6}[ \t]*"""), "")
            .replace(Regex("""\[([^\]]+)\]\([^\)]+\)"""), "$1")
            .replace(Regex("""\*\*([^*]+)\*\*"""), "$1")
            .replace(Char(96).toString(), "")
            .replace(Regex("""(?m)^[ \t]*[-*+][ \t]+"""), "• ")
            .replace(Regex("""(?m)^[ \t]*---+[ \t]*$"""), "")
            .replace(Regex("""[ \t]+\n"""), "\n")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .trim()
}
