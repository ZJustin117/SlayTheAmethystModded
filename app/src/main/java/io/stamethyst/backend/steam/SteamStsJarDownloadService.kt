package io.stamethyst.backend.steam

import android.content.Context
import io.stamethyst.backend.steamcloud.SteamCloudAcceleratedHttp
import io.stamethyst.backend.steamcloud.SteamCloudAuthStore
import io.stamethyst.backend.workshop.WorkshopSteamClientIdentity
import io.stamethyst.ui.preferences.LauncherPreferences
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import top.apricityx.workshop.steam.protocol.SteamAccountSession
import top.apricityx.workshop.steam.protocol.SteamAppProductInfo
import top.apricityx.workshop.steam.protocol.SteamDirectoryClient
import top.apricityx.workshop.workshop.SteamDepotFileDownloadProgress
import top.apricityx.workshop.workshop.SteamDepotFileDownloadRequest
import top.apricityx.workshop.workshop.SteamDepotSingleFileDownloader
import `in`.dragonbra.javasteam.types.KeyValue
import `in`.dragonbra.javasteam.util.stream.MemoryStream

enum class SteamStsJarDownloadPhase {
    CONNECTING,
    RESOLVING,
    DOWNLOADING,
}

data class SteamStsJarDownloadProgress(
    val phase: SteamStsJarDownloadPhase,
    val progressPercent: Int? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = null,
)

internal class SteamStsJarDownloadService(
    private val context: Context,
    private val client: OkHttpClient = SteamCloudAcceleratedHttp.createClient(
        context = context,
        connectTimeoutMs = 15_000L,
        readTimeoutMs = 60_000L,
        callTimeoutMs = 120_000L,
        enabled = true,
        enabledProvider = { LauncherPreferences.isWorkshopWattAccelerationEnabled(context) },
    ),
) {
    fun downloadDesktopJar(
        onProgress: (SteamStsJarDownloadProgress) -> Unit,
    ): File = runBlocking {
        onProgress(SteamStsJarDownloadProgress(phase = SteamStsJarDownloadPhase.CONNECTING, progressPercent = 0))
        val identity = WorkshopSteamClientIdentity(context)
        val account = readSteamAccountSession(identity)
        val directoryClient = SteamDirectoryClient(client)
        val outputFile = prepareOutputFile()

        identity.createSession(client).use { session ->
            val cmServers = directoryClient.loadServers()
            session.connectWithRefreshToken(cmServers, account)
            onProgress(SteamStsJarDownloadProgress(phase = SteamStsJarDownloadPhase.RESOLVING, progressPercent = 0))
            val appInfo = parseAppInfo(session.requestAppProductInfo(STS_APP_ID))
            val candidates = resolveDepotCandidates(
                session = session,
                appId = STS_APP_ID,
                appInfo = appInfo,
                visitedAppIds = linkedSetOf(),
            ).sortedWith(
                compareBy<DepotManifestCandidate> { preferredDepotRank(it.depotId) }
                    .thenBy { it.depotId.toLong() }
            )
            if (candidates.isEmpty()) {
                throw IOException("Steam appinfo did not expose any public depot manifests for app=$STS_APP_ID")
            }

            val downloader = SteamDepotSingleFileDownloader(
                client = client,
                directoryClient = directoryClient,
                sessionFactory = { identity.createSession(client) },
                sessionConnector = { downloadSession, servers ->
                    downloadSession.connectWithRefreshToken(servers, account)
                },
            )
            var lastError: Throwable? = null
            for (candidate in candidates) {
                try {
                    outputFile.delete()
                    val depotKey = session.requestDepotDecryptionKey(
                        appId = candidate.appId,
                        depotId = candidate.depotId,
                    )
                    return@runBlocking downloader.download(
                        request = SteamDepotFileDownloadRequest(
                            appId = candidate.appId,
                            depotId = candidate.depotId,
                            manifestId = candidate.manifestId,
                            branch = candidate.branch,
                            fileName = STS_DESKTOP_JAR_FILE_NAME,
                            outputFile = outputFile,
                            depotKey = depotKey,
                        ),
                        emitProgress = { progress ->
                            onProgress(progress.toStsJarProgress())
                        },
                    )
                } catch (error: Throwable) {
                    lastError = error
                }
            }
            throw IOException("Failed to download $STS_DESKTOP_JAR_FILE_NAME from Steam depots", lastError)
        }
    }

    private fun readSteamAccountSession(identity: WorkshopSteamClientIdentity): SteamAccountSession {
        val auth = SteamCloudAuthStore.readAuthMaterial(context)
            ?: throw IOException("Steam account is not logged in")
        val snapshot = SteamCloudAuthStore.readSnapshot(context)
        val steamId = snapshot.steamId64.toLongOrNull() ?: 0L
        if (steamId <= 0L) {
            throw IOException("Steam account session is missing SteamID64")
        }
        return SteamAccountSession(
            accountName = auth.accountName,
            steamId = steamId,
            refreshToken = auth.refreshToken,
            machineName = identity.machineName,
        )
    }

    private fun prepareOutputFile(): File {
        val outputDir = File(context.cacheDir, "quickstart-steam").apply {
            if (!exists() && !mkdirs()) {
                throw IOException("Failed to create Steam quick-start cache directory: $absolutePath")
            }
        }
        return File(outputDir, STS_DESKTOP_JAR_FILE_NAME)
    }

    private fun parseAppInfo(productInfo: SteamAppProductInfo): KeyValue {
        val root = KeyValue()
        val bufferSize = productInfo.buffer.size.let { size ->
            if (size > 0 && productInfo.buffer[size - 1] == 0.toByte()) size - 1 else size
        }
        MemoryStream(productInfo.buffer, 0, bufferSize).use { stream ->
            if (!root.readAsText(stream)) {
                throw IOException("Failed to parse Steam appinfo for app=${productInfo.appId}")
            }
        }
        return root
    }

    private suspend fun resolveDepotCandidates(
        session: top.apricityx.workshop.steam.protocol.SteamCmSession,
        appId: UInt,
        appInfo: KeyValue,
        visitedAppIds: LinkedHashSet<UInt>,
    ): List<DepotManifestCandidate> {
        if (!visitedAppIds.add(appId)) {
            return emptyList()
        }
        val depots = appInfo.child("depots") ?: return emptyList()
        val candidates = mutableListOf<DepotManifestCandidate>()
        for (depot in depots.children) {
            val depotId = depot.name.trim().toUIntOrNull() ?: continue
            val manifestId = depot.child("manifests")
                ?.child(DEFAULT_BRANCH)
                ?.child("gid")
                ?.asManifestId()
            if (manifestId != null) {
                candidates += DepotManifestCandidate(
                    appId = appId,
                    depotId = depotId,
                    manifestId = manifestId,
                    branch = DEFAULT_BRANCH,
                )
                continue
            }

            val depotFromApp = depot.child("depotfromapp")?.asAppId()
                ?.takeIf { it != appId }
                ?: continue
            val parentInfo = parseAppInfo(session.requestAppProductInfo(depotFromApp))
            candidates += resolveDepotCandidates(
                session = session,
                appId = depotFromApp,
                appInfo = parentInfo,
                visitedAppIds = visitedAppIds,
            ).filter { it.depotId == depotId }
        }
        return candidates
    }

    private fun SteamDepotFileDownloadProgress.toStsJarProgress(): SteamStsJarDownloadProgress =
        SteamStsJarDownloadProgress(
            phase = SteamStsJarDownloadPhase.DOWNLOADING,
            progressPercent = progressPercent,
            downloadedBytes = writtenBytes,
            totalBytes = totalBytes,
        )

    private fun KeyValue.child(name: String): KeyValue? = get(name).takeIf { it != KeyValue.INVALID }

    private fun KeyValue.asManifestId(): ULong? {
        asString()?.trim()?.toULongOrNull()?.let { return it }
        val value = asLong(0L)
        return value.takeIf { it > 0L }?.toULong()
    }

    private fun KeyValue.asAppId(): UInt? {
        asString()?.trim()?.toUIntOrNull()?.let { return it }
        val value = asInteger(0)
        return value.takeIf { it > 0 }?.toUInt()
    }

    private fun preferredDepotRank(depotId: UInt): Int {
        val index = PREFERRED_DEPOT_IDS.indexOf(depotId)
        return if (index >= 0) index else Int.MAX_VALUE
    }

    private data class DepotManifestCandidate(
        val appId: UInt,
        val depotId: UInt,
        val manifestId: ULong,
        val branch: String,
    )

    private companion object {
        private const val STS_DESKTOP_JAR_FILE_NAME = "desktop-1.0.jar"
        private const val DEFAULT_BRANCH = "public"
        private val STS_APP_ID = 646570u
        private val PREFERRED_DEPOT_IDS = listOf(646571u, 646572u, 646573u, 646570u)
    }
}
