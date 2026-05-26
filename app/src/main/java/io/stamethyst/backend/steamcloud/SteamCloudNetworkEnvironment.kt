package io.stamethyst.backend.steamcloud

import android.content.Context
import io.stamethyst.config.LauncherConfig
import java.io.File
import java.util.Locale

internal object SteamCloudNetworkEnvironment {
    private const val LAST_CM_ENDPOINT_FILE_NAME = "last-websocket-cm-endpoint.txt"
    private const val CM_SERVER_LIST_FILE_NAME = "steam-cm-server-list.bin"
    private const val DIRECT_MODE_SWITCH_MARKER_FILE_NAME = "direct-mode-switch-confirmed-at.txt"
    private const val PROXY_DETECTION_ENABLED = false

    fun shouldPromptForDirectMode(context: Context): Boolean {
        if (!PROXY_DETECTION_ENABLED) {
            return false
        }
        if (LauncherConfig.isSteamCloudWattAccelerationEnabled(context)) {
            return true
        }
        if (isProxyOrAcceleratorEndpoint(readCachedCmEndpoint(context))) {
            return true
        }
        return wasProxyOrAcceleratorDetectedInLastSummary(context)
    }

    fun switchToDirectMode(context: Context) {
        LauncherConfig.setSteamCloudWattAccelerationEnabled(context, false)
        clearNetworkCache(context)
        writeDirectModeSwitchMarker(context)
    }

    fun clearNetworkCache(context: Context) {
        lastCmEndpointFile(context).delete()
        cmServerListFile(context).delete()
    }

    fun lastCmEndpointFile(context: Context): File =
        File(SteamCloudManifestStore.outputDir(context), LAST_CM_ENDPOINT_FILE_NAME)

    fun cmServerListFile(context: Context): File =
        File(SteamCloudManifestStore.outputDir(context), CM_SERVER_LIST_FILE_NAME)

    private fun directModeSwitchMarkerFile(context: Context): File =
        File(SteamCloudManifestStore.outputDir(context), DIRECT_MODE_SWITCH_MARKER_FILE_NAME)

    fun readCachedCmEndpoint(context: Context): String =
        runCatching { lastCmEndpointFile(context).takeIf { it.isFile }?.readText(Charsets.UTF_8).orEmpty().trim() }
            .getOrDefault("")

    private fun wasProxyOrAcceleratorDetectedInLastSummary(context: Context): Boolean =
        runCatching {
            val summary = SteamCloudDiagnosticsStore.summaryFile(context)
            if (!summary.isFile) {
                return@runCatching false
            }
            val marker = directModeSwitchMarkerFile(context)
            if (marker.isFile && marker.lastModified() >= summary.lastModified()) {
                return@runCatching false
            }
            summary.readLines(Charsets.UTF_8).any { line ->
                line.equals("Proxy/Accelerator Detected: yes", ignoreCase = true) ||
                    (line.startsWith("Resolved CM Endpoint:", ignoreCase = true) &&
                        isProxyOrAcceleratorEndpoint(line)) ||
                    (line.startsWith("CM Candidate Source:", ignoreCase = true) &&
                        isProxyOrAcceleratorEndpoint(line))
            }
        }.getOrDefault(false)

    private fun writeDirectModeSwitchMarker(context: Context) {
        val marker = directModeSwitchMarkerFile(context)
        runCatching {
            val parent = marker.parentFile
            if (parent != null && !parent.isDirectory) {
                parent.mkdirs()
            }
            marker.writeText(System.currentTimeMillis().toString(), Charsets.UTF_8)
        }
    }

    @JvmStatic
    fun isProxyOrAcceleratorEndpoint(value: String?): Boolean {
        if (!PROXY_DETECTION_ENABLED) {
            return false
        }
        val normalized = value.orEmpty().trim().lowercase(Locale.US)
        if (normalized.isEmpty()) {
            return false
        }
        if (normalized.contains("proxy") ||
            normalized.contains("vpn") ||
            normalized.contains("watt") ||
            normalized.contains("accelerat")
        ) {
            return true
        }
        return extractIpv4Candidates(normalized).any(::isProxyLikeIpv4)
    }

    private fun extractIpv4Candidates(value: String): List<String> =
        Regex("(?<!\\d)(?:\\d{1,3}\\.){3}\\d{1,3}(?!\\d)")
            .findAll(value)
            .map { it.value }
            .toList()

    private fun isProxyLikeIpv4(address: String): Boolean {
        val parts = address.split('.')
            .mapNotNull { part -> part.toIntOrNull()?.takeIf { it in 0..255 } }
        if (parts.size != 4) {
            return false
        }
        val first = parts[0]
        val second = parts[1]
        return first == 10 ||
            first == 127 ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 168) ||
            (first == 198 && second in 18..19)
    }
}
