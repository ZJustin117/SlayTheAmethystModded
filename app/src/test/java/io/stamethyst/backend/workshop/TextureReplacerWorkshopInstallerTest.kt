package io.stamethyst.backend.workshop

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextureReplacerWorkshopInstallerTest {
    @Test
    fun installsWorkshopDirectoryAsEnabledTexturePack() {
        val roots = TestRoots.create("texture-replacer-installer")
        val sourceDir = File(roots.rootDir, "workshop/646570/123456").apply { mkdirs() }
        File(sourceDir, "images/card.png").apply {
            parentFile?.mkdirs()
            writeText("png")
        }
        val details = WorkshopItemDetails(
            summary = WorkshopItemSummary(
                publishedFileId = 123456u,
                appId = 646570u,
                title = "Card Art Pack",
                previewUrl = "",
                description = "",
            )
        )

        val installedDir = TextureReplacerWorkshopInstaller.install(roots.context, details, sourceDir)

        assertEquals(RuntimePaths.texturePackDir(roots.context, 123456u), installedDir)
        assertEquals("png", File(installedDir, "images/card.png").readText())
        val config = JSONArray(RuntimePaths.textureReplacerPackOrderFile(roots.context).readText())
        assertEquals(1, config.length())
        val pack = config.getJSONObject(0)
        assertEquals("123456", pack.getString("id"))
        assertEquals("Card Art Pack", pack.getString("name"))
        assertTrue(pack.getBoolean("enabled"))
    }

    private class TestRoots private constructor(
        val rootDir: File,
        val context: Context,
    ) {
        companion object {
            fun create(prefix: String): TestRoots {
                val rootDir = Files.createTempDirectory(prefix).toFile()
                val filesDir = File(rootDir, "internal-files").apply { mkdirs() }
                val externalFilesDir = File(rootDir, "external-files").apply { mkdirs() }
                return TestRoots(
                    rootDir = rootDir,
                    context = object : ContextWrapper(Application()) {
                        override fun getFilesDir(): File = filesDir

                        override fun getExternalFilesDir(type: String?): File = externalFilesDir

                        override fun getPackageName(): String = "io.stamethyst.test"
                    }
                )
            }
        }
    }
}
