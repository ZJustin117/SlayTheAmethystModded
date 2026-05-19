package io.stamethyst.backend.workshop

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkshopAutoImporterTest {
    @Test
    fun importDownloadedJarReturnsFailedWhenPlanningFails() {
        val roots = TestRoots.create("workshop-auto-import")
        val details = WorkshopItemDetails(
            summary = WorkshopItemSummary(
                publishedFileId = 123456u,
                appId = 646570u,
                title = "Broken Jar",
                previewUrl = "",
                description = "",
            )
        )
        val missingJar = File(roots.rootDir, "missing.jar")

        val result = WorkshopAutoImporter.importDownloadedJar(roots.context, details, missingJar)

        assertTrue(result is WorkshopAutoImportResult.Failed)
    }

    private class TestRoots private constructor(
        val rootDir: File,
        val context: Context,
    ) {
        companion object {
            fun create(prefix: String): TestRoots {
                val rootDir = Files.createTempDirectory(prefix).toFile()
                val filesDir = File(rootDir, "internal-files").apply { mkdirs() }
                val cacheDir = File(rootDir, "cache").apply { mkdirs() }
                return TestRoots(
                    rootDir = rootDir,
                    context = object : ContextWrapper(Application()) {
                        override fun getFilesDir(): File = filesDir

                        override fun getCacheDir(): File = cacheDir

                        override fun getPackageName(): String = "io.stamethyst.test"
                    }
                )
            }
        }
    }
}
