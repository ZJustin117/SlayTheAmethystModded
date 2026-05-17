package io.stamethyst.backend.mods

import java.nio.file.Files
import java.util.jar.Attributes
import java.util.jar.Manifest
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StsJarValidatorTest {
    @Test
    fun isValidStreaming_acceptsRenamedDesktopJarByContent() {
        val tempDir = Files.createTempDirectory("sts-validator")
        val jarFile = tempDir.resolve("renamed-game.jar").toFile()
        writeJar(
            jarFile = jarFile,
            manifestMainClass = "com.megacrit.cardcrawl.desktop.DesktopLauncher",
            entries = listOf("com/megacrit/cardcrawl/desktop/DesktopLauncher.class")
        )

        assertTrue(StsJarValidator.isValidStreaming(jarFile))
    }

    @Test
    fun isValidStreaming_rejectsModJarByContent() {
        val tempDir = Files.createTempDirectory("sts-validator-mod")
        val jarFile = tempDir.resolve("MapMarker.jar").toFile()
        writeJar(
            jarFile = jarFile,
            manifestMainClass = null,
            entries = listOf("ModTheSpire.json", "mapmarker/MapMarkerMod.class")
        )

        assertFalse(StsJarValidator.isValidStreaming(jarFile))
    }

    private fun writeJar(
        jarFile: java.io.File,
        manifestMainClass: String?,
        entries: List<String>
    ) {
        ZipArchiveOutputStream(jarFile).use { zipOut ->
            if (manifestMainClass != null) {
                val manifest = Manifest().apply {
                    mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
                    mainAttributes[Attributes.Name.MAIN_CLASS] = manifestMainClass
                }
                val manifestBytes = java.io.ByteArrayOutputStream().use { output ->
                    manifest.write(output)
                    output.toByteArray()
                }
                writeEntry(zipOut, "META-INF/MANIFEST.MF", manifestBytes)
            }
            entries.forEach { entryName ->
                writeEntry(zipOut, entryName, byteArrayOf(0x01))
            }
        }
    }

    private fun writeEntry(
        zipOut: ZipArchiveOutputStream,
        entryName: String,
        bytes: ByteArray
    ) {
        val entry = ZipArchiveEntry(entryName)
        zipOut.putArchiveEntry(entry)
        zipOut.write(bytes)
        zipOut.closeArchiveEntry()
    }
}
