package io.stamethyst.backend.workshop

import org.json.JSONObject

internal object WorkshopMetadataCodec {
    fun toJson(record: WorkshopInstalledModRecord): JSONObject = JSONObject()
        .put("appId", record.appId.toLong())
        .put("publishedFileId", record.publishedFileId.toString())
        .put("title", record.title)
        .put("description", record.description)
        .put("previewUrl", record.previewUrl)
        .put("versionText", record.versionText)
        .put("updatedAtMillis", record.updatedAtMillis)
        .put("installedAtMillis", record.installedAtMillis)
        .put("localJarPath", record.localJarPath)
        .put("cardState", record.cardState.name)
        .put("statusText", record.statusText)

    fun fromJson(json: JSONObject): WorkshopInstalledModRecord = WorkshopInstalledModRecord(
        appId = json.optLong("appId").toUInt(),
        publishedFileId = json.optString("publishedFileId").toULongOrNull() ?: 0u,
        title = json.optString("title"),
        description = json.optString("description"),
        previewUrl = json.optString("previewUrl"),
        versionText = json.optString("versionText"),
        updatedAtMillis = json.optLong("updatedAtMillis"),
        installedAtMillis = json.optLong("installedAtMillis"),
        localJarPath = json.optString("localJarPath"),
        cardState = json.optString("cardState").let { raw ->
            runCatching { WorkshopModCardState.valueOf(raw) }.getOrDefault(WorkshopModCardState.ImportedUnpatched)
        },
        statusText = json.optString("statusText")
    )
}
