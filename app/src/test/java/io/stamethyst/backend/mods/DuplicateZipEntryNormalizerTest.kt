package io.stamethyst.backend.mods

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import io.stamethyst.backend.mods.importing.ModImportExecutor
import io.stamethyst.backend.mods.importing.ModImportPlanner
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicateZipEntryNormalizerTest {
    @Test
    fun normalizeAndValidateInspectionJar_acceptsDuplicateRootManifestBeforeZipFileValidation() {
        val tempDir = Files.createTempDirectory("duplicate-zip-normalizer-manifest")
        val jarFile = tempDir.resolve("MapMarker.jar").toFile()
        writeJarWithDuplicateRootManifests(jarFile)

        val result = ModImportPlanner.normalizeAndValidateInspectionJar(jarFile)

        assertTrue(result.changed)
        assertTrue(result.rewritten)
        assertEquals(4, result.totalEntries)
        assertEquals(2, result.uniqueEntries)
        assertEquals(2, result.duplicateEntriesRemoved)
        assertEquals(
            listOf("ModTheSpire.json", "mapmarker/MapMarkerMod.class"),
            readEntryNames(jarFile)
        )
        assertEquals("MapMarkerMod", ModJarSupport.readModManifest(jarFile).modId)
    }

    @Test
    fun normalizeWorkingJarForImport_acceptsDuplicateRootManifestBeforeLaunchValidation() {
        val tempDir = Files.createTempDirectory("duplicate-zip-normalizer-working")
        val jarFile = tempDir.resolve("MapMarker.jar").toFile()
        writeJarWithDuplicateRootManifests(jarFile)

        ModImportExecutor.normalizeWorkingJarForImport(jarFile)

        assertEquals(
            listOf("ModTheSpire.json", "mapmarker/MapMarkerMod.class"),
            readEntryNames(jarFile)
        )
        assertEquals("MapMarkerMod", MtsLaunchManifestValidator.resolveLaunchModId(jarFile))
    }

    @Test
    fun normalizeInPlaceIfNeeded_removesDuplicateEntriesAndPreservesManifest() {
        val tempDir = Files.createTempDirectory("duplicate-zip-normalizer")
        val jarFile = tempDir.resolve("Astesia.jar").toFile()
        writeJarWithDuplicateEntries(jarFile)

        assertEquals(
            listOf(
                "ModTheSpire.json",
                "tutorial3/Tutorial.class",
                "META-INF/maven/qwert.example/tutorial3/pom.xml",
                "META-INF/maven/qwert.example/tutorial3/pom.xml"
            ),
            readEntryNames(jarFile)
        )

        val result = DuplicateZipEntryNormalizer.normalizeInPlaceIfNeeded(jarFile)

        assertTrue(result.changed)
        assertTrue(result.rewritten)
        assertEquals(4, result.totalEntries)
        assertEquals(3, result.uniqueEntries)
        assertEquals(1, result.duplicateEntriesRemoved)
        assertEquals(
            listOf(
                "ModTheSpire.json",
                "tutorial3/Tutorial.class",
                "META-INF/maven/qwert.example/tutorial3/pom.xml"
            ),
            readEntryNames(jarFile)
        )

        val manifest = ModJarSupport.readModManifest(jarFile)
        assertEquals("Astesiamod", manifest.modId)
        assertEquals("Astesia", manifest.name)
        assertEquals(listOf("basemod", "stslib"), manifest.dependencies)
    }

    @Test
    fun normalizeInPlaceIfNeeded_isNoOpWhenArchiveHasNoDuplicates() {
        val tempDir = Files.createTempDirectory("duplicate-zip-normalizer-clean")
        val jarFile = tempDir.resolve("Clean.jar").toFile()

        ZipArchiveOutputStream(jarFile).use { zipOut ->
            writeEntry(
                zipOut = zipOut,
                entryName = "ModTheSpire.json",
                bytes = """
                    {
                      "modid": "cleanmod",
                      "name": "Clean Mod"
                    }
                """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            )
        }

        val result = DuplicateZipEntryNormalizer.normalizeInPlaceIfNeeded(jarFile)

        assertFalse(result.changed)
        assertFalse(result.rewritten)
        assertEquals(1, result.totalEntries)
        assertEquals(1, result.uniqueEntries)
        assertEquals(0, result.duplicateEntriesRemoved)
        assertEquals(listOf("ModTheSpire.json"), readEntryNames(jarFile))
    }

    @Test
    fun normalizeInPlaceIfNeeded_acceptsPlatformReadableJarWithCentralDirectoryPreamble() {
        val tempDir = Files.createTempDirectory("duplicate-zip-normalizer-central-preamble")
        val jarFile = tempDir.resolve("MintyLike.jar").toFile()

        ZipArchiveOutputStream(jarFile).use { zipOut ->
            writeEntry(
                zipOut = zipOut,
                entryName = "ModTheSpire.json",
                bytes = """
                    {
                      "modid": "MintySpire",
                      "name": "Minty Spire (QoL Compilation)",
                      "dependencies": ["basemod"]
                    }
                """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            )
            writeEntry(
                zipOut = zipOut,
                entryName = "mintySpire/MintySpire.class",
                bytes = byteArrayOf(0x01)
            )
        }
        insertCentralDirectoryPreamble(jarFile, byteArrayOf(0x04, 0x14, 0x00, 0x00))

        assertEquals(2, ZipFile(jarFile).use { it.size() })

        val result = DuplicateZipEntryNormalizer.normalizeInPlaceIfNeeded(jarFile)

        assertFalse(result.changed)
        assertFalse(result.rewritten)
        assertEquals(2, result.totalEntries)
        assertEquals(2, result.uniqueEntries)
        assertEquals(0, result.duplicateEntriesRemoved)
        assertEquals("MintySpire", ModJarSupport.readModManifest(jarFile).modId)
        assertEquals("MintySpire", MtsLaunchManifestValidator.resolveLaunchModId(jarFile))
    }

    private fun writeJarWithDuplicateEntries(jarFile: java.io.File) {
        ZipArchiveOutputStream(jarFile).use { zipOut ->
            writeEntry(
                zipOut = zipOut,
                entryName = "ModTheSpire.json",
                bytes = """
                    {
                      "modid": "Astesiamod",
                      "name": "Astesia",
                      "dependencies": ["basemod", "stslib"]
                    }
                """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            )
            writeEntry(
                zipOut = zipOut,
                entryName = "tutorial3/Tutorial.class",
                bytes = byteArrayOf(0x01, 0x02, 0x03)
            )
            writeEntry(
                zipOut = zipOut,
                entryName = "META-INF/maven/qwert.example/tutorial3/pom.xml",
                bytes = "<project/>".toByteArray(StandardCharsets.UTF_8)
            )
            writeEntry(
                zipOut = zipOut,
                entryName = "META-INF/maven/qwert.example/tutorial3/pom.xml",
                bytes = "<project version=\"2\"/>".toByteArray(StandardCharsets.UTF_8)
            )
        }
    }

    private fun writeJarWithDuplicateRootManifests(jarFile: java.io.File) {
        val manifest = """
            {
              "modid": "MapMarkerMod",
              "name": "Map Marker",
              "author_list": ["YourName"],
              "dependencies": ["basemod", "stslib"]
            }
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)
        ZipArchiveOutputStream(jarFile).use { zipOut ->
            writeEntry(zipOut, "ModTheSpire.json", manifest)
            writeEntry(zipOut, "mapmarker/MapMarkerMod.class", byteArrayOf(0x01))
            writeEntry(zipOut, "ModTheSpire.json", manifest)
            writeEntry(zipOut, "ModTheSpire.json", manifest)
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

    private fun readEntryNames(jarFile: java.io.File): List<String> {
        val names = ArrayList<String>()
        ZipInputStream(jarFile.inputStream()).use { zipInput ->
            while (true) {
                val entry = zipInput.nextEntry ?: break
                names += entry.name
                zipInput.closeEntry()
            }
        }
        return names
    }

    private fun insertCentralDirectoryPreamble(jarFile: java.io.File, preamble: ByteArray) {
        val bytes = jarFile.readBytes()
        val eocdOffset = findSignature(bytes, byteArrayOf(0x50, 0x4b, 0x05, 0x06))
        require(eocdOffset >= 0) { "End of central directory not found" }
        val centralDirectoryOffset = readLittleEndianInt(bytes, eocdOffset + 16)
        require(centralDirectoryOffset in 0..bytes.size) { "Invalid central directory offset" }

        val updated = ByteArray(bytes.size + preamble.size)
        System.arraycopy(bytes, 0, updated, 0, centralDirectoryOffset)
        System.arraycopy(preamble, 0, updated, centralDirectoryOffset, preamble.size)
        System.arraycopy(
            bytes,
            centralDirectoryOffset,
            updated,
            centralDirectoryOffset + preamble.size,
            bytes.size - centralDirectoryOffset
        )
        writeLittleEndianInt(updated, eocdOffset + preamble.size + 16, centralDirectoryOffset + preamble.size)
        jarFile.writeBytes(updated)
    }

    private fun findSignature(bytes: ByteArray, signature: ByteArray): Int {
        for (index in bytes.size - signature.size downTo 0) {
            var matched = true
            for (offset in signature.indices) {
                if (bytes[index + offset] != signature[offset]) {
                    matched = false
                    break
                }
            }
            if (matched) {
                return index
            }
        }
        return -1
    }

    private fun readLittleEndianInt(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)
    }

    private fun writeLittleEndianInt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xff).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xff).toByte()
        bytes[offset + 2] = ((value ushr 16) and 0xff).toByte()
        bytes[offset + 3] = ((value ushr 24) and 0xff).toByte()
    }
}
