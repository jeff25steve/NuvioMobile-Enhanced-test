package com.nuvio.app.features.streams

import com.nuvio.app.core.build.AppFeaturePolicy

object StreamAutoPlaySelector {

    fun orderAddonStreams(
        groups: List<AddonStreamGroup>,
        installedOrder: List<String>,
        preferredGroupIds: Set<String> = emptySet(),
    ): List<AddonStreamGroup> {
        if (groups.isEmpty()) return groups

        val addonRankByName: Map<String, Int> = if (installedOrder.isEmpty()) {
            emptyMap()
        } else {
            HashMap<String, Int>(installedOrder.size).apply {
                installedOrder.forEachIndexed { index, addonName ->
                    if (addonName !in this) this[addonName] = index
                }
            }
        }
        val preferredEntries = if (preferredGroupIds.isEmpty()) null else ArrayList<AddonStreamGroup>()
        val directDebridEntries = ArrayList<AddonStreamGroup>()
        val unorderedEntries = if (installedOrder.isEmpty()) ArrayList<AddonStreamGroup>() else null
        val addonEntries = if (installedOrder.isEmpty()) null else ArrayList<AddonStreamGroup>()
        val pluginEntries = if (installedOrder.isEmpty()) null else ArrayList<AddonStreamGroup>()

        for (group in groups) {
            if (preferredGroupIds.isNotEmpty() && group.addonId in preferredGroupIds) {
                preferredEntries?.add(group)
                continue
            }

            val isDirectDebrid = group.addonId.startsWith("debrid:") ||
                group.streams.any { stream -> stream.isAddonDebridCandidate && stream.isDirectDebridStream }
            when {
                isDirectDebrid -> directDebridEntries.add(group)
                unorderedEntries != null -> unorderedEntries.add(group)
                addonRankByName.containsKey(group.addonName) -> addonEntries?.add(group)
                else -> pluginEntries?.add(group)
            }
        }

        val result = ArrayList<AddonStreamGroup>(groups.size)
        if (preferredEntries != null) result.addAll(preferredEntries)
        result.addAll(directDebridEntries)
        if (unorderedEntries != null) {
            result.addAll(unorderedEntries)
        } else {
            val orderedAddons = addonEntries.orEmpty().sortedBy { group ->
                addonRankByName.getValue(group.addonName)
            }
            result.addAll(orderedAddons)
            result.addAll(pluginEntries.orEmpty())
        }
        return result
    }

    fun selectAutoPlayStream(
        streams: List<StreamItem>,
        mode: StreamAutoPlayMode,
        regexPattern: String,
        source: StreamAutoPlaySource,
        installedAddonNames: Set<String>,
        selectedAddons: Set<String>,
        selectedPlugins: Set<String>,
        preferredBingeGroup: String? = null,
        preferBingeGroupInSelection: Boolean = false,
        bingeGroupOnly: Boolean = false,
        debridEnabled: Boolean = true,
        activeResolverProviderId: String? = null,
    ): StreamItem? =
        evaluateAutoPlayStream(
            streams = streams,
            mode = mode,
            regexPattern = regexPattern,
            source = source,
            installedAddonNames = installedAddonNames,
            selectedAddons = selectedAddons,
            selectedPlugins = selectedPlugins,
            preferredBingeGroup = preferredBingeGroup,
            preferBingeGroupInSelection = preferBingeGroupInSelection,
            bingeGroupOnly = bingeGroupOnly,
            debridEnabled = debridEnabled,
            activeResolverProviderId = activeResolverProviderId,
        ).stream

    fun evaluateAutoPlayStream(
        streams: List<StreamItem>,
        mode: StreamAutoPlayMode,
        regexPattern: String,
        source: StreamAutoPlaySource,
        installedAddonNames: Set<String>,
        selectedAddons: Set<String>,
        selectedPlugins: Set<String>,
        preferredBingeGroup: String? = null,
        preferBingeGroupInSelection: Boolean = false,
        bingeGroupOnly: Boolean = false,
        debridEnabled: Boolean = true,
        activeResolverProviderId: String? = null,
    ): StreamAutoPlayEvaluation {
        if (streams.isEmpty()) return StreamAutoPlayEvaluation()

        val candidateStreams = streams.filter { stream ->
            val isInSource = when (source) {
                StreamAutoPlaySource.ALL_SOURCES -> true
                StreamAutoPlaySource.INSTALLED_ADDONS_ONLY ->
                    stream.serverTarget != null || stream.addonName in installedAddonNames
                StreamAutoPlaySource.ENABLED_PLUGINS_ONLY ->
                    stream.addonName !in installedAddonNames
            }
            if (!isInSource) {
                false
            } else if (stream.serverTarget != null) {
                true
            } else if (stream.addonName in installedAddonNames) {
                selectedAddons.isEmpty() || stream.addonName in selectedAddons
            } else {
                selectedPlugins.isEmpty() || stream.addonName in selectedPlugins
            }
        }
        if (candidateStreams.isEmpty()) return StreamAutoPlayEvaluation()
        if (mode == StreamAutoPlayMode.MANUAL && !bingeGroupOnly) {
            return StreamAutoPlayEvaluation()
        }

        val targetBingeGroup = preferredBingeGroup?.trim().orEmpty()
        val preferredStream = if (preferBingeGroupInSelection && targetBingeGroup.isNotEmpty()) {
            candidateStreams.firstOrNull { stream ->
                stream.behaviorHints.bingeGroup == targetBingeGroup &&
                    stream.isAutoPlayable(debridEnabled, activeResolverProviderId)
            }
        } else {
            null
        }
        if (bingeGroupOnly) {
            return StreamAutoPlayEvaluation(
                stream = preferredStream,
                readyStreams = preferredStream?.let(::listOf).orEmpty(),
                hasPendingDebridCandidate = preferredStream == null &&
                    preferBingeGroupInSelection &&
                    targetBingeGroup.isNotEmpty() &&
                    candidateStreams.any { stream ->
                        stream.behaviorHints.bingeGroup == targetBingeGroup &&
                            stream.isPendingDebridAutoPlay(debridEnabled, activeResolverProviderId)
                    },
            )
        }
        val matchingStreams = when (mode) {
            StreamAutoPlayMode.MANUAL -> emptyList()
            StreamAutoPlayMode.FIRST_STREAM -> candidateStreams
            StreamAutoPlayMode.REGEX_MATCH -> {
                val pattern = regexPattern.trim()

                val userRegex = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull()
                    ?: return StreamAutoPlayEvaluation()

                val exclusionMatches = Regex("\\(\\?![^)]*?\\(([^)]+)\\)").findAll(pattern)

                val exclusionWords = exclusionMatches
                    .flatMap { match -> match.groupValues[1].split("|") }
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .toList()

                val excludeRegex = if (exclusionWords.isNotEmpty()) {
                    Regex(
                        "\\b(${exclusionWords.joinToString("|") { Regex.escape(it) }})\\b",
                        RegexOption.IGNORE_CASE,
                    )
                } else null

                candidateStreams.filter { stream ->
                    val url = stream.playableDirectUrl.orEmpty()

                    val searchableText = buildString {
                        append(stream.addonName).append(' ')
                        append(stream.name.orEmpty()).append(' ')
                        append(stream.streamLabel).append(' ')
                        append(stream.description.orEmpty()).append(' ')
                        append(url)
                    }

                    if (!userRegex.containsMatchIn(searchableText)) return@filter false

                    if (excludeRegex != null && excludeRegex.containsMatchIn(searchableText)) {
                        return@filter false
                    }

                    true
                }
            }
        }
        if (matchingStreams.isEmpty() && preferredStream == null) return StreamAutoPlayEvaluation()

        val readyStreams = ArrayList<StreamItem>()
        preferredStream?.let(readyStreams::add)
        for (stream in matchingStreams) {
            if (
                stream.isAutoPlayable(debridEnabled, activeResolverProviderId) &&
                stream != preferredStream
            ) {
                readyStreams.add(stream)
            }
        }
        val selected = readyStreams.firstOrNull()
        if (selected != null) {
            return StreamAutoPlayEvaluation(
                stream = selected,
                readyStreams = readyStreams,
            )
        }

        return StreamAutoPlayEvaluation(
            readyStreams = readyStreams,
            hasPendingDebridCandidate = matchingStreams.any {
                it.isPendingDebridAutoPlay(debridEnabled, activeResolverProviderId)
            },
        )
    }

    private fun StreamItem.isAutoPlayable(
        debridEnabled: Boolean,
        activeResolverProviderId: String?,
    ): Boolean =
        playableDirectUrl != null ||
            serverTarget != null ||
            (
                AppFeaturePolicy.p2pEnabled &&
                    needsLocalDebridResolve &&
                    p2pInfoHash != null &&
                    !isPendingDebridAutoPlay(debridEnabled, activeResolverProviderId)
            ) ||
            (debridEnabled && isAddonDebridCandidate && isReadyDebridAutoPlay(activeResolverProviderId))

    private fun StreamItem.isReadyDebridAutoPlay(activeResolverProviderId: String?): Boolean =
        when {
            isDirectDebridStream -> clientResolve?.service.matchesResolver(activeResolverProviderId)
            isCachedDebridTorrentStream -> debridCacheStatus?.providerId.matchesResolver(activeResolverProviderId)
            else -> false
        }

    private fun StreamItem.isPendingDebridAutoPlay(
        debridEnabled: Boolean,
        activeResolverProviderId: String?,
    ): Boolean {
        if (!debridEnabled || !isInstalledAddonStream || !needsLocalDebridResolve) return false
        if (!debridCacheStatus?.providerId.matchesResolver(activeResolverProviderId)) return false
        val state = debridCacheStatus?.state
        return state == null || state == StreamDebridCacheState.CHECKING
    }

    private fun String?.matchesResolver(activeResolverProviderId: String?): Boolean {
        val active = activeResolverProviderId?.trim().orEmpty()
        return active.isBlank() || this == null || equals(active, ignoreCase = true)
    }
}

data class StreamAutoPlayEvaluation(
    val stream: StreamItem? = null,
    val readyStreams: List<StreamItem> = emptyList(),
    val hasPendingDebridCandidate: Boolean = false,
)
