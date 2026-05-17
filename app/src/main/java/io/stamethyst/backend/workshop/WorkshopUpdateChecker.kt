package io.stamethyst.backend.workshop

import android.content.Context

internal class WorkshopUpdateChecker(context: Context) {
    private val service = WorkshopService(context)
    private val store = WorkshopMetadataStore(context)

    suspend fun checkInstalledMods(): List<WorkshopUpdateCheckResult> {
        return store.list().mapNotNull { record ->
            runCatching {
                val details = service.getDetails(record.appId, record.publishedFileId)
                WorkshopUpdateCheckResult(
                    appId = record.appId,
                    publishedFileId = record.publishedFileId,
                    hasUpdate = WorkshopUpdatePolicy.hasUpdate(
                        localUpdatedAtMillis = record.updatedAtMillis,
                        remoteUpdatedAtMillis = details.summary.updatedAtMillis,
                    ),
                    remoteUpdatedAtMillis = details.summary.updatedAtMillis,
                    localUpdatedAtMillis = record.updatedAtMillis,
                    remoteVersionText = details.summary.updatedAtMillis.toString(),
                )
            }.getOrNull()
        }
    }
}
