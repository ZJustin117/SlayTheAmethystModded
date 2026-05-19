package io.stamethyst.ui.workshop

import io.stamethyst.backend.mods.ModManager
import io.stamethyst.backend.workshop.WorkshopInstalledModRecord
import io.stamethyst.backend.workshop.WorkshopItemSummary
import io.stamethyst.backend.workshop.WorkshopModCardState
import io.stamethyst.backend.workshop.WorkshopDownloadTaskStatus
import java.util.Locale

internal data class WorkshopDependencyUiState(
    val item: WorkshopItemSummary,
    val installed: Boolean,
    val defaultInstalled: Boolean,
    val downloadState: WorkshopModDownloadState,
    val statusLabel: String,
)

internal fun resolveWorkshopDependencyUiStates(
    dependencies: List<WorkshopItemSummary>,
    installedMods: List<WorkshopInstalledModRecord>,
    downloadTasks: List<WorkshopDownloadTaskUi>,
): List<WorkshopDependencyUiState> = dependencies.map { dependency ->
    val defaultInstalled = dependency.isDefaultInstalledWorkshopDependency()
    val downloadState = resolveWorkshopModDownloadState(
        item = dependency,
        installedMods = installedMods,
        downloadTasks = downloadTasks,
    )
    val installed = defaultInstalled || dependency.isInstalledOrQueued(installedMods, downloadTasks)
    WorkshopDependencyUiState(
        item = dependency,
        installed = installed,
        defaultInstalled = defaultInstalled,
        downloadState = downloadState,
        statusLabel = when {
            defaultInstalled -> "默认已安装"
            installed -> "已安装"
            else -> "未安装"
        },
    )
}

internal fun findMissingWorkshopDependencies(
    dependencies: List<WorkshopItemSummary>,
    installedMods: List<WorkshopInstalledModRecord>,
    downloadTasks: List<WorkshopDownloadTaskUi>,
): List<WorkshopItemSummary> = dependencies.filterNot { dependency ->
    dependency.isDefaultInstalledWorkshopDependency() || dependency.isInstalledOrQueued(installedMods, downloadTasks)
}

private fun WorkshopItemSummary.isInstalledOrQueued(
    installedMods: List<WorkshopInstalledModRecord>,
    downloadTasks: List<WorkshopDownloadTaskUi>,
): Boolean {
    val installed = installedMods.any { record ->
        record.appId == appId &&
            record.publishedFileId == publishedFileId &&
            record.cardState != WorkshopModCardState.DownloadFailed &&
            record.cardState != WorkshopModCardState.FileMissing
    }
    if (installed) return true
    return downloadTasks.any { task ->
        task.publishedFileId == publishedFileId && when (task.status) {
            WorkshopDownloadTaskStatus.Queued,
            WorkshopDownloadTaskStatus.Resolving,
            WorkshopDownloadTaskStatus.Downloading,
            WorkshopDownloadTaskStatus.Pausing,
            WorkshopDownloadTaskStatus.Cancelling,
            WorkshopDownloadTaskStatus.Paused,
            WorkshopDownloadTaskStatus.Completed -> true
            WorkshopDownloadTaskStatus.Failed,
            WorkshopDownloadTaskStatus.Cancelled -> false
        }
    }
}

private fun WorkshopItemSummary.isDefaultInstalledWorkshopDependency(): Boolean {
    if (publishedFileId in defaultInstalledWorkshopDependencyIds) return true
    val tokens = listOf(title, description, authorName)
        .map { it.normalizedWorkshopDependencyToken() }
    return tokens.any { it in defaultInstalledWorkshopDependencyTokens }
}

private fun String.normalizedWorkshopDependencyToken(): String {
    val normalized = ModManager.normalizeModId(this)
    return normalized
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]"), "")
}

private val defaultInstalledWorkshopDependencyIds = setOf(
    1605060445uL, // ModTheSpire
    1605833019uL, // BaseMod
    1609158507uL, // StSLib
)

private val defaultInstalledWorkshopDependencyTokens = setOf(
    "modthespire",
    "basemod",
    "stslib",
)
