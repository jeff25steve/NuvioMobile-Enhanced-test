package com.nuvio.app.features.catalog

import com.nuvio.app.core.poster.CustomPosterUrlRepository
import com.nuvio.app.core.poster.withCustomPosterUrls
import com.nuvio.app.features.collection.CollectionRepository
import com.nuvio.app.features.details.MoreLikeThisSource
import com.nuvio.app.features.simkl.SimklRelatedRepository
import com.nuvio.app.features.details.MoreLikeThisPage
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.tmdb.TmdbMetadataService
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.features.trakt.TraktRelatedRepository
import com.nuvio.app.features.collection.TmdbCollectionSourceResolver
import com.nuvio.app.features.collection.catalogRouteKey
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.servers.ServerCatalog
import com.nuvio.app.features.servers.serverMessage
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.filterReleasedItems
import com.nuvio.app.features.trakt.TraktPublicListSourceResolver
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

internal fun <T> isCurrentCatalogRequest(
    activeRequest: T?,
    request: T,
    activeJob: Job?,
    requestJob: Job,
): Boolean = activeRequest.value == request && activeJob === requestJob

object CatalogRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(CatalogUiState())
    val uiState: StateFlow<CatalogUiState> = _uiState.asStateFlow()

    private val activeJob = atomic<Job?>(null)
    private val activeRequest = atomic<CatalogRequest?>(null)
    private val scrollPositions = linkedMapOf<CatalogRequest, CatalogScrollPosition>()

    fun load(
        target: CatalogTarget,
        force: Boolean = false,
    ) {
        val request = catalogRequest(target)
        if (!force && activeRequest.value == request && (_uiState.value.items.isNotEmpty() || _uiState.value.isLoading)) {
            return
        }
        activeRequest.value = request
        if (target is CatalogTarget.Library) {
            observeInternalLibrary(request)
            return
        }
        fetchPage(
            request = request,
            reset = true,
            forceRefresh = force,
        )
    }

    fun loadMore() {
        val request = activeRequest.value ?: return
        val current = _uiState.value
        if (current.isLoading || current.nextSkip == null) return
        fetchPage(
            request = request,
            reset = false,
            forceRefresh = false,
        )
    }

    fun clear() {
        val previousJob = activeJob.getAndSet(null)
        previousJob?.cancel()
        activeRequest.value = null
        scrollPositions.clear()
        _uiState.value = CatalogUiState()
    }

    fun scrollPosition(
        target: CatalogTarget,
    ): CatalogScrollPosition =
        scrollPositions[catalogRequest(target)]
            ?: CatalogScrollPosition()

    fun saveScrollPosition(
        target: CatalogTarget,
        firstVisibleItemIndex: Int,
        firstVisibleItemScrollOffset: Int,
    ) {
        val request = catalogRequest(target)
        scrollPositions[request] = CatalogScrollPosition(
            firstVisibleItemIndex = firstVisibleItemIndex,
            firstVisibleItemScrollOffset = firstVisibleItemScrollOffset,
        )
    }

    private fun observeInternalLibrary(request: CatalogRequest) {
        val previousJob = activeJob.getAndSet(null)
        previousJob?.cancel()
        _uiState.value = _uiState.value.copy(
            isLoading = true,
            errorMessage = null,
        )

        val job = scope.launch(start = CoroutineStart.LAZY) {
            val requestJob = currentCoroutineContext()[Job] ?: return@launch
            try {
                val target = request.target as CatalogTarget.Library
                LibraryRepository.ensureLoaded()
                if (!isCurrentCatalogRequest(activeRequest.value, request, activeJob.value, requestJob)) return@launch
                LibraryRepository.uiState.libraryCatalogStates(
                    target, LibraryRepository.uiState.libraryCatalogOrders(target),
                ).collect { state ->
                    if (!isCurrentCatalogRequest(activeRequest.value, request, activeJob.value, requestJob)) return@collect
                    CustomPosterUrlRepository.ensureLoaded()
                    if (!isCurrentCatalogRequest(activeRequest.value, request, activeJob.value, requestJob)) return@collect
                    val posterPattern = CustomPosterUrlRepository.patternForScreen(
                        com.nuvio.app.core.poster.CustomPosterScreen.LIBRARY
                    )
                    _uiState.value = state.copy(
                        items = state.items.withCustomPosterUrls(posterPattern)
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!isCurrentCatalogRequest(activeRequest.value, request, activeJob.value, requestJob)) return@launch
                _uiState.value = CatalogUiState(
                    errorMessage = error.serverMessage() ?: error.message ?: getString(Res.string.catalog_load_failed),
                )
            }
        }
        activeJob.value = job
        job.start()
    }

    private fun fetchPage(
        request: CatalogRequest,
        reset: Boolean,
        forceRefresh: Boolean,
    ) {
        val previousJob = activeJob.getAndSet(null)
        previousJob?.cancel()
        val current = _uiState.value
        val requestedSkip = if (reset) 0 else current.nextSkip ?: return

        _uiState.value = current.copy(
            items = if (reset) emptyList() else current.items,
            isLoading = true,
            nextSkip = if (reset) null else current.nextSkip,
            errorMessage = null,
        )

        val job = scope.launch(start = CoroutineStart.LAZY) {
            val requestJob = currentCoroutineContext()[Job] ?: return@launch
            runCatching {
                when (val target = request.target) {
                    is CatalogTarget.Addon -> fetchCatalogPage(
                        manifestUrl = target.manifestUrl,
                        type = target.contentType,
                        catalogId = target.catalogId,
                        genre = target.genre,
                        skip = requestedSkip.takeIf { it > 0 },
                        forceRefresh = forceRefresh,
                    )

                    is CatalogTarget.CollectionSource -> fetchCollectionSourcePage(
                        target = target,
                        page = requestedSkip.takeIf { it > 0 } ?: 1,
                    )

                    is CatalogTarget.MoreLikeThis -> fetchMoreLikeThisCatalogPage(
                        target = target,
                        page = requestedSkip.takeIf { it > 0 } ?: 1,
                    )

                    is CatalogTarget.Server -> ServerCatalog.page(target, skip = requestedSkip)

                    is CatalogTarget.Library -> error(getString(Res.string.catalog_load_failed))
                }.withUnreleasedFilter(request.hideUnreleasedContent)
            }.fold(
                onSuccess = { page ->
                    if (!isCurrentCatalogRequest(activeRequest.value, request, activeJob.value, requestJob)) return@fold

                    val mergedItems = if (reset) {
                        dedupeCatalogItems(page.items)
                    } else {
                        mergeCatalogItems(_uiState.value.items, page.items)
                    }
                    val supportsPagination = request.target.supportsPagination || page.rawItemCount >= CATALOG_PAGE_SIZE
                    val loadedNewItems = reset || mergedItems.size > current.items.size
                    val paginationState = nextCatalogPaginationState(
                        supportsPagination = supportsPagination,
                        requestedSkip = requestedSkip,
                        page = page,
                        loadedNewItems = loadedNewItems,
                        consecutiveDuplicatePages = if (reset) 0 else current.consecutiveDuplicatePages,
                    )
                    CustomPosterUrlRepository.ensureLoaded()
                    val posterScreen = when (request.target) {
                        is CatalogTarget.Library -> com.nuvio.app.core.poster.CustomPosterScreen.LIBRARY
                        is CatalogTarget.CollectionSource -> com.nuvio.app.core.poster.CustomPosterScreen.COLLECTIONS
                        else -> com.nuvio.app.core.poster.CustomPosterScreen.HOME
                    }
                    val posterPattern = CustomPosterUrlRepository.patternForScreen(posterScreen)
                    if (!isCurrentCatalogRequest(activeRequest.value, request, activeJob.value, requestJob)) return@fold
                    _uiState.value = CatalogUiState(
                        items = mergedItems.withCustomPosterUrls(posterPattern),
                        isLoading = false,
                        nextSkip = paginationState.nextSkip,
                        consecutiveDuplicatePages = paginationState.consecutiveDuplicatePages,
                        errorMessage = null,
                    )
                },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    if (!isCurrentCatalogRequest(activeRequest.value, request, activeJob.value, requestJob)) return@fold

                    _uiState.value = current.copy(
                        items = if (reset) emptyList() else current.items,
                        isLoading = false,
                        nextSkip = null,
                        errorMessage = error.serverMessage() ?: error.message ?: getString(Res.string.catalog_load_failed),
                    )
                },
            )
        }
        activeJob.value = job
        job.start()
    }

    private fun catalogRequest(target: CatalogTarget): CatalogRequest =
        CatalogRequest(
            target = target,
            hideUnreleasedContent = HomeCatalogSettingsRepository.snapshot().hideUnreleasedContent,
        )
}

private fun CatalogPage.withUnreleasedFilter(hideUnreleasedContent: Boolean): CatalogPage {
    if (!hideUnreleasedContent) return this
    val filteredItems = items.filterReleasedItems(CurrentDateProvider.todayIsoDate())
    return if (filteredItems.size == items.size) this else copy(items = filteredItems)
}

private suspend fun fetchCollectionSourcePage(
    target: CatalogTarget.CollectionSource,
    page: Int,
): CatalogPage {
    CollectionRepository.initialize()
    val source = CollectionRepository.getCollection(target.collectionId)
        ?.folders
        ?.firstOrNull { it.id == target.folderId }
        ?.resolvedSources
        ?.firstOrNull { it.catalogRouteKey() == target.sourceKey }
        ?: error(getString(Res.string.catalog_load_failed))

    return when {
        source.isTmdb -> TmdbCollectionSourceResolver.resolve(source = source, page = page)
        source.isTrakt -> TraktPublicListSourceResolver.resolve(source = source, page = page)
        else -> error(getString(Res.string.catalog_load_failed))
    }
}

private suspend fun fetchMoreLikeThisCatalogPage(
    target: CatalogTarget.MoreLikeThis,
    page: Int,
): CatalogPage {
    val result = when (target.source) {
        MoreLikeThisSource.TRAKT -> TraktRelatedRepository.getRelated(
            itemId = target.itemId,
            itemType = target.itemType,
            page = page,
        )

        MoreLikeThisSource.TMDB -> {
            TmdbSettingsRepository.ensureLoaded()
            TmdbMetadataService.fetchMoreLikeThisPage(
                itemId = target.itemId,
                itemType = target.itemType,
                page = page,
                settings = TmdbSettingsRepository.snapshot(),
            )
        }

        MoreLikeThisSource.SIMKL -> {
            if (page > 1) {
                MoreLikeThisPage()
            } else {
                val meta = MetaDetailsRepository.peek(type = target.itemType, id = target.itemId)
                    ?: MetaDetailsRepository.fetch(type = target.itemType, id = target.itemId)
                MoreLikeThisPage(
                    items = meta?.let { details ->
                        SimklRelatedRepository.getRelated(
                            meta = details,
                            fallbackItemId = target.itemId,
                            fallbackItemType = target.itemType,
                        )
                    }.orEmpty(),
                    hasMore = false,
                )
            }
        }
    }
    return CatalogPage(
        items = result.items,
        rawItemCount = result.items.size,
        nextSkip = if (result.hasMore && result.items.isNotEmpty()) page + 1 else null,
    )
}

private data class CatalogRequest(
    val target: CatalogTarget,
    val hideUnreleasedContent: Boolean,
)
