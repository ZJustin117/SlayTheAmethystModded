package io.stamethyst.ui.workshop

import io.stamethyst.backend.workshop.WorkshopInstalledModRecord
import io.stamethyst.backend.workshop.WorkshopDownloadTaskStatus
import io.stamethyst.backend.workshop.WorkshopItemSummary
import io.stamethyst.backend.workshop.WorkshopModCardState

internal enum class WorkshopModDownloadState(
    val statusLabel: String,
    val actionLabel: String,
    val canStartDownload: Boolean,
) {
    Downloaded("已下载", "已下载", false),
    NotDownloaded("未下载", "下载", true),
    UpdateAvailable("有更新", "更新", true),
    Queued("排队中", "排队中", false),
    Downloading("下载中", "下载中", false),
    Paused("已暂停", "继续", true),
    Cancelling("正在取消", "正在取消", false),
    DownloadFailed("下载失败", "重试", true),
}

internal fun resolveWorkshopModDownloadState(
    item: WorkshopItemSummary,
    installedMods: List<WorkshopInstalledModRecord>,
    downloadTasks: List<WorkshopDownloadTaskUi>,
): WorkshopModDownloadState {
    val task = downloadTasks.firstOrNull { it.publishedFileId == item.publishedFileId }
    when (task?.status) {
        WorkshopDownloadTaskStatus.Queued -> return WorkshopModDownloadState.Queued
        WorkshopDownloadTaskStatus.Resolving,
        WorkshopDownloadTaskStatus.Downloading -> return WorkshopModDownloadState.Downloading
        WorkshopDownloadTaskStatus.Pausing,
        WorkshopDownloadTaskStatus.Paused -> return WorkshopModDownloadState.Paused
        WorkshopDownloadTaskStatus.Cancelling -> return WorkshopModDownloadState.Cancelling
        WorkshopDownloadTaskStatus.Completed -> return WorkshopModDownloadState.Downloaded
        WorkshopDownloadTaskStatus.Failed -> return WorkshopModDownloadState.DownloadFailed
        WorkshopDownloadTaskStatus.Cancelled,
        null -> Unit
    }

    val installed = installedMods.firstOrNull {
        it.appId == item.appId && it.publishedFileId == item.publishedFileId
    } ?: return WorkshopModDownloadState.NotDownloaded

    return when (installed.cardState) {
        WorkshopModCardState.Downloading -> WorkshopModDownloadState.Downloading
        WorkshopModCardState.DownloadFailed -> WorkshopModDownloadState.DownloadFailed
        WorkshopModCardState.NonStandardDownloaded -> WorkshopModDownloadState.Downloaded
        WorkshopModCardState.UpdateAvailable -> WorkshopModDownloadState.UpdateAvailable
        WorkshopModCardState.ImportedPatched,
        WorkshopModCardState.ImportedUnpatched -> {
            if (item.updatedAtMillis > 0L &&
                installed.updatedAtMillis > 0L &&
                item.updatedAtMillis > installed.updatedAtMillis
            ) {
                WorkshopModDownloadState.UpdateAvailable
            } else {
                WorkshopModDownloadState.Downloaded
            }
        }
    }
}

internal fun WorkshopDownloadTaskStatus.isActiveWorkshopDownload(): Boolean = when (this) {
    WorkshopDownloadTaskStatus.Queued,
    WorkshopDownloadTaskStatus.Resolving,
    WorkshopDownloadTaskStatus.Downloading,
    WorkshopDownloadTaskStatus.Pausing,
    WorkshopDownloadTaskStatus.Cancelling -> true
    WorkshopDownloadTaskStatus.Paused,
    WorkshopDownloadTaskStatus.Completed,
    WorkshopDownloadTaskStatus.Failed,
    WorkshopDownloadTaskStatus.Cancelled -> false
}
