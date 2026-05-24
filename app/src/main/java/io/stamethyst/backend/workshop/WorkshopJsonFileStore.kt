package io.stamethyst.backend.workshop

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal object WorkshopJsonFileStore {
    fun <T> withFileLock(file: File, inMemoryLock: Any, block: () -> T): T = synchronized(inMemoryLock) {
        file.parentFile?.mkdirs()
        val lockFile = File(file.parentFile, "${file.name}.lock")
        RandomAccessFile(lockFile, "rw").channel.use { channel ->
            channel.lock().use { block() }
        }
    }

    fun <T> readJsonOrDefault(file: File, defaultValue: T, parse: (String) -> T): T {
        if (!file.isFile) return defaultValue
        val primaryText = runCatching { file.readText(StandardCharsets.UTF_8) }.getOrElse { return defaultValue }
        runCatching { parse(primaryText) }.onSuccess { return it }

        val backupFile = backupFile(file)
        if (backupFile.isFile) {
            val backupText = runCatching { backupFile.readText(StandardCharsets.UTF_8) }.getOrNull()
            if (backupText != null) {
                runCatching { parse(backupText) }.onSuccess { recovered ->
                    quarantineCorrupt(file)
                    writeAtomically(file, backupText, updateBackup = false)
                    return recovered
                }
            }
        }

        quarantineCorrupt(file)
        return defaultValue
    }

    fun writeAtomically(file: File, text: String) {
        writeAtomically(file, text, updateBackup = true)
    }

    private fun writeAtomically(file: File, text: String, updateBackup: Boolean) {
        val parent = file.parentFile ?: return
        parent.mkdirs()
        if (updateBackup && file.isFile) {
            runCatching { file.copyTo(backupFile(file), overwrite = true) }
        }
        val tempFile = File(parent, "${file.name}.${System.nanoTime()}.${Thread.currentThread().id}.tmp")
        tempFile.writeText(text, StandardCharsets.UTF_8)
        moveReplacing(tempFile, file)
    }

    private fun moveReplacing(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun quarantineCorrupt(file: File) {
        if (!file.isFile) return
        val corruptFile = File(file.parentFile, "${file.name}.corrupt.${System.currentTimeMillis()}")
        runCatching {
            Files.move(file.toPath(), corruptFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.onFailure {
            runCatching { file.copyTo(corruptFile, overwrite = true) }
        }
    }

    private fun backupFile(file: File): File = File(file.parentFile, "${file.name}.bak")
}
