package io.stamethyst.backend.workshop

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

internal class WorkshopMetadataStore(context: Context) {
    private val file = File(context.filesDir, "workshop/index.json")

    fun load(): List<WorkshopInstalledModRecord> {
        if (!file.isFile) return emptyList()
        return runCatching {
            val root = JSONObject(file.readText(StandardCharsets.UTF_8))
            val array = root.optJSONArray("items") ?: return emptyList()
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    add(WorkshopMetadataCodec.fromJson(item))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun save(records: List<WorkshopInstalledModRecord>) {
        file.parentFile?.mkdirs()
        val root = JSONObject().put("items", JSONArray())
        val array = root.getJSONArray("items")
        records.sortedByDescending(WorkshopInstalledModRecord::updatedAtMillis).forEach { record ->
            array.put(WorkshopMetadataCodec.toJson(record))
        }
        file.writeText(root.toString(2), StandardCharsets.UTF_8)
    }

    fun upsert(record: WorkshopInstalledModRecord) {
        val current = load().filterNot {
            it.appId == record.appId && it.publishedFileId == record.publishedFileId
        }.toMutableList()
        current.add(record)
        save(current)
    }

    fun findByPublishedFileId(appId: UInt, publishedFileId: ULong): WorkshopInstalledModRecord? =
        load().firstOrNull { it.appId == appId && it.publishedFileId == publishedFileId }

    fun list(): List<WorkshopInstalledModRecord> = load()

}
