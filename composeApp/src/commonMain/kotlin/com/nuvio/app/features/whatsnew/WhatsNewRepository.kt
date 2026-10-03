package com.nuvio.app.features.whatsnew

import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.updater.VersionUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val GITHUB_API_BASE = "https://api.github.com"
private const val GITHUB_OWNER = "luqmanfadlli"
private const val GITHUB_REPO = "NuvioMobile-Enhanced"
private const val PAGE_SIZE = 20
private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L

@Serializable
private data class GitHubReleaseDto(
    @SerialName("tag_name") val tagName: String? = null,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("html_url") val htmlUrl: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
)

@Serializable
private data class CachePayload(
    val fetchedAtEpochMs: Long,
    val releases: List<GitHubReleaseDto>,
)

data class WhatsNewNote(val text: String, val contributor: String?)

data class WhatsNewRelease(
    val tag: String,
    val title: String,
    val notes: List<WhatsNewNote>,
    val publishedAt: String?,
    val isPrerelease: Boolean,
    val url: String?,
) {
    val isNewerThanCurrent: Boolean
        get() = VersionUtils.isRemoteNewer(tag, AppVersionConfig.VERSION_NAME)
}

data class WhatsNewUiState(
    val releases: List<WhatsNewRelease> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val nextPage: Int = 2,
    val errorMessage: String? = null,
    val lastCheckedEpochMs: Long? = null,
    val hasCachedData: Boolean = false,
)

internal object WhatsNewRepository {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val _uiState = MutableStateFlow(WhatsNewUiState())
    val uiState: StateFlow<WhatsNewUiState> = _uiState.asStateFlow()

    private var initialized = false
    private var refreshInFlight = false

    suspend fun load(forceRefresh: Boolean = false) {
        if (refreshInFlight) return
        if (!initialized) {
            initialized = true
            loadCached()?.let { cached ->
                _uiState.value = WhatsNewUiState(
                    releases = cached.releases.map(::mapRelease),
                    hasMore = cached.releases.size >= PAGE_SIZE,
                    nextPage = 2,
                    lastCheckedEpochMs = cached.fetchedAtEpochMs,
                    hasCachedData = true,
                )
            }
        }

        val cachedTime = _uiState.value.lastCheckedEpochMs
        val cacheFresh = cachedTime != null &&
            System.currentTimeMillis() - cachedTime < CACHE_TTL_MS
        if (!forceRefresh && cacheFresh) return

        refreshInFlight = true
        _uiState.update {
            it.copy(
                isRefreshing = it.releases.isNotEmpty(),
                isLoading = it.releases.isEmpty(),
                errorMessage = null,
            )
        }
        try {
            val releases = fetchPage(1)
            currentCoroutineContext().ensureActive()
            val now = System.currentTimeMillis()
            saveCached(CachePayload(now, releases))
            _uiState.value = WhatsNewUiState(
                releases = releases.map(::mapRelease),
                hasMore = releases.size >= PAGE_SIZE,
                nextPage = 2,
                lastCheckedEpochMs = now,
                hasCachedData = true,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isRefreshing = false,
                    errorMessage = error.message ?: "Unable to load release history.",
                )
            }
        } finally {
            refreshInFlight = false
        }
    }

    suspend fun loadMore() {
        val state = _uiState.value
        if (state.isLoadingMore || !state.hasMore || refreshInFlight) return
        _uiState.update { it.copy(isLoadingMore = true, errorMessage = null) }
        try {
            val page = state.nextPage
            val releases = fetchPage(page)
            currentCoroutineContext().ensureActive()
            _uiState.update {
                it.copy(
                    releases = it.releases + releases.map(::mapRelease),
                    isLoadingMore = false,
                    hasMore = releases.size >= PAGE_SIZE,
                    nextPage = page + 1,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            _uiState.update {
                it.copy(
                    isLoadingMore = false,
                    errorMessage = error.message ?: "Unable to load more releases.",
                )
            }
        }
    }

    private suspend fun fetchPage(page: Int): List<GitHubReleaseDto> {
        val response = httpRequestRaw(
            method = "GET",
            url = "$GITHUB_API_BASE/repos/$GITHUB_OWNER/$GITHUB_REPO/releases?per_page=$PAGE_SIZE&page=$page",
            headers = mapOf(
                "Accept" to "application/vnd.github+json",
                "User-Agent" to "NuvioMobile",
            ),
            body = "",
        )
        currentCoroutineContext().ensureActive()
        if (response.status !in 200..299) {
            error(
                when (response.status) {
                    403, 429 -> "GitHub is temporarily rate-limiting release history. Try again later."
                    else -> "GitHub release history returned HTTP ${response.status}.",
                },
            )
        }
        return json.decodeFromString<List<GitHubReleaseDto>>(response.body)
            .filter { !it.draft && it.tagName != null }
    }

    private fun mapRelease(dto: GitHubReleaseDto): WhatsNewRelease {
        val tag = dto.tagName!!.trim()
        val url = dto.htmlUrl?.takeIf {
            it.startsWith("https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/")
        }
        return WhatsNewRelease(
            tag = tag,
            title = dto.name?.trim().takeUnless { it.isNullOrBlank() } ?: tag,
            notes = parseNotes(dto.body.orEmpty()),
            publishedAt = dto.publishedAt,
            isPrerelease = dto.prerelease,
            url = url,
        )
    }

    private fun parseNotes(body: String): List<WhatsNewNote> =
        body.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") && it != "---" }
            .map { line ->
                val bullet = line.removePrefix("- ").removePrefix("* ").trim()
                val match = Regex("""\s+@([A-Za-z0-9_.-]+)$""").find(bullet)
                val contributor = match?.groupValues?.getOrNull(1)
                val text = match?.let { bullet.removeRange(it.range).trim() } ?: bullet
                WhatsNewNote(text, contributor)
            }
            .filter { it.text.isNotBlank() }

    private fun loadCached(): CachePayload? =
        WhatsNewStorage.loadPayload()?.let {
            runCatching { json.decodeFromString<CachePayload>(it) }.getOrNull()
        }

    private fun saveCached(payload: CachePayload) {
        runCatching { WhatsNewStorage.savePayload(json.encodeToString(payload)) }
    }
}
