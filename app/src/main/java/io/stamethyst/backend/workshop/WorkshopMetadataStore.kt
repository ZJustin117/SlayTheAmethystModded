package io.stamethyst.backend.workshop

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

internal class WorkshopMetadataStore(context: Context) {
    private val filesDir = context.filesDir
    private val file = File(context.filesDir, "workshop/index.json")

    fun load(): List<WorkshopInstalledModRecord> = withStoreLock { loadUnlocked() }

    fun save(records: List<WorkshopInstalledModRecord>) = withStoreLock { saveUnlocked(records) }

    fun upsert(record: WorkshopInstalledModRecord) = withStoreLock {
        val current = loadUnlocked().toMutableList()
        val index = current.indexOfFirst { it.appId == record.appId && it.publishedFileId == record.publishedFileId }
        if (index >= 0) {
            current[index] = record.preserveLocalPreviewImage(current[index])
        } else {
            current.add(record)
        }
        saveUnlocked(current)
    }

    fun findByPublishedFileId(appId: UInt, publishedFileId: ULong): WorkshopInstalledModRecord? = withStoreLock {
        loadUnlocked().firstOrNull { it.appId == appId && it.publishedFileId == publishedFileId }
    }

    fun updateState(appId: UInt, publishedFileId: ULong, state: WorkshopModCardState, statusText: String) = withStoreLock {
        val records = loadUnlocked().map { record ->
            if (record.appId == appId && record.publishedFileId == publishedFileId) {
                record.copy(cardState = state, statusText = statusText)
            } else {
                record
            }
        }
        saveUnlocked(records)
    }

    fun applyUpdateCheckResults(results: List<WorkshopUpdateCheckResult>) = withStoreLock {
        if (results.isEmpty()) return@withStoreLock
        val resultByPublishedFileId = results.associateBy { it.publishedFileId }
        val records = loadUnlocked().map { record ->
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
        saveUnlocked(records)
    }

    fun remove(appId: UInt, publishedFileId: ULong) = withStoreLock {
        saveUnlocked(loadUnlocked().filterNot { it.appId == appId && it.publishedFileId == publishedFileId })
    }

    fun removeByLocalJarPaths(localJarPaths: Collection<String>): Int = withStoreLock {
        val normalizedPaths = localJarPaths
            .mapNotNull { it.normalizedLocalJarPath().takeIf(String::isNotEmpty) }
            .toSet()
        if (normalizedPaths.isEmpty()) return@withStoreLock 0
        val current = loadUnlocked()
        val remaining = current.filterNot { record ->
            record.localJarPath.normalizedLocalJarPath() in normalizedPaths
        }
        val removedCount = current.size - remaining.size
        if (removedCount > 0) saveUnlocked(remaining)
        removedCount
    }

    fun markPatched(appId: UInt, publishedFileId: ULong, localJarPath: String, statusText: String) = withStoreLock {
        val records = loadUnlocked().map { record ->
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
        saveUnlocked(records)
    }

    fun updatePreviewImagePath(appId: UInt, publishedFileId: ULong, localPreviewImagePath: String) {
        if (localPreviewImagePath.isBlank()) return
        withStoreLock {
            val records = loadUnlocked().map { record ->
                if (record.appId == appId && record.publishedFileId == publishedFileId) {
                    record.copy(localPreviewImagePath = localPreviewImagePath)
                } else {
                    record
                }
            }
            saveUnlocked(records)
        }
    }

    fun markMissingFiles() = withStoreLock {
        var changed = false
        val records = loadUnlocked().map { record ->
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
        if (changed) saveUnlocked(records)
    }

    fun list(): List<WorkshopInstalledModRecord> = load()

    private fun loadUnlocked(): List<WorkshopInstalledModRecord> {
        return WorkshopJsonFileStore.readJsonOrDefault(file, emptyList<WorkshopInstalledModRecord>()) { text ->
            val root = JSONObject(text)
            val array = root.optJSONArray("items") ?: return@readJsonOrDefault emptyList()
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    add(WorkshopMetadataCodec.fromJson(item))
                }
            }
        }
    }

    private fun saveUnlocked(records: List<WorkshopInstalledModRecord>) {
        val root = JSONObject().put("items", JSONArray())
        val array = root.getJSONArray("items")
        records.sortedByDescending(WorkshopInstalledModRecord::updatedAtMillis).forEach { record ->
            array.put(WorkshopMetadataCodec.toJson(record))
        }
        WorkshopJsonFileStore.writeAtomically(file, root.toString(2))
    }

    private fun <T> withStoreLock(block: () -> T): T = WorkshopJsonFileStore.withFileLock(file, lock, block)

    companion object {
        private val lock = Any()
    }
}

private fun WorkshopInstalledModRecord.preserveLocalPreviewImage(existing: WorkshopInstalledModRecord): WorkshopInstalledModRecord {
    if (localPreviewImagePath.isNotBlank()) return this
    return copy(localPreviewImagePath = existing.localPreviewImagePath)
}

private fun String.normalizedLocalJarPath(): String = trim().replace('\\', '/')

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
