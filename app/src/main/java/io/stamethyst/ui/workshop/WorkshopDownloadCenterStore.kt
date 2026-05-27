package io.stamethyst.ui.workshop

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import io.stamethyst.R
import io.stamethyst.backend.workshop.WorkshopDownloadProcessService
import io.stamethyst.backend.workshop.WorkshopDownloadTaskRecord
import io.stamethyst.backend.workshop.WorkshopDownloadTaskStatus
import io.stamethyst.backend.workshop.WorkshopDownloadTaskStore
import io.stamethyst.backend.workshop.WorkshopInterruptedDownloadRecovery
import io.stamethyst.backend.workshop.WorkshopItemDetails
import io.stamethyst.backend.workshop.WorkshopMetadataStore
import io.stamethyst.backend.workshop.WorkshopModCardState
import io.stamethyst.backend.workshop.isRunningDownload

internal object WorkshopDownloadCenterStore {
    private const val ACTIVE_DOWNLOAD_RECOVERY_GRACE_MS = 30_000L

    val tasks = mutableStateListOf<WorkshopDownloadTaskUi>()
    val taskStatuses = mutableStateMapOf<ULong, WorkshopDownloadTaskStatus>()
    private var store: WorkshopDownloadTaskStore? = null
    private var appContext: Context? = null
    private var loaded = false

    fun initialize(context: Context) {
        appContext = context.applicationContext
        if (!loaded) {
            loaded = true
            store = WorkshopDownloadTaskStore(context)
            recoverInterruptedDownloads(context)
            refresh()
        }
    }

    fun refresh() {
        replaceInMemory(loadTasks())
    }

    fun loadTasks(): List<WorkshopDownloadTaskUi> {
        val records = store?.list().orEmpty()
        val context = appContext
        return records.map { it.toUi(context) }
    }

    fun replaceInMemory(loadedTasks: List<WorkshopDownloadTaskUi>) {
        replaceTaskStatuses(loadedTasks)
        if (tasks == loadedTasks) return
        tasks.clear()
        tasks.addAll(loadedTasks)
    }

    fun upsert(task: WorkshopDownloadTaskUi) {
        store?.upsert(task.toRecord())
        refresh()
    }

    fun persistUpsert(task: WorkshopDownloadTaskUi) {
        store?.upsert(task.toRecord())
    }

    fun upsertInMemory(task: WorkshopDownloadTaskUi) {
        taskStatuses[task.publishedFileId] = task.status
        val index = tasks.indexOfFirst { it.publishedFileId == task.publishedFileId }
        if (index >= 0) tasks[index] = task else tasks.add(0, task)
    }

    private fun replaceTaskStatuses(loadedTasks: List<WorkshopDownloadTaskUi>) {
        val loadedStatuses = loadedTasks.associate { it.publishedFileId to it.status }
        taskStatuses.keys.toList().forEach { publishedFileId ->
            if (publishedFileId !in loadedStatuses) {
                taskStatuses.remove(publishedFileId)
            }
        }
        loadedStatuses.forEach { (publishedFileId, status) ->
            if (taskStatuses[publishedFileId] != status) {
                taskStatuses[publishedFileId] = status
            }
        }
    }

    fun update(publishedFileId: ULong, transform: (WorkshopDownloadTaskUi) -> WorkshopDownloadTaskUi) {
        store?.update(publishedFileId) { record -> transform(record.toUi(appContext)).toRecord() }
        refresh()
    }

    fun remove(publishedFileId: ULong) {
        store?.remove(publishedFileId)
        refresh()
    }

    fun find(publishedFileId: ULong): WorkshopDownloadTaskUi? = store?.find(publishedFileId)?.toUi(appContext)

    fun hasRunningTask(): Boolean = store?.hasRunningTask() == true

    fun nextQueuedTask(): WorkshopDownloadTaskUi? = store?.nextQueuedTask()?.toUi(appContext)

    private fun recoverInterruptedDownloads(context: Context) {
        val taskStore = store ?: return
        val metadataStore = WorkshopMetadataStore(context)
        val now = System.currentTimeMillis()
        taskStore.list().forEach { task ->
            if (task.shouldRecoverInterrupted(context, now)) {
                WorkshopInterruptedDownloadRecovery.recoverFinishedTransferIfPossible(
                    context = context,
                    metadataStore = metadataStore,
                    taskStore = taskStore,
                    task = task,
                )
            }
        }
        val recovered = store?.recoverInterruptedTasksWithResult { task ->
            task.shouldRecoverInterrupted(context, now)
        }.orEmpty()
        if (recovered.isEmpty()) return
        recovered.forEach { task ->
            if (WorkshopInterruptedDownloadRecovery.recoverFinishedTransferIfPossible(
                    context = context,
                    metadataStore = metadataStore,
                    taskStore = taskStore,
                    task = task,
                )
            ) {
                return@forEach
            }
            val summary = task.details.summary
            metadataStore.updateState(
                appId = summary.appId,
                publishedFileId = summary.publishedFileId,
                state = WorkshopModCardState.DownloadPaused,
                statusText = task.message.ifBlank { context.getString(R.string.workshop_download_task_message_paused) },
            )
        }
    }

    private fun WorkshopDownloadTaskRecord.shouldRecoverInterrupted(context: Context, now: Long): Boolean {
        if (WorkshopDownloadProcessService.isActiveDownload(context, publishedFileId)) return false
        if (status.isRunningDownload() && now - updatedAtMillis < ACTIVE_DOWNLOAD_RECOVERY_GRACE_MS) return false
        return true
    }
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
    val downloadLog: String = "",
    val preservePartialDownload: Boolean = false,
)

private fun WorkshopDownloadTaskRecord.toUi(context: Context?): WorkshopDownloadTaskUi {
    val normalizedStatus = if (
        status == WorkshopDownloadTaskStatus.Paused &&
        context != null &&
        WorkshopDownloadProcessService.isActiveDownload(context, publishedFileId)
    ) {
        WorkshopDownloadTaskStatus.Downloading
    } else {
        status
    }
    val normalizedMessage = if (normalizedStatus != status) {
        context?.getString(R.string.workshop_download_task_message_downloading) ?: "正在下载"
    } else {
        message
    }
    return WorkshopDownloadTaskUi(
        publishedFileId = publishedFileId,
        title = title,
        status = normalizedStatus,
        message = normalizedMessage,
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
        downloadLog = downloadLog,
        preservePartialDownload = preservePartialDownload,
    )
}

internal fun WorkshopDownloadTaskUi.toRecord(): WorkshopDownloadTaskRecord = WorkshopDownloadTaskRecord(
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
    downloadLog = downloadLog,
    preservePartialDownload = preservePartialDownload,
)
