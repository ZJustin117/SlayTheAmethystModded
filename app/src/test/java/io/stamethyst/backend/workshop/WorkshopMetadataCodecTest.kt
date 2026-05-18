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
            dependencies = listOf(
                WorkshopItemSummary(
                    appId = 646570u,
                    publishedFileId = 1609158507u,
                    title = "StSLib",
                    previewUrl = "https://cdn.example/stslib.jpg",
                    description = "Library",
                    authorName = "Author",
                    fileSizeBytes = 42L,
                    updatedAtMillis = 1_710_000_200_000L,
                )
            ),
        )

        val decoded = WorkshopMetadataCodec.fromJson(WorkshopMetadataCodec.toJson(record))

        assertEquals(record, decoded)
    }

    @Test
    fun roundTripsTexturePackWorkshopRecord() {
        val record = WorkshopInstalledModRecord(
            appId = 646570u,
            publishedFileId = 3506704233u,
            title = "Texture pack",
            description = "Resource replacement pack",
            previewUrl = "",
            versionText = "1710000000000",
            updatedAtMillis = 1_710_000_000_000L,
            installedAtMillis = 1_710_000_100_000L,
            localJarPath = "/data/user/0/io.stamethyst/files/sts/texPacks/3506704233",
            contentKind = WorkshopInstalledContentKind.TexturePack,
            texturePackPath = "/data/user/0/io.stamethyst/files/sts/texPacks/3506704233",
            cardState = WorkshopModCardState.TexturePackInstalled,
            statusText = "Texture Replacer resource pack installed",
        )

        val decoded = WorkshopMetadataCodec.fromJson(WorkshopMetadataCodec.toJson(record))

        assertEquals(record, decoded)
    }
}
