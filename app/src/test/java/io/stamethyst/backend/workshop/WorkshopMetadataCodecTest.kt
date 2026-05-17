package io.stamethyst.backend.workshop

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkshopMetadataCodecTest {
    @Test
    fun roundTripsInstalledWorkshopRecord() {
        val record = WorkshopInstalledModRecord(
            appId = 646570u,
            publishedFileId = 1605060445u,
            title = "BaseMod",
            description = "Required mod",
            previewUrl = "https://cdn.example/preview.jpg",
            versionText = "1710000000000",
            updatedAtMillis = 1_710_000_000_000L,
            installedAtMillis = 1_710_000_100_000L,
            localJarPath = "mods/BaseMod.jar",
            autoImported = true,
        )

        val decoded = WorkshopMetadataCodec.fromJson(WorkshopMetadataCodec.toJson(record))

        assertEquals(record, decoded)
    }
}
