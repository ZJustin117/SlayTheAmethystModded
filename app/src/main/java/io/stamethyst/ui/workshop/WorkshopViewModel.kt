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
import io.stamethyst.backend.workshop.WorkshopBrowseQuery
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
import io.stamethyst.backend.workshop.isRunningDownload
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Stable
internal class WorkshopViewModel : ViewModel() {
    var uiState by mutableStateOf(WorkshopUiState())
        private set

    private var service: WorkshopService? = null
    private var metadataStore: WorkshopMetadataStore? = null
    private var loaded = false
    private var activeQueryText: String = ""

    fun load(context: Context) {
        WorkshopDownloadCenterStore.initialize(context)
        if (loaded) {
            refreshLocalDownloadState()
            return
        }
        loaded = true
        service = WorkshopService(context)
        metadataStore = WorkshopMetadataStore(context)
        metadataStore?.markMissingFiles()
        uiState = uiState.copy(
            steamLoggedIn = service?.hasSteamAuth() == true,
            installedMods = metadataStore?.list().orEmpty(),
        )
        WorkshopDownloadProcessService.startNextQueued(context)
        search(context, "")
    }

    private fun refreshLocalDownloadState() {
        metadataStore?.markMissingFiles()
        uiState = uiState.copy(
            steamLoggedIn = service?.hasSteamAuth() == true,
            installedMods = metadataStore?.list().orEmpty(),
        )
    }

    fun search(context: Context, queryText: String) {
        activeQueryText = queryText
        loadBrowsePage(context, queryText = queryText, page = 1, append = false)
    }

    fun loadNextPage(context: Context) {
        val state = uiState
        if (state.browseLoading || state.loadingMore || !state.hasMorePages) return
        loadBrowsePage(context, queryText = activeQueryText, page = state.nextPage, append = true)
    }

    private fun loadBrowsePage(
        context: Context,
        queryText: String,
        page: Int,
        append: Boolean,
    ) {
        val currentService = service ?: return
        viewModelScope.launch {
            uiState = if (append) {
                uiState.copy(loadingMore = true, errorMessage = null)
            } else {
                uiState.copy(
                    browseLoading = true,
                    loadingMore = false,
                    errorMessage = null,
                    items = emptyList(),
                    nextPage = 1,
                    hasMorePages = true,
                )
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    currentService.browse(
                        WorkshopBrowseQuery(
                            searchText = queryText,
                            page = page,
                            pageSize = WorkshopUiState.PAGE_SIZE,
                        )
                    )
                }
            }.onSuccess { result ->
                val existing = if (append) uiState.items else emptyList()
                val merged = (existing + result.items).distinctBy { it.publishedFileId }
                uiState = uiState.copy(
                    browseLoading = false,
                    loadingMore = false,
                    items = merged,
                    selected = uiState.selected?.takeIf { selected ->
                        merged.any { it.publishedFileId == selected.summary.publishedFileId }
                    },
                    nextPage = page + 1,
                    hasMorePages = result.items.size >= WorkshopUiState.PAGE_SIZE,
                    errorMessage = if (merged.isEmpty()) "未找到创意工坊条目" else null,
                )
            }.onFailure { error ->
                uiState = uiState.copy(
                    browseLoading = false,
                    loadingMore = false,
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
            uiState = uiState.copy(detailLoadingId = publishedFileId, errorMessage = null)
            runCatching {
                withContext(Dispatchers.IO) { currentService.getDetails(appId, publishedFileId) }
            }.onSuccess { details ->
                uiState = uiState.copy(selected = details, detailLoadingId = null, errorMessage = null)
            }.onFailure { error ->
                uiState = uiState.copy(detailLoadingId = null, errorMessage = error.message ?: error.javaClass.simpleName)
            }
        }
    }

    fun downloadSelected(context: Context) {
        val details = uiState.selected ?: return
        startDownloadAfterDependencyCheck(context, details.summary, details)
    }

    fun pauseDownload(context: Context, task: WorkshopDownloadTaskUi) {
        val details = task.details
        if (task.status.isRunningDownload()) {
            WorkshopDownloadCenterStore.update(task.publishedFileId) {
                it.copy(status = WorkshopDownloadTaskStatus.Pausing, message = "正在暂停", updatedAtMillis = System.currentTimeMillis())
            }
            WorkshopDownloadProcessService.pause(context, details.summary.appId, details.summary.publishedFileId, createDownloadResultReceiver(context.applicationContext, details.summary))
        } else {
            WorkshopDownloadCenterStore.update(task.publishedFileId) {
                it.copy(status = WorkshopDownloadTaskStatus.Paused, message = "下载已暂停", updatedAtMillis = System.currentTimeMillis())
            }
            metadataStore?.updateState(details.summary.appId, details.summary.publishedFileId, WorkshopModCardState.DownloadPaused, "下载已暂停")
        }
        uiState = uiState.copy(downloadStatus = "正在暂停", downloadInProgress = true, installedMods = metadataStore?.list().orEmpty())
    }

    fun resumeDownload(context: Context, task: WorkshopDownloadTaskUi) {
        restartDownload(context, task, "继续下载")
    }

    fun retryDownload(context: Context, task: WorkshopDownloadTaskUi) {
        restartDownload(context, task, "重新下载")
    }

    fun cancelDownload(context: Context, task: WorkshopDownloadTaskUi) {
        val details = task.details
        if (task.status.isRunningDownload()) {
            WorkshopDownloadCenterStore.update(task.publishedFileId) {
                it.copy(status = WorkshopDownloadTaskStatus.Cancelling, message = "正在取消", updatedAtMillis = System.currentTimeMillis())
            }
            WorkshopDownloadProcessService.cancel(context, details.summary.appId, details.summary.publishedFileId, createDownloadResultReceiver(context.applicationContext, details.summary))
            uiState = uiState.copy(downloadStatus = "正在取消", downloadInProgress = true, installedMods = metadataStore?.list().orEmpty())
            return
        }
        WorkshopDownloadCenterStore.remove(task.publishedFileId)
        metadataStore?.remove(details.summary.appId, details.summary.publishedFileId)
        File(context.filesDir, "workshop/${details.summary.appId}/${details.summary.publishedFileId}").deleteRecursively()
        uiState = uiState.copy(downloadStatus = "下载已取消", downloadInProgress = false, installedMods = metadataStore?.list().orEmpty())
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
            installedMods = metadataStore?.list().orEmpty(),
            downloadTasks = WorkshopDownloadCenterStore.tasks,
        )
        if (!state.canStartDownload) return
        val selectedDetails = uiState.selected?.takeIf { selected ->
            selected.summary.appId == item.appId && selected.summary.publishedFileId == item.publishedFileId
        }
        if (selectedDetails != null) {
            startDownloadAfterDependencyCheck(context, item, selectedDetails)
            return
        }
        val currentService = service ?: return
        viewModelScope.launch {
            uiState = uiState.copy(downloadStatus = "正在检查前置模组")
            runCatching {
                withContext(Dispatchers.IO) { currentService.getDetails(item.appId, item.publishedFileId) }
            }.onSuccess { details ->
                uiState = uiState.copy(selected = details)
                startDownloadAfterDependencyCheck(context, item, details)
            }.onFailure { error ->
                uiState = uiState.copy(downloadStatus = "前置检查失败：${error.message ?: error.javaClass.simpleName}")
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
            installedMods = metadataStore?.list().orEmpty(),
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
        val alreadyRunning = WorkshopDownloadCenterStore.hasRunningTask()
        val queuedDetails = details ?: WorkshopItemDetails(summary = summary)
        WorkshopDownloadCenterStore.upsert(
            WorkshopDownloadTaskUi(
                publishedFileId = summary.publishedFileId,
                title = summary.title,
                status = WorkshopDownloadTaskStatus.Queued,
                message = if (alreadyRunning) "已加入下载队列" else "等待下载",
                details = queuedDetails,
                previewUrl = summary.previewUrl,
                description = summary.description,
                authorName = summary.authorName,
                fileSizeBytes = summary.fileSizeBytes,
                totalBytes = summary.fileSizeBytes.takeIf { it > 0L },
                errorClass = "",
                errorMessage = "",
                errorStackTrace = "",
            )
        )
        metadataStore?.upsert(
                WorkshopInstalledModRecord(
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
                    statusText = "等待下载",
                    dependencies = queuedDetails.dependencies,
                )
            )
        uiState = uiState.copy(
            downloadStatus = if (alreadyRunning) "已加入下载队列：${summary.title}" else "正在下载 ${summary.title}",
            downloadInProgress = WorkshopDownloadCenterStore.hasRunningTask() || !alreadyRunning,
            installedMods = metadataStore?.list().orEmpty(),
        )
        WorkshopDownloadProcessService.startNextQueued(context)
    }

    private fun restartDownload(context: Context, task: WorkshopDownloadTaskUi, message: String) {
        val details = task.details
        WorkshopDownloadCenterStore.upsert(
            task.copy(
                status = WorkshopDownloadTaskStatus.Queued,
                message = if (WorkshopDownloadCenterStore.hasRunningTask()) "已加入下载队列" else message,
                updatedAtMillis = System.currentTimeMillis(),
                progressPercent = null,
                downloadedBytes = 0L,
                completedFiles = null,
                completedChunks = null,
                errorClass = "",
                errorMessage = "",
                errorStackTrace = "",
            )
        )
        metadataStore?.updateState(details.summary.appId, details.summary.publishedFileId, WorkshopModCardState.Downloading, "等待下载")
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
                            message = message.ifBlank { "下载完成" },
                            progressPercent = 100,
                            downloadedBytes = (it.totalBytes ?: it.downloadedBytes).coerceAtLeast(it.downloadedBytes),
                            completedFiles = it.totalFiles ?: it.completedFiles,
                            updatedAtMillis = System.currentTimeMillis(),
                        )
                    }
                    uiState = uiState.copy(
                        downloadStatus = message.ifBlank { "下载完成" },
                        downloadInProgress = false,
                        installedMods = metadataStore?.list().orEmpty(),
                    )
                    WorkshopDownloadCenterStore.refresh()
                }
                WorkshopDownloadProcessService.RESULT_FAILURE -> {
                    uiState = uiState.copy(
                        downloadStatus = message.ifBlank { "下载失败" },
                        downloadInProgress = false,
                        installedMods = metadataStore?.list().orEmpty(),
                    )
                    WorkshopDownloadCenterStore.refresh()
                }
                WorkshopDownloadProcessService.RESULT_PAUSED -> {
                    uiState = uiState.copy(
                        downloadStatus = message.ifBlank { "下载已暂停" },
                        downloadInProgress = false,
                        installedMods = metadataStore?.list().orEmpty(),
                    )
                    WorkshopDownloadCenterStore.refresh()
                }
                WorkshopDownloadProcessService.RESULT_CANCELLED -> {
                    WorkshopDownloadCenterStore.remove(summary.publishedFileId)
                    uiState = uiState.copy(
                        downloadStatus = message.ifBlank { "下载已取消" },
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
            uiState = uiState.copy(downloadStatus = "正在检查创意工坊更新", updateChecking = true)
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
                                append("发现 ").append(updateCount).append(" 个创意工坊更新")
                                if (report.failedCount > 0) append("，").append(report.failedCount).append(" 个检查失败")
                            }
                        } else if (report.failedCount > 0) {
                            "创意工坊更新检查完成，${report.failedCount} 个检查失败"
                        } else {
                            "创意工坊模组均为最新"
                        },
                    )
                }
                .onFailure { error ->
                    uiState = uiState.copy(
                        updateChecking = false,
                        downloadStatus = "更新检查失败：${error.message ?: error.javaClass.simpleName}",
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
    val errorMessage: String? = null,
) {
    companion object {
        const val PAGE_SIZE = 20
    }
}

internal data class WorkshopPendingDependencyDownload(
    val details: WorkshopItemDetails,
    val missingDependencies: List<WorkshopItemSummary>,
)

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
