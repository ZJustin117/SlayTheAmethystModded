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
import io.stamethyst.ui.main.NewlyImportedModHighlightStore
import io.stamethyst.ui.preferences.LauncherPreferences
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
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
        const val EXTRA_DOWNLOAD_COUNT = "io.stamethyst.extra.DOWNLOAD_COUNT"
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
        private const val ACTIVE_MARKER_STALE_MS = 30_000L
        private const val ACTIVE_MARKER_HEARTBEAT_MS = 5_000L
        private const val ACTIVE_MARKER_DIR = "workshop/active_downloads"

        @Volatile
        private var activeDownloadKeySnapshot: Set<String> = emptySet()

        fun isActiveDownload(publishedFileId: ULong): Boolean {
            return activeDownloadKeySnapshot.any { it.substringAfter(':') == publishedFileId.toString() }
        }

        fun isActiveDownload(context: Context, publishedFileId: ULong): Boolean {
            if (isActiveDownload(publishedFileId)) return true
            val markerFile = activeDownloadMarkerFile(context, publishedFileId)
            if (!markerFile.isFile) return false
            return System.currentTimeMillis() - markerFile.lastModified() <= ACTIVE_MARKER_STALE_MS
        }

        private fun activeDownloadMarkerFile(context: Context, publishedFileId: ULong): File =
            File(context.applicationContext.filesDir, "$ACTIVE_MARKER_DIR/$publishedFileId.active")

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
                putExtra(EXTRA_DOWNLOAD_COUNT, details.summary.downloadCount)
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
                        dependency.downloadCount,
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
            repeat(availableDownloadSlots(context)) {
                val task = WorkshopDownloadTaskStore(context).nextQueuedTask() ?: return
                start(context, task.details, null)
            }
        }

        private fun availableDownloadSlots(context: Context): Int {
            val limit = LauncherPreferences.readWorkshopMaxConcurrentDownloads(context)
            val runningCount = activeDownloadKeySnapshot.size
            return (limit - runningCount).coerceAtLeast(0)
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
    private var workerThreads: MutableMap<String, Thread> = ConcurrentHashMap()

    private val requestedStops: MutableMap<String, StopReason> = ConcurrentHashMap()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val safeIntent = intent ?: return START_NOT_STICKY
        if (safeIntent.action == ACTION_PAUSE || safeIntent.action == ACTION_CANCEL) {
            handleControlIntent(safeIntent)
            return START_NOT_STICKY
        }
        if (safeIntent.action != ACTION_DOWNLOAD) return START_NOT_STICKY

        val receiver = extractResultReceiver(safeIntent)
        val requestedDownloadKey = safeIntent.downloadKeyOrNull()
        if (requestedDownloadKey != null && workerThreads.containsKey(requestedDownloadKey)) {
            startForeground(NOTIFICATION_ID, buildNotification("下载正在进行"))
            receiver?.send(RESULT_PROGRESS, Bundle().apply {
                putString(EXTRA_MESSAGE, "下载已在后台进行中")
                putString(EXTRA_TASK_STATUS, "Downloading")
            })
            return START_NOT_STICKY
        }
        if (workerThreads.size >= LauncherPreferences.readWorkshopMaxConcurrentDownloads(applicationContext)) {
            receiver?.send(RESULT_PROGRESS, Bundle().apply {
                putString(EXTRA_MESSAGE, "等待下载")
                putString(EXTRA_TASK_STATUS, "Queued")
            })
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification("准备下载"))
        val startingDetails = extractDetails(safeIntent)
        val startingTaskStore = WorkshopDownloadTaskStore(applicationContext)
        startingTaskStore.clearDeletedMarker(startingDetails.summary.publishedFileId)
        startingTaskStore.update(startingDetails.summary.publishedFileId) {
            it.copy(
                status = WorkshopDownloadTaskStatus.Resolving,
                message = "正在解析下载内容",
                updatedAtMillis = System.currentTimeMillis(),
                downloadLog = buildInitialDownloadLog(startingDetails),
            )
        }
        startingTaskStore.appendLog(startingDetails.summary.publishedFileId, "服务已启动，准备解析下载内容")
        val activeDownloadKey = requireNotNull(requestedDownloadKey)
        requestedStops.remove(activeDownloadKey)
        touchActiveDownloadMarker(startingDetails.summary.publishedFileId)
        val thread = Thread({ runDownload(startId, safeIntent, receiver) }, "STS-WorkshopDownload-$activeDownloadKey")
        workerThreads[activeDownloadKey] = thread
        activeDownloadKeySnapshot = workerThreads.keys.toSet()
        thread.start()
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        val threads = workerThreads.values.toList()
        workerThreads.clear()
        activeDownloadKeySnapshot = emptySet()
        threads.forEach { thread ->
            thread.interrupt()
        }
        super.onDestroy()
    }

    private fun handleControlIntent(intent: Intent) {
        val requestedDownloadKey = intent.downloadKeyOrNull() ?: return
        val thread = workerThreads[requestedDownloadKey] ?: return
        requestedStops[requestedDownloadKey] = if (intent.action == ACTION_PAUSE) StopReason.Pause else StopReason.Cancel
        thread.interrupt()
    }

    private fun runDownload(startId: Int, intent: Intent, receiver: ResultReceiver?) {
        var details = extractDetails(intent)
        val metadataStore = WorkshopMetadataStore(applicationContext)
        val taskStore = WorkshopDownloadTaskStore(applicationContext)
        val service = WorkshopService(applicationContext)
        val downloadKey = requireNotNull(intent.downloadKeyOrNull())
        val activeDownloadHeartbeat = startActiveDownloadHeartbeat(details.summary.publishedFileId, downloadKey)
        var existingRecord: WorkshopInstalledModRecord? = null
        try {
            val previousRecord = metadataStore.findByPublishedFileId(
                appId = details.summary.appId,
                publishedFileId = details.summary.publishedFileId,
            )
            existingRecord = previousRecord?.restoreCandidateForInterruptedUpdate()
            val queuedMessage = if (existingRecord != null) "等待更新" else "等待下载"
            metadataStore.upsert(
                (previousRecord ?: WorkshopInstalledModRecord(
                    appId = details.summary.appId,
                    publishedFileId = details.summary.publishedFileId,
                    title = details.summary.title,
                    description = details.summary.description,
                    previewUrl = details.summary.previewUrl,
                    versionText = details.summary.updatedAtMillis.toString(),
                    updatedAtMillis = details.summary.updatedAtMillis,
                    installedAtMillis = System.currentTimeMillis(),
                    localJarPath = "",
                    dependencies = details.dependencies,
                )).copy(
                    title = details.summary.title,
                    description = details.summary.description,
                    previewUrl = details.summary.previewUrl,
                    cardState = WorkshopModCardState.Downloading,
                    statusText = queuedMessage,
                    dependencies = details.dependencies,
                )
            )
            taskStore.update(details.summary.publishedFileId) {
                it.copy(status = WorkshopDownloadTaskStatus.Queued, message = queuedMessage, updatedAtMillis = System.currentTimeMillis())
            }
            taskStore.appendLog(details.summary.publishedFileId, queuedMessage)
            sendProgress(receiver, queuedMessage, "Queued")
            runBlocking {
                if (!details.hasDownloadSource()) {
                    taskStore.update(details.summary.publishedFileId) {
                        it.copy(status = WorkshopDownloadTaskStatus.Resolving, message = "正在解析下载内容", updatedAtMillis = System.currentTimeMillis())
                    }
                    taskStore.appendLog(details.summary.publishedFileId, "正在通过 Steam 创意工坊解析下载内容")
                    sendProgress(receiver, "正在解析下载内容", "Resolving")
                    details = service.getDetails(details.summary.appId, details.summary.publishedFileId)
                    taskStore.update(details.summary.publishedFileId) { it.copy(details = details) }
                    taskStore.appendLog(
                        details.summary.publishedFileId,
                        "解析完成：fileUrl=${details.fileUrl?.takeIf(String::isNotBlank) ?: "<empty>"}, hcontentFile=${details.hcontentFile ?: "<empty>"}, depotId=${details.depotId ?: "<empty>"}",
                    )
                }
                downloadPreviewImageInBackground(service, metadataStore, details)
                val outputDir = File(applicationContext.filesDir, "workshop/${details.summary.appId}/${details.summary.publishedFileId}")
                cleanDownloadedContent(outputDir)
                taskStore.appendLog(details.summary.publishedFileId, "输出目录：${outputDir.absolutePath}")
                service.download(WorkshopDownloadRequest(details, outputDir)).collect { event ->
                    when (event) {
                        WorkshopDownloadEvent.Ignored -> Unit
                        is WorkshopDownloadEvent.Log -> {
                            taskStore.appendLog(details.summary.publishedFileId, event.message)
                            sendProgress(receiver, event.message, null)
                        }
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
                            taskStore.appendLog(details.summary.publishedFileId, event.progress.downloadLogLine())
                            sendProgress(receiver, "正在下载", "Downloading", event.progress)
                        }
                        is WorkshopDownloadEvent.StateChanged -> {
                            if (taskStore.isMarkedDeleted(details.summary.publishedFileId)) return@collect
                            val message = event.state.displayText()
                            updateNotification(message)
                            if (event.state == WorkshopDownloadState.Success) {
                                rememberCompletedJarBeforeFinalization(
                                    metadataStore = metadataStore,
                                    taskStore = taskStore,
                                    service = service,
                                    details = details,
                                )
                                taskStore.appendLog(details.summary.publishedFileId, "状态变更：$message")
                                return@collect
                            }
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
                            taskStore.appendLog(details.summary.publishedFileId, "状态变更：$message")
                            sendProgress(receiver, message, event.state.toTaskStatusName())
                        }
                        is WorkshopDownloadEvent.Completed -> {
                            if (requestedStops[downloadKey] == StopReason.Cancel || taskStore.isMarkedDeleted(details.summary.publishedFileId) || taskStore.find(details.summary.publishedFileId) == null) {
                                cleanOutput(details)
                                return@collect
                            }
                            val outputDir = File(applicationContext.filesDir, "workshop/${details.summary.appId}/${details.summary.publishedFileId}")
                            val jarArtifact = findDownloadedJar(outputDir)
                            val message: String
                            val record = if (jarArtifact != null) {
                                val downloadedMessage = if (countDownloadedJars(outputDir) > 1) {
                                    "下载完成，检测到多个 jar，已选择最大文件：${jarArtifact.relativePath}"
                                } else {
                                    "下载完成"
                                }
                                val autoImportEnabled = LauncherPreferences.isWorkshopAutoImportEnabled(applicationContext)
                                val preserveExistingUntilAutoImportFinishes = autoImportEnabled && existingRecord.hasInstalledJar()
                                var downloadedRecord = service.createInstalledRecord(details, jarArtifact)
                                if (!preserveExistingUntilAutoImportFinishes) {
                                    metadataStore.upsert(downloadedRecord)
                                }
                                taskStore.update(details.summary.publishedFileId) {
                                    it.copy(
                                        status = WorkshopDownloadTaskStatus.Completed,
                                        message = "下载完成，正在处理导入修补",
                                        progressPercent = 100,
                                        downloadedBytes = (it.totalBytes ?: it.downloadedBytes).coerceAtLeast(it.downloadedBytes),
                                        completedFiles = it.totalFiles ?: it.completedFiles,
                                        updatedAtMillis = System.currentTimeMillis(),
                                    )
                                }
                                taskStore.appendLog(details.summary.publishedFileId, "下载文件已落盘，进入导入修补阶段")
                                val previewImagePath = downloadPreviewImageIfNeeded(service, metadataStore, details)
                                downloadedRecord = downloadedRecord.copy(localPreviewImagePath = previewImagePath)
                                if (!preserveExistingUntilAutoImportFinishes) {
                                    metadataStore.upsert(downloadedRecord)
                                }
                                if (autoImportEnabled) {
                                    val jarFile = File(outputDir, jarArtifact.relativePath)
                                    when (val importResult = WorkshopAutoImporter.importDownloadedJar(applicationContext, details, jarFile)) {
                                        is WorkshopAutoImportResult.Imported -> {
                                            message = "下载完成，已自动导入 ${importResult.modName}"
                                            downloadedRecord.copy(
                                                localJarPath = importResult.storagePath,
                                                cardState = WorkshopModCardState.ImportedPatched,
                                                statusText = "已安装 ${importResult.modName}",
                                            )
                                        }
                                        is WorkshopAutoImportResult.Failed -> {
                                            message = "$downloadedMessage，自动导入失败：${importResult.message}"
                                            if (preserveExistingUntilAutoImportFinishes) {
                                                existingRecord!!.copy(statusText = message)
                                            } else {
                                                downloadedRecord.copy(statusText = "自动导入失败，请手动导入")
                                            }
                                        }
                                    }
                                } else {
                                    message = downloadedMessage
                                    downloadedRecord
                                }
                            } else {
                                val previewImagePath = downloadPreviewImageIfNeeded(service, metadataStore, details)
                                val texturePackDir = TextureReplacerWorkshopInstaller.install(applicationContext, details, outputDir)
                                message = "下载完成，已作为 Texture Replacer 资源包安装并启用"
                                service.createTexturePackRecord(details, texturePackDir)
                                    .copy(localPreviewImagePath = previewImagePath)
                            }
                            if (existingRecord == null) {
                                NewlyImportedModHighlightStore.mark(applicationContext, record.newlyImportedHighlightKeys())
                            }
                            metadataStore.upsert(record)
                            if (jarArtifact != null && record.cardState == WorkshopModCardState.ImportedPatched) {
                                cleanDownloadedContent(outputDir)
                            }
                            taskStore.update(details.summary.publishedFileId) {
                                it.copy(
                                    status = WorkshopDownloadTaskStatus.Completed,
                                    message = message,
                                    progressPercent = 100,
                                    updatedAtMillis = System.currentTimeMillis(),
                                )
                            }
                            taskStore.appendLog(details.summary.publishedFileId, message)
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
            when (requestedStops[downloadKey]) {
                StopReason.Pause -> {
                    taskStore.update(details.summary.publishedFileId) {
                        it.copy(status = WorkshopDownloadTaskStatus.Paused, message = "下载已暂停", updatedAtMillis = System.currentTimeMillis())
                    }
                    taskStore.appendLog(details.summary.publishedFileId, "下载已暂停")
                    restoreExistingRecordOrUpdateState(
                        metadataStore = metadataStore,
                        existingRecord = existingRecord,
                        details = details,
                        fallbackState = WorkshopModCardState.DownloadPaused,
                        fallbackStatusText = "下载已暂停",
                    )
                    receiver?.send(RESULT_PAUSED, Bundle().apply {
                        putString(EXTRA_MESSAGE, "下载已暂停")
                        putString(EXTRA_TASK_STATUS, "Paused")
                    })
                }
                StopReason.Cancel -> {
                    cleanOutput(details)
                    taskStore.update(details.summary.publishedFileId) {
                        it.copy(status = WorkshopDownloadTaskStatus.Cancelled, message = "下载已取消", updatedAtMillis = System.currentTimeMillis())
                    }
                    taskStore.appendLog(details.summary.publishedFileId, "下载已取消，已清理输出目录")
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
                    taskStore.appendLog(details.summary.publishedFileId, "下载失败：$error")
                    taskStore.appendLog(details.summary.publishedFileId, throwable.stackTraceToString())
                    restoreExistingRecordOrUpdateState(
                        metadataStore = metadataStore,
                        existingRecord = existingRecord,
                        details = details,
                        fallbackState = WorkshopModCardState.DownloadFailed,
                        fallbackStatusText = "下载失败：${throwable.message ?: throwable.javaClass.simpleName}",
                    )
                    receiver?.send(RESULT_FAILURE, errorBundle(throwable))
                }
            }
        } finally {
            activeDownloadHeartbeat.interrupt()
            clearActiveDownloadMarker(details.summary.publishedFileId)
            workerThreads.remove(downloadKey)
            requestedStops.remove(downloadKey)
            activeDownloadKeySnapshot = workerThreads.keys.toSet()
            if (workerThreads.isEmpty()) {
                stopForegroundCompat()
                stopSelf()
            }
            startNextQueued(applicationContext)
        }
    }

    private fun startActiveDownloadHeartbeat(publishedFileId: ULong, downloadKey: String): Thread {
        touchActiveDownloadMarker(publishedFileId)
        return thread(name = "STS-WorkshopDownloadHeartbeat-$downloadKey", isDaemon = true) {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(ACTIVE_MARKER_HEARTBEAT_MS)
                    touchActiveDownloadMarker(publishedFileId)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
    }

    private fun touchActiveDownloadMarker(publishedFileId: ULong) {
        runCatching {
            val markerFile = activeDownloadMarkerFile(applicationContext, publishedFileId)
            markerFile.parentFile?.mkdirs()
            markerFile.writeText(System.currentTimeMillis().toString(), StandardCharsets.UTF_8)
        }
    }

    private fun clearActiveDownloadMarker(publishedFileId: ULong) {
        runCatching { activeDownloadMarkerFile(applicationContext, publishedFileId).delete() }
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
                downloadCount = intent.getLongExtra(EXTRA_DOWNLOAD_COUNT, 0L),
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

    private fun downloadPreviewImageInBackground(
        service: WorkshopService,
        metadataStore: WorkshopMetadataStore,
        details: WorkshopItemDetails,
    ) {
        thread(name = "STS-WorkshopPreview-${details.summary.publishedFileId}", isDaemon = true) {
            runCatching { downloadPreviewImageIfNeeded(service, metadataStore, details) }
        }
    }

    private fun cleanOutput(details: WorkshopItemDetails) {
        File(applicationContext.filesDir, "workshop/${details.summary.appId}/${details.summary.publishedFileId}").deleteRecursively()
    }

    private fun restoreExistingRecordOrUpdateState(
        metadataStore: WorkshopMetadataStore,
        existingRecord: WorkshopInstalledModRecord?,
        details: WorkshopItemDetails,
        fallbackState: WorkshopModCardState,
        fallbackStatusText: String,
    ) {
        if (existingRecord != null) {
            metadataStore.upsert(existingRecord)
            return
        }
        metadataStore.updateState(
            appId = details.summary.appId,
            publishedFileId = details.summary.publishedFileId,
            state = fallbackState,
            statusText = fallbackStatusText,
        )
    }

    private fun rememberCompletedJarBeforeFinalization(
        metadataStore: WorkshopMetadataStore,
        taskStore: WorkshopDownloadTaskStore,
        service: WorkshopService,
        details: WorkshopItemDetails,
    ) {
        val task = taskStore.find(details.summary.publishedFileId)
        if (task?.status == WorkshopDownloadTaskStatus.Completed) return
        val outputDir = File(applicationContext.filesDir, "workshop/${details.summary.appId}/${details.summary.publishedFileId}")
        val jarArtifact = findDownloadedJar(outputDir) ?: return
        val existingRecord = metadataStore.findByPublishedFileId(
            appId = details.summary.appId,
            publishedFileId = details.summary.publishedFileId,
        )
        if (existingRecord?.localJarPath?.trim()?.let { path -> File(path).isAbsolute && File(path).isFile } == true) {
            return
        }
        metadataStore.upsert(service.createInstalledRecord(details, jarArtifact))
        taskStore.update(details.summary.publishedFileId) {
            it.copy(
                status = WorkshopDownloadTaskStatus.Completed,
                message = "下载完成，正在处理导入修补",
                progressPercent = 100,
                downloadedBytes = (it.totalBytes ?: it.downloadedBytes).coerceAtLeast(it.downloadedBytes),
                completedFiles = it.totalFiles ?: it.completedFiles,
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
        taskStore.appendLog(details.summary.publishedFileId, "下载文件已落盘，进入导入修补阶段")
    }

    private fun WorkshopInstalledModRecord?.hasInstalledJar(): Boolean {
        val path = this?.localJarPath?.trim().orEmpty()
        return path.isNotEmpty() && File(path).isAbsolute && File(path).isFile
    }

    private fun WorkshopInstalledModRecord.restoreCandidateForInterruptedUpdate(): WorkshopInstalledModRecord? {
        if (cardState == WorkshopModCardState.UpdateAvailable) {
            return this
        }
        if (cardState != WorkshopModCardState.Downloading) {
            return null
        }
        return when (contentKind) {
            WorkshopInstalledContentKind.TexturePack -> copy(
                cardState = WorkshopModCardState.TexturePackInstalled,
                statusText = statusText.ifBlank { "已作为 Texture Replacer 资源包安装并启用" },
            )
            WorkshopInstalledContentKind.NonStandard -> copy(
                cardState = WorkshopModCardState.NonStandardDownloaded,
                statusText = statusText.ifBlank { "该模组不是标准 jar 格式，请手动处理" },
            )
            WorkshopInstalledContentKind.JarMod -> if (localJarPath.isNotBlank()) {
                copy(
                    cardState = WorkshopModCardState.ImportedPatched,
                    statusText = statusText.ifBlank { "已安装" },
                )
            } else {
                null
            }
        }
    }

    private fun cleanDownloadedContent(outputDir: File) {
        if (!outputDir.isDirectory) return
        outputDir.listFiles().orEmpty().forEach { file ->
            if (!file.name.startsWith("preview.", ignoreCase = true)) {
                file.deleteRecursively()
            }
        }
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

private fun WorkshopDownloadProgress.downloadLogLine(): String = buildString {
    append("下载进度：writtenBytes=").append(writtenBytes)
    totalBytes?.let { append(", totalBytes=").append(it) }
    progressPercent()?.let { append(", percent=").append(it).append('%') }
    if (completedFiles != null || totalFiles != null) {
        append(", files=").append(completedFiles ?: 0).append('/').append(totalFiles ?: "?")
    }
    if (completedChunks != null || totalChunks != null) {
        append(", chunks=").append(completedChunks ?: 0).append('/').append(totalChunks ?: "?")
    }
}

private fun buildInitialDownloadLog(details: WorkshopItemDetails): String = buildString {
    appendLine("创意工坊下载日志")
    appendLine("Title: ${details.summary.title}")
    appendLine("App ID: ${details.summary.appId}")
    appendLine("Workshop ID: ${details.summary.publishedFileId}")
    appendLine("Author: ${details.summary.authorName.ifBlank { "<empty>" }}")
    appendLine("Remote updatedAtMillis: ${details.summary.updatedAtMillis}")
    appendLine("Declared fileSizeBytes: ${details.summary.fileSizeBytes}")
    appendLine("Preview URL: ${details.summary.previewUrl.ifBlank { "<empty>" }}")
    appendLine("Initial fileUrl: ${details.fileUrl?.takeIf(String::isNotBlank) ?: "<empty>"}")
    appendLine("Initial hcontentFile: ${details.hcontentFile ?: "<empty>"}")
    appendLine("Initial depotId: ${details.depotId ?: "<empty>"}")
    appendLine("Dependencies: ${details.dependencies.size}")
}.trimEnd()

private fun WorkshopInstalledModRecord.newlyImportedHighlightKeys(): List<String> {
    return listOf(
        "workshop:$appId:$publishedFileId",
        localJarPath,
        texturePackPath,
    ).map { it.trim() }.filter { it.isNotEmpty() }
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
            downloadCount = parts.getOrNull(8)?.toLongOrNull() ?: 0L,
        )
    }.toList()
}
