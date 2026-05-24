package io.stamethyst.backend.workshop

import android.content.Context
import java.io.File

internal object WorkshopInterruptedDownloadRecovery {
    fun recoverFinishedTransferIfPossible(
        context: Context,
        metadataStore: WorkshopMetadataStore,
        taskStore: WorkshopDownloadTaskStore,
        task: WorkshopDownloadTaskRecord,
    ): Boolean {
        // Queued tasks may be brand-new updates waiting for the download service to start.
        if (task.status == WorkshopDownloadTaskStatus.Queued) return false
        val summary = task.details.summary
        val outputDir = workshopOutputDir(context, summary.appId, summary.publishedFileId)
        val existingRecord = metadataStore.findByPublishedFileId(
            appId = summary.appId,
            publishedFileId = summary.publishedFileId,
        )
        if (existingRecord?.hasRestorableInstalledJar(context) == true) {
            return restoreExistingRecordIfPossible(context, metadataStore, taskStore, task, existingRecord)
        }
        if (task.status == WorkshopDownloadTaskStatus.Completed && existingRecord != null) return false
        val completedArtifact = if (task.hasFinishedFileTransfer()) findDownloadedJar(outputDir) else null
        if (completedArtifact != null) {
            metadataStore.upsert(
                task.details.createRecoveredDownloadedJarRecord(
                    artifact = completedArtifact,
                    existingRecord = existingRecord,
                )
            )
            taskStore.markCompleted(task.publishedFileId, RECOVERED_PATCH_INTERRUPTED_MESSAGE)
            return true
        }
        return restoreExistingRecordIfPossible(context, metadataStore, taskStore, task, existingRecord)
    }

    private fun restoreExistingRecordIfPossible(
        context: Context,
        metadataStore: WorkshopMetadataStore,
        taskStore: WorkshopDownloadTaskStore,
        task: WorkshopDownloadTaskRecord,
        record: WorkshopInstalledModRecord?,
    ): Boolean {
        if (record == null || record.contentKind != WorkshopInstalledContentKind.JarMod) {
            return false
        }
        val path = record.localJarPath.trim()
        if (path.isEmpty()) return false
        val file = resolveWorkshopJarFile(context, record, path)
        if (!file.isFile) return false
        when (record.cardState) {
            WorkshopModCardState.ImportedUnpatched -> {
                taskStore.markCompleted(task.publishedFileId, RECOVERED_PATCH_INTERRUPTED_MESSAGE)
                return true
            }
            WorkshopModCardState.Downloading,
            WorkshopModCardState.DownloadPaused,
            WorkshopModCardState.DownloadFailed -> {
                val restoredState = if (File(path).isAbsolute) {
                    if (task.details.summary.updatedAtMillis > record.updatedAtMillis) {
                        WorkshopModCardState.UpdateAvailable
                    } else {
                        WorkshopModCardState.ImportedPatched
                    }
                } else {
                    WorkshopModCardState.ImportedUnpatched
                }
                val restoredMessage = when (restoredState) {
                    WorkshopModCardState.ImportedPatched,
                    WorkshopModCardState.UpdateAvailable -> RECOVERED_INSTALLED_VERSION_MESSAGE
                    else -> RECOVERED_PATCH_INTERRUPTED_MESSAGE
                }
                metadataStore.upsert(
                    record.copy(
                        cardState = restoredState,
                        statusText = restoredMessage,
                    )
                )
                taskStore.markCompleted(task.publishedFileId, restoredMessage)
                return true
            }
            WorkshopModCardState.ImportedPatched -> {
                taskStore.markCompleted(task.publishedFileId, record.statusText.ifBlank { RECOVERED_INSTALLED_VERSION_MESSAGE })
                return true
            }
            WorkshopModCardState.UpdateAvailable -> {
                taskStore.markCompleted(task.publishedFileId, record.statusText.ifBlank { RECOVERED_INSTALLED_VERSION_MESSAGE })
                return true
            }
            WorkshopModCardState.NonStandardDownloaded,
            WorkshopModCardState.TexturePackInstalled,
            WorkshopModCardState.FileMissing -> return false
        }
    }

    private fun WorkshopInstalledModRecord.hasRestorableInstalledJar(context: Context): Boolean {
        if (contentKind != WorkshopInstalledContentKind.JarMod) return false
        val path = localJarPath.trim()
        if (path.isEmpty() || !File(path).isAbsolute) return false
        return resolveWorkshopJarFile(context, this, path).isFile
    }

    private fun WorkshopDownloadTaskRecord.hasFinishedFileTransfer(): Boolean {
        if (status == WorkshopDownloadTaskStatus.Completed) return true
        val completed = completedFiles
        val total = totalFiles
        if (completed != null && total != null && total > 0 && completed >= total) return true
        if ((progressPercent ?: 0) >= 100) return true
        val totalDownloadedBytes = totalBytes
        return !details.fileUrl.isNullOrBlank() &&
            totalDownloadedBytes != null &&
            totalDownloadedBytes > 0L &&
            downloadedBytes >= totalDownloadedBytes
    }

    private fun WorkshopItemDetails.createRecoveredDownloadedJarRecord(
        artifact: WorkshopDownloadedArtifact,
        existingRecord: WorkshopInstalledModRecord?,
    ): WorkshopInstalledModRecord = WorkshopInstalledModRecord(
        appId = summary.appId,
        publishedFileId = summary.publishedFileId,
        title = summary.title,
        description = summary.description,
        previewUrl = summary.previewUrl,
        versionText = summary.updatedAtMillis.toString(),
        updatedAtMillis = summary.updatedAtMillis,
        installedAtMillis = System.currentTimeMillis(),
        localJarPath = artifact.relativePath,
        cardState = WorkshopModCardState.ImportedUnpatched,
        statusText = RECOVERED_PATCH_INTERRUPTED_MESSAGE,
        localPreviewImagePath = existingRecord?.localPreviewImagePath.orEmpty(),
        dependencies = dependencies,
    )

    private fun WorkshopDownloadTaskStore.markCompleted(publishedFileId: ULong, message: String) {
        update(publishedFileId) { task ->
            task.copy(
                status = WorkshopDownloadTaskStatus.Completed,
                message = message,
                progressPercent = 100,
                downloadedBytes = (task.totalBytes ?: task.downloadedBytes).coerceAtLeast(task.downloadedBytes),
                completedFiles = task.totalFiles ?: task.completedFiles,
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
    }

    private fun findDownloadedJar(outputDir: File): WorkshopDownloadedArtifact? {
        if (!outputDir.isDirectory) return null
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

    private fun resolveWorkshopJarFile(context: Context, record: WorkshopInstalledModRecord, path: String): File {
        val file = File(path)
        return if (file.isAbsolute) file else File(
            workshopOutputDir(context, record.appId, record.publishedFileId),
            path,
        )
    }

    private fun workshopOutputDir(context: Context, appId: UInt, publishedFileId: ULong): File =
        File(context.filesDir, "workshop/$appId/$publishedFileId")

    private const val RECOVERED_PATCH_INTERRUPTED_MESSAGE = "下载已完成，上次修补中断，可手动安装"
    private const val RECOVERED_INSTALLED_VERSION_MESSAGE = "已恢复上次安装，更新中断后可重新更新"
}
