package top.apricityx.workshop.workshop

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import top.apricityx.workshop.steam.protocol.CdnServer
import top.apricityx.workshop.steam.protocol.CmServer
import top.apricityx.workshop.steam.protocol.SessionContext
import top.apricityx.workshop.steam.protocol.SteamCmSession
import top.apricityx.workshop.steam.protocol.SteamContentClient
import top.apricityx.workshop.steam.protocol.SteamDirectoryClient

data class SteamDepotFileDownloadRequest(
    val appId: UInt,
    val depotId: UInt,
    val manifestId: ULong,
    val branch: String = "public",
    val fileName: String,
    val outputFile: File,
    val depotKey: ByteArray,
)

data class SteamDepotFileDownloadProgress(
    val writtenBytes: Long,
    val totalBytes: Long,
    val completedChunks: Int,
    val totalChunks: Int,
) {
    val progressPercent: Int
        get() = if (totalBytes <= 0L) {
            0
        } else {
            ((writtenBytes.coerceIn(0L, totalBytes) * 100L) / totalBytes).toInt()
        }
}

class SteamDepotSingleFileDownloader(
    private val client: OkHttpClient,
    private val directoryClient: SteamDirectoryClient,
    private val sessionFactory: () -> SteamCmSession,
    private val sessionConnector: suspend (SteamCmSession, List<CmServer>) -> SessionContext,
) {
    suspend fun download(
        request: SteamDepotFileDownloadRequest,
        emitProgress: suspend (SteamDepotFileDownloadProgress) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val cmServers = directoryClient.loadServers()
        val cdnTransport = SteamCdnTransport(client)

        sessionFactory().use { session ->
            sessionConnector(session, cmServers)
            val contentClient = SteamContentClient(session, directoryClient)
            val manifestRequestCode = contentClient.getManifestRequestCode(
                appId = request.appId,
                depotId = request.depotId,
                manifestId = request.manifestId,
                branch = request.branch,
            )
            if (manifestRequestCode == 0uL) {
                throw WorkshopDownloadException(
                    "Steam returned no manifest request code for depot=${request.depotId} manifest=${request.manifestId}",
                )
            }
            val contentServers = runCatching { contentClient.getServersForSteamPipe() }
                .getOrElse { directoryClient.loadContentServers() }
            require(contentServers.isNotEmpty()) { "No CDN servers available for SteamPipe" }
            val serverPool = cdnTransport.buildServerPool(request.appId, contentServers)
            require(serverPool.downloadServers.isNotEmpty()) { "No CDN download servers available for app=${request.appId}" }
            val cdnAuthTokenCache = ConcurrentHashMap<String, String>()

            val manifest = downloadManifest(
                request = request,
                contentServers = serverPool.downloadServers,
                proxyServer = serverPool.proxyServer,
                manifestRequestCode = manifestRequestCode,
                contentClient = contentClient,
                cdnTransport = cdnTransport,
                cdnAuthTokenCache = cdnAuthTokenCache,
            )
            val preparedManifest = if (manifest.filenamesEncrypted) {
                manifest.decryptFilenames(request.depotKey)
            } else {
                manifest
            }
            val targetEntry = preparedManifest.files.firstOrNull { it.matchesTargetFileName(request.fileName) }
                ?: throw WorkshopDownloadException(
                    "Steam depot ${request.depotId} manifest ${request.manifestId} did not contain ${request.fileName}",
                )
            if (!targetEntry.linkTarget.isNullOrBlank() || preparedManifest.requiresDirectory(targetEntry)) {
                throw WorkshopDownloadException("Steam depot entry ${targetEntry.path} is not a regular file")
            }

            downloadFileChunks(
                request = request,
                manifestFile = targetEntry,
                contentServers = serverPool.downloadServers,
                proxyServer = serverPool.proxyServer,
                contentClient = contentClient,
                cdnTransport = cdnTransport,
                cdnAuthTokenCache = cdnAuthTokenCache,
                emitProgress = emitProgress,
            )
            request.outputFile
        }
    }

    private suspend fun downloadManifest(
        request: SteamDepotFileDownloadRequest,
        contentServers: List<CdnServer>,
        proxyServer: CdnServer?,
        manifestRequestCode: ULong,
        contentClient: SteamContentClient,
        cdnTransport: SteamCdnTransport,
        cdnAuthTokenCache: ConcurrentHashMap<String, String>,
    ): DepotManifest {
        var lastError: Throwable? = null
        for (server in contentServers) {
            try {
                val bytes = requestBytes(
                    server = server,
                    proxyServer = proxyServer,
                    path = "depot/${request.depotId}/manifest/${request.manifestId}/5/$manifestRequestCode",
                    query = cdnAuthTokenCache[server.host],
                    appId = request.appId,
                    depotId = request.depotId,
                    contentClient = contentClient,
                    cdnTransport = cdnTransport,
                    cdnAuthTokenCache = cdnAuthTokenCache,
                )
                return DepotManifestParser.parse(unzipSingleEntry(bytes))
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw WorkshopDownloadException("Unable to download Steam depot manifest", lastError)
    }

    private suspend fun downloadFileChunks(
        request: SteamDepotFileDownloadRequest,
        manifestFile: ManifestFile,
        contentServers: List<CdnServer>,
        proxyServer: CdnServer?,
        contentClient: SteamContentClient,
        cdnTransport: SteamCdnTransport,
        cdnAuthTokenCache: ConcurrentHashMap<String, String>,
        emitProgress: suspend (SteamDepotFileDownloadProgress) -> Unit,
    ) {
        val parent = request.outputFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw WorkshopDownloadException("Failed to create output directory: ${parent.absolutePath}")
        }

        val chunks = manifestFile.chunks.sortedBy(ManifestChunk::offset)
        val totalBytes = manifestFile.size
        var writtenBytes = 0L
        var completedChunks = 0
        emitProgress(
            SteamDepotFileDownloadProgress(
                writtenBytes = 0L,
                totalBytes = totalBytes,
                completedChunks = 0,
                totalChunks = chunks.size,
            ),
        )

        RandomAccessFile(request.outputFile, "rw").use { output ->
            output.setLength(totalBytes)
            for (chunk in chunks) {
                val processed = downloadChunkWithRetries(
                    request = request,
                    contentServers = contentServers,
                    proxyServer = proxyServer,
                    contentClient = contentClient,
                    cdnTransport = cdnTransport,
                    cdnAuthTokenCache = cdnAuthTokenCache,
                    chunk = chunk,
                )
                output.seek(chunk.offset)
                output.write(processed)
                writtenBytes += processed.size.toLong()
                completedChunks += 1
                emitProgress(
                    SteamDepotFileDownloadProgress(
                        writtenBytes = writtenBytes,
                        totalBytes = totalBytes,
                        completedChunks = completedChunks,
                        totalChunks = chunks.size,
                    ),
                )
            }
        }

        when (val validation = WorkshopFileIntegrityVerifier.assess(request.outputFile, manifestFile)) {
            AssembledFileValidation.Verified,
            is AssembledFileValidation.ChunkVerifiedHashMismatch -> Unit
            is AssembledFileValidation.Invalid -> throw WorkshopDownloadException(
                "Downloaded file checksum mismatch for ${manifestFile.path} " +
                    "(expected=${validation.expectedShaHex} actual=${validation.actualShaHex})",
            )
        }
    }

    private suspend fun downloadChunkWithRetries(
        request: SteamDepotFileDownloadRequest,
        contentServers: List<CdnServer>,
        proxyServer: CdnServer?,
        contentClient: SteamContentClient,
        cdnTransport: SteamCdnTransport,
        cdnAuthTokenCache: ConcurrentHashMap<String, String>,
        chunk: ManifestChunk,
    ): ByteArray {
        var lastError: Throwable? = null
        for (attempt in 1..MAX_CHUNK_DOWNLOAD_ATTEMPTS) {
            for (server in rotateServers(contentServers, attempt - 1)) {
                try {
                    val raw = requestBytes(
                        server = server,
                        proxyServer = proxyServer,
                        path = "depot/${request.depotId}/chunk/${chunk.idHex}",
                        query = cdnAuthTokenCache[server.host],
                        appId = request.appId,
                        depotId = request.depotId,
                        contentClient = contentClient,
                        cdnTransport = cdnTransport,
                        cdnAuthTokenCache = cdnAuthTokenCache,
                    )
                    return ChunkProcessor.process(raw, chunk, request.depotKey)
                } catch (error: Throwable) {
                    lastError = error
                }
            }
            if (attempt < MAX_CHUNK_DOWNLOAD_ATTEMPTS) {
                delay(CHUNK_RETRY_DELAY_MILLIS * attempt)
            }
        }
        throw WorkshopDownloadException("Failed to download chunk ${chunk.idHex}", lastError)
    }

    private suspend fun requestBytes(
        server: CdnServer,
        proxyServer: CdnServer?,
        path: String,
        query: String?,
        appId: UInt,
        depotId: UInt,
        contentClient: SteamContentClient,
        cdnTransport: SteamCdnTransport,
        cdnAuthTokenCache: ConcurrentHashMap<String, String>,
    ): ByteArray {
        return cdnTransport.requestBytes(
            server = server,
            path = path,
            query = query,
            proxyServer = proxyServer,
            resolveAuthToken = { host ->
                cdnAuthTokenCache[host] ?: contentClient.getCdnAuthToken(appId, depotId, host).token.also {
                    cdnAuthTokenCache[host] = it
                }
            },
        )
    }

    private fun unzipSingleEntry(zipBytes: ByteArray): ByteArray {
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            val entry = zip.nextEntry ?: throw WorkshopDownloadException("Zip payload was empty")
            val output = ByteArrayOutputStream()
            zip.copyTo(output)
            zip.closeEntry()
            return output.toByteArray()
        }
    }

    private fun rotateServers(
        servers: List<CdnServer>,
        offset: Int,
    ): List<CdnServer> {
        if (servers.isEmpty()) {
            return emptyList()
        }
        return List(servers.size) { index -> servers[(index + offset) % servers.size] }
    }

    private fun ManifestFile.matchesTargetFileName(fileName: String): Boolean {
        val normalizedPath = path.replace('\\', '/')
        val normalizedFileName = fileName.trim().replace('\\', '/')
        return normalizedPath.equals(normalizedFileName, ignoreCase = true) ||
            normalizedPath.endsWith("/$normalizedFileName", ignoreCase = true)
    }

    private companion object {
        private const val MAX_CHUNK_DOWNLOAD_ATTEMPTS = 3
        private const val CHUNK_RETRY_DELAY_MILLIS = 750L
    }
}
