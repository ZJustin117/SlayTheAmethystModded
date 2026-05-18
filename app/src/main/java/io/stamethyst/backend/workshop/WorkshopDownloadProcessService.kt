package io.stamethyst.backend.workshop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ResultReceiver
import androidx.core.app.NotificationCompat
import io.stamethyst.R
import io.stamethyst.LauncherActivity
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import kotlinx.coroutines.runBlocking

class WorkshopDownloadProcessService : Service() {
    companion object {
        const val ACTION_DOWNLOAD = "io.stamethyst.action.WORKSHOP_DOWNLOAD"
        const val ACTION_PAUSE = "io.stamethyst.action.WORKSHOP_DOWNLOAD_PAUSE"
        const val ACTION_CANCEL = "io.stamethyst.action.WORKSHOP_DOWNLOAD_CANCEL"

        const val EXTRA_RESULT_RECEIVER = "io.stamethyst.extra.RESULT_RECEIVER"
        const val EXTRA_APP_ID = "io.stamethyst.extra.APP_ID"
        const val EXTRA_PUBLISHED_FILE_ID = "io.stamethyst.extra.PUBLISHED_FILE_ID"
        const val EXTRA_TITLE = "io.stamethyst.extra.TITLE"
        const val EXTRA_DESCRIPTION = "io.stamethyst.extra.DESCRIPTION"
        const val EXTRA_PREVIEW_URL = "io.stamethyst.extra.PREVIEW_URL"
        const val EXTRA_AUTHOR_NAME = "io.stamethyst.extra.AUTHOR_NAME"
        const val EXTRA_FILE_SIZE_BYTES = "io.stamethyst.extra.FILE_SIZE_BYTES"
        const val EXTRA_UPDATED_AT_MILLIS = "io.stamethyst.extra.UPDATED_AT_MILLIS"
        const val EXTRA_FILE_URL = "io.stamethyst.extra.FILE_URL"
        const val EXTRA_HCONTENT_FILE = "io.stamethyst.extra.HCONTENT_FILE"
        const val EXTRA_DEPOT_ID = "io.stamethyst.extra.DEPOT_ID"
        const val EXTRA_JSON_METADATA = "io.stamethyst.extra.JSON_METADATA"
        const val EXTRA_DEPENDENCIES = "io.stamethyst.extra.DEPENDENCIES"
        const val EXTRA_MESSAGE = "io.stamethyst.extra.MESSAGE"
        const val EXTRA_TASK_STATUS = "io.stamethyst.extra.TASK_STATUS"
        const val EXTRA_WRITTEN_BYTES = "io.stamethyst.extra.WRITTEN_BYTES"
        const val EXTRA_TOTAL_BYTES = "io.stamethyst.extra.TOTAL_BYTES"
        const val EXTRA_PROGRESS_PERCENT = "io.stamethyst.extra.PROGRESS_PERCENT"
        const val EXTRA_COMPLETED_FILES = "io.stamethyst.extra.COMPLETED_FILES"
        const val EXTRA_TOTAL_FILES = "io.stamethyst.extra.TOTAL_FILES"
        const val EXTRA_COMPLETED_CHUNKS = "io.stamethyst.extra.COMPLETED_CHUNKS"
        const val EXTRA_TOTAL_CHUNKS = "io.stamethyst.extra.TOTAL_CHUNKS"
        const val EXTRA_ERROR_CLASS = "io.stamethyst.extra.ERROR_CLASS"
        const val EXTRA_ERROR_MESSAGE = "io.stamethyst.extra.ERROR_MESSAGE"
        const val EXTRA_ERROR_STACKTRACE = "io.stamethyst.extra.ERROR_STACKTRACE"

        const val RESULT_PROGRESS = 1
        const val RESULT_COMPLETED = 2
        const val RESULT_FAILURE = 3
        const val RESULT_PAUSED = 4
        const val RESULT_CANCELLED = 5
        private const val CHANNEL_ID = "workshop_download"
        private const val NOTIFICATION_ID = 646570

        @Volatile
        private var activeDownloadKeySnapshot: String? = null

        fun isActiveDownload(publishedFileId: ULong): Boolean {
            return activeDownloadKeySnapshot?.substringAfter(':') == publishedFileId.toString()
        }

        fun start(context: Context, details: WorkshopItemDetails, receiver: ResultReceiver? = null) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, WorkshopDownloadProcessService::class.java).apply {
                action = ACTION_DOWNLOAD
                putExtra(EXTRA_RESULT_RECEIVER, receiver)
                putExtra(EXTRA_APP_ID, details.summary.appId.toString())
                putExtra(EXTRA_PUBLISHED_FILE_ID, details.summary.publishedFileId.toString())
                putExtra(EXTRA_TITLE, details.summary.title)
                putExtra(EXTRA_DESCRIPTION, details.summary.description)
                putExtra(EXTRA_PREVIEW_URL, details.summary.previewUrl)
                putExtra(EXTRA_AUTHOR_NAME, details.summary.authorName)
                putExtra(EXTRA_FILE_SIZE_BYTES, details.summary.fileSizeBytes)
                putExtra(EXTRA_UPDATED_AT_MILLIS, details.summary.updatedAtMillis)
                putExtra(EXTRA_FILE_URL, details.fileUrl)
                putExtra(EXTRA_HCONTENT_FILE, details.hcontentFile?.toString())
                putExtra(EXTRA_DEPOT_ID, details.depotId?.toString())
                putExtra(EXTRA_JSON_METADATA, details.jsonMetadata)
                putExtra(EXTRA_DEPENDENCIES, details.dependencies.joinToString("\n") { dependency ->
                    listOf(
                        dependency.appId,
                        dependency.publishedFileId,
                        dependency.title,
                        dependency.previewUrl,
                        dependency.description,
                        dependency.authorName,
                        dependency.fileSizeBytes,
                        dependency.updatedAtMillis,
                    ).joinToString("\t")
                })
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent)
            } else {
                appContext.startService(intent)
            }
        }

        fun start(context: Context, summary: WorkshopItemSummary, receiver: ResultReceiver? = null) {
            start(context, WorkshopItemDetails(summary = summary), receiver)
        }

        fun startNextQueued(context: Context) {
            val task = WorkshopDownloadTaskStore(context).nextQueuedTask() ?: return
            start(context, task.details, null)
        }

        fun pause(context: Context, appId: UInt, publishedFileId: ULong, receiver: ResultReceiver? = null) {
            control(context, ACTION_PAUSE, appId, publishedFileId, receiver)
        }

        fun cancel(context: Context, appId: UInt, publishedFileId: ULong, receiver: ResultReceiver? = null) {
            control(context, ACTION_CANCEL, appId, publishedFileId, receiver)
        }

        private fun control(context: Context, action: String, appId: UInt, publishedFileId: ULong, receiver: ResultReceiver?) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, WorkshopDownloadProcessService::class.java).apply {
                this.action = action
                putExtra(EXTRA_APP_ID, appId.toString())
                putExtra(EXTRA_PUBLISHED_FILE_ID, publishedFileId.toString())
                putExtra(EXTRA_RESULT_RECEIVER, receiver)
            }
            appContext.startService(intent)
        }
    }

    @Volatile
    private var workerThread: Thread? = null

    @Volatile
    private var activeDownloadKey: String? = null

    @Volatile
    private var requestedStop: StopReason? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val safeIntent = intent ?: return START_NOT_STICKY
        if (safeIntent.action == ACTION_PAUSE || safeIntent.action == ACTION_CANCEL) {
            handleControlIntent(safeIntent)
            return START_NOT_STICKY
        }
        if (safeIntent.action != ACTION_DOWNLOAD) return START_NOT_STICKY

        val receiver = extractResultReceiver(safeIntent)
        if (workerThread != null) {
            startForeground(NOTIFICATION_ID, buildNotification("下载正在进行"))
            val requestedDownloadKey = safeIntent.downloadKeyOrNull()
            if (requestedDownloadKey != null && requestedDownloadKey == activeDownloadKey) {
                receiver?.send(RESULT_PROGRESS, Bundle().apply {
                    putString(EXTRA_MESSAGE, "下载已在后台进行中")
                    putString(EXTRA_TASK_STATUS, "Downloading")
                })
            } else {
                receiver?.send(RESULT_FAILURE, errorBundle(IllegalStateException("Workshop download process is already busy")))
            }
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification("准备下载"))
        requestedStop = null
        val startingDetails = extractDetails(safeIntent)
        val startingTaskStore = WorkshopDownloadTaskStore(applicationContext)
        startingTaskStore.clearDeletedMarker(startingDetails.summary.publishedFileId)
        startingTaskStore.update(startingDetails.summary.publishedFileId) {
            it.copy(status = WorkshopDownloadTaskStatus.Resolving, message = "正在解析下载内容", updatedAtMillis = System.currentTimeMillis())
        }
        val thread = Thread({ runDownload(startId, safeIntent, receiver) }, "STS-WorkshopDownload")
        activeDownloadKey = safeIntent.downloadKeyOrNull()
        activeDownloadKeySnapshot = activeDownloadKey
        workerThread = thread
        thread.start()
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        val thread = workerThread
        workerThread = null
        activeDownloadKey = null
        activeDownloadKeySnapshot = null
        requestedStop = StopReason.Cancel
        thread?.interrupt()
        super.onDestroy()
    }

    private fun handleControlIntent(intent: Intent) {
        val requestedDownloadKey = intent.downloadKeyOrNull() ?: return
        if (requestedDownloadKey != activeDownloadKey) return
        requestedStop = if (intent.action == ACTION_PAUSE) StopReason.Pause else StopReason.Cancel
        workerThread?.interrupt()
    }

    private fun runDownload(startId: Int, intent: Intent, receiver: ResultReceiver?) {
        var details = extractDetails(intent)
        val metadataStore = WorkshopMetadataStore(applicationContext)
        val taskStore = WorkshopDownloadTaskStore(applicationContext)
        val service = WorkshopService(applicationContext)
        try {
            metadataStore.upsert(
                WorkshopInstalledModRecord(
                    appId = details.summary.appId,
                    publishedFileId = details.summary.publishedFileId,
                    title = details.summary.title,
                    description = details.summary.description,
                    previewUrl = details.summary.previewUrl,
                    versionText = details.summary.updatedAtMillis.toString(),
                    updatedAtMillis = details.summary.updatedAtMillis,
                    installedAtMillis = System.currentTimeMillis(),
                    localJarPath = "",
                    cardState = WorkshopModCardState.Downloading,
                    statusText = "等待下载",
                    dependencies = details.dependencies,
                )
            )
            taskStore.update(details.summary.publishedFileId) {
                it.copy(status = WorkshopDownloadTaskStatus.Queued, message = "等待下载", updatedAtMillis = System.currentTimeMillis())
            }
            downloadPreviewImageIfNeeded(service, metadataStore, details)
            sendProgress(receiver, "等待下载", "Queued")
            runBlocking {
                if (!details.hasDownloadSource()) {
                    taskStore.update(details.summary.publishedFileId) {
                        it.copy(status = WorkshopDownloadTaskStatus.Resolving, message = "正在解析下载内容", updatedAtMillis = System.currentTimeMillis())
                    }
                    sendProgress(receiver, "正在解析下载内容", "Resolving")
                    details = service.getDetails(details.summary.appId, details.summary.publishedFileId)
                    taskStore.update(details.summary.publishedFileId) { it.copy(details = details) }
                    downloadPreviewImageIfNeeded(service, metadataStore, details)
                }
                val outputDir = File(applicationContext.filesDir, "workshop/${details.summary.appId}/${details.summary.publishedFileId}")
                service.download(WorkshopDownloadRequest(details, outputDir)).collect { event ->
                    when (event) {
                        WorkshopDownloadEvent.Ignored -> Unit
                        is WorkshopDownloadEvent.Log -> sendProgress(receiver, event.message, null)
                        is WorkshopDownloadEvent.Progress -> {
                            if (taskStore.isMarkedDeleted(details.summary.publishedFileId)) return@collect
                            taskStore.update(details.summary.publishedFileId) {
                                it.copy(
                                    status = WorkshopDownloadTaskStatus.Downloading,
                                    message = "正在下载",
                                    progressPercent = event.progress.progressPercent(),
                                    downloadedBytes = event.progress.writtenBytes,
                                    totalBytes = event.progress.totalBytes ?: it.totalBytes,
                                    completedFiles = event.progress.completedFiles ?: it.completedFiles,
                                    totalFiles = event.progress.totalFiles ?: it.totalFiles,
                                    completedChunks = event.progress.completedChunks ?: it.completedChunks,
                                    totalChunks = event.progress.totalChunks ?: it.totalChunks,
                                    updatedAtMillis = System.currentTimeMillis(),
                                )
                            }
                            sendProgress(receiver, "正在下载", "Downloading", event.progress)
                        }
                        is WorkshopDownloadEvent.StateChanged -> {
                            if (taskStore.isMarkedDeleted(details.summary.publishedFileId)) return@collect
                            val message = event.state.displayText()
                            updateNotification(message)
                            if (event.state != WorkshopDownloadState.Success) {
                                metadataStore.updateState(
                                    appId = details.summary.appId,
                                    publishedFileId = details.summary.publishedFileId,
                                    state = WorkshopModCardState.Downloading,
                                    statusText = message,
                                )
                            }
                            taskStore.update(details.summary.publishedFileId) {
                                it.copy(
                                    status = event.state.toDownloadTaskStatus(),
                                    message = message,
                                    updatedAtMillis = System.currentTimeMillis(),
                                )
                            }
                            sendProgress(receiver, message, event.state.toTaskStatusName())
                        }
                        is WorkshopDownloadEvent.Completed -> {
                            if (requestedStop == StopReason.Cancel || taskStore.isMarkedDeleted(details.summary.publishedFileId) || taskStore.find(details.summary.publishedFileId) == null) {
                                cleanOutput(details)
                                return@collect
                            }
                            val outputDir = File(applicationContext.filesDir, "workshop/${details.summary.appId}/${details.summary.publishedFileId}")
                            val jarArtifact = findDownloadedJar(outputDir)
                            val previewImagePath = downloadPreviewImageIfNeeded(service, metadataStore, details)
                            val message: String
                            val record = if (jarArtifact != null) {
                                message = if (countDownloadedJars(outputDir) > 1) {
                                    "下载完成，检测到多个 jar，已选择最大文件：${jarArtifact.relativePath}"
                                } else {
                                    "下载完成"
                                }
                                service.createInstalledRecord(details, jarArtifact)
                            } else {
                                val texturePackDir = TextureReplacerWorkshopInstaller.install(applicationContext, details, outputDir)
                                message = "下载完成，已作为 Texture Replacer 资源包安装并启用"
                                service.createTexturePackRecord(details, texturePackDir)
                            }.copy(localPreviewImagePath = previewImagePath)
                            metadataStore.upsert(record)
                            taskStore.update(details.summary.publishedFileId) {
                                it.copy(
                                    status = WorkshopDownloadTaskStatus.Completed,
                                    message = message,
                                    progressPercent = 100,
                                    updatedAtMillis = System.currentTimeMillis(),
                                )
                            }
                            receiver?.send(
                                RESULT_COMPLETED,
                                Bundle().apply {
                                    putString(EXTRA_MESSAGE, message)
                                    putString(EXTRA_TASK_STATUS, "Completed")
                                }
                            )
                        }
                        is WorkshopDownloadEvent.Failed -> error(
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
        } catch (throwable: Throwable) {
            when (requestedStop) {
                StopReason.Pause -> {
                    taskStore.update(details.summary.publishedFileId) {
                        it.copy(status = WorkshopDownloadTaskStatus.Paused, message = "下载已暂停", updatedAtMillis = System.currentTimeMillis())
                    }
                    metadataStore.updateState(
                        appId = details.summary.appId,
                        publishedFileId = details.summary.publishedFileId,
                        state = WorkshopModCardState.DownloadPaused,
                        statusText = "下载已暂停",
                    )
                    receiver?.send(RESULT_PAUSED, Bundle().apply {
                        putString(EXTRA_MESSAGE, "下载已暂停")
                        putString(EXTRA_TASK_STATUS, "Paused")
                    })
                }
                StopReason.Cancel -> {
                    cleanOutput(details)
                    taskStore.remove(details.summary.publishedFileId)
                    metadataStore.remove(details.summary.appId, details.summary.publishedFileId)
                    receiver?.send(RESULT_CANCELLED, Bundle().apply {
                        putString(EXTRA_MESSAGE, "下载已取消")
                        putString(EXTRA_TASK_STATUS, "Cancelled")
                    })
                }
                null -> {
                    cleanOutput(details)
                    val error = throwable.message ?: throwable.javaClass.simpleName
                    taskStore.update(details.summary.publishedFileId) {
                        it.copy(
                            status = WorkshopDownloadTaskStatus.Failed,
                            message = "下载失败：$error",
                            errorClass = throwable.javaClass.name,
                            errorMessage = error,
                            errorStackTrace = throwable.stackTraceToString(),
                            updatedAtMillis = System.currentTimeMillis(),
                        )
                    }
                    metadataStore.updateState(
                        appId = details.summary.appId,
                        publishedFileId = details.summary.publishedFileId,
                        state = WorkshopModCardState.DownloadFailed,
                        statusText = "下载失败：${throwable.message ?: throwable.javaClass.simpleName}",
                    )
                    receiver?.send(RESULT_FAILURE, errorBundle(throwable))
                }
            }
        } finally {
            if (Thread.currentThread() === workerThread) {
                workerThread = null
                activeDownloadKey = null
                activeDownloadKeySnapshot = null
            }
            stopForegroundCompat()
            stopSelf()
            startNextQueued(applicationContext)
        }
    }

    private fun extractDetails(intent: Intent): WorkshopItemDetails {
        val appId = intent.getStringExtra(EXTRA_APP_ID)?.toUIntOrNull() ?: error("Missing workshop app id")
        val publishedFileId = intent.getStringExtra(EXTRA_PUBLISHED_FILE_ID)?.toULongOrNull()
            ?: error("Missing workshop published file id")
        return WorkshopItemDetails(
            summary = WorkshopItemSummary(
                appId = appId,
                publishedFileId = publishedFileId,
                title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                description = intent.getStringExtra(EXTRA_DESCRIPTION).orEmpty(),
                previewUrl = intent.getStringExtra(EXTRA_PREVIEW_URL).orEmpty(),
                authorName = intent.getStringExtra(EXTRA_AUTHOR_NAME).orEmpty(),
                fileSizeBytes = intent.getLongExtra(EXTRA_FILE_SIZE_BYTES, 0L),
                updatedAtMillis = intent.getLongExtra(EXTRA_UPDATED_AT_MILLIS, 0L),
            ),
            fileUrl = intent.getStringExtra(EXTRA_FILE_URL),
            hcontentFile = intent.getStringExtra(EXTRA_HCONTENT_FILE)?.toULongOrNull(),
            depotId = intent.getStringExtra(EXTRA_DEPOT_ID)?.toUIntOrNull(),
            jsonMetadata = intent.getStringExtra(EXTRA_JSON_METADATA).orEmpty(),
            dependencies = intent.getStringExtra(EXTRA_DEPENDENCIES).orEmpty().parseDependenciesExtra(),
        )
    }

    private fun WorkshopItemDetails.hasDownloadSource(): Boolean = !fileUrl.isNullOrBlank() || hcontentFile != null

    private fun sendProgress(
        receiver: ResultReceiver?,
        message: String,
        taskStatus: String?,
        progress: WorkshopDownloadProgress? = null,
    ) {
        updateNotification(message)
        receiver?.send(
            RESULT_PROGRESS,
            Bundle().apply {
                putString(EXTRA_MESSAGE, message)
                if (taskStatus != null) putString(EXTRA_TASK_STATUS, taskStatus)
                progress?.let { current ->
                    putLong(EXTRA_WRITTEN_BYTES, current.writtenBytes)
                    current.totalBytes?.let { putLong(EXTRA_TOTAL_BYTES, it) }
                    current.progressPercent()?.let { putInt(EXTRA_PROGRESS_PERCENT, it) }
                    current.completedFiles?.let { putInt(EXTRA_COMPLETED_FILES, it) }
                    current.totalFiles?.let { putInt(EXTRA_TOTAL_FILES, it) }
                    current.completedChunks?.let { putInt(EXTRA_COMPLETED_CHUNKS, it) }
                    current.totalChunks?.let { putInt(EXTRA_TOTAL_CHUNKS, it) }
                }
            }
        )
    }

    private fun extractResultReceiver(intent: Intent): ResultReceiver? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(EXTRA_RESULT_RECEIVER, ResultReceiver::class.java)
    } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(EXTRA_RESULT_RECEIVER)
    }

    private fun errorBundle(throwable: Throwable): Bundle {
        val stackTraceWriter = StringWriter()
        throwable.printStackTrace(PrintWriter(stackTraceWriter))
        return Bundle().apply {
            putString(EXTRA_ERROR_CLASS, throwable.javaClass.name)
            putString(EXTRA_ERROR_MESSAGE, throwable.message)
            putString(EXTRA_ERROR_STACKTRACE, stackTraceWriter.toString())
            putString(EXTRA_MESSAGE, "下载失败：${throwable.message ?: throwable.javaClass.simpleName}")
            putString(EXTRA_TASK_STATUS, "Failed")
        }
    }

    private fun Intent.downloadKeyOrNull(): String? {
        val appId = getStringExtra(EXTRA_APP_ID).orEmpty()
        val publishedFileId = getStringExtra(EXTRA_PUBLISHED_FILE_ID).orEmpty()
        return if (appId.isBlank() || publishedFileId.isBlank()) null else "$appId:$publishedFileId"
    }

    private fun downloadPreviewImageIfNeeded(
        service: WorkshopService,
        metadataStore: WorkshopMetadataStore,
        details: WorkshopItemDetails,
    ): String {
        val existing = metadataStore.findByPublishedFileId(
            appId = details.summary.appId,
            publishedFileId = details.summary.publishedFileId,
        )?.localPreviewImagePath.orEmpty()
        if (existing.isNotBlank()) return existing
        val previewImagePath = service.downloadPreviewImage(
            appId = details.summary.appId,
            publishedFileId = details.summary.publishedFileId,
            previewUrl = details.summary.previewUrl,
        )
        metadataStore.updatePreviewImagePath(
            appId = details.summary.appId,
            publishedFileId = details.summary.publishedFileId,
            localPreviewImagePath = previewImagePath,
        )
        return previewImagePath
    }

    private fun cleanOutput(details: WorkshopItemDetails) {
        File(applicationContext.filesDir, "workshop/${details.summary.appId}/${details.summary.publishedFileId}").deleteRecursively()
    }

    private fun findDownloadedJar(outputDir: File): WorkshopDownloadedArtifact? {
        val jar = outputDir.walkTopDown()
            .filter { file -> file.isFile && file.extension.equals("jar", ignoreCase = true) && file.length() > 0L }
            .maxByOrNull { it.length() }
            ?: return null
        return WorkshopDownloadedArtifact(
            relativePath = jar.relativeTo(outputDir).path,
            sizeBytes = jar.length(),
            modifiedAtMillis = jar.lastModified(),
        )
    }

    private fun countDownloadedJars(outputDir: File): Int = outputDir.walkTopDown()
        .count { file -> file.isFile && file.extension.equals("jar", ignoreCase = true) && file.length() > 0L }

    private fun buildNotification(message: String): Notification {
        ensureNotificationChannel()
        val intent = Intent(this, LauncherActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cloud)
            .setContentTitle("创意工坊下载")
            .setContentText(message)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(message: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, "创意工坊下载", NotificationManager.IMPORTANCE_LOW)
        manager.createNotificationChannel(channel)
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private enum class StopReason { Pause, Cancel }
}

private fun WorkshopDownloadProgress.progressPercent(): Int? {
    val safeTotalBytes = totalBytes?.takeIf { it > 0L } ?: return null
    return ((writtenBytes.coerceIn(0L, safeTotalBytes) * 100L) / safeTotalBytes).toInt().coerceIn(0, 100)
}

private fun WorkshopDownloadState.displayText(): String = when (this) {
    WorkshopDownloadState.Resolving -> "正在解析下载内容"
    WorkshopDownloadState.Downloading -> "正在下载"
    WorkshopDownloadState.Success -> "下载完成"
    WorkshopDownloadState.Failed -> "下载失败"
}

private fun WorkshopDownloadState.toTaskStatusName(): String = when (this) {
    WorkshopDownloadState.Resolving -> "Resolving"
    WorkshopDownloadState.Downloading -> "Downloading"
    WorkshopDownloadState.Success -> "Completed"
    WorkshopDownloadState.Failed -> "Failed"
}

private fun WorkshopDownloadState.toDownloadTaskStatus(): WorkshopDownloadTaskStatus = when (this) {
    WorkshopDownloadState.Resolving -> WorkshopDownloadTaskStatus.Resolving
    WorkshopDownloadState.Downloading -> WorkshopDownloadTaskStatus.Downloading
    WorkshopDownloadState.Success -> WorkshopDownloadTaskStatus.Completed
    WorkshopDownloadState.Failed -> WorkshopDownloadTaskStatus.Failed
}

private fun String.parseDependenciesExtra(): List<WorkshopItemSummary> {
    if (isBlank()) return emptyList()
    return lineSequence().mapNotNull { line ->
        val parts = line.split('\t')
        val appId = parts.getOrNull(0)?.toUIntOrNull() ?: 646570u
        val publishedFileId = parts.getOrNull(1)?.toULongOrNull() ?: return@mapNotNull null
        WorkshopItemSummary(
            appId = appId,
            publishedFileId = publishedFileId,
            title = parts.getOrNull(2).orEmpty(),
            previewUrl = parts.getOrNull(3).orEmpty(),
            description = parts.getOrNull(4).orEmpty(),
            authorName = parts.getOrNull(5).orEmpty(),
            fileSizeBytes = parts.getOrNull(6)?.toLongOrNull() ?: 0L,
            updatedAtMillis = parts.getOrNull(7)?.toLongOrNull() ?: 0L,
        )
    }.toList()
}
