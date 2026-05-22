package io.stamethyst.backend.mods

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.LinkedHashSet
import java.util.zip.ZipFile
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream

internal data class DuplicateZipNormalizationResult(
    val totalEntries: Int,
    val uniqueEntries: Int,
    val duplicateEntriesRemoved: Int,
    val rewritten: Boolean = duplicateEntriesRemoved > 0
) {
    val changed: Boolean
        get() = duplicateEntriesRemoved > 0
}

internal object DuplicateZipEntryNormalizer {
    @Throws(IOException::class)
    fun normalizeInPlaceIfNeeded(zipFile: File): DuplicateZipNormalizationResult {
        if (!zipFile.isFile) {
            throw IOException("Zip file not found: ${zipFile.absolutePath}")
        }

        val platformScanResult = scanWithPlatformZipFile(zipFile)
        if (platformScanResult != null && platformScanResult.duplicateEntriesRemoved <= 0) {
            return platformScanResult.copy(rewritten = false)
        }

        val scanResult = platformScanResult ?: scanWithArchiveInputStream(zipFile)
        rewriteKeepingFirstEntry(zipFile)
        return scanResult.copy(rewritten = true)
    }

    private fun scanWithPlatformZipFile(zipFile: File): DuplicateZipNormalizationResult? {
        return try {
            val seenNames = LinkedHashSet<String>()
            var totalEntries = 0
            var duplicateEntriesRemoved = 0
            ZipFile(zipFile).use { platformZip ->
                val entries = platformZip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    totalEntries++
                    if (!seenNames.add(entry.name)) {
                        duplicateEntriesRemoved++
                    }
                }
            }
            DuplicateZipNormalizationResult(
                totalEntries = totalEntries,
                uniqueEntries = seenNames.size,
                duplicateEntriesRemoved = duplicateEntriesRemoved
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun scanWithArchiveInputStream(zipFile: File): DuplicateZipNormalizationResult {
        val seenNames = LinkedHashSet<String>()
        var totalEntries = 0
        var duplicateEntriesRemoved = 0

        ZipArchiveInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zipInput ->
            while (true) {
                val entry = zipInput.nextZipEntry ?: break
                totalEntries++
                if (!seenNames.add(entry.name)) {
                    duplicateEntriesRemoved++
                }
            }
        }

        return DuplicateZipNormalizationResult(
            totalEntries = totalEntries,
            uniqueEntries = seenNames.size,
            duplicateEntriesRemoved = duplicateEntriesRemoved
        )
    }

    @Throws(IOException::class)
    private fun rewriteKeepingFirstEntry(zipFile: File) {
        val tempFile = File(zipFile.absolutePath + ".dedup.tmp")
        val seenNames = LinkedHashSet<String>()
        try {
            ZipArchiveInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zipInput ->
                FileOutputStream(tempFile, false).use { outputStream ->
                    ZipArchiveOutputStream(outputStream).use { zipOut ->
                        while (true) {
                            val entry = zipInput.nextZipEntry ?: break
                            val entryName = entry.name
                            if (!seenNames.add(entryName)) {
                                continue
                            }

                            val outEntry = ZipArchiveEntry(entryName)
                            if (entry.time > 0L) {
                                outEntry.time = entry.time
                            }
                            zipOut.putArchiveEntry(outEntry)
                            if (!entry.isDirectory) {
                                JarFileIoUtils.copyStream(zipInput, zipOut)
                            }
                            zipOut.closeArchiveEntry()
                        }
                    }
                }
            }

            if (zipFile.exists() && !zipFile.delete()) {
                throw IOException("Failed to replace ${zipFile.absolutePath}")
            }
            if (!tempFile.renameTo(zipFile)) {
                throw IOException("Failed to move ${tempFile.absolutePath} -> ${zipFile.absolutePath}")
            }
            zipFile.setLastModified(System.currentTimeMillis())
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }
}
