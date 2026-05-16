package io.stamethyst.backend.mods.importing

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModImportPlannerFileTypeTest {
    @Test
    fun shouldAttemptJarInspection_acceptsReadableJarWithoutExtension() {
        val tempDir = Files.createTempDirectory("mod-import-file-type-no-extension")
        val source = tempDir.resolve("ReadableMod").toFile()
        writeModJar(source)

        assertTrue(ModImportPlanner.shouldAttemptJarInspection("ReadableMod", source))
    }

    @Test
    fun shouldAttemptJarInspection_rejectsUnreadableFileWithoutExtension() {
        val tempDir = Files.createTempDirectory("mod-import-file-type-text")
        val source = tempDir.resolve("NotAJar").toFile()
        source.writeText("not a zip container", StandardCharsets.UTF_8)

        assertFalse(ModImportPlanner.shouldAttemptJarInspection("NotAJar", source))
    }

    @Test
    fun shouldAttemptJarInspection_keepsJarExtensionCandidatesForDetailedErrors() {
        val tempDir = Files.createTempDirectory("mod-import-file-type-jar-name")
        val source = tempDir.resolve("Broken.jar").toFile()
        source.writeText("not a zip container", StandardCharsets.UTF_8)

        assertTrue(ModImportPlanner.shouldAttemptJarInspection("Broken.jar", source))
    }

    private fun writeModJar(jarFile: File) {
        ZipOutputStream(jarFile.outputStream()).use { zipOut ->
            zipOut.putNextEntry(ZipEntry("ModTheSpire.json"))
            zipOut.write(
                """
                    {
                      "modid": "nosuffixmod",
                      "name": "No Suffix Mod"
                    }
                """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            )
            zipOut.closeEntry()
        }
    }
}
