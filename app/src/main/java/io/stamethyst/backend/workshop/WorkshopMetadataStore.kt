package io.stamethyst.backend.workshop

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

internal class WorkshopMetadataStore(context: Context) {
    private val filesDir = context.filesDir
    private val file = File(context.filesDir, "workshop/index.json")

    fun load(): List<WorkshopInstalledModRecord> {
        return synchronized(lock) {
            if (!file.isFile) return@synchronized emptyList()
            runCatching {
                val root = JSONObject(file.readText(StandardCharsets.UTF_8))
                val array = root.optJSONArray("items") ?: return@synchronized emptyList()
                buildList {
                    for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i) ?: continue
                        add(WorkshopMetadataCodec.fromJson(item))
                    }
                }
            }.getOrDefault(emptyList())
        }
    }

    fun save(records: List<WorkshopInstalledModRecord>) {
        synchronized(lock) {
            file.parentFile?.mkdirs()
            val root = JSONObject().put("items", JSONArray())
            val array = root.getJSONArray("items")
            records.sortedByDescending(WorkshopInstalledModRecord::updatedAtMillis).forEach { record ->
                array.put(WorkshopMetadataCodec.toJson(record))
            }
            val tempFile = File(file.parentFile, "${file.name}.tmp")
            tempFile.writeText(root.toString(2), StandardCharsets.UTF_8)
            if (file.exists() && !file.delete()) return@synchronized
            tempFile.renameTo(file)
        }
    }

    fun upsert(record: WorkshopInstalledModRecord) {
        synchronized(lock) {
            val current = load().toMutableList()
            val index = current.indexOfFirst { it.appId == record.appId && it.publishedFileId == record.publishedFileId }
            if (index >= 0) {
                current[index] = record.preserveLocalPreviewImage(current[index])
            } else {
                current.add(record)
            }
            save(current)
        }
    }

    fun findByPublishedFileId(appId: UInt, publishedFileId: ULong): WorkshopInstalledModRecord? {
        return synchronized(lock) {
            load().firstOrNull { it.appId == appId && it.publishedFileId == publishedFileId }
        }
    }

    fun updateState(appId: UInt, publishedFileId: ULong, state: WorkshopModCardState, statusText: String) {
        synchronized(lock) {
            val records = load().map { record ->
                if (record.appId == appId && record.publishedFileId == publishedFileId) {
                    record.copy(cardState = state, statusText = statusText)
                } else {
                    record
                }
            }
            save(records)
        }
    }

    fun applyUpdateCheckResults(results: List<WorkshopUpdateCheckResult>) {
        synchronized(lock) {
            if (results.isEmpty()) return@synchronized
            val resultByPublishedFileId = results.associateBy { it.publishedFileId }
            val records = load().map { record ->
                val result = resultByPublishedFileId[record.publishedFileId]
                    ?.takeIf { it.appId == record.appId }
                    ?: return@map record
                when {
                    result.hasUpdate -> record.copy(
                        cardState = WorkshopModCardState.UpdateAvailable,
                        statusText = "发现创意工坊更新",
                    )
                    record.cardState == WorkshopModCardState.UpdateAvailable -> record.copy(
                        cardState = record.restoredImportedState(),
                        statusText = "创意工坊模组均为最新",
                    )
                    else -> record
                }
            }
            save(records)
        }
    }

    fun remove(appId: UInt, publishedFileId: ULong) {
        synchronized(lock) {
            save(load().filterNot { it.appId == appId && it.publishedFileId == publishedFileId })
        }
    }

    fun markPatched(appId: UInt, publishedFileId: ULong, localJarPath: String, statusText: String) {
        synchronized(lock) {
            val records = load().map { record ->
                if (record.appId == appId && record.publishedFileId == publishedFileId) {
                    record.copy(
                        localJarPath = localJarPath,
                        cardState = WorkshopModCardState.ImportedPatched,
                        statusText = statusText,
                        localPreviewImagePath = record.localPreviewImagePath,
                    )
                } else {
                    record
                }
            }
            save(records)
        }
    }

    fun updatePreviewImagePath(appId: UInt, publishedFileId: ULong, localPreviewImagePath: String) {
        if (localPreviewImagePath.isBlank()) return
        synchronized(lock) {
            val records = load().map { record ->
                if (record.appId == appId && record.publishedFileId == publishedFileId) {
                    record.copy(localPreviewImagePath = localPreviewImagePath)
                } else {
                    record
                }
            }
            save(records)
        }
    }

    fun markMissingFiles() {
        synchronized(lock) {
            var changed = false
            val records = load().map { record ->
                if (record.shouldMarkFileMissing(filesDir)) {
                    changed = true
                    record.copy(
                        cardState = WorkshopModCardState.FileMissing,
                        statusText = "已下载文件缺失，请重新下载",
                    )
                } else {
                    record
                }
            }
            if (changed) save(records)
        }
    }

    fun list(): List<WorkshopInstalledModRecord> = load()

    companion object {
        private val lock = Any()
    }

}

private fun WorkshopInstalledModRecord.preserveLocalPreviewImage(existing: WorkshopInstalledModRecord): WorkshopInstalledModRecord {
    if (localPreviewImagePath.isNotBlank()) return this
    return copy(localPreviewImagePath = existing.localPreviewImagePath)
}

private fun WorkshopInstalledModRecord.restoredImportedState(): WorkshopModCardState {
    if (contentKind == WorkshopInstalledContentKind.TexturePack) return WorkshopModCardState.TexturePackInstalled
    val path = localJarPath.trim()
    if (path.isEmpty()) return WorkshopModCardState.ImportedUnpatched
    val file = File(path)
    return if (file.isAbsolute) {
        when {
            file.isDirectory -> WorkshopModCardState.NonStandardDownloaded
            file.isFile -> WorkshopModCardState.ImportedPatched
            else -> WorkshopModCardState.FileMissing
        }
    } else {
        WorkshopModCardState.ImportedUnpatched
    }
}

private fun WorkshopInstalledModRecord.shouldMarkFileMissing(filesDir: File): Boolean {
    val path = localJarPath.trim()
    if (path.isEmpty()) return false
    val file = if (File(path).isAbsolute) {
        File(path)
    } else {
        File(filesDir, "workshop/$appId/$publishedFileId/$path")
    }
    return when (cardState) {
        WorkshopModCardState.ImportedPatched,
        WorkshopModCardState.ImportedUnpatched -> !file.isFile
        WorkshopModCardState.NonStandardDownloaded -> !file.exists()
        WorkshopModCardState.TexturePackInstalled -> !file.isDirectory
        WorkshopModCardState.UpdateAvailable -> !file.exists()
        WorkshopModCardState.Downloading,
        WorkshopModCardState.DownloadPaused,
        WorkshopModCardState.DownloadFailed,
        WorkshopModCardState.FileMissing -> false
    }
}
