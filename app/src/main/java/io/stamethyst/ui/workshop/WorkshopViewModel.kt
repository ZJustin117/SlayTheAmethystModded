package io.stamethyst.ui.workshop

import android.content.Context
import android.app.Activity
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.stamethyst.backend.workshop.WorkshopBrowseQuery
import io.stamethyst.backend.workshop.WorkshopItemDetails
import io.stamethyst.backend.workshop.WorkshopItemSummary
import io.stamethyst.backend.workshop.WorkshopMetadataStore
import io.stamethyst.backend.workshop.WorkshopService
import io.stamethyst.backend.workshop.WorkshopSettingsRepository
import io.stamethyst.backend.workshop.WorkshopDownloadedArtifact
import io.stamethyst.backend.workshop.WorkshopDownloadRequest
import io.stamethyst.backend.workshop.WorkshopInstalledModRecord
import io.stamethyst.backend.workshop.WorkshopUpdateCheckResult
import io.stamethyst.backend.workshop.WorkshopUpdateChecker
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.stamethyst.ui.settings.DuplicateModImportReplaceOptions
import io.stamethyst.ui.settings.SettingsFileService

@Stable
internal class WorkshopViewModel : ViewModel() {
    var uiState by mutableStateOf(WorkshopUiState())
        private set

    private var service: WorkshopService? = null
    private var metadataStore: WorkshopMetadataStore? = null
    private var settings: WorkshopSettingsRepository? = null
    private var loaded = false
    private var activeQueryText: String = ""

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        service = WorkshopService(context)
        metadataStore = WorkshopMetadataStore(context)
        settings = WorkshopSettingsRepository(context)
        uiState = uiState.copy(
            steamLoggedIn = service?.hasSteamAuth() == true,
            autoImportEnabled = settings?.isAutoImportEnabled() ?: true,
            installedMods = metadataStore?.list().orEmpty(),
        )
        search(context, "")
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
        val currentService = service ?: return
        val details = uiState.selected ?: return
        viewModelScope.launch {
            WorkshopDownloadCenterStore.upsert(
                WorkshopDownloadTaskUi(
                    publishedFileId = details.summary.publishedFileId,
                    title = details.summary.title,
                    status = WorkshopDownloadTaskStatus.Queued,
                    message = "等待下载",
                    details = details,
                )
            )
            uiState = uiState.copy(downloadStatus = "正在下载 ${details.summary.title}", downloadInProgress = true)
            val outputDir = File(context.filesDir, "workshop/${details.summary.appId}/${details.summary.publishedFileId}")
            runCatching {
                withContext(Dispatchers.IO) {
                    currentService.download(WorkshopDownloadRequest(details, outputDir)).collect { event ->
                        when (event) {
                            io.stamethyst.backend.workshop.WorkshopDownloadEvent.Ignored -> Unit
                            is io.stamethyst.backend.workshop.WorkshopDownloadEvent.Log -> {
                                WorkshopDownloadCenterStore.update(details.summary.publishedFileId) {
                                    it.copy(message = event.message, updatedAtMillis = System.currentTimeMillis())
                                }
                            }
                            is io.stamethyst.backend.workshop.WorkshopDownloadEvent.StateChanged -> {
                                val status = event.state.toTaskStatus()
                                WorkshopDownloadCenterStore.update(details.summary.publishedFileId) {
                                    it.copy(status = status, message = event.state.displayText(), updatedAtMillis = System.currentTimeMillis())
                                }
                                uiState = uiState.copy(downloadStatus = event.state.displayText())
                            }
                            is io.stamethyst.backend.workshop.WorkshopDownloadEvent.Completed -> {
                                handleCompletedDownload(context, details, event.files.firstOrNull())
                            }
                            is io.stamethyst.backend.workshop.WorkshopDownloadEvent.Failed -> {
                                error(
                                    buildString {
                                        append(event.failure.message)
                                        if (event.failure.detail.isNotBlank()) {
                                            append(": ")
                                            append(event.failure.detail)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }.onFailure { error ->
                WorkshopDownloadCenterStore.update(details.summary.publishedFileId) {
                    it.copy(
                        status = WorkshopDownloadTaskStatus.Failed,
                        message = "下载失败：${error.message ?: error.javaClass.simpleName}",
                        updatedAtMillis = System.currentTimeMillis(),
                    )
                }
                uiState = uiState.copy(
                    downloadStatus = "下载失败：${error.message ?: error.javaClass.simpleName}",
                    downloadInProgress = false,
                )
            }
        }
    }

    fun setAutoImport(context: Context, enabled: Boolean) {
        settings?.setAutoImportEnabled(enabled)
        uiState = uiState.copy(autoImportEnabled = enabled)
    }

    fun setSelectedForImport(details: WorkshopItemDetails) {
        uiState = uiState.copy(selected = details)
    }

    fun checkUpdates(context: Context) {
        viewModelScope.launch {
            uiState = uiState.copy(downloadStatus = "正在检查创意工坊更新", updateChecking = true)
            runCatching { withContext(Dispatchers.IO) { WorkshopUpdateChecker(context).checkInstalledMods() } }
                .onSuccess { results ->
                    val updateCount = results.count { it.hasUpdate }
                    uiState = uiState.copy(
                        updateResults = results,
                        updateChecking = false,
                        downloadStatus = if (updateCount > 0) {
                            "发现 $updateCount 个创意工坊更新"
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

    fun manualImport(context: Context) {
        val details = uiState.selected ?: return
        viewModelScope.launch {
            val jar = withContext(Dispatchers.IO) {
                val outputDir = File(context.filesDir, "workshop/${details.summary.appId}/${details.summary.publishedFileId}")
                outputDir.listFiles()?.firstOrNull { it.extension.equals("jar", ignoreCase = true) }
            } ?: run {
                uiState = uiState.copy(downloadStatus = "未找到可导入的 jar 文件")
                return@launch
            }
            importJar(context, jar, details, autoImported = false)
        }
    }

    private suspend fun handleCompletedDownload(
        context: Context,
        details: WorkshopItemDetails,
        artifact: WorkshopDownloadedArtifact?,
    ) {
        if (artifact == null) {
            uiState = uiState.copy(downloadStatus = "下载完成但未发现可导入文件")
            return
        }
        val autoImport = settings?.isAutoImportEnabled() ?: true
        val record = service?.createInstalledRecord(details, artifact, autoImport) ?: return
        metadataStore?.upsert(record)
        WorkshopDownloadCenterStore.update(details.summary.publishedFileId) {
            it.copy(
                status = if (autoImport) WorkshopDownloadTaskStatus.Importing else WorkshopDownloadTaskStatus.WaitingImport,
                message = if (autoImport) "下载完成，正在自动导入" else "下载完成，等待手动导入",
                updatedAtMillis = System.currentTimeMillis(),
                canManualImport = !autoImport,
            )
        }
        uiState = uiState.copy(
            downloadStatus = if (autoImport) "已下载并准备自动导入" else "已下载，等待手动导入",
            downloadInProgress = false,
            pendingManualImportTitle = if (autoImport) null else details.summary.title,
            installedMods = metadataStore?.list().orEmpty(),
        )
        if (autoImport) {
            val outputDir = File(context.filesDir, "workshop/${details.summary.appId}/${details.summary.publishedFileId}")
            val jar = File(outputDir, artifact.relativePath)
            importJar(context, jar, details, autoImported = true)
        }
    }

    private fun importJar(
        context: Context,
        jar: File,
        details: WorkshopItemDetails,
        autoImported: Boolean,
    ) {
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", jar)
        val activity = context as? Activity
        if (activity == null) {
            io.stamethyst.ui.modimport.ModImportRequestBus.requestImport(listOf(uri))
            return
        }
        runCatching {
            val result = SettingsFileService.importModJar(
                host = activity,
                uri = uri,
                replaceExistingDuplicates = true,
                duplicateReplaceOptions = DuplicateModImportReplaceOptions(
                    renameToPreviousFileName = true,
                    moveToPreviousFolder = true,
                ),
            )
            metadataStore?.upsert(
                WorkshopInstalledModRecord(
                    appId = details.summary.appId,
                    publishedFileId = details.summary.publishedFileId,
                    title = details.summary.title,
                    description = details.summary.description,
                    previewUrl = details.summary.previewUrl,
                    versionText = details.summary.updatedAtMillis.toString(),
                    updatedAtMillis = details.summary.updatedAtMillis,
                    installedAtMillis = System.currentTimeMillis(),
                    localJarPath = result.storagePath,
                    autoImported = autoImported,
                )
            )
            uiState = uiState.copy(
                downloadStatus = if (autoImported) "已自动导入 ${result.modName}" else "已导入 ${result.modName}",
                downloadInProgress = false,
                pendingManualImportTitle = null,
                installedMods = metadataStore?.list().orEmpty(),
            )
            WorkshopDownloadCenterStore.update(details.summary.publishedFileId) {
                it.copy(
                    status = WorkshopDownloadTaskStatus.Imported,
                    message = if (autoImported) "已自动导入 ${result.modName}" else "已导入 ${result.modName}",
                    updatedAtMillis = System.currentTimeMillis(),
                    canManualImport = false,
                )
            }
        }.onFailure { error ->
            uiState = uiState.copy(
                downloadStatus = "导入失败：${error.message ?: error.javaClass.simpleName}",
                downloadInProgress = false,
                pendingManualImportTitle = details.summary.title,
            )
            WorkshopDownloadCenterStore.update(details.summary.publishedFileId) {
                it.copy(
                    status = WorkshopDownloadTaskStatus.Failed,
                    message = "导入失败：${error.message ?: error.javaClass.simpleName}",
                    updatedAtMillis = System.currentTimeMillis(),
                    canManualImport = true,
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
    val autoImportEnabled: Boolean = true,
    val items: List<WorkshopItemSummary> = emptyList(),
    val selected: WorkshopItemDetails? = null,
    val downloadStatus: String = "",
    val pendingManualImportTitle: String? = null,
    val installedMods: List<WorkshopInstalledModRecord> = emptyList(),
    val updateResults: List<WorkshopUpdateCheckResult> = emptyList(),
    val errorMessage: String? = null,
) {
    companion object {
        const val PAGE_SIZE = 20
    }
}

private fun io.stamethyst.backend.workshop.WorkshopDownloadState.displayText(): String = when (this) {
    io.stamethyst.backend.workshop.WorkshopDownloadState.Resolving -> "正在解析下载内容"
    io.stamethyst.backend.workshop.WorkshopDownloadState.Downloading -> "正在下载"
    io.stamethyst.backend.workshop.WorkshopDownloadState.Success -> "下载完成"
    io.stamethyst.backend.workshop.WorkshopDownloadState.Failed -> "下载失败"
}

private fun io.stamethyst.backend.workshop.WorkshopDownloadState.toTaskStatus(): WorkshopDownloadTaskStatus = when (this) {
    io.stamethyst.backend.workshop.WorkshopDownloadState.Resolving -> WorkshopDownloadTaskStatus.Resolving
    io.stamethyst.backend.workshop.WorkshopDownloadState.Downloading -> WorkshopDownloadTaskStatus.Downloading
    io.stamethyst.backend.workshop.WorkshopDownloadState.Success -> WorkshopDownloadTaskStatus.WaitingImport
    io.stamethyst.backend.workshop.WorkshopDownloadState.Failed -> WorkshopDownloadTaskStatus.Failed
}
