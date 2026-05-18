package io.stamethyst.ui.workshop

import io.stamethyst.backend.workshop.WorkshopInstalledModRecord
import io.stamethyst.backend.workshop.WorkshopDownloadTaskStatus
import io.stamethyst.backend.workshop.WorkshopItemSummary
import io.stamethyst.backend.workshop.WorkshopModStateResolver
import io.stamethyst.backend.workshop.WorkshopResolvedModStateKind

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
    val installed = installedMods.firstOrNull {
        it.appId == item.appId && it.publishedFileId == item.publishedFileId
    }
    val resolved = WorkshopModStateResolver.resolve(
        record = installed,
        taskStatus = task?.status,
        taskMessage = task?.message.orEmpty(),
        remoteUpdatedAtMillis = item.updatedAtMillis,
    )
    return resolved.kind.toWorkshopModDownloadState()
}

private fun WorkshopResolvedModStateKind.toWorkshopModDownloadState(): WorkshopModDownloadState = when (this) {
    WorkshopResolvedModStateKind.NotDownloaded -> WorkshopModDownloadState.NotDownloaded
    WorkshopResolvedModStateKind.ImportedUnpatched,
    WorkshopResolvedModStateKind.ImportedPatched,
    WorkshopResolvedModStateKind.NonStandardDownloaded,
    WorkshopResolvedModStateKind.TexturePackInstalled -> WorkshopModDownloadState.Downloaded
    WorkshopResolvedModStateKind.Queued -> WorkshopModDownloadState.Queued
    WorkshopResolvedModStateKind.Downloading -> WorkshopModDownloadState.Downloading
    WorkshopResolvedModStateKind.DownloadPaused -> WorkshopModDownloadState.Paused
    WorkshopResolvedModStateKind.Cancelling -> WorkshopModDownloadState.Cancelling
    WorkshopResolvedModStateKind.DownloadFailed,
    WorkshopResolvedModStateKind.FileMissing -> WorkshopModDownloadState.DownloadFailed
    WorkshopResolvedModStateKind.UpdateAvailable -> WorkshopModDownloadState.UpdateAvailable
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
