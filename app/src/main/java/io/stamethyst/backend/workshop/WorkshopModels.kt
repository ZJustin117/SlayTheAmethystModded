package io.stamethyst.backend.workshop

import java.io.File

data class WorkshopBrowseQuery(
    val appId: UInt = 646570u,
    val searchText: String = "",
    val page: Int = 1,
    val pageSize: Int = 20,
)

data class WorkshopBrowseResult(
    val items: List<WorkshopItemSummary>,
    val total: Int,
    val page: Int,
    val pageSize: Int,
)

data class WorkshopItemSummary(
    val publishedFileId: ULong,
    val appId: UInt,
    val title: String,
    val previewUrl: String,
    val description: String,
    val authorName: String = "",
    val fileSizeBytes: Long = 0L,
    val updatedAtMillis: Long = 0L,
)

data class WorkshopItemDetails(
    val summary: WorkshopItemSummary,
    val fileUrl: String? = null,
    val hcontentFile: ULong? = null,
    val depotId: UInt? = null,
    val jsonMetadata: String = "",
)

data class WorkshopDownloadRequest(
    val details: WorkshopItemDetails,
    val outputDir: File,
)

data class WorkshopDownloadedArtifact(
    val relativePath: String,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
)

data class WorkshopDownloadFailure(
    val message: String,
    val detail: String = "",
)

data class WorkshopDownloadProgress(
    val writtenBytes: Long,
    val totalBytes: Long? = null,
    val completedChunks: Int? = null,
    val totalChunks: Int? = null,
    val completedFiles: Int? = null,
    val totalFiles: Int? = null,
)

data class WorkshopInstalledModRecord(
    val appId: UInt,
    val publishedFileId: ULong,
    val title: String,
    val description: String,
    val previewUrl: String,
    val versionText: String,
    val updatedAtMillis: Long,
    val installedAtMillis: Long,
    val localJarPath: String,
    val cardState: WorkshopModCardState = WorkshopModCardState.ImportedUnpatched,
    val statusText: String = "",
)

enum class WorkshopModCardState {
    ImportedUnpatched,
    ImportedPatched,
    Downloading,
    DownloadFailed,
    NonStandardDownloaded,
    UpdateAvailable,
}

data class WorkshopUpdateCheckResult(
    val appId: UInt,
    val publishedFileId: ULong,
    val hasUpdate: Boolean,
    val remoteUpdatedAtMillis: Long,
    val localUpdatedAtMillis: Long,
    val remoteVersionText: String,
)
