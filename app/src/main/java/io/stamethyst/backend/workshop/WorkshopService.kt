package io.stamethyst.backend.workshop

import android.content.Context
import io.stamethyst.backend.steamcloud.SteamCloudAcceleratedHttp
import io.stamethyst.backend.steamcloud.SteamCloudAuthStore
import io.stamethyst.backend.steamcloud.SteamCloudAuthStore.AuthSnapshot
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import top.apricityx.workshop.steam.protocol.CmServer
import top.apricityx.workshop.steam.protocol.SessionContext
import top.apricityx.workshop.steam.protocol.SteamAccountSession
import top.apricityx.workshop.steam.protocol.SteamCmSession
import top.apricityx.workshop.workshop.WorkshopDownloadEngine

internal class WorkshopService(
    private val context: Context,
    private val client: OkHttpClient = SteamCloudAcceleratedHttp.createClient(
        context = context,
        connectTimeoutMs = 15_000L,
        readTimeoutMs = 60_000L,
        callTimeoutMs = 120_000L,
    ),
    private val contentDownloaderFactory: ((WorkshopService) -> WorkshopContentDownloader)? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun hasSteamAuth(): Boolean = SteamCloudAuthStore.readAuthMaterial(context) != null

    fun authSnapshot(): AuthSnapshot = SteamCloudAuthStore.readSnapshot(context)

    suspend fun browse(query: WorkshopBrowseQuery): WorkshopBrowseResult = withContext(Dispatchers.IO) {
        val items = searchWorkshop(query)
        WorkshopBrowseResult(items = items, total = items.size, page = query.page, pageSize = query.pageSize)
    }

    suspend fun getDetails(appId: UInt, publishedFileId: ULong): WorkshopItemDetails = withContext(Dispatchers.IO) {
        val requestBody = FormBody.Builder()
            .add("itemcount", "1")
            .add("publishedfileids[0]", publishedFileId.toString())
            .add("appid", appId.toString())
            .build()
        val request = Request.Builder()
            .url("https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/".toHttpUrl())
            .post(requestBody)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Steam workshop details failed: ${response.code}")
            val payload = response.body?.string().orEmpty()
            val envelope = json.decodeFromString<PublishedFileDetailsEnvelope>(payload)
            val detail = envelope.response.publishedFileDetails.firstOrNull() ?: error("No workshop detail returned")
            val summary = WorkshopItemSummary(
                publishedFileId = publishedFileId,
                appId = appId,
                title = detail.title.ifBlank { "Workshop $publishedFileId" },
                previewUrl = detail.previewUrl.orEmpty(),
                description = detail.description.orEmpty(),
                authorName = detail.creatorName.orEmpty(),
                fileSizeBytes = detail.fileSize ?: 0L,
                updatedAtMillis = (detail.timeUpdated ?: 0L) * 1000L,
            )
            WorkshopItemDetails(
                summary = summary,
                fileUrl = detail.fileUrl,
                hcontentFile = detail.hcontentFile?.takeIf { it > 0L }?.toULong(),
                depotId = detail.consumerAppId?.takeIf { it > 0 }?.toUInt(),
                jsonMetadata = payload,
            )
        }
    }

    suspend fun download(request: WorkshopDownloadRequest): Flow<WorkshopDownloadEvent> = flow {
        emit(WorkshopDownloadEvent.StateChanged(WorkshopDownloadState.Resolving))
        val details = request.details
        val outputFile = File(request.outputDir, sanitizeFileName(details.summary.title) + ".jar")
        request.outputDir.mkdirs()
        when {
            !details.fileUrl.isNullOrBlank() -> {
                emit(WorkshopDownloadEvent.StateChanged(WorkshopDownloadState.Downloading))
                val req = Request.Builder().url(details.fileUrl).build()
                client.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) error("Workshop download failed: ${response.code}")
                    val body = response.body ?: error("Workshop download body empty")
                    FileOutputStream(outputFile, false).use { output ->
                        body.byteStream().use { input -> input.copyTo(output) }
                    }
                }
                emit(WorkshopDownloadEvent.Completed(listOf(WorkshopDownloadedArtifact(outputFile.name, outputFile.length(), outputFile.lastModified()))))
                emit(WorkshopDownloadEvent.StateChanged(WorkshopDownloadState.Success))
            }
            details.hcontentFile != null -> {
                emit(WorkshopDownloadEvent.StateChanged(WorkshopDownloadState.Downloading))
                contentDownloader().download(details, request.outputDir).collect { event ->
                    if (event != WorkshopDownloadEvent.Ignored) {
                        emit(event)
                    }
                }
            }
            else -> error("Workshop item has no downloadable source")
        }
    }

    private fun contentDownloader(): WorkshopContentDownloader =
        contentDownloaderFactory?.invoke(this)
            ?: SteamPipeWorkshopContentDownloader(::createEngine)

    private fun createEngine(): WorkshopDownloadEngine {
        val identity = WorkshopSteamClientIdentity(context)
        val account = SteamCloudAuthStore.readAuthMaterial(context)?.let { auth ->
            val snapshot = SteamCloudAuthStore.readSnapshot(context)
            val steamId = snapshot.steamId64.toLongOrNull() ?: 0L
            if (steamId > 0L) {
                SteamAccountSession(
                    accountName = auth.accountName,
                    steamId = steamId,
                    refreshToken = auth.refreshToken,
                    machineName = identity.machineName,
                )
            } else {
                null
            }
        }
        return WorkshopDownloadEngine.createDefault(
            client = client,
            sessionFactory = { identity.createSession(client) },
            sessionConnector = buildSessionConnector(account),
            maxConcurrentChunks = 4,
            allowPublicCdnFallbackOnSessionFailure = account == null,
            publishedFileLanguage = "schinese",
        )
    }

    private fun buildSessionConnector(
        account: SteamAccountSession?,
    ): suspend (SteamCmSession, List<CmServer>) -> SessionContext =
        if (account == null) {
            { session, servers -> session.connectAnonymous(servers) }
        } else {
            { session, servers -> session.connectWithRefreshToken(servers, account) }
        }

    fun createInstalledRecord(details: WorkshopItemDetails, artifact: WorkshopDownloadedArtifact, autoImported: Boolean): WorkshopInstalledModRecord {
        return WorkshopInstalledModRecord(
            appId = details.summary.appId,
            publishedFileId = details.summary.publishedFileId,
            title = details.summary.title,
            description = details.summary.description,
            previewUrl = details.summary.previewUrl,
            versionText = details.summary.updatedAtMillis.toString(),
            updatedAtMillis = details.summary.updatedAtMillis,
            installedAtMillis = System.currentTimeMillis(),
            localJarPath = artifact.relativePath,
            autoImported = autoImported,
        )
    }

    private fun searchWorkshop(query: WorkshopBrowseQuery): List<WorkshopItemSummary> {
        val searchUrl = "https://steamcommunity.com/workshop/browse/".toHttpUrl().newBuilder()
            .addQueryParameter("appid", query.appId.toString())
            .addQueryParameter("searchtext", query.searchText)
            .addQueryParameter("actualsort", "textsearch")
            .addQueryParameter("p", query.page.toString())
            .build()
        val html = client.newCall(Request.Builder().url(searchUrl).get().build()).execute().use { response ->
            if (!response.isSuccessful) error("Steam workshop browse failed: ${response.code}")
            response.body?.string().orEmpty()
        }
        return WorkshopBrowseParser.parse(html).take(query.pageSize)
    }

    private fun sanitizeFileName(value: String): String = value.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "workshop_mod" }
}

internal enum class WorkshopDownloadState { Resolving, Downloading, Success, Failed }

internal sealed interface WorkshopDownloadEvent {
    data object Ignored : WorkshopDownloadEvent
    data class StateChanged(val state: WorkshopDownloadState) : WorkshopDownloadEvent
    data class Log(val message: String) : WorkshopDownloadEvent
    data class Completed(val files: List<WorkshopDownloadedArtifact>) : WorkshopDownloadEvent
    data class Failed(val failure: WorkshopDownloadFailure) : WorkshopDownloadEvent
}

@Serializable
private data class PublishedFileDetailsEnvelope(
    val response: PublishedFileDetailsResponse,
)

@Serializable
private data class PublishedFileDetailsResponse(
    @SerialName("publishedfiledetails") val publishedFileDetails: List<PublishedFileDetailsDto> = emptyList(),
)

@Serializable
private data class PublishedFileDetailsDto(
    val title: String = "",
    @SerialName("file_url") val fileUrl: String? = null,
    @SerialName("file_size") val fileSize: Long? = null,
    @SerialName("hcontent_file") val hcontentFile: Long? = null,
    @SerialName("consumer_app_id") val consumerAppId: Long? = null,
    @SerialName("creator_name") val creatorName: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("time_updated") val timeUpdated: Long? = null,
    @SerialName("preview_url") val previewUrl: String? = null,
)
