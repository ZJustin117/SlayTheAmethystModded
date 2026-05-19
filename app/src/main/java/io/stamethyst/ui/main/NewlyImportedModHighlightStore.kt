package io.stamethyst.ui.main

import android.content.Context
import java.io.File
import java.nio.charset.StandardCharsets

internal object NewlyImportedModHighlightStore {
    private val storagePaths = LinkedHashSet<String>()

    @Synchronized
    fun reload(context: Context) {
        replaceWith(readStoragePaths(context))
    }

    @Synchronized
    fun mark(context: Context, paths: Collection<String>): Boolean {
        val updated = readStoragePaths(context)
        var changed = false
        paths.forEach { path ->
            val normalized = path.trim()
            if (normalized.isNotEmpty() && updated.add(normalized)) {
                changed = true
            }
        }
        replaceWith(updated)
        if (changed) {
            writeStoragePaths(context, updated)
        }
        return changed
    }

    @Synchronized
    fun contains(path: String): Boolean {
        return storagePaths.any { storedPath -> matchesModStoragePathCandidate(storedPath, path) }
    }

    @Synchronized
    fun clear(context: Context, path: String): Boolean {
        val normalized = path.trim()
        if (normalized.isEmpty()) {
            return false
        }
        val updated = readStoragePaths(context)
        val matching = updated.filter { storedPath -> matchesModStoragePathCandidate(storedPath, normalized) }
        if (matching.isEmpty()) {
            replaceWith(updated)
            return false
        }
        updated.removeAll(matching.toSet())
        replaceWith(updated)
        writeStoragePaths(context, updated)
        return true
    }

    @Synchronized
    fun clearAll(context: Context): Boolean {
        val updated = readStoragePaths(context)
        if (updated.isEmpty()) {
            storagePaths.clear()
            return false
        }
        storagePaths.clear()
        writeStoragePaths(context, emptySet())
        return true
    }

    private fun replaceWith(paths: Collection<String>) {
        storagePaths.clear()
        storagePaths.addAll(paths)
    }

    private fun readStoragePaths(context: Context): LinkedHashSet<String> {
        return runCatching {
            val file = storeFile(context)
            if (!file.isFile) {
                return@runCatching LinkedHashSet<String>()
            }
            file.readLines(StandardCharsets.UTF_8)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toCollection(LinkedHashSet())
        }.getOrDefault(LinkedHashSet())
    }

    private fun writeStoragePaths(context: Context, paths: Collection<String>) {
        runCatching {
            val file = storeFile(context)
            val parent = file.parentFile ?: return@runCatching
            if (!parent.exists() && !parent.mkdirs()) {
                return@runCatching
            }
            if (paths.isEmpty()) {
                file.delete()
                return@runCatching
            }
            val tempFile = File(parent, "${file.name}.tmp")
            tempFile.writeText(paths.joinToString(separator = "\n", postfix = "\n"), StandardCharsets.UTF_8)
            if (file.exists() && !file.delete()) {
                tempFile.delete()
                return@runCatching
            }
            tempFile.renameTo(file)
        }
    }

    private fun storeFile(context: Context): File {
        return File(File(context.filesDir, STORE_DIR_NAME), STORE_FILE_NAME)
    }

    private const val STORE_DIR_NAME = "main"
    private const val STORE_FILE_NAME = "newly_imported_mods.txt"
}
