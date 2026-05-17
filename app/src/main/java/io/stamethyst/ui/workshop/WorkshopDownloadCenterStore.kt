package io.stamethyst.ui.workshop

import androidx.compose.runtime.mutableStateListOf
import io.stamethyst.backend.workshop.WorkshopItemDetails

internal object WorkshopDownloadCenterStore {
    val tasks = mutableStateListOf<WorkshopDownloadTaskUi>()

    fun upsert(task: WorkshopDownloadTaskUi) {
        val index = tasks.indexOfFirst { it.publishedFileId == task.publishedFileId }
        if (index >= 0) {
            tasks[index] = task
        } else {
            tasks.add(0, task)
        }
    }

    fun update(publishedFileId: ULong, transform: (WorkshopDownloadTaskUi) -> WorkshopDownloadTaskUi) {
        val index = tasks.indexOfFirst { it.publishedFileId == publishedFileId }
        if (index >= 0) {
            tasks[index] = transform(tasks[index])
        }
    }
}

internal data class WorkshopDownloadTaskUi(
    val publishedFileId: ULong,
    val title: String,
    val status: WorkshopDownloadTaskStatus,
    val message: String,
    val updatedAtMillis: Long = System.currentTimeMillis(),
    val details: WorkshopItemDetails? = null,
    val canManualImport: Boolean = false,
)

internal enum class WorkshopDownloadTaskStatus {
    Queued,
    Resolving,
    Downloading,
    WaitingImport,
    Importing,
    Imported,
    Failed,
}
