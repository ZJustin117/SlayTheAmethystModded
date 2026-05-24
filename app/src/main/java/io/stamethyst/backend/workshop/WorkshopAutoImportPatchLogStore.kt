package io.stamethyst.backend.workshop

import android.content.Context
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object WorkshopAutoImportPatchLogStore {
    const val MAX_LOG_SLOTS = 10

    private const val LOG_FILE_PREFIX = "auto_import_patch_"
    private const val LOG_FILE_SUFFIX = ".log"
    private val lock = Any()

    fun createLogFile(context: Context): File {
        synchronized(lock) {
            val logsDir = RuntimePaths.workshopAutoImportPatchLogsDir(context)
            ensureDirectory(logsDir)
            val logFile = allocateLogFile(logsDir)
            pruneOldLogs(logsDir)
            return logFile
        }
    }

    fun appendLine(logFile: File?, message: String) {
        val safeLogFile = logFile ?: return
        val cleanMessage = message.trim().takeIf { it.isNotEmpty() } ?: return
        runCatching {
            synchronized(lock) {
                safeLogFile.parentFile?.let(::ensureDirectory)
                safeLogFile.appendText("${timestamp()} $cleanMessage\n", StandardCharsets.UTF_8)
            }
        }
    }

    fun listLogFiles(context: Context): List<File> {
        val logsDir = RuntimePaths.workshopAutoImportPatchLogsDir(context)
        if (!logsDir.isDirectory) {
            return emptyList()
        }
        return enumerateLogFiles(logsDir).sortedByDescending { it.name }
    }

    private fun ensureDirectory(directory: File) {
        if (directory.isDirectory) {
            return
        }
        if (!directory.exists() && directory.mkdirs()) {
            return
        }
        if (!directory.isDirectory) {
            throw IOException("Failed to create auto import patch log directory: ${directory.absolutePath}")
        }
    }

    private fun allocateLogFile(logsDir: File): File {
        val formatter = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)
        val baseName = formatter.format(Date())
        var sequence = 0
        while (sequence < 20) {
            val suffix = "-${sequence.toString().padStart(2, '0')}"
            val candidate = File(logsDir, "$LOG_FILE_PREFIX$baseName$suffix$LOG_FILE_SUFFIX")
            if (candidate.createNewFile()) {
                return candidate
            }
            sequence++
        }
        throw IOException("Failed to allocate auto import patch log slot in ${logsDir.absolutePath}")
    }

    private fun pruneOldLogs(logsDir: File) {
        val files = enumerateLogFiles(logsDir).sortedBy { it.name }
        if (files.size <= MAX_LOG_SLOTS) {
            return
        }
        val removeCount = files.size - MAX_LOG_SLOTS
        for (index in 0 until removeCount) {
            files[index].delete()
        }
    }

    private fun enumerateLogFiles(logsDir: File): List<File> {
        return logsDir.listFiles()
            .orEmpty()
            .filter { file ->
                file.isFile &&
                    file.name.startsWith(LOG_FILE_PREFIX) &&
                    file.name.endsWith(LOG_FILE_SUFFIX)
            }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
}
