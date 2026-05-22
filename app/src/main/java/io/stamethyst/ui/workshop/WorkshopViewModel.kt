package io.stamethyst.ui.workshop

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.stamethyst.R
import io.stamethyst.backend.workshop.BaiduAiTextTranslationClient
import io.stamethyst.backend.workshop.BaiduTranslationCredentials
import io.stamethyst.backend.workshop.BaiduTranslationCredentialsRepository
import io.stamethyst.backend.workshop.WorkshopBrowseQuery
import io.stamethyst.backend.workshop.WorkshopBrowseSort
import io.stamethyst.backend.workshop.WorkshopBrowseTimeFilter
import io.stamethyst.backend.workshop.WorkshopDownloadProcessService
import io.stamethyst.backend.workshop.WorkshopDownloadTaskStatus
import io.stamethyst.backend.workshop.WorkshopInstalledModRecord
import io.stamethyst.backend.workshop.WorkshopItemDetails
import io.stamethyst.backend.workshop.WorkshopItemSummary
import io.stamethyst.backend.workshop.WorkshopMetadataStore
import io.stamethyst.backend.workshop.WorkshopModCardState
import io.stamethyst.backend.workshop.WorkshopService
import io.stamethyst.backend.workshop.WorkshopUpdateCheckResult
import io.stamethyst.backend.workshop.WorkshopUpdateChecker
import io.stamethyst.backend.workshop.buildBaiduModDescriptionReference
import io.stamethyst.backend.workshop.isActiveDownload
import io.stamethyst.backend.workshop.isRunningDownload
import io.stamethyst.backend.workshop.mapLocaleLanguageToBaiduLanguage
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Stable
internal class WorkshopViewModel : ViewModel() {
    var uiState by mutableStateOf(WorkshopUiState())
        private set

    private var service: WorkshopService? = null
    private var metadataStore: WorkshopMetadataStore? = null
    private var loaded = false
    private var activeListMode: WorkshopListMode = WorkshopListMode.Browse
    private var activeQueryText: String = ""
    private var activeSort: WorkshopBrowseSort = WorkshopBrowseSort.MostPopular
    private var activeTimeFilter: WorkshopBrowseTimeFilter = WorkshopBrowseTimeFilter.OneWeek
    private val detailsCache = mutableMapOf<String, WorkshopItemDetails>()
    private val translationClient = BaiduAiTextTranslationClient()

    fun load(context: Context, initialListMode: WorkshopListMode = WorkshopListMode.Browse) {
        WorkshopDownloadCenterStore.initialize(context)
        if (loaded) {
            refreshLocalDownloadState()
            if (uiState.listMode != initialListMode) {
                when (initialListMode) {
                    WorkshopListMode.Browse -> showWorkshopBrowse(context)
                    WorkshopListMode.Subscriptions -> showSubscribedWorkshopMods(context)
                }
            }
            return
        }
        loaded = true
        activeListMode = initialListMode
        service = WorkshopService(context)
        metadataStore = WorkshopMetadataStore(context)
        metadataStore?.markMissingFiles()
        val steamLoggedIn = service?.hasSteamAuth() == true
        uiState = uiState.copy(
            steamLoggedIn = steamLoggedIn,
            listMode = activeListMode,
            installedMods = metadataStore?.list().orEmpty(),
        )
        WorkshopDownloadProcessService.startNextQueued(context)
        when (activeListMode) {
            WorkshopListMode.Browse -> search(context, "")
            WorkshopListMode.Subscriptions -> loadSubscribedPage(context, page = 1, append = false)
        }
    }

    private fun refreshLocalDownloadState() {
        metadataStore?.markMissingFiles()
        WorkshopDownloadCenterStore.refresh()
        val steamLoggedIn = service?.hasSteamAuth() == true
        uiState = uiState.copy(
            steamLoggedIn = steamLoggedIn,
            listMode = activeListMode,
            installedMods = metadataStore?.list().orEmpty(),
            downloadInProgress = WorkshopDownloadCenterStore.tasks.any { it.status.isActiveDownload() },
        )
    }

    private fun findCachedDetails(appId: UInt, publishedFileId: ULong): WorkshopItemDetails? {
        return uiState.selected?.takeIf { selected ->
            selected.summary.appId == appId && selected.summary.publishedFileId == publishedFileId
        } ?: detailsCache[detailsCacheKey(appId, publishedFileId)]
    }

    fun refreshDownloadState(context: Context) {
        WorkshopDownloadCenterStore.initialize(context)
        refreshLocalDownloadState()
    }

    fun search(context: Context, queryText: String) {
        activeListMode = WorkshopListMode.Browse
        activeQueryText = queryText
        loadBrowsePage(context, queryText = queryText, page = 1, append = false)
    }

    fun search(
        context: Context,
        queryText: String,
        sort: WorkshopBrowseSort,
        timeFilter: WorkshopBrowseTimeFilter,
    ) {
        activeListMode = WorkshopListMode.Browse
        activeQueryText = queryText
        activeSort = sort
        activeTimeFilter = timeFilter
        loadBrowsePage(context, queryText = queryText, page = 1, append = false)
    }

    fun refreshBrowse(context: Context) {
        when (activeListMode) {
            WorkshopListMode.Browse -> loadBrowsePage(
                context = context,
                queryText = activeQueryText,
                page = 1,
                append = false,
                clearItems = false,
            )
            WorkshopListMode.Subscriptions -> loadSubscribedPage(
                context = context,
                page = 1,
                append = false,
                clearItems = false,
            )
        }
    }

    fun loadNextPage(context: Context) {
        val state = uiState
        if (state.browseLoading || state.loadingMore || !state.hasMorePages) return
        when (state.listMode) {
            WorkshopListMode.Browse -> loadBrowsePage(context, queryText = activeQueryText, page = state.nextPage, append = true)
            WorkshopListMode.Subscriptions -> loadSubscribedPage(context, page = state.nextPage, append = true)
        }
    }

    fun showSubscribedWorkshopMods(context: Context) {
        activeListMode = WorkshopListMode.Subscriptions
        loadSubscribedPage(context, page = 1, append = false)
    }

    fun showWorkshopBrowse(context: Context) {
        activeListMode = WorkshopListMode.Browse
        loadBrowsePage(context, queryText = activeQueryText, page = 1, append = false)
    }

    private fun loadBrowsePage(
        context: Context,
        queryText: String,
        page: Int,
        append: Boolean,
        clearItems: Boolean = true,
    ) {
        val currentService = service ?: return
        activeListMode = WorkshopListMode.Browse
        viewModelScope.launch {
            uiState = if (append) {
                uiState.copy(loadingMore = true, errorMessage = null)
            } else {
                uiState.copy(
                    browseLoading = true,
                    loadingMore = false,
                    listMode = WorkshopListMode.Browse,
                    errorMessage = null,
                    items = if (clearItems) emptyList() else uiState.items,
                    nextPage = 1,
                    hasMorePages = true,
                )
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    currentService.browse(
                        WorkshopBrowseQuery(
                            searchText = queryText,
                            sort = activeSort,
                            timeFilter = activeTimeFilter,
                            page = page,
                            pageSize = WorkshopUiState.PAGE_SIZE,
                        )
                    )
                }
            }.onSuccess { result ->
                if (activeListMode != WorkshopListMode.Browse) return@onSuccess
                val existing = if (append) uiState.items else emptyList()
                val merged = (existing + result.items).distinctBy { it.publishedFileId }
                uiState = uiState.copy(
                    browseLoading = false,
                    loadingMore = false,
                    items = merged,
                    nextPage = page + 1,
                    hasMorePages = result.hasNextPage,
                    errorMessage = if (merged.isEmpty()) context.getString(R.string.workshop_error_no_entries_found) else null,
                )
            }.onFailure { error ->
                if (activeListMode != WorkshopListMode.Browse) return@onFailure
                uiState = uiState.copy(
                    browseLoading = false,
                    loadingMore = false,
                    errorMessage = error.message ?: error.javaClass.simpleName,
                )
            }
        }
    }

    private fun loadSubscribedPage(
        context: Context,
        page: Int,
        append: Boolean,
        clearItems: Boolean = true,
    ) {
        val currentService = service ?: return
        activeListMode = WorkshopListMode.Subscriptions
        if (!currentService.hasSteamAuth()) {
            uiState = uiState.copy(
                browseLoading = false,
                loadingMore = false,
                listMode = WorkshopListMode.Subscriptions,
                steamLoggedIn = false,
                items = emptyList(),
                nextPage = 1,
                hasMorePages = false,
                errorMessage = null,
            )
            return
        }
        viewModelScope.launch {
            uiState = if (append) {
                uiState.copy(loadingMore = true, errorMessage = null)
            } else {
                uiState.copy(
                    browseLoading = true,
                    loadingMore = false,
                    listMode = WorkshopListMode.Subscriptions,
                    errorMessage = null,
                    items = if (clearItems) emptyList() else uiState.items,
                    nextPage = 1,
                    hasMorePages = true,
                )
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    currentService.browseSubscriptions(page = page, pageSize = WorkshopUiState.PAGE_SIZE)
                }
            }.onSuccess { result ->
                if (activeListMode != WorkshopListMode.Subscriptions) return@onSuccess
                val existing = if (append) uiState.items else emptyList()
                val merged = (existing + result.items).distinctBy { it.publishedFileId }
                uiState = uiState.copy(
                    browseLoading = false,
                    loadingMore = false,
                    items = merged,
                    nextPage = page + 1,
                    hasMorePages = result.hasNextPage,
                    steamLoggedIn = currentService.hasSteamAuth(),
                    errorMessage = null,
                )
            }.onFailure { error ->
                if (activeListMode != WorkshopListMode.Subscriptions) return@onFailure
                uiState = uiState.copy(
                    browseLoading = false,
                    loadingMore = false,
                    steamLoggedIn = currentService.hasSteamAuth(),
                    errorMessage = error.message ?: error.javaClass.simpleName,
                )
            }
        }
    }

    fun openDetails(context: Context, item: WorkshopItemSummary) {
        loadDetails(context, item.appId, item.publishedFileId)
    }

    fun loadDetails(context: Context, appId: UInt, publishedFileId: ULong) {
        val currentService = service ?: return
        viewModelScope.launch {
            uiState = uiState.copy(
                detailLoadingId = publishedFileId,
                errorMessage = null,
                commentLoadingId = null,
                commentErrorMessage = null,
                detailTranslationLoadingId = null,
                detailTranslationErrorMessage = null,
            )
            runCatching {
                withContext(Dispatchers.IO) { currentService.getDetails(appId, publishedFileId) }
            }.onSuccess { details ->
                detailsCache[details.cacheKey()] = details
                val shouldLoadComments = details.shouldLoadWorkshopComments()
                uiState = uiState.copy(
                    selected = details,
                    detailLoadingId = null,
                    errorMessage = null,
                    commentLoadingId = if (shouldLoadComments) publishedFileId else null,
                    commentErrorMessage = details.commentUnavailableMessage(context),
                )
                if (shouldLoadComments) {
                    loadWorkshopCommentsPage(context, appId, publishedFileId, page = 1)
                }
            }.onFailure { error ->
                uiState = uiState.copy(detailLoadingId = null, errorMessage = error.message ?: error.javaClass.simpleName)
            }
        }
    }

    fun loadPreviousWorkshopCommentsPage(context: Context) {
        shiftWorkshopCommentsPage(context, delta = -1)
    }

    fun loadNextWorkshopCommentsPage(context: Context) {
        shiftWorkshopCommentsPage(context, delta = 1)
    }

    fun retryWorkshopCommentsPage(context: Context) {
        val details = uiState.selected ?: return
        if (uiState.detailLoadingId != null || uiState.commentLoadingId != null) return
        loadWorkshopCommentsPage(
            context = context,
            appId = details.summary.appId,
            publishedFileId = details.summary.publishedFileId,
            page = details.commentPage.coerceAtLeast(1),
        )
    }

    private fun shiftWorkshopCommentsPage(context: Context, delta: Int) {
        val details = uiState.selected ?: return
        if (uiState.detailLoadingId != null || uiState.commentLoadingId != null) return
        val targetPage = (details.commentPage + delta).coerceAtLeast(1)
        if (targetPage == details.commentPage) return
        if (delta < 0 && !details.hasPreviousCommentPage) return
        if (delta > 0 && !details.hasNextCommentPage) return
        loadWorkshopCommentsPage(
            context = context,
            appId = details.summary.appId,
            publishedFileId = details.summary.publishedFileId,
            page = targetPage,
        )
    }

    private fun loadWorkshopCommentsPage(
        context: Context,
        appId: UInt,
        publishedFileId: ULong,
        page: Int,
    ) {
        val currentService = service ?: return
        val detailSnapshot = uiState.selected?.takeIf { details ->
            details.summary.appId == appId && details.summary.publishedFileId == publishedFileId
        } ?: return
        val commentUnavailableMessage = detailSnapshot.commentUnavailableMessage(context)
        if (!detailSnapshot.shouldLoadWorkshopComments()) {
            uiState = uiState.copy(
                commentLoadingId = null,
                commentErrorMessage = commentUnavailableMessage,
            )
            return
        }

        uiState = uiState.copy(commentLoadingId = publishedFileId, commentErrorMessage = null)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { currentService.getCommentsPage(detailSnapshot, page) }
            }.onSuccess { commentPage ->
                uiState = uiState.copy(
                    selected = uiState.selected?.takeIf { current ->
                        current.summary.appId == appId && current.summary.publishedFileId == publishedFileId
                    }?.copy(
                        commentsUrl = commentPage.commentsUrl,
                        commentCount = commentPage.commentCount,
                        commentPage = commentPage.page,
                        commentTotalPages = commentPage.totalPages,
                        hasPreviousCommentPage = commentPage.hasPreviousPage,
                        hasNextCommentPage = commentPage.hasNextPage,
                        comments = commentPage.comments,
                    ) ?: uiState.selected,
                    commentLoadingId = null,
                    commentErrorMessage = null,
                )
            }.onFailure { error ->
                uiState = uiState.copy(
                    commentLoadingId = null,
                    commentErrorMessage = error.message ?: context.getString(R.string.workshop_error_load_comments_failed),
                )
            }
        }
    }

    fun downloadSelected(context: Context) {
        val details = uiState.selected ?: return
        startDownloadAfterDependencyCheck(context, details.summary, details)
    }

    fun translateSelectedDetails(context: Context) {
        val details = uiState.selected ?: return
        val summary = details.summary
        if (uiState.detailTranslationLoadingId == summary.publishedFileId) return

        val originalTitle = summary.title.trim()
        val originalDescription = summary.description.trim()
        if (originalTitle.isBlank() && originalDescription.isBlank()) {
            uiState = uiState.copy(detailTranslationErrorMessage = context.getString(R.string.workshop_translate_no_text))
            return
        }

        val credentials = BaiduTranslationCredentialsRepository(context).getCredentials()
        validateBaiduTranslationCredentials(context, credentials)?.let { message ->
            uiState = uiState.copy(detailTranslationErrorMessage = message)
            return
        }

        val appId = summary.appId
        val publishedFileId = summary.publishedFileId
        uiState = uiState.copy(
            detailTranslationLoadingId = publishedFileId,
            detailTranslationErrorMessage = null,
        )
        viewModelScope.launch {
            runCatching {
                val targetLanguage = mapLocaleLanguageToBaiduLanguage(Locale.getDefault()) ?: BAIDU_DEFAULT_TARGET_LANGUAGE
                val reference = buildBaiduModDescriptionReference(
                    modTitle = originalTitle,
                    gameTitle = BAIDU_STS_GAME_TITLE,
                )
                coroutineScope {
                    val translatedTitle = if (originalTitle.isBlank()) {
                        null
                    } else {
                        async {
                            translateWithBaiduCredentials(
                                text = originalTitle,
                                targetLanguage = targetLanguage,
                                credentials = credentials,
                                reference = reference,
                            )
                        }
                    }
                    val translatedDescription = if (originalDescription.isBlank()) {
                        null
                    } else {
                        async {
                            translateWithBaiduCredentials(
                                text = originalDescription,
                                targetLanguage = targetLanguage,
                                credentials = credentials,
                                reference = reference,
                            )
                        }
                    }
                    TranslatedWorkshopDetailText(
                        title = translatedTitle?.await()?.trim()?.takeIf(String::isNotBlank) ?: summary.title,
                        description = translatedDescription?.await()?.trim()?.takeIf(String::isNotBlank) ?: summary.description,
                    )
                }
            }.onSuccess { translated ->
                val current = uiState.selected?.takeIf { selected ->
                    selected.summary.appId == appId && selected.summary.publishedFileId == publishedFileId
                }
                if (current == null) {
                    uiState = uiState.copy(detailTranslationLoadingId = null)
                    return@onSuccess
                }
                val translatedSummary = current.summary.copy(
                    title = translated.title,
                    description = translated.description,
                )
                val translatedDetails = current.copy(summary = translatedSummary)
                detailsCache[translatedDetails.cacheKey()] = translatedDetails
                uiState = uiState.copy(
                    selected = translatedDetails,
                    items = uiState.items.map { item ->
                        if (item.appId == appId && item.publishedFileId == publishedFileId) {
                            item.copy(title = translated.title, description = translated.description)
                        } else {
                            item
                        }
                    },
                    detailTranslationLoadingId = null,
                    detailTranslationErrorMessage = null,
                )
            }.onFailure { error ->
                uiState = uiState.copy(
                    detailTranslationLoadingId = null,
                    detailTranslationErrorMessage = error.message ?: context.getString(R.string.workshop_translate_failed),
                )
            }
        }
    }

    private suspend fun translateWithBaiduCredentials(
        text: String,
        targetLanguage: String,
        credentials: BaiduTranslationCredentials,
        reference: String,
    ): String = translationClient.translate(
        text = text,
        sourceLanguage = BAIDU_AUTO_DETECT_LANGUAGE,
        targetLanguage = targetLanguage,
        credentials = credentials,
        reference = reference,
    )

    private fun validateBaiduTranslationCredentials(
        context: Context,
        credentials: BaiduTranslationCredentials,
    ): String? = when {
        credentials.appId.isBlank() && credentials.apiKey.isBlank() ->
            context.getString(R.string.workshop_translate_missing_app_id_api_key)

        credentials.appId.isBlank() -> context.getString(R.string.workshop_translate_missing_app_id)
        credentials.apiKey.isBlank() -> context.getString(R.string.workshop_translate_missing_api_key)
        else -> null
    }

    fun pauseDownload(context: Context, task: WorkshopDownloadTaskUi) {
        val details = task.details
        if (task.status.isRunningDownload()) {
            WorkshopDownloadCenterStore.update(task.publishedFileId) {
                it.copy(status = WorkshopDownloadTaskStatus.Pausing, message = context.getString(R.string.workshop_status_pausing), updatedAtMillis = System.currentTimeMillis())
            }
            WorkshopDownloadProcessService.pause(context, details.summary.appId, details.summary.publishedFileId, createDownloadResultReceiver(context.applicationContext, details.summary))
        } else {
            WorkshopDownloadCenterStore.update(task.publishedFileId) {
                it.copy(status = WorkshopDownloadTaskStatus.Paused, message = context.getString(R.string.workshop_status_paused), updatedAtMillis = System.currentTimeMillis())
            }
            metadataStore?.updateState(details.summary.appId, details.summary.publishedFileId, WorkshopModCardState.DownloadPaused, context.getString(R.string.workshop_status_paused))
        }
        uiState = uiState.copy(downloadStatus = context.getString(R.string.workshop_status_pausing), downloadInProgress = true, installedMods = metadataStore?.list().orEmpty())
    }

    fun resumeDownload(context: Context, task: WorkshopDownloadTaskUi) {
        restartDownload(context, task, context.getString(R.string.main_mod_workshop_action_continue_download))
    }

    fun retryDownload(context: Context, task: WorkshopDownloadTaskUi) {
        restartDownload(context, task, context.getString(R.string.main_mod_workshop_action_redownload))
    }

    fun cancelDownload(context: Context, task: WorkshopDownloadTaskUi) {
        val details = task.details
        if (task.status.isRunningDownload()) {
            WorkshopDownloadCenterStore.update(task.publishedFileId) {
                it.copy(status = WorkshopDownloadTaskStatus.Cancelling, message = context.getString(R.string.workshop_status_canceling), updatedAtMillis = System.currentTimeMillis())
            }
            WorkshopDownloadProcessService.cancel(context, details.summary.appId, details.summary.publishedFileId, createDownloadResultReceiver(context.applicationContext, details.summary))
            uiState = uiState.copy(downloadStatus = context.getString(R.string.workshop_status_canceling), downloadInProgress = true, installedMods = metadataStore?.list().orEmpty())
            return
        }
        WorkshopDownloadCenterStore.remove(task.publishedFileId)
        metadataStore?.remove(details.summary.appId, details.summary.publishedFileId)
        File(context.filesDir, "workshop/${details.summary.appId}/${details.summary.publishedFileId}").deleteRecursively()
        uiState = uiState.copy(downloadStatus = context.getString(R.string.workshop_status_cancelled), downloadInProgress = false, installedMods = metadataStore?.list().orEmpty())
        if (!task.status.isRunningDownload()) WorkshopDownloadProcessService.startNextQueued(context)
    }

    fun download(context: Context, item: WorkshopItemSummary) {
        val existingTask = WorkshopDownloadCenterStore.find(item.publishedFileId)
        if (existingTask?.status == WorkshopDownloadTaskStatus.Paused) {
            resumeDownload(context, existingTask)
            return
        }
        if (existingTask?.status == WorkshopDownloadTaskStatus.Failed || existingTask?.status == WorkshopDownloadTaskStatus.Cancelled) {
            retryDownload(context, existingTask)
            return
        }
        val state = resolveWorkshopModDownloadState(
            item = item,
            installedMods = uiState.installedMods,
            downloadTasks = WorkshopDownloadCenterStore.tasks,
        )
        if (!state.canStartDownload) return
        val selectedDetails = findCachedDetails(item.appId, item.publishedFileId)
        if (selectedDetails != null) {
            startDownloadAfterDependencyCheck(context, item, selectedDetails)
            return
        }
        val currentService = service ?: return
        viewModelScope.launch {
            uiState = uiState.copy(downloadStatus = context.getString(R.string.workshop_status_checking_dependencies))
            runCatching {
                withContext(Dispatchers.IO) { currentService.getDetails(item.appId, item.publishedFileId) }
            }.onSuccess { details ->
                detailsCache[details.cacheKey()] = details
                uiState = uiState.copy(selected = details)
                startDownloadAfterDependencyCheck(context, item, details)
            }.onFailure { error ->
                uiState = uiState.copy(downloadStatus = context.getString(R.string.workshop_status_dependency_check_failed, error.message ?: error.javaClass.simpleName))
            }
        }
    }

    fun confirmPendingDependencyDownload(context: Context) {
        val pending = uiState.pendingDependencyDownload ?: return
        uiState = uiState.copy(pendingDependencyDownload = null)
        pending.missingDependencies.forEach { dependency -> startDownload(context, dependency) }
        startDownload(context, pending.details.summary, pending.details)
    }

    fun dismissPendingDependencyDownload() {
        uiState = uiState.copy(pendingDependencyDownload = null)
    }

    private fun startDownloadAfterDependencyCheck(
        context: Context,
        item: WorkshopItemSummary,
        details: WorkshopItemDetails,
    ) {
        val missingDependencies = findMissingWorkshopDependencies(
            dependencies = details.dependencies,
            installedMods = uiState.installedMods,
            downloadTasks = WorkshopDownloadCenterStore.tasks,
        )
        if (missingDependencies.isNotEmpty()) {
            uiState = uiState.copy(
                pendingDependencyDownload = WorkshopPendingDependencyDownload(
                    details = details,
                    missingDependencies = missingDependencies,
                ),
            )
            return
        }
        startDownload(context, item, details)
    }

    private fun startDownload(
        context: Context,
        summary: WorkshopItemSummary,
        details: WorkshopItemDetails? = null,
    ) {
        val alreadyRunning = WorkshopDownloadCenterStore.tasks.any { it.status.isRunningDownload() }
        val queuedDetails = details ?: WorkshopItemDetails(summary = summary)
        val queuedTask = WorkshopDownloadTaskUi(
            publishedFileId = summary.publishedFileId,
            title = summary.title,
            status = WorkshopDownloadTaskStatus.Queued,
            message = if (alreadyRunning) context.getString(R.string.workshop_status_added_to_queue) else context.getString(R.string.workshop_download_task_message_waiting),
            details = queuedDetails,
            previewUrl = summary.previewUrl,
            description = summary.description,
            authorName = summary.authorName,
            fileSizeBytes = summary.fileSizeBytes,
            totalBytes = summary.fileSizeBytes.takeIf { it > 0L },
            errorClass = "",
            errorMessage = "",
            errorStackTrace = "",
            downloadLog = "",
        )
        val queuedRecord = WorkshopInstalledModRecord(
            appId = summary.appId,
            publishedFileId = summary.publishedFileId,
            title = summary.title,
                description = summary.description,
                previewUrl = summary.previewUrl,
                versionText = summary.updatedAtMillis.toString(),
                updatedAtMillis = summary.updatedAtMillis,
                installedAtMillis = System.currentTimeMillis(),
            localJarPath = "",
            cardState = WorkshopModCardState.Downloading,
            statusText = context.getString(R.string.workshop_download_task_message_waiting),
            dependencies = queuedDetails.dependencies,
        )
        WorkshopDownloadCenterStore.upsertInMemory(queuedTask)
        uiState = uiState.copy(
            downloadStatus = if (alreadyRunning) {
                context.getString(R.string.workshop_status_added_to_queue_format, summary.title)
            } else {
                context.getString(R.string.workshop_status_starting_download_format, summary.title)
            },
            downloadInProgress = true,
            installedMods = listOf(queuedRecord) + uiState.installedMods.filterNot {
                it.appId == queuedRecord.appId && it.publishedFileId == queuedRecord.publishedFileId
            },
        )
        viewModelScope.launch(Dispatchers.IO) {
            WorkshopDownloadCenterStore.persistUpsert(queuedTask)
            metadataStore?.upsert(queuedRecord)
            WorkshopDownloadProcessService.startNextQueued(context)
        }
    }

    private fun restartDownload(context: Context, task: WorkshopDownloadTaskUi, message: String) {
        val details = task.details
        WorkshopDownloadCenterStore.upsert(
            task.copy(
                status = WorkshopDownloadTaskStatus.Queued,
                message = if (WorkshopDownloadCenterStore.hasRunningTask()) context.getString(R.string.workshop_status_added_to_queue) else message,
                updatedAtMillis = System.currentTimeMillis(),
                progressPercent = null,
                downloadedBytes = 0L,
                completedFiles = null,
                completedChunks = null,
                errorClass = "",
                errorMessage = "",
                errorStackTrace = "",
                downloadLog = "",
            )
        )
        metadataStore?.updateState(details.summary.appId, details.summary.publishedFileId, WorkshopModCardState.Downloading, context.getString(R.string.workshop_download_task_message_waiting))
        WorkshopDownloadProcessService.startNextQueued(context)
    }

    private fun createDownloadResultReceiver(context: Context, summary: WorkshopItemSummary): ResultReceiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
        override fun onReceiveResult(resultCode: Int, resultData: Bundle) {
            val message = resultData.getString(WorkshopDownloadProcessService.EXTRA_MESSAGE).orEmpty()
            val status = resultData.getString(WorkshopDownloadProcessService.EXTRA_TASK_STATUS)?.toTaskStatus()
            if (status != null || message.isNotBlank()) {
                WorkshopDownloadCenterStore.refresh()
                WorkshopDownloadCenterStore.update(summary.publishedFileId) {
                    it.copy(
                        status = status ?: it.status,
                        message = message.ifBlank { it.message },
                        updatedAtMillis = System.currentTimeMillis(),
                        progressPercent = resultData.optionalInt(WorkshopDownloadProcessService.EXTRA_PROGRESS_PERCENT) ?: it.progressPercent,
                        downloadedBytes = resultData.optionalLong(WorkshopDownloadProcessService.EXTRA_WRITTEN_BYTES) ?: it.downloadedBytes,
                        totalBytes = resultData.optionalLong(WorkshopDownloadProcessService.EXTRA_TOTAL_BYTES) ?: it.totalBytes,
                        completedFiles = resultData.optionalInt(WorkshopDownloadProcessService.EXTRA_COMPLETED_FILES) ?: it.completedFiles,
                        totalFiles = resultData.optionalInt(WorkshopDownloadProcessService.EXTRA_TOTAL_FILES) ?: it.totalFiles,
                        completedChunks = resultData.optionalInt(WorkshopDownloadProcessService.EXTRA_COMPLETED_CHUNKS) ?: it.completedChunks,
                        totalChunks = resultData.optionalInt(WorkshopDownloadProcessService.EXTRA_TOTAL_CHUNKS) ?: it.totalChunks,
                        errorClass = resultData.getString(WorkshopDownloadProcessService.EXTRA_ERROR_CLASS).orEmpty().ifBlank { it.errorClass },
                        errorMessage = resultData.getString(WorkshopDownloadProcessService.EXTRA_ERROR_MESSAGE).orEmpty().ifBlank { it.errorMessage },
                        errorStackTrace = resultData.getString(WorkshopDownloadProcessService.EXTRA_ERROR_STACKTRACE).orEmpty().ifBlank { it.errorStackTrace },
                    )
                }
            }
            when (resultCode) {
                WorkshopDownloadProcessService.RESULT_PROGRESS -> {
                    if (message.isNotBlank()) uiState = uiState.copy(downloadStatus = message)
                }
                WorkshopDownloadProcessService.RESULT_COMPLETED -> {
                    WorkshopDownloadCenterStore.update(summary.publishedFileId) {
                        it.copy(
                            status = WorkshopDownloadTaskStatus.Completed,
                            message = message.ifBlank { context.getString(R.string.workshop_status_download_completed) },
                            progressPercent = 100,
                            downloadedBytes = (it.totalBytes ?: it.downloadedBytes).coerceAtLeast(it.downloadedBytes),
                            completedFiles = it.totalFiles ?: it.completedFiles,
                            updatedAtMillis = System.currentTimeMillis(),
                        )
                    }
                    uiState = uiState.copy(
                        downloadStatus = message.ifBlank { context.getString(R.string.workshop_status_download_completed) },
                        downloadInProgress = false,
                        installedMods = metadataStore?.list().orEmpty(),
                    )
                    WorkshopDownloadCenterStore.refresh()
                }
                WorkshopDownloadProcessService.RESULT_FAILURE -> {
                    uiState = uiState.copy(
                        downloadStatus = message.ifBlank { context.getString(R.string.workshop_status_download_failed) },
                        downloadInProgress = false,
                        installedMods = metadataStore?.list().orEmpty(),
                    )
                    WorkshopDownloadCenterStore.refresh()
                }
                WorkshopDownloadProcessService.RESULT_PAUSED -> {
                    uiState = uiState.copy(
                        downloadStatus = message.ifBlank { context.getString(R.string.workshop_status_paused) },
                        downloadInProgress = false,
                        installedMods = metadataStore?.list().orEmpty(),
                    )
                    WorkshopDownloadCenterStore.refresh()
                }
                WorkshopDownloadProcessService.RESULT_CANCELLED -> {
                    uiState = uiState.copy(
                        downloadStatus = message.ifBlank { context.getString(R.string.workshop_status_cancelled) },
                        downloadInProgress = false,
                        installedMods = metadataStore?.list().orEmpty(),
                    )
                    WorkshopDownloadCenterStore.refresh()
                }
            }
        }
    }

    fun checkUpdates(context: Context) {
        viewModelScope.launch {
            uiState = uiState.copy(downloadStatus = context.getString(R.string.workshop_status_checking_updates), updateChecking = true)
            runCatching { withContext(Dispatchers.IO) { WorkshopUpdateChecker(context).checkInstalledMods() } }
                .onSuccess { report ->
                    val results = report.results
                    val updateCount = results.count { it.hasUpdate }
                    uiState = uiState.copy(
                        updateResults = results,
                        installedMods = metadataStore?.list().orEmpty(),
                        updateChecking = false,
                        downloadStatus = if (updateCount > 0) {
                            buildString {
                                append(context.getString(R.string.workshop_status_updates_found, updateCount))
                                if (report.failedCount > 0) append(context.getString(R.string.workshop_status_update_failed_suffix, report.failedCount))
                            }
                        } else if (report.failedCount > 0) {
                            context.getString(R.string.workshop_status_update_check_completed_failed, report.failedCount)
                        } else {
                            context.getString(R.string.workshop_status_all_up_to_date)
                        },
                    )
                }
                .onFailure { error ->
                    uiState = uiState.copy(
                        updateChecking = false,
                        downloadStatus = context.getString(R.string.workshop_status_update_check_failed, error.message ?: error.javaClass.simpleName),
                    )
                }
        }
    }
}

internal data class WorkshopUiState(
    val browseLoading: Boolean = false,
    val loadingMore: Boolean = false,
    val nextPage: Int = 1,
    val hasMorePages: Boolean = true,
    val listMode: WorkshopListMode = WorkshopListMode.Browse,
    val detailLoadingId: ULong? = null,
    val downloadInProgress: Boolean = false,
    val updateChecking: Boolean = false,
    val steamLoggedIn: Boolean = false,
    val items: List<WorkshopItemSummary> = emptyList(),
    val selected: WorkshopItemDetails? = null,
    val downloadStatus: String = "",
    val installedMods: List<WorkshopInstalledModRecord> = emptyList(),
    val updateResults: List<WorkshopUpdateCheckResult> = emptyList(),
    val pendingDependencyDownload: WorkshopPendingDependencyDownload? = null,
    val commentLoadingId: ULong? = null,
    val commentErrorMessage: String? = null,
    val detailTranslationLoadingId: ULong? = null,
    val detailTranslationErrorMessage: String? = null,
    val errorMessage: String? = null,
) {
    companion object {
        const val PAGE_SIZE = 30
    }
}

internal enum class WorkshopListMode {
    Browse,
    Subscriptions,
}

internal data class WorkshopPendingDependencyDownload(
    val details: WorkshopItemDetails,
    val missingDependencies: List<WorkshopItemSummary>,
)

private data class TranslatedWorkshopDetailText(
    val title: String,
    val description: String,
)

private fun WorkshopItemDetails.shouldLoadWorkshopComments(): Boolean =
    commentThreadContext != null && commentCount != 0L

private fun WorkshopItemDetails.cacheKey(): String = detailsCacheKey(summary.appId, summary.publishedFileId)

private fun detailsCacheKey(appId: UInt, publishedFileId: ULong): String = "$appId:$publishedFileId"

private fun WorkshopItemDetails.commentUnavailableMessage(context: Context): String? = when {
    commentThreadContext == null -> context.getString(R.string.workshop_comments_unavailable)
    commentCount == 0L -> null
    else -> null
}

private fun String.toTaskStatus(): WorkshopDownloadTaskStatus? = when (this) {
    "Queued" -> WorkshopDownloadTaskStatus.Queued
    "Resolving" -> WorkshopDownloadTaskStatus.Resolving
    "Downloading" -> WorkshopDownloadTaskStatus.Downloading
    "Pausing" -> WorkshopDownloadTaskStatus.Pausing
    "Cancelling" -> WorkshopDownloadTaskStatus.Cancelling
    "Paused" -> WorkshopDownloadTaskStatus.Paused
    "Completed" -> WorkshopDownloadTaskStatus.Completed
    "Failed" -> WorkshopDownloadTaskStatus.Failed
    "Cancelled" -> WorkshopDownloadTaskStatus.Cancelled
    else -> null
}

private fun Bundle.optionalInt(key: String): Int? = if (containsKey(key)) getInt(key) else null

private fun Bundle.optionalLong(key: String): Long? = if (containsKey(key)) getLong(key) else null

private const val BAIDU_AUTO_DETECT_LANGUAGE = "auto"
private const val BAIDU_DEFAULT_TARGET_LANGUAGE = "zh"
private const val BAIDU_STS_GAME_TITLE = "Slay the Spire"
