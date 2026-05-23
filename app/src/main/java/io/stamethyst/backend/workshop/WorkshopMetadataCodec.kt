package io.stamethyst.backend.workshop

import org.json.JSONArray
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
        .put("contentKind", record.contentKind.name)
        .put("texturePackPath", record.texturePackPath)
        .put("cardState", record.cardState.name)
        .put("statusText", record.statusText)
        .put("localPreviewImagePath", record.localPreviewImagePath)
        .put("dependencies", record.dependencies.toJsonArray())

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
        contentKind = json.optString("contentKind").let { raw ->
            runCatching { WorkshopInstalledContentKind.valueOf(raw) }.getOrDefault(WorkshopInstalledContentKind.JarMod)
        },
        texturePackPath = json.optString("texturePackPath"),
        cardState = json.optString("cardState").let { raw ->
            runCatching { WorkshopModCardState.valueOf(raw) }.getOrDefault(WorkshopModCardState.ImportedUnpatched)
        },
        statusText = json.optString("statusText"),
        localPreviewImagePath = json.optString("localPreviewImagePath"),
        dependencies = json.optJSONArray("dependencies").toWorkshopSummaries(),
    )
}

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
                .put("ratingScore", item.rating?.score ?: 0)
                .put("ratingMaxScore", item.rating?.maxScore ?: 0)
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
                    rating = item.toWorkshopItemRating(),
                )
            )
        }
    }
}

private fun JSONObject.toWorkshopItemRating(): WorkshopItemRating? {
    val maxScore = optInt("ratingMaxScore").takeIf { it > 0 } ?: return null
    val score = optInt("ratingScore").takeIf { it > 0 } ?: return null
    return WorkshopItemRating(score = score.coerceIn(1, maxScore), maxScore = maxScore)
}
