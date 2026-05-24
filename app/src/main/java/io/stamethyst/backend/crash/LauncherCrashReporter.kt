package io.stamethyst.backend.crash

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import io.stamethyst.BuildConfig
import io.stamethyst.config.RuntimePaths
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.system.exitProcess

object LauncherCrashReporter {
    private const val GAME_PROCESS_SUFFIX = ":game"
    private const val MAX_TRACE_BYTES = 512 * 1024
    private const val UNCAUGHT_DUPLICATE_WINDOW_MS = 2 * 60 * 1000L
    private const val MIME_TYPE_TEXT = "text/plain"
    private const val REPORT_PREFIX = "sts-launcher-crash"
    private const val FALLBACK_REPORT_DIR_NAME = "launcher_crash_reports"
    private const val EXIT_MARKER_FILE_NAME = ".last_launcher_exit_marker"
    private const val UNCAUGHT_MARKER_FILE_NAME = ".last_launcher_uncaught_crash"

    private val installed = AtomicBoolean(false)

    @JvmStatic
    fun install(context: Context) {
        val appContext = context.applicationContext
        val processName = currentProcessName(appContext)
        if (isGameProcess(appContext, processName)) {
            return
        }
        if (!installed.compareAndSet(false, true)) {
            return
        }

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val crashTimestamp = System.currentTimeMillis()
                val report = buildUncaughtExceptionReport(
                    context = appContext,
                    processName = processName,
                    thread = thread,
                    throwable = throwable,
                    generatedAtMs = crashTimestamp
                )
                val destination = writeReportBestEffort(
                    context = appContext,
                    fileName = buildReportFileName(
                        kind = "uncaught",
                        processName = processName,
                        pid = Process.myPid(),
                        timestampMs = crashTimestamp
                    ),
                    reportText = report
                )
                persistUncaughtCrashMarker(
                    context = appContext,
                    processName = processName,
                    pid = Process.myPid(),
                    timestampMs = crashTimestamp,
                    destination = destination
                )
            } catch (_: Throwable) {
            } finally {
                try {
                    previousHandler?.uncaughtException(thread, throwable)
                } finally {
                    Process.killProcess(Process.myPid())
                    exitProcess(10)
                }
            }
        }
    }

    @JvmStatic
    fun recordLatestLauncherProcessExitIfNeeded(context: Context) {
        val appContext = context.applicationContext
        if (currentProcessName(appContext) != appContext.packageName) {
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return
        }
        recordLatestLauncherProcessExitIfNeededApi30(appContext)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun recordLatestLauncherProcessExitIfNeededApi30(context: Context) {
        val latestExit = resolveLatestLauncherExitInfo(context) ?: return
        val markerValue = buildExitMarker(latestExit)
        if (!isNewExitMarker(context, markerValue)) {
            return
        }

        val alreadyRecordedByUncaughtHandler = wasAlreadyRecordedByUncaughtHandler(context, latestExit)
        if (!alreadyRecordedByUncaughtHandler) {
            val generatedAtMs = System.currentTimeMillis()
            val report = buildProcessExitReport(
                context = context,
                exitInfo = latestExit,
                traceText = readTraceText(latestExit),
                generatedAtMs = generatedAtMs
            )
            val destination = writeReportBestEffort(
                context = context,
                fileName = buildReportFileName(
                    kind = "process-exit",
                    processName = latestExit.processName,
                    pid = latestExit.pid,
                    timestampMs = generatedAtMs
                ),
                reportText = report
            )
            if (destination == null) {
                return
            }
        }

        persistExitMarker(context, markerValue)
    }

    private fun buildUncaughtExceptionReport(
        context: Context,
        processName: String,
        thread: Thread,
        throwable: Throwable,
        generatedAtMs: Long
    ): String {
        return buildString {
            appendLine("Slay the Amethyst launcher crash report")
            appendLine("Generated at: ${formatTimestamp(generatedAtMs)}")
            appendLine("Report type: uncaught_exception")
            appendLine()
            appendAppAndDeviceInfo(context, processName, Process.myPid())
            appendLine("Thread: ${thread.name}")
            appendLine("Exception: ${throwable.javaClass.name}: ${throwable.message.orEmpty()}")
            appendLine()
            appendLine("Stack trace:")
            appendLine(stackTraceToString(throwable))
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun buildProcessExitReport(
        context: Context,
        exitInfo: ApplicationExitInfo,
        traceText: String?,
        generatedAtMs: Long
    ): String {
        return buildString {
            appendLine("Slay the Amethyst launcher process exit report")
            appendLine("Generated at: ${formatTimestamp(generatedAtMs)}")
            appendLine("Report type: application_exit_info")
            appendLine()
            appendAppAndDeviceInfo(context, exitInfo.processName, exitInfo.pid)
            appendLine("Exit timestamp: ${formatTimestamp(exitInfo.timestamp)}")
            appendLine("Exit reason: ${reasonName(exitInfo.reason)} (${exitInfo.reason})")
            appendLine("Exit status: ${exitInfo.status}")
            appendLine("Importance: ${exitInfo.importance}")
            val description = exitInfo.description?.trim().orEmpty()
            if (description.isNotEmpty()) {
                appendLine("Description: $description")
            }
            appendLine()
            appendLine("Trace:")
            appendLine(traceText?.takeIf { it.isNotBlank() } ?: "No trace was provided by Android.")
        }
    }

    private fun StringBuilder.appendAppAndDeviceInfo(
        context: Context,
        processName: String,
        pid: Int
    ) {
        appendLine("Package: ${context.packageName}")
        appendLine("App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Process: $processName")
        appendLine("PID: $pid")
        appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.BRAND} ${Build.MODEL}")
        appendLine("Product: ${Build.PRODUCT} / ${Build.DEVICE}")
        appendLine("Hardware: ${Build.HARDWARE}")
        appendLine("Supported ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun resolveLatestLauncherExitInfo(context: Context): ApplicationExitInfo? {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        val reasons = try {
            manager.getHistoricalProcessExitReasons(context.packageName, 0, 32)
        } catch (_: Throwable) {
            return null
        }
        if (reasons.isNullOrEmpty()) {
            return null
        }
        return reasons
            .asSequence()
            .sortedByDescending { it.timestamp }
            .firstOrNull { exitInfo ->
                isLauncherProcess(context, exitInfo.processName) && isInterestingExitReason(exitInfo)
            }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun readTraceText(exitInfo: ApplicationExitInfo): String? {
        val traceInput = try {
            exitInfo.traceInputStream
        } catch (_: Throwable) {
            null
        } ?: return null

        return try {
            traceInput.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var remaining = MAX_TRACE_BYTES
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                    if (read <= 0) {
                        break
                    }
                    output.write(buffer, 0, read)
                    remaining -= read
                }
                var text = output.toString(StandardCharsets.UTF_8.name()).trim()
                if (text.isEmpty()) {
                    return null
                }
                if (remaining == 0) {
                    text += "\n\n[truncated to ${MAX_TRACE_BYTES} bytes]"
                }
                text
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun writeReportBestEffort(
        context: Context,
        fileName: String,
        reportText: String
    ): String? {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeReportToScopedDownloads(context, fileName, reportText)
            } else {
                writeReportToLegacyDownloads(fileName, reportText)
            }
        }.recoverCatching {
            writeReportToAppExternalDownloads(context, fileName, reportText)
        }.recoverCatching {
            writeReportToInternalFallback(context, fileName, reportText)
        }.getOrNull()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun writeReportToScopedDownloads(
        context: Context,
        fileName: String,
        reportText: String
    ): String {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, MIME_TYPE_TEXT)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Failed to create launcher crash report in Downloads")

        var success = false
        try {
            resolver.openOutputStream(uri).use { output ->
                if (output == null) {
                    throw IOException("Unable to open launcher crash report destination")
                }
                output.write(reportText.toByteArray(StandardCharsets.UTF_8))
            }
            success = true
        } finally {
            if (success) {
                val pendingValues = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                resolver.update(uri, pendingValues, null, null)
            } else {
                resolver.delete(uri, null, null)
            }
        }
        return "${Environment.DIRECTORY_DOWNLOADS}/$fileName"
    }

    @Suppress("DEPRECATION")
    @Throws(IOException::class)
    private fun writeReportToLegacyDownloads(
        fileName: String,
        reportText: String
    ): String {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            ?: throw IOException("Downloads directory is unavailable")
        if (!downloadsDir.exists() && !downloadsDir.mkdirs()) {
            throw IOException("Failed to create Downloads directory: ${downloadsDir.absolutePath}")
        }
        val reportFile = File(downloadsDir, fileName)
        FileOutputStream(reportFile, false).use { output ->
            output.write(reportText.toByteArray(StandardCharsets.UTF_8))
        }
        return reportFile.absolutePath
    }

    @Throws(IOException::class)
    private fun writeReportToAppExternalDownloads(
        context: Context,
        fileName: String,
        reportText: String
    ): String {
        val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw IOException("App external Downloads directory is unavailable")
        if (!downloadsDir.exists() && !downloadsDir.mkdirs()) {
            throw IOException("Failed to create app Downloads directory: ${downloadsDir.absolutePath}")
        }
        val reportFile = File(downloadsDir, fileName)
        FileOutputStream(reportFile, false).use { output ->
            output.write(reportText.toByteArray(StandardCharsets.UTF_8))
        }
        return reportFile.absolutePath
    }

    @Throws(IOException::class)
    private fun writeReportToInternalFallback(
        context: Context,
        fileName: String,
        reportText: String
    ): String {
        val reportDir = File(context.filesDir, FALLBACK_REPORT_DIR_NAME)
        if (!reportDir.exists() && !reportDir.mkdirs()) {
            throw IOException("Failed to create internal crash report directory: ${reportDir.absolutePath}")
        }
        val reportFile = File(reportDir, fileName)
        FileOutputStream(reportFile, false).use { output ->
            output.write(reportText.toByteArray(StandardCharsets.UTF_8))
        }
        return reportFile.absolutePath
    }

    private fun currentProcessName(context: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val processName = Application.getProcessName()
            if (!processName.isNullOrBlank()) {
                return processName
            }
        }
        val pid = Process.myPid()
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val processName = manager?.runningAppProcesses
            ?.firstOrNull { processInfo -> processInfo.pid == pid }
            ?.processName
        return processName?.takeIf { it.isNotBlank() } ?: context.packageName
    }

    private fun isLauncherProcess(context: Context, processName: String): Boolean {
        return processName == context.packageName ||
            (processName.startsWith(context.packageName + ":") && !isGameProcess(context, processName))
    }

    private fun isGameProcess(context: Context, processName: String): Boolean {
        return processName == context.packageName + GAME_PROCESS_SUFFIX
    }

    private fun buildReportFileName(
        kind: String,
        processName: String,
        pid: Int,
        timestampMs: Long
    ): String {
        val safeProcessName = processName
            .replace(':', '-')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        return "$REPORT_PREFIX-$kind-${formatFileTimestamp(timestampMs)}-$safeProcessName-pid$pid.txt"
    }

    private fun stackTraceToString(throwable: Throwable): String {
        val writer = StringWriter()
        throwable.printStackTrace(PrintWriter(writer))
        return writer.toString().trimEnd()
    }

    private fun formatTimestamp(timestampMs: Long): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US)
        return formatter.format(Date(timestampMs))
    }

    private fun formatFileTimestamp(timestampMs: Long): String {
        val formatter = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)
        return formatter.format(Date(timestampMs))
    }

    private fun markerFile(context: Context, fileName: String): File {
        return File(RuntimePaths.stsRoot(context), fileName)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun buildExitMarker(exitInfo: ApplicationExitInfo): String {
        return buildString(128) {
            append(exitInfo.processName).append(':')
            append(exitInfo.pid).append(':')
            append(exitInfo.timestamp).append(':')
            append(exitInfo.reason).append(':')
            append(exitInfo.status)
        }
    }

    private fun isNewExitMarker(context: Context, markerValue: String): Boolean {
        return try {
            val markerFile = markerFile(context, EXIT_MARKER_FILE_NAME)
            if (!markerFile.isFile) {
                return true
            }
            markerFile.readText(StandardCharsets.UTF_8).trim() != markerValue
        } catch (_: Throwable) {
            true
        }
    }

    private fun persistExitMarker(context: Context, markerValue: String) {
        try {
            val markerFile = markerFile(context, EXIT_MARKER_FILE_NAME)
            val parent = markerFile.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return
            }
            markerFile.writeText(markerValue, StandardCharsets.UTF_8)
        } catch (_: Throwable) {
        }
    }

    private fun persistUncaughtCrashMarker(
        context: Context,
        processName: String,
        pid: Int,
        timestampMs: Long,
        destination: String?
    ) {
        try {
            val markerFile = markerFile(context, UNCAUGHT_MARKER_FILE_NAME)
            val parent = markerFile.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return
            }
            markerFile.writeText(
                buildString {
                    appendLine("processName=$processName")
                    appendLine("pid=$pid")
                    appendLine("timestampMs=$timestampMs")
                    appendLine("destination=${destination.orEmpty()}")
                },
                StandardCharsets.UTF_8
            )
        } catch (_: Throwable) {
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun wasAlreadyRecordedByUncaughtHandler(
        context: Context,
        exitInfo: ApplicationExitInfo
    ): Boolean {
        val marker = readUncaughtCrashMarker(context) ?: return false
        return marker.destination.isNotBlank() &&
            marker.processName == exitInfo.processName &&
            marker.pid == exitInfo.pid &&
            abs(marker.timestampMs - exitInfo.timestamp) <= UNCAUGHT_DUPLICATE_WINDOW_MS
    }

    private fun readUncaughtCrashMarker(context: Context): UncaughtCrashMarker? {
        return try {
            val markerFile = markerFile(context, UNCAUGHT_MARKER_FILE_NAME)
            if (!markerFile.isFile) {
                return null
            }
            val values = markerFile.readLines(StandardCharsets.UTF_8)
                .mapNotNull { line ->
                    val separator = line.indexOf('=')
                    if (separator <= 0) {
                        null
                    } else {
                        line.substring(0, separator) to line.substring(separator + 1)
                    }
                }
                .toMap()
            UncaughtCrashMarker(
                processName = values["processName"] ?: return null,
                pid = values["pid"]?.toIntOrNull() ?: return null,
                timestampMs = values["timestampMs"]?.toLongOrNull() ?: return null,
                destination = values["destination"].orEmpty()
            )
        } catch (_: Throwable) {
            null
        }
    }

    private data class UncaughtCrashMarker(
        val processName: String,
        val pid: Int,
        val timestampMs: Long,
        val destination: String
    )

    @RequiresApi(Build.VERSION_CODES.R)
    private fun isInterestingExitReason(exitInfo: ApplicationExitInfo): Boolean {
        return when (exitInfo.reason) {
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_ANR,
            ApplicationExitInfo.REASON_SIGNALED,
            ApplicationExitInfo.REASON_LOW_MEMORY,
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> true
            else -> false
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun reasonName(reason: Int): String {
        return when (reason) {
            ApplicationExitInfo.REASON_ANR -> "REASON_ANR"
            ApplicationExitInfo.REASON_CRASH -> "REASON_CRASH"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "REASON_CRASH_NATIVE"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "REASON_DEPENDENCY_DIED"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "REASON_EXCESSIVE_RESOURCE_USAGE"
            ApplicationExitInfo.REASON_EXIT_SELF -> "REASON_EXIT_SELF"
            ApplicationExitInfo.REASON_FREEZER -> "REASON_FREEZER"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "REASON_INITIALIZATION_FAILURE"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "REASON_LOW_MEMORY"
            ApplicationExitInfo.REASON_OTHER -> "REASON_OTHER"
            ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "REASON_PACKAGE_STATE_CHANGE"
            ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "REASON_PACKAGE_UPDATED"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "REASON_PERMISSION_CHANGE"
            ApplicationExitInfo.REASON_SIGNALED -> "REASON_SIGNALED"
            ApplicationExitInfo.REASON_UNKNOWN -> "REASON_UNKNOWN"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "REASON_USER_REQUESTED"
            ApplicationExitInfo.REASON_USER_STOPPED -> "REASON_USER_STOPPED"
            else -> "REASON_$reason"
        }
    }
}
