package io.stamethyst.backend.workshop

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkshopMetadataStoreTest {
    @Test
    fun upsertReplacesRecordForSamePublishedFileId() {
        val roots = TestRoots.create("workshop-metadata-store")
        val store = WorkshopMetadataStore(roots.context)
        val first = record(title = "Old", updatedAtMillis = 100L)
        val second = record(title = "New", updatedAtMillis = 200L)

        store.upsert(first)
        store.upsert(second)

        val records = store.list()
        assertEquals(1, records.size)
        assertEquals("New", records.single().title)
        assertEquals(200L, records.single().updatedAtMillis)
    }

    @Test
    fun saveSortsByRemoteUpdatedTimeDescending() {
        val roots = TestRoots.create("workshop-metadata-store-sort")
        val store = WorkshopMetadataStore(roots.context)

        store.save(
            listOf(
                record(title = "Older", publishedFileId = 1u, updatedAtMillis = 100L),
                record(title = "Newer", publishedFileId = 2u, updatedAtMillis = 300L),
            )
        )

        assertEquals(listOf("Newer", "Older"), store.list().map { it.title })
    }

    private fun record(
        title: String,
        publishedFileId: ULong = 1605060445u,
        updatedAtMillis: Long,
    ): WorkshopInstalledModRecord = WorkshopInstalledModRecord(
        appId = 646570u,
        publishedFileId = publishedFileId,
        title = title,
        description = "description",
        previewUrl = "https://cdn.example/$publishedFileId.jpg",
        versionText = updatedAtMillis.toString(),
        updatedAtMillis = updatedAtMillis,
        installedAtMillis = updatedAtMillis + 10L,
        localJarPath = "mods/$title.jar",
        autoImported = true,
    )

    private class TestRoots private constructor(
        val rootDir: File,
        val context: Context,
    ) {
        companion object {
            fun create(prefix: String): TestRoots {
                val rootDir = Files.createTempDirectory(prefix).toFile()
                val filesDir = File(rootDir, "internal-files").apply { mkdirs() }
                return TestRoots(
                    rootDir = rootDir,
                    context = object : ContextWrapper(Application()) {
                        override fun getFilesDir(): File = filesDir

                        override fun getPackageName(): String = "io.stamethyst.test"
                    }
                )
            }
        }
    }
}
