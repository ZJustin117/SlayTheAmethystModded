package io.stamethyst.backend.workshop

import android.content.Context
import io.stamethyst.backend.steamcloud.SteamCloudAcceleratedHttp
import io.stamethyst.backend.steamcloud.SteamCloudAuthStore
import io.stamethyst.backend.steamcloud.SteamCloudAuthStore.AuthSnapshot
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
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
            val dependencyIds = detail.children.mapNotNull { child ->
                child.publishedFileId.toULongOrNull()
            }.distinct()
            val dependencyDetailsById = loadDependencyDetails(appId, dependencyIds).associateBy { childDetail ->
                childDetail.publishedFileId.toULongOrNull()
            }
            WorkshopItemDetails(
                summary = summary,
                fileUrl = detail.fileUrl,
                hcontentFile = detail.hcontentFile?.takeIf { it > 0L }?.toULong(),
                depotId = detail.consumerAppId?.takeIf { it > 0 }?.toUInt(),
                jsonMetadata = payload,
                dependencies = dependencyIds.map { dependencyId ->
                    dependencyDetailsById[dependencyId]?.toSummary(appId, dependencyId)
                        ?: WorkshopItemSummary(
                            publishedFileId = dependencyId,
                            appId = appId,
                            title = knownWorkshopDependencyTitle(dependencyId) ?: "Workshop ID $dependencyId",
                            previewUrl = "",
                            description = "",
                        )
                },
            )
        }
    }

    private fun loadDependencyDetails(appId: UInt, publishedFileIds: List<ULong>): List<PublishedFileDetailsDto> {
        if (publishedFileIds.isEmpty()) return emptyList()
        val requestBody = FormBody.Builder().apply {
            add("itemcount", publishedFileIds.size.toString())
            publishedFileIds.forEachIndexed { index, publishedFileId ->
                add("publishedfileids[$index]", publishedFileId.toString())
            }
            add("appid", appId.toString())
        }.build()
        val request = Request.Builder()
            .url("https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/".toHttpUrl())
            .post(requestBody)
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val payload = response.body?.string().orEmpty()
            runCatching {
                json.decodeFromString<PublishedFileDetailsEnvelope>(payload).response.publishedFileDetails
            }.getOrDefault(emptyList())
        }
    }

    suspend fun download(request: WorkshopDownloadRequest): Flow<WorkshopDownloadEvent> = flow {
        emit(WorkshopDownloadEvent.StateChanged(WorkshopDownloadState.Resolving))
        val details = request.details
        val outputFile = File(request.outputDir, sanitizeFileName(details.summary.title) + ".jar")
        val tempFile = File(request.outputDir, outputFile.name + ".tmp")
        request.outputDir.mkdirs()
        when {
            !details.fileUrl.isNullOrBlank() -> {
                emit(WorkshopDownloadEvent.StateChanged(WorkshopDownloadState.Downloading))
                try {
                    val req = Request.Builder().url(details.fileUrl).build()
                    client.newCall(req).execute().use { response ->
                        if (!response.isSuccessful) error("Workshop download failed: ${response.code}")
                        val body = response.body ?: error("Workshop download body empty")
                        FileOutputStream(tempFile, false).use { output ->
                            body.byteStream().use { input ->
                                val totalBytes = body.contentLength().takeIf { it > 0L }
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                var writtenBytes = 0L
                                while (true) {
                                    if (Thread.currentThread().isInterrupted) throw InterruptedException("Workshop download interrupted")
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    if (read == 0) continue
                                    output.write(buffer, 0, read)
                                    writtenBytes += read
                                    emit(
                                        WorkshopDownloadEvent.Progress(
                                            WorkshopDownloadProgress(
                                                writtenBytes = writtenBytes,
                                                totalBytes = totalBytes,
                                                completedFiles = 0,
                                                totalFiles = 1,
                                            )
                                        )
                                    )
                                }
                            }
                        }
                    }
                    if (outputFile.exists() && !outputFile.delete()) throw IOException("Failed to replace existing workshop file")
                    if (!tempFile.renameTo(outputFile)) throw IOException("Failed to finalize workshop download")
                } catch (throwable: Throwable) {
                    tempFile.delete()
                    throw throwable
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

    fun downloadPreviewImage(appId: UInt, publishedFileId: ULong, previewUrl: String): String {
        return WorkshopPreviewImageStore(context, client).download(appId, publishedFileId, previewUrl)
    }

    fun createInstalledRecord(details: WorkshopItemDetails, artifact: WorkshopDownloadedArtifact): WorkshopInstalledModRecord {
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
            cardState = WorkshopModCardState.ImportedUnpatched,
            statusText = "等待修补",
            dependencies = details.dependencies,
        )
    }

    fun createNonStandardDownloadRecord(details: WorkshopItemDetails, outputDir: File): WorkshopInstalledModRecord {
        val path = outputDir.absolutePath
        return WorkshopInstalledModRecord(
            appId = details.summary.appId,
            publishedFileId = details.summary.publishedFileId,
            title = details.summary.title,
            description = details.summary.description,
            previewUrl = details.summary.previewUrl,
            versionText = details.summary.updatedAtMillis.toString(),
            updatedAtMillis = details.summary.updatedAtMillis,
            installedAtMillis = System.currentTimeMillis(),
            localJarPath = path,
            contentKind = WorkshopInstalledContentKind.NonStandard,
            cardState = WorkshopModCardState.NonStandardDownloaded,
            statusText = "该模组不是标准 jar 格式，请手动处理后导入，已存储到$path",
            dependencies = details.dependencies,
        )
    }

    fun createTexturePackRecord(details: WorkshopItemDetails, texturePackDir: File): WorkshopInstalledModRecord {
        val path = texturePackDir.absolutePath
        return WorkshopInstalledModRecord(
            appId = details.summary.appId,
            publishedFileId = details.summary.publishedFileId,
            title = details.summary.title,
            description = details.summary.description,
            previewUrl = details.summary.previewUrl,
            versionText = details.summary.updatedAtMillis.toString(),
            updatedAtMillis = details.summary.updatedAtMillis,
            installedAtMillis = System.currentTimeMillis(),
            localJarPath = path,
            contentKind = WorkshopInstalledContentKind.TexturePack,
            texturePackPath = path,
            cardState = WorkshopModCardState.TexturePackInstalled,
            statusText = "已作为 Texture Replacer 资源包安装并启用",
            dependencies = details.dependencies,
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
    data class Progress(val progress: WorkshopDownloadProgress) : WorkshopDownloadEvent
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
    @SerialName("publishedfileid") val publishedFileId: String = "",
    val title: String = "",
    @SerialName("file_url") val fileUrl: String? = null,
    @SerialName("file_size") val fileSize: Long? = null,
    @SerialName("hcontent_file") val hcontentFile: Long? = null,
    @SerialName("consumer_app_id") val consumerAppId: Long? = null,
    @SerialName("creator_name") val creatorName: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("time_updated") val timeUpdated: Long? = null,
    @SerialName("preview_url") val previewUrl: String? = null,
    val children: List<PublishedFileChildDto> = emptyList(),
)

@Serializable
private data class PublishedFileChildDto(
    @SerialName("publishedfileid") val publishedFileId: String = "",
)

private fun PublishedFileDetailsDto.toSummary(appId: UInt, fallbackPublishedFileId: ULong): WorkshopItemSummary = WorkshopItemSummary(
    publishedFileId = publishedFileId.toULongOrNull() ?: fallbackPublishedFileId,
    appId = consumerAppId?.takeIf { it > 0 }?.toUInt() ?: appId,
    title = title.ifBlank { knownWorkshopDependencyTitle(fallbackPublishedFileId) ?: "Workshop ID $fallbackPublishedFileId" },
    previewUrl = previewUrl.orEmpty(),
    description = description.orEmpty(),
    authorName = creatorName.orEmpty(),
    fileSizeBytes = fileSize ?: 0L,
    updatedAtMillis = (timeUpdated ?: 0L) * 1000L,
)

private fun knownWorkshopDependencyTitle(publishedFileId: ULong): String? = when (publishedFileId) {
    1605060445uL -> "ModTheSpire"
    1605833019uL -> "BaseMod"
    1609158507uL -> "StSLib"
    else -> null
}
