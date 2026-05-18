package io.stamethyst.ui.workshop

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import io.stamethyst.backend.workshop.WorkshopDownloadTaskRecord
import io.stamethyst.backend.workshop.WorkshopDownloadTaskStatus
import io.stamethyst.backend.workshop.WorkshopDownloadTaskStore
import io.stamethyst.backend.workshop.WorkshopItemDetails
import io.stamethyst.backend.workshop.isActiveDownload
import io.stamethyst.backend.workshop.isRunningDownload
import io.stamethyst.backend.workshop.isStoppingDownload

internal object WorkshopDownloadCenterStore {
    val tasks = mutableStateListOf<WorkshopDownloadTaskUi>()
    private var store: WorkshopDownloadTaskStore? = null
    private var loaded = false

    fun initialize(context: Context) {
        if (!loaded) {
            loaded = true
            store = WorkshopDownloadTaskStore(context).also { it.recoverInterruptedTasks() }
        }
        refresh()
    }

    fun refresh() {
        val records = store?.list().orEmpty()
        tasks.clear()
        tasks.addAll(records.map { it.toUi() })
    }

    fun upsert(task: WorkshopDownloadTaskUi) {
        store?.upsert(task.toRecord())
        refresh()
    }

    fun update(publishedFileId: ULong, transform: (WorkshopDownloadTaskUi) -> WorkshopDownloadTaskUi) {
        store?.update(publishedFileId) { record -> transform(record.toUi()).toRecord() }
        refresh()
    }

    fun remove(publishedFileId: ULong) {
        store?.remove(publishedFileId)
        refresh()
    }

    fun find(publishedFileId: ULong): WorkshopDownloadTaskUi? = store?.find(publishedFileId)?.toUi()

    fun hasRunningTask(): Boolean = store?.hasRunningTask() == true

    fun nextQueuedTask(): WorkshopDownloadTaskUi? = store?.nextQueuedTask()?.toUi()
}

internal data class WorkshopDownloadTaskUi(
    val publishedFileId: ULong,
    val title: String,
    val status: WorkshopDownloadTaskStatus,
    val message: String,
    val updatedAtMillis: Long = System.currentTimeMillis(),
    val details: WorkshopItemDetails,
    val previewUrl: String = details.summary.previewUrl,
    val description: String = details.summary.description,
    val authorName: String = details.summary.authorName,
    val fileSizeBytes: Long = details.summary.fileSizeBytes,
    val progressPercent: Int? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = details.summary.fileSizeBytes.takeIf { it > 0L },
    val completedFiles: Int? = null,
    val totalFiles: Int? = null,
    val completedChunks: Int? = null,
    val totalChunks: Int? = null,
    val errorClass: String = "",
    val errorMessage: String = "",
    val errorStackTrace: String = "",
)

private fun WorkshopDownloadTaskRecord.toUi(): WorkshopDownloadTaskUi = WorkshopDownloadTaskUi(
    publishedFileId = publishedFileId,
    title = title,
    status = status,
    message = message,
    updatedAtMillis = updatedAtMillis,
    details = details,
    previewUrl = previewUrl,
    description = description,
    authorName = authorName,
    fileSizeBytes = fileSizeBytes,
    progressPercent = progressPercent,
    downloadedBytes = downloadedBytes,
    totalBytes = totalBytes,
    completedFiles = completedFiles,
    totalFiles = totalFiles,
    completedChunks = completedChunks,
    totalChunks = totalChunks,
    errorClass = errorClass,
    errorMessage = errorMessage,
    errorStackTrace = errorStackTrace,
)

private fun WorkshopDownloadTaskUi.toRecord(): WorkshopDownloadTaskRecord = WorkshopDownloadTaskRecord(
    publishedFileId = publishedFileId,
    title = title,
    status = status,
    message = message,
    updatedAtMillis = updatedAtMillis,
    details = details,
    previewUrl = previewUrl,
    description = description,
    authorName = authorName,
    fileSizeBytes = fileSizeBytes,
    progressPercent = progressPercent,
    downloadedBytes = downloadedBytes,
    totalBytes = totalBytes,
    completedFiles = completedFiles,
    totalFiles = totalFiles,
    completedChunks = completedChunks,
    totalChunks = totalChunks,
    errorClass = errorClass,
    errorMessage = errorMessage,
    errorStackTrace = errorStackTrace,
)
