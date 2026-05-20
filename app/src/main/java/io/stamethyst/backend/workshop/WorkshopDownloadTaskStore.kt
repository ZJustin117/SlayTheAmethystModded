package io.stamethyst.backend.workshop

import android.content.Context
import java.io.File
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

enum class WorkshopDownloadTaskStatus {
    Queued,
    Resolving,
    Downloading,
    Pausing,
    Cancelling,
    Paused,
    Completed,
    Failed,
    Cancelled,
}

data class WorkshopDownloadTaskRecord(
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
)

class WorkshopDownloadTaskStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "workshop/download_tasks.json")

    @Synchronized fun list(): List<WorkshopDownloadTaskRecord> = load()

    @Synchronized fun upsert(task: WorkshopDownloadTaskRecord) {
        clearDeletedMarker(task.publishedFileId)
        val current = load().toMutableList()
        val index = current.indexOfFirst { it.publishedFileId == task.publishedFileId }
        if (index >= 0) current[index] = task else current.add(0, task)
        save(current)
    }

    @Synchronized fun update(publishedFileId: ULong, transform: (WorkshopDownloadTaskRecord) -> WorkshopDownloadTaskRecord) {
        val current = load().toMutableList()
        val index = current.indexOfFirst { it.publishedFileId == publishedFileId }
        if (index < 0) return
        current[index] = transform(current[index])
        save(current)
    }

    @Synchronized fun appendLog(publishedFileId: ULong, message: String) {
        val cleanMessage = message.trim().takeIf { it.isNotEmpty() } ?: return
        update(publishedFileId) { task ->
            val line = "${downloadLogTimestamp()} $cleanMessage"
            task.copy(downloadLog = listOf(task.downloadLog, line).filter(String::isNotBlank).joinToString("\n"))
        }
    }

    @Synchronized fun remove(publishedFileId: ULong) {
        save(load().filterNot { it.publishedFileId == publishedFileId })
    }

    @Synchronized fun removeAndMarkDeleted(publishedFileId: ULong) {
        save(load().filterNot { it.publishedFileId == publishedFileId })
        val markerFile = deletedMarkerFile(publishedFileId)
        markerFile.parentFile?.mkdirs()
        markerFile.writeText(System.currentTimeMillis().toString(), StandardCharsets.UTF_8)
    }

    @Synchronized fun clearDeletedMarker(publishedFileId: ULong) {
        deletedMarkerFile(publishedFileId).delete()
    }

    @Synchronized fun isMarkedDeleted(publishedFileId: ULong): Boolean = deletedMarkerFile(publishedFileId).isFile

    @Synchronized fun find(publishedFileId: ULong): WorkshopDownloadTaskRecord? = load().firstOrNull { it.publishedFileId == publishedFileId }

    @Synchronized fun hasRunningTask(): Boolean = load().any { it.status.isRunningDownload() }

    @Synchronized fun nextQueuedTask(): WorkshopDownloadTaskRecord? = load()
        .filter { it.status == WorkshopDownloadTaskStatus.Queued }
        .minByOrNull { it.updatedAtMillis }

    @Synchronized fun recoverInterruptedTasksWithResult(
        shouldRecover: (WorkshopDownloadTaskRecord) -> Boolean = { true },
    ): List<WorkshopDownloadTaskRecord> {
        val recovered = ArrayList<WorkshopDownloadTaskRecord>()
        val tasks = load().map { task ->
            if ((task.status.isRunningDownload() || task.status.isStoppingDownload()) && shouldRecover(task)) {
                task.copy(status = WorkshopDownloadTaskStatus.Paused, message = "下载已暂停，可继续").also(recovered::add)
            } else {
                task
            }
        }
        if (recovered.isNotEmpty()) save(tasks)
        return recovered
    }

    private fun load(): List<WorkshopDownloadTaskRecord> {
        if (!file.isFile) return emptyList()
        return runCatching {
            val root = JSONObject(file.readText(StandardCharsets.UTF_8))
            val array = root.optJSONArray("tasks") ?: return emptyList()
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    add(item.toTask())
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun save(tasks: List<WorkshopDownloadTaskRecord>) {
        file.parentFile?.mkdirs()
        val array = JSONArray()
        tasks.forEach { array.put(it.toJson()) }
        val tempFile = File(file.parentFile, "${file.name}.tmp")
        tempFile.writeText(JSONObject().put("tasks", array).toString(2), StandardCharsets.UTF_8)
        if (file.exists() && !file.delete()) return
        tempFile.renameTo(file)
    }

    private fun deletedMarkerFile(publishedFileId: ULong): File = File(file.parentFile, "deleted_$publishedFileId")
}

fun WorkshopDownloadTaskStatus.isRunningDownload(): Boolean = when (this) {
    WorkshopDownloadTaskStatus.Resolving,
    WorkshopDownloadTaskStatus.Downloading -> true
    WorkshopDownloadTaskStatus.Queued,
    WorkshopDownloadTaskStatus.Pausing,
    WorkshopDownloadTaskStatus.Cancelling,
    WorkshopDownloadTaskStatus.Paused,
    WorkshopDownloadTaskStatus.Completed,
    WorkshopDownloadTaskStatus.Failed,
    WorkshopDownloadTaskStatus.Cancelled -> false
}

fun WorkshopDownloadTaskStatus.isStoppingDownload(): Boolean = when (this) {
    WorkshopDownloadTaskStatus.Pausing,
    WorkshopDownloadTaskStatus.Cancelling -> true
    else -> false
}

fun WorkshopDownloadTaskStatus.isActiveDownload(): Boolean = when (this) {
    WorkshopDownloadTaskStatus.Queued,
    WorkshopDownloadTaskStatus.Resolving,
    WorkshopDownloadTaskStatus.Downloading,
    WorkshopDownloadTaskStatus.Pausing,
    WorkshopDownloadTaskStatus.Cancelling -> true
    else -> false
}

private fun WorkshopDownloadTaskRecord.toJson(): JSONObject = JSONObject()
    .put("publishedFileId", publishedFileId.toString())
    .put("title", title)
    .put("status", status.name)
    .put("message", message)
    .put("updatedAtMillis", updatedAtMillis)
    .put("previewUrl", previewUrl)
    .put("description", description)
    .put("authorName", authorName)
    .put("fileSizeBytes", fileSizeBytes)
    .put("progressPercent", progressPercent ?: JSONObject.NULL)
    .put("downloadedBytes", downloadedBytes)
    .put("totalBytes", totalBytes ?: JSONObject.NULL)
    .put("completedFiles", completedFiles ?: JSONObject.NULL)
    .put("totalFiles", totalFiles ?: JSONObject.NULL)
    .put("completedChunks", completedChunks ?: JSONObject.NULL)
    .put("totalChunks", totalChunks ?: JSONObject.NULL)
    .put("errorClass", errorClass)
    .put("errorMessage", errorMessage)
    .put("errorStackTrace", errorStackTrace)
    .put("downloadLog", downloadLog)
    .put("appId", details.summary.appId.toString())
    .put("updatedAtMillisRemote", details.summary.updatedAtMillis)
    .put("downloadCount", details.summary.downloadCount)
    .put("fileUrl", details.fileUrl.orEmpty())
    .put("hcontentFile", details.hcontentFile?.toString().orEmpty())
    .put("depotId", details.depotId?.toString().orEmpty())
    .put("jsonMetadata", details.jsonMetadata)
    .put("dependencies", details.dependencies.toJsonArray())

private fun JSONObject.toTask(): WorkshopDownloadTaskRecord {
    val publishedFileId = optString("publishedFileId").toULongOrNull() ?: 0u
    val summary = WorkshopItemSummary(
        appId = optString("appId").toUIntOrNull() ?: 646570u,
        publishedFileId = publishedFileId,
        title = optString("title"),
        previewUrl = optString("previewUrl"),
        description = optString("description"),
        authorName = optString("authorName"),
        fileSizeBytes = optLong("fileSizeBytes"),
        updatedAtMillis = optLong("updatedAtMillisRemote"),
        downloadCount = optLong("downloadCount"),
    )
    val details = WorkshopItemDetails(
        summary = summary,
        fileUrl = optString("fileUrl").takeIf { it.isNotBlank() },
        hcontentFile = optString("hcontentFile").toULongOrNull(),
        depotId = optString("depotId").toUIntOrNull(),
        jsonMetadata = optString("jsonMetadata"),
        dependencies = optJSONArray("dependencies").toWorkshopSummaries(),
    )
    return WorkshopDownloadTaskRecord(
        publishedFileId = publishedFileId,
        title = summary.title,
        status = runCatching { WorkshopDownloadTaskStatus.valueOf(optString("status")) }.getOrDefault(WorkshopDownloadTaskStatus.Paused),
        message = optString("message"),
        updatedAtMillis = optLong("updatedAtMillis"),
        details = details,
        previewUrl = summary.previewUrl,
        description = summary.description,
        authorName = summary.authorName,
        fileSizeBytes = summary.fileSizeBytes,
        progressPercent = optionalInt("progressPercent"),
        downloadedBytes = optLong("downloadedBytes"),
        totalBytes = optionalLong("totalBytes"),
        completedFiles = optionalInt("completedFiles"),
        totalFiles = optionalInt("totalFiles"),
        completedChunks = optionalInt("completedChunks"),
        totalChunks = optionalInt("totalChunks"),
        errorClass = optString("errorClass"),
        errorMessage = optString("errorMessage"),
        errorStackTrace = optString("errorStackTrace"),
        downloadLog = optString("downloadLog"),
    )
}

private fun downloadLogTimestamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

private fun JSONObject.optionalInt(key: String): Int? = if (has(key) && !isNull(key)) optInt(key) else null
private fun JSONObject.optionalLong(key: String): Long? = if (has(key) && !isNull(key)) optLong(key) else null

private fun List<WorkshopItemSummary>.toJsonArray(): JSONArray = JSONArray().also { array ->
    forEach { item ->
        array.put(
            JSONObject()
                .put("publishedFileId", item.publishedFileId.toString())
                .put("appId", item.appId.toString())
                .put("title", item.title)
                .put("previewUrl", item.previewUrl)
                .put("description", item.description)
                .put("authorName", item.authorName)
                .put("fileSizeBytes", item.fileSizeBytes)
                .put("updatedAtMillis", item.updatedAtMillis)
                .put("downloadCount", item.downloadCount)
        )
    }
}

private fun JSONArray?.toWorkshopSummaries(): List<WorkshopItemSummary> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            val publishedFileId = item.optString("publishedFileId").toULongOrNull() ?: continue
            add(
                WorkshopItemSummary(
                    publishedFileId = publishedFileId,
                    appId = item.optString("appId").toUIntOrNull() ?: 646570u,
                    title = item.optString("title"),
                    previewUrl = item.optString("previewUrl"),
                    description = item.optString("description"),
                    authorName = item.optString("authorName"),
                    fileSizeBytes = item.optLong("fileSizeBytes"),
                    updatedAtMillis = item.optLong("updatedAtMillis"),
                    downloadCount = item.optLong("downloadCount"),
                )
            )
        }
    }
}
