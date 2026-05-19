import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

abstract class StsPullLogsTask : DefaultTask() {
    @get:Inject
    abstract val execOperations: ExecOperations

    @get:Input
    abstract val adbPath: Property<String>

    @get:Input
    abstract val applicationId: Property<String>

    @get:Input
    @get:Optional
    abstract val deviceSerial: Property<String>

    @get:Input
    @get:Optional
    abstract val logsDir: Property<String>

    @TaskAction
    fun pullLogs() {
        val packageName = applicationId.get()
        val outputDir = logsDir.orNull
            ?.takeIf { it.isNotEmpty() }
            ?.let { project.file(it) }
            ?: project.layout.buildDirectory.dir("sts-logs").get().asFile
        outputDir.mkdirs()

        val deviceStsPaths = resolveDeviceStsPaths(packageName)
        logger.lifecycle(
            "Resolved device stsRoot: ${deviceStsPaths.stsRoot} (${deviceStsPaths.accessMode.name.lowercase(Locale.US)})"
        )

        val pulledEntries = mutableListOf<PulledEntry>()
        val histogramEntries = mutableListOf<PulledEntry>()
        var exportedCount = 0

        pulledEntries.add(
            textEntry(
                entryName = "sts/jvm_logs/device_info.txt",
                content = buildJvmLogDeviceInfo(packageName)
            )
        )

        val latestExists = remoteFileExists(deviceStsPaths, "latest.log", packageName)
        if (latestExists) {
            logger.lifecycle("Pulling shared JVM log: latest.log")
            readRemoteFile(deviceStsPaths, "latest.log", packageName)?.let { content ->
                pulledEntries.add(PulledEntry("sts/jvm_logs/latest.log", content))
                exportedCount++
            }
        } else {
            logger.lifecycle("latest.log not found on device.")
        }

        val optionalLogPaths = listOf(
            "boot_bridge_events.log",
            "jvm_gc.log",
            "jvm_heap_snapshot.txt",
            "last_signal_dump.txt"
        )
        optionalLogPaths.forEach { relativePath ->
            if (!remoteFileExists(deviceStsPaths, relativePath, packageName)) {
                logger.lifecycle("$relativePath not found on device.")
                return@forEach
            }
            logger.lifecycle("Pulling shared JVM log: $relativePath")
            readRemoteFile(deviceStsPaths, relativePath, packageName)?.let { content ->
                if (content.isEmpty()) {
                    return@let
                }
                pulledEntries.add(
                    PulledEntry(
                        entryName = "sts/jvm_logs/${relativePath.substringAfterLast('/')}",
                        content = content
                    )
                )
                exportedCount++
            }
        }

        val logcatNames = listLogcatCaptureNames(deviceStsPaths, packageName)
        if (logcatNames.isEmpty()) {
            logger.lifecycle("No logcat capture files found on device.")
        }
        logcatNames.forEach { name ->
            logger.lifecycle("Pulling logcat capture: $name")
            readRemoteFile(deviceStsPaths, "logcat/$name", packageName)?.let { content ->
                pulledEntries.add(PulledEntry("sts/logcat/$name", content))
                exportedCount++
            }
        }

        val archivedLimit = if (latestExists) MAX_JVM_LOG_EXPORT_SLOTS - 1 else MAX_JVM_LOG_EXPORT_SLOTS
        val archivedNames = listArchivedJvmLogNames(deviceStsPaths, packageName).take(archivedLimit)
        if (archivedNames.isEmpty()) {
            logger.lifecycle("No archived jvm_log_*.log found on device.")
        }
        for (name in archivedNames) {
            if (!remoteFileExists(deviceStsPaths, "jvm_logs/$name", packageName)) {
                continue
            }
            logger.lifecycle("Pulling shared JVM log: $name")
            readRemoteFile(deviceStsPaths, "jvm_logs/$name", packageName)?.let { content ->
                pulledEntries.add(PulledEntry("sts/jvm_logs/$name", content))
                exportedCount++
            }
        }

        listMemoryDiagnosticsNames(deviceStsPaths, packageName).forEach { name ->
            if (!remoteFileExists(deviceStsPaths, "jvm_logs/$name", packageName)) {
                return@forEach
            }
            logger.lifecycle("Pulling memory diagnostics log: $name")
            readRemoteFile(deviceStsPaths, "jvm_logs/$name", packageName)?.let { content ->
                if (content.isEmpty()) {
                    return@let
                }
                pulledEntries.add(PulledEntry("sts/jvm_logs/$name", content))
                exportedCount++
            }
        }

        listHistogramNames(deviceStsPaths, packageName).forEach { name ->
            if (!remoteFileExists(deviceStsPaths, "jvm_histograms/$name", packageName)) {
                return@forEach
            }
            logger.lifecycle("Pulling JVM histogram: $name")
            readRemoteFile(deviceStsPaths, "jvm_histograms/$name", packageName)?.let { content ->
                if (content.isEmpty()) {
                    return@let
                }
                histogramEntries.add(PulledEntry("sts/jvm_histograms/$name", content))
                exportedCount++
            }
        }

        pulledEntries.add(
            textEntry(
                entryName = "sts/jvm_histograms/summary.txt",
                content = buildHistogramSummary(histogramEntries)
            )
        )

        val archiveFile = File(outputDir, buildJvmLogExportFileName())
        FileOutputStream(archiveFile, false).use { output ->
            ZipOutputStream(output).use { zipOutput ->
                for (pulled in pulledEntries + histogramEntries) {
                    zipOutput.putNextEntry(ZipEntry(pulled.entryName))
                    zipOutput.write(pulled.content)
                    zipOutput.closeEntry()
                }
                if (exportedCount <= 0) {
                    zipOutput.putNextEntry(ZipEntry("sts/README.txt"))
                    zipOutput.write(
                        (
                            "No diagnostic logs found.\n" +
                                "Expected files under:\n" +
                                "- ${deviceStsPaths.stsRoot}\n"
                            ).toByteArray(StandardCharsets.UTF_8)
                    )
                    zipOutput.closeEntry()
                }
            }
        }

        logger.lifecycle("SlayTheAmethyst JVM logs exported to: ${archiveFile.absolutePath}")
        if (exportedCount <= 0) {
            logger.lifecycle("No diagnostic logs found on device; wrote README.txt into archive.")
        } else {
            val exportedNames = (pulledEntries + histogramEntries)
                .map { it.entryName.removePrefix("sts/") }
            logger.lifecycle("Pulled: ${exportedNames.joinToString(", ")}")
        }
    }

    private fun resolveDeviceStsPaths(packageName: String): DeviceStsPaths {
        val candidates = listOf(
            DeviceStsPaths("/sdcard/Android/data/$packageName/files/sts", RemoteFileAccessMode.SHELL),
            DeviceStsPaths("/storage/emulated/0/Android/data/$packageName/files/sts", RemoteFileAccessMode.SHELL),
            DeviceStsPaths("files/sts", RemoteFileAccessMode.RUN_AS)
        )
        return candidates.firstOrNull { devicePathReadable(it.stsRoot, it.accessMode, packageName) }
            ?: candidates.first()
    }

    private fun remoteFileExists(paths: DeviceStsPaths, relativePath: String, packageName: String): Boolean =
        devicePathReadable(resolveRemotePath(paths, relativePath), paths.accessMode, packageName)

    private fun readRemoteFile(paths: DeviceStsPaths, relativePath: String, packageName: String): ByteArray? {
        val remotePath = resolveRemotePath(paths, relativePath)
        return when (paths.accessMode) {
            RemoteFileAccessMode.SHELL -> {
                if (!remoteFileExists(paths, relativePath, packageName)) {
                    return null
                }
                val tempFile = File(temporaryDir, relativePath.replace('/', '_'))
                if (tempFile.exists()) {
                    tempFile.delete()
                }
                val pullResult = runAdbCommand(listOf("pull", remotePath, tempFile.absolutePath))
                if (pullResult.exitCode != 0 || !tempFile.isFile) {
                    tempFile.delete()
                    null
                } else {
                    tempFile.readBytes().also { tempFile.delete() }
                }
            }

            RemoteFileAccessMode.RUN_AS -> {
                val bytes = runAdbBinaryCommand(
                    listOf("exec-out", "run-as", packageName, "sh", "-c", "cat ${shellQuote(remotePath)}")
                )
                bytes.takeIf { it.isNotEmpty() || remoteFileExists(paths, relativePath, packageName) }
            }
        }
    }

    private fun listRemoteFileNames(paths: DeviceStsPaths, relativePath: String, packageName: String): List<String> {
        val remotePath = resolveRemotePath(paths, relativePath)
        val result = when (paths.accessMode) {
            RemoteFileAccessMode.SHELL -> runAdbCommand(listOf("shell", "ls", remotePath))
            RemoteFileAccessMode.RUN_AS -> runAdbCommand(listOf("shell", "run-as", packageName, "ls", remotePath))
        }
        if (result.exitCode != 0) {
            return emptyList()
        }
        return result.stdout.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
    }

    private fun listArchivedJvmLogNames(paths: DeviceStsPaths, packageName: String): List<String> =
        listRemoteFileNames(paths, "jvm_logs", packageName)
            .filter { it.matches(Regex("""jvm_log_.*\.log""")) }
            .sortedDescending()

    private fun listMemoryDiagnosticsNames(paths: DeviceStsPaths, packageName: String): List<String> {
        fun rotationIndex(name: String): Int = when {
            name == "memory_diagnostics.log" -> 0
            name.startsWith("memory_diagnostics.log.") ->
                name.substringAfter("memory_diagnostics.log.").toIntOrNull() ?: Int.MAX_VALUE
            else -> Int.MAX_VALUE
        }
        return listRemoteFileNames(paths, "jvm_logs", packageName)
            .filter { it == "memory_diagnostics.log" || it.startsWith("memory_diagnostics.log.") }
            .sortedWith(compareBy(::rotationIndex).thenBy { it })
    }

    private fun listHistogramNames(paths: DeviceStsPaths, packageName: String): List<String> =
        listRemoteFileNames(paths, "jvm_histograms", packageName)
            .filter { it.endsWith(".txt", ignoreCase = true) }
            .sortedDescending()
            .take(6)

    private fun listLogcatCaptureNames(paths: DeviceStsPaths, packageName: String): List<String> =
        listRemoteFileNames(paths, "logcat", packageName)
            .filter { name ->
                name.endsWith(".log", ignoreCase = true) || name.contains(".log.", ignoreCase = true)
            }
            .sorted()

    private fun buildJvmLogDeviceInfo(packageName: String): String {
        val (versionName, versionCode) = readPackageVersionInfo(packageName)
        return buildString {
            append("launcher.package=").append(packageName).append('\n')
            append("launcher.versionName=").append(versionName).append('\n')
            append("launcher.versionCode=").append(versionCode).append('\n')
            append("device.manufacturer=").append(readDeviceProp("ro.product.manufacturer")).append('\n')
            append("device.brand=").append(readDeviceProp("ro.product.brand")).append('\n')
            append("device.model=").append(readDeviceProp("ro.product.model")).append('\n')
            append("device.device=").append(readDeviceProp("ro.product.device")).append('\n')
            append("device.product=").append(readDeviceProp("ro.product.name")).append('\n')
            append("device.hardware=").append(readDeviceProp("ro.hardware")).append('\n')
            append("android.release=").append(readDeviceProp("ro.build.version.release")).append('\n')
            append("android.sdkInt=").append(normalizeInfoValue(runAdbCommand(listOf("shell", "getprop", "ro.build.version.sdk")).stdout)).append('\n')
            append("android.securityPatch=").append(readDeviceProp("ro.build.version.security_patch")).append('\n')
            append("device.abis=").append(readDeviceProp("ro.product.cpu.abilist")).append('\n')
            append("device.fingerprint=").append(readDeviceProp("ro.build.fingerprint")).append('\n')
        }
    }

    private fun readDeviceProp(key: String): String = normalizeInfoValue(
        runAdbCommand(listOf("shell", "getprop", key)).stdout
    )

    private fun readPackageVersionInfo(packageName: String): Pair<String, String> {
        val dump = runAdbCommand(listOf("shell", "dumpsys", "package", packageName)).stdout
        val versionName = dump.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("versionName=") }
            ?.substringAfter("versionName=")
            ?.trim()
        val versionCode = dump.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("versionCode=") }
            ?.substringAfter("versionCode=")
            ?.substringBefore(' ')
            ?.trim()
        return normalizeInfoValue(versionName) to normalizeInfoValue(versionCode)
    }

    private fun buildHistogramSummary(histogramFiles: List<PulledEntry>): String {
        if (histogramFiles.isEmpty()) {
            return "No JVM histogram dumps captured yet.\n"
        }

        val latest = histogramFiles.first()
        val header = linkedMapOf<String, String>()
        val topClasses = ArrayList<String>(12)
        var inBody = false
        for (rawLine in String(latest.content, StandardCharsets.UTF_8).lineSequence()) {
            val line = rawLine.trimEnd()
            if (!inBody) {
                if (line.isBlank()) {
                    inBody = true
                    continue
                }
                val separatorIndex = line.indexOf('=')
                if (separatorIndex > 0 && separatorIndex < line.length - 1) {
                    header[line.substring(0, separatorIndex).trim()] = line.substring(separatorIndex + 1).trim()
                }
                continue
            }
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("num") || trimmed.startsWith("-")) {
                continue
            }
            topClasses.add(trimmed)
            if (topClasses.size >= 12) {
                break
            }
        }

        return buildString {
            append("Histogram files captured: ").append(histogramFiles.size).append('\n')
            append("Latest file: ").append(latest.entryName.substringAfterLast('/')).append('\n')
            header.forEach { (key, value) -> append(key).append('=').append(value).append('\n') }
            append('\n')
            append("Top classes from latest dump:\n")
            if (topClasses.isEmpty()) {
                append("(no class rows parsed)\n")
            } else {
                topClasses.forEach { row -> append(row).append('\n') }
            }
        }
    }

    private fun runAdbCommand(args: List<String>): AdbCommandResult {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val execResult = execOperations.exec {
            commandLine(buildAdbCommand(args))
            isIgnoreExitValue = true
            standardOutput = stdout
            errorOutput = stderr
        }
        return AdbCommandResult(execResult.exitValue, stdout.toString(StandardCharsets.UTF_8))
    }

    private fun runAdbBinaryCommand(args: List<String>): ByteArray {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        execOperations.exec {
            commandLine(buildAdbCommand(args))
            isIgnoreExitValue = true
            standardOutput = stdout
            errorOutput = stderr
        }
        return stdout.toByteArray()
    }

    private fun buildAdbCommand(args: List<String>): List<String> = buildList {
        add(adbPath.get())
        val serial = deviceSerial.orNull.orEmpty()
        if (serial.isNotEmpty()) {
            add("-s")
            add(serial)
        }
        addAll(args)
    }

    private fun devicePathReadable(remotePath: String, accessMode: RemoteFileAccessMode, packageName: String): Boolean =
        runDeviceCommand(accessMode, listOf("sh", "-c", "ls ${shellQuote(remotePath)} >/dev/null 2>&1"), packageName).exitCode == 0

    private fun runDeviceCommand(
        accessMode: RemoteFileAccessMode,
        command: List<String>,
        packageName: String
    ): AdbCommandResult {
        val deviceCommand = when (accessMode) {
            RemoteFileAccessMode.SHELL -> listOf("shell") + command
            RemoteFileAccessMode.RUN_AS -> listOf("shell", "run-as", packageName) + command
        }
        return runAdbCommand(deviceCommand)
    }

    private fun resolveRemotePath(paths: DeviceStsPaths, relativePath: String): String {
        val trimmed = relativePath.trimStart('/')
        return if (trimmed.isEmpty()) paths.stsRoot else "${paths.stsRoot}/$trimmed"
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

    private fun normalizeInfoValue(value: String?): String = value?.trim().orEmpty().ifEmpty { "unknown" }

    private fun buildJvmLogExportFileName(): String =
        "sts-jvm-logs-export-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.zip"

    private fun textEntry(entryName: String, content: String): PulledEntry =
        PulledEntry(entryName, content.toByteArray(StandardCharsets.UTF_8))

    private data class PulledEntry(
        val entryName: String,
        val content: ByteArray
    )

    private enum class RemoteFileAccessMode {
        SHELL,
        RUN_AS
    }

    private data class DeviceStsPaths(
        val stsRoot: String,
        val accessMode: RemoteFileAccessMode
    )

    private data class AdbCommandResult(
        val exitCode: Int,
        val stdout: String
    )

    private companion object {
        const val MAX_JVM_LOG_EXPORT_SLOTS = 5
    }
}
