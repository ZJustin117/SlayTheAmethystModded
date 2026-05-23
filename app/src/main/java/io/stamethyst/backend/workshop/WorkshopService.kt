package io.stamethyst.backend.workshop

import android.content.Context
import android.util.Log
import io.stamethyst.backend.steamcloud.SteamCloudAcceleratedHttp
import io.stamethyst.backend.steamcloud.SteamCloudAuthStore
import io.stamethyst.backend.steamcloud.SteamCloudAuthStore.AuthSnapshot
import io.stamethyst.ui.preferences.LauncherPreferences
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import top.apricityx.workshop.steam.protocol.CmServer
import top.apricityx.workshop.steam.protocol.SessionContext
import top.apricityx.workshop.steam.protocol.STEAM_PUBLISHED_FILE_QUERY_TYPE_RANKED_BY_TEXT_SEARCH
import top.apricityx.workshop.steam.protocol.SteamAccountSession
import top.apricityx.workshop.steam.protocol.SteamCmSession
import top.apricityx.workshop.steam.protocol.SteamDirectoryClient
import top.apricityx.workshop.steam.protocol.SteamPublishedFileClient
import top.apricityx.workshop.steam.protocol.SteamPublishedFileQuery
import top.apricityx.workshop.steam.protocol.SteamPublishedFileQueryResult
import top.apricityx.workshop.workshop.WorkshopDownloadEngine

internal class WorkshopService(
    private val context: Context,
    private val client: OkHttpClient = SteamCloudAcceleratedHttp.createClient(
        context = context,
        connectTimeoutMs = 15_000L,
        readTimeoutMs = 60_000L,
        callTimeoutMs = 120_000L,
        enabled = LauncherPreferences.isWorkshopWattAccelerationEnabled(context),
    ),
    private val contentDownloaderFactory: ((WorkshopService) -> WorkshopContentDownloader)? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val identity = WorkshopSteamClientIdentity(context)
    private val steamWebSession = WorkshopSteamWebSession(client, identity)
    private val steamLanguagePreference: SteamLanguagePreference
        get() = runCatching { LauncherPreferences.readWorkshopSteamLanguage(context) }
            .getOrDefault(SteamLanguagePreference.SimplifiedChinese)
    private val workshopClient = client.newBuilder()
        .cookieJar(steamWebSession.cookieJar)
        .addInterceptor(SteamLanguageInterceptor(::steamLanguagePreference))
        .build()
    private val browseDetailClient = client.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    fun hasSteamAuth(): Boolean = SteamCloudAuthStore.readAuthMaterial(context) != null

    fun authSnapshot(): AuthSnapshot = SteamCloudAuthStore.readSnapshot(context)

    suspend fun browse(query: WorkshopBrowseQuery): WorkshopBrowseResult = withContext(Dispatchers.IO) {
        val page = searchWorkshop(query)
        val items = enrichBrowseFileSizes(page.items).take(query.pageSize)
        WorkshopBrowseResult(
            items = items,
            total = items.size,
            page = page.page,
            pageSize = query.pageSize,
            hasNextPage = page.hasNextPage,
        )
    }

    suspend fun browseSubscriptions(
        appId: UInt = 646570u,
        page: Int = 1,
        pageSize: Int = 30,
    ): WorkshopBrowseResult = withContext(Dispatchers.IO) {
        val diagnostic = StringBuilder()
        runCatching {
            val account = readSteamAccountSession()
                ?: error("Steam 登录信息不完整，请重新登录后查看已订阅模组。")
            diagnostic.append("account=").append(account.accountName)
                .append(" steamId=").append(account.steamId)
                .append(" appId=").append(appId)
                .append(" page=").append(page)
                .append(" pageSize=").append(pageSize)
            val protocolResult = SteamPublishedFileClient(
                directoryClient = SteamDirectoryClient(client),
                sessionFactory = { identity.createSession(client) },
            ).getUserFiles(
                account = account,
                appId = appId,
                page = page.coerceAtLeast(1),
                pageSize = pageSize,
                type = "mysubscriptions",
                language = steamLanguagePreference.protocolLanguage,
            )
            diagnostic.append(" protocolTotal=").append(protocolResult.total)
                .append(" protocolItems=").append(protocolResult.items.size)
            val parsedPage = protocolResult.toBrowseParseResult(page.coerceAtLeast(1), pageSize)
            val items = parsedPage.items.take(pageSize)
            diagnostic.append(" enrichedItems=").append(items.size)
            WorkshopBrowseResult(
                items = items,
                total = items.size,
                page = parsedPage.page,
                pageSize = pageSize,
                hasNextPage = parsedPage.hasNextPage,
            )
        }.onFailure { error ->
            Log.e(TAG, "browseSubscriptions failed. $diagnostic", error)
        }.getOrThrow()
    }

    suspend fun getDetails(appId: UInt, publishedFileId: ULong): WorkshopItemDetails = withContext(Dispatchers.IO) {
        val languagePreference = steamLanguagePreference
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
            val localizedDetail = runCatching {
                loadLocalizedDetailPage(
                    publishedFileId = publishedFileId,
                    languageRequestValue = languagePreference.requestValue,
                )
            }.getOrNull()
            val summary = WorkshopItemSummary(
                publishedFileId = publishedFileId,
                appId = appId,
                title = detail.title.ifBlank { "Workshop $publishedFileId" },
                previewUrl = detail.previewUrl.orEmpty(),
                description = localizedDetail?.description?.ifBlank { detail.description.orEmpty() } ?: detail.description.orEmpty(),
                authorName = detail.creatorName.orEmpty().ifBlank { localizedDetail?.authorName.orEmpty() },
                fileSizeBytes = detail.fileSize ?: 0L,
                updatedAtMillis = (detail.timeUpdated ?: 0L) * 1000L,
                downloadCount = detail.subscriptions ?: 0L,
                rating = normalizedWorkshopRating(detail.voteData?.score),
            )
            val dependencyIds = (
                detail.children.mapNotNull { child -> child.publishedFileId.toULongOrNull() } +
                    localizedDetail?.requiredItemIds.orEmpty()
                ).distinct()
            val dependencyDetailsById = loadDependencyDetails(appId, dependencyIds).associateBy { childDetail ->
                childDetail.publishedFileId.toULongOrNull()
            }
            WorkshopItemDetails(
                summary = summary,
                fileUrl = detail.fileUrl,
                hcontentFile = detail.hcontentFile?.takeIf { it > 0L }?.toULong(),
                depotId = detail.consumerAppId?.takeIf { it > 0 }?.toUInt(),
                jsonMetadata = payload,
                changeNotesUrl = buildWorkshopChangeNotesUrl(publishedFileId, languagePreference.requestValue),
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
                commentsUrl = buildWorkshopCommentsUrl(publishedFileId, languagePreference.requestValue, page = 1),
                commentThreadContext = localizedDetail?.commentThreadContext,
                commentCount = localizedDetail?.commentCount,
                commentTotalPages = localizedDetail?.commentCount?.let(::resolveCommentTotalPages),
                hasNextCommentPage = localizedDetail?.commentCount?.let { count -> count > COMMENT_PAGE_SIZE } == true,
            )
        }
    }

    suspend fun getChangeNotes(publishedFileId: ULong): WorkshopChangeNotes = withContext(Dispatchers.IO) {
        val languagePreference = steamLanguagePreference
        val blocks = loadChangeNotesMarkdownBlocks(
            publishedFileId = publishedFileId,
            languageRequestValue = languagePreference.requestValue,
        )
        WorkshopChangeNotes(
            publishedFileId = publishedFileId,
            markdown = blocks.joinToString("\n\n"),
            latestMarkdown = blocks.firstOrNull().orEmpty(),
            url = buildWorkshopChangeNotesUrl(publishedFileId, languagePreference.requestValue),
        )
    }

    suspend fun getCommentsPage(
        details: WorkshopItemDetails,
        page: Int,
    ): WorkshopCommentPage = withContext(Dispatchers.IO) {
        loadWorkshopCommentPage(
            details = details,
            page = page,
            languageRequestValue = steamLanguagePreference.requestValue,
        )
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

    private fun loadLocalizedDetailPage(
        publishedFileId: ULong,
        languageRequestValue: String,
    ): LocalizedWorkshopDetail {
        val request = Request.Builder()
            .url(
                "https://steamcommunity.com/sharedfiles/filedetails/".toHttpUrl().newBuilder()
                    .addQueryParameter("id", publishedFileId.toString())
                    .addQueryParameter("l", languageRequestValue)
                    .build(),
            )
            .header("Accept-Language", languagePreferenceFor(languageRequestValue).acceptLanguageValue)
            .header("User-Agent", USER_AGENT)
            .build()

        return workshopClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Steam workshop community detail failed: ${response.code}")
            val payload = response.body?.string().orEmpty()
            LocalizedWorkshopDetail(
                description = extractDivInnerHtml(
                    payload = payload,
                    openingTag = """<div class="workshopItemDescription" id="highlightContent">""",
                )?.let(WorkshopServiceHtmlDecoder::decodeWorkshopHtmlDescription).orEmpty(),
                authorName = extractWorkshopAuthorName(payload),
                requiredItemIds = extractRequiredItemIds(payload),
                commentThreadContext = extractCommentThreadContext(payload),
                commentCount = extractCommentCount(payload),
            )
        }
    }

    private fun loadChangeNotesMarkdownBlocks(
        publishedFileId: ULong,
        languageRequestValue: String,
    ): List<String> {
        val request = Request.Builder()
            .url(buildWorkshopChangeNotesUrl(publishedFileId, languageRequestValue))
            .header("Accept-Language", languagePreferenceFor(languageRequestValue).acceptLanguageValue)
            .header("User-Agent", USER_AGENT)
            .build()

        return workshopClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Steam workshop changelog failed: ${response.code}")
            extractChangeNotesMarkdownBlocks(response.body.string())
        }
    }

    private fun loadWorkshopCommentPage(
        details: WorkshopItemDetails,
        page: Int,
        languageRequestValue: String,
    ): WorkshopCommentPage {
        val commentThreadContext = details.commentThreadContext ?: error("Workshop comment thread context was missing")
        val safePage = page.coerceAtLeast(1)
        val start = (safePage - 1) * COMMENT_PAGE_SIZE
        val formBody = FormBody.Builder()
            .add("start", start.toString())
            .add("count", COMMENT_PAGE_SIZE.toString())
            .apply {
                commentThreadContext.sessionId?.takeIf(String::isNotBlank)?.let { add("sessionid", it) }
                commentThreadContext.extendedData?.takeIf(String::isNotBlank)?.let { add("extended_data", it) }
                commentThreadContext.feature2?.takeIf { it.isNotBlank() && it != "-1" }?.let { add("feature2", it) }
            }
            .build()
        val request = Request.Builder()
            .url(
                "https://steamcommunity.com/".toHttpUrl().newBuilder()
                    .addPathSegments(
                        "comment/PublishedFile_Public/render/${commentThreadContext.ownerId}/${commentThreadContext.featureId}/",
                    )
                    .addQueryParameter("l", languageRequestValue)
                    .build(),
            )
            .post(formBody)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept-Language", languagePreferenceFor(languageRequestValue).acceptLanguageValue)
            .header("User-Agent", USER_AGENT)
            .build()

        return workshopClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Workshop comments request failed: ${response.code}")
            val payload = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
            val commentCount = payload.longValue("total_count")
            val pageSize = payload.intValue("pagesize") ?: COMMENT_PAGE_SIZE
            val responseStart = payload.intValue("start")
            val comments = extractComments(payload.stringValue("comments_html"))
            val resolvedPage = if (responseStart != null && pageSize > 0) {
                (responseStart / pageSize) + 1
            } else {
                safePage
            }
            val totalPages = resolveCommentTotalPages(commentCount)
            WorkshopCommentPage(
                commentsUrl = buildWorkshopCommentsUrl(
                    details.summary.publishedFileId,
                    languageRequestValue,
                    resolvedPage,
                ),
                commentCount = commentCount,
                page = resolvedPage,
                totalPages = totalPages,
                hasPreviousPage = resolvedPage > 1,
                hasNextPage = when {
                    totalPages != null -> resolvedPage < totalPages
                    else -> comments.size >= pageSize
                },
                comments = comments,
            )
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
        val account = readSteamAccountSession(identity)
        return WorkshopDownloadEngine.createDefault(
            client = workshopClient,
            sessionFactory = { identity.createSession(workshopClient) },
            sessionConnector = buildSessionConnector(account),
            maxConcurrentChunks = LauncherPreferences.readWorkshopDownloadThreads(context),
            allowPublicCdnFallbackOnSessionFailure = true,
            publishedFileLanguage = steamLanguagePreference.requestValue,
        )
    }

    private fun readSteamAccountSession(
        identity: WorkshopSteamClientIdentity = WorkshopSteamClientIdentity(context),
    ): SteamAccountSession? = SteamCloudAuthStore.readAuthMaterial(context)?.let { auth ->
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

    private suspend fun searchWorkshop(query: WorkshopBrowseQuery): WorkshopBrowseParseResult {
        if (query.searchText.isNotBlank()) {
            authenticatedPublishedFileSearch(query)
                ?.takeIf { it.items.isNotEmpty() }
                ?.let { return it }
        }
        primeSteamWebSessionIfNeeded()
        val searchUrl = "https://steamcommunity.com/workshop/browse/".toHttpUrl().newBuilder()
            .addQueryParameter("appid", query.appId.toString())
            .addQueryParameter("searchtext", query.searchText)
            .addQueryParameter("childpublishedfileid", "0")
            .addQueryParameter("l", steamLanguagePreference.requestValue)
            .addQueryParameter("browsesort", query.sort.browseSortValue)
            .addQueryParameter("section", "readytouseitems")
            .addQueryParameter("actualsort", query.sort.actualSortValue)
            .addQueryParameter("p", query.page.toString())
            .addQueryParameter("numperpage", query.pageSize.toString())
            .apply {
                query.category.requiredTag?.let { tag ->
                    addQueryParameter("requiredtags[]", tag)
                }
                if (query.sort.usesTimeFilter) {
                    addQueryParameter("days", query.timeFilter.days.toString())
                }
            }
            .build()
        val html = workshopClient.newCall(
            Request.Builder()
                .url(searchUrl)
                .header("Accept-Language", steamLanguagePreference.acceptLanguageValue)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
        ).execute().use { response ->
            if (!response.isSuccessful) error("Steam workshop browse failed: ${response.code}")
            response.body?.string().orEmpty()
        }
        val page = WorkshopBrowseParser.parsePage(html, query.page)
        if (page.items.isEmpty() && looksLikeCaptivePortal(html)) {
            error("当前网络返回了 Wi-Fi/校园网认证页面，请先完成网络认证后重试")
        }
        return page
    }

    private suspend fun authenticatedPublishedFileSearch(query: WorkshopBrowseQuery): WorkshopBrowseParseResult? {
        val account = runCatching { readSteamAccountSession() }.getOrNull() ?: return null
        return runCatching {
            SteamPublishedFileClient(
                directoryClient = SteamDirectoryClient(workshopClient),
                sessionFactory = { identity.createSession(workshopClient) },
            ).queryFiles(
                account = account,
                query = SteamPublishedFileQuery(
                    appId = query.appId,
                    searchText = query.searchText.trim(),
                    page = query.page,
                    pageSize = query.pageSize,
                    queryType = STEAM_PUBLISHED_FILE_QUERY_TYPE_RANKED_BY_TEXT_SEARCH,
                    language = steamLanguagePreference.protocolLanguage,
                    requiredTags = query.category.requiredTag?.let(::listOf).orEmpty(),
                ),
            ).toBrowseParseResult(query.page, query.pageSize)
        }.getOrNull()
    }

    private suspend fun primeSteamWebSessionIfNeeded() {
        val account = runCatching { readSteamAccountSession(identity) }.getOrNull()
        runCatching {
            steamWebSession.ensurePrimed(
                account = account,
                client = workshopClient,
                languagePreference = steamLanguagePreference,
            )
        }
    }

    private fun enrichBrowseFileSizes(items: List<WorkshopItemSummary>): List<WorkshopItemSummary> {
        if (items.isEmpty() || items.all { it.fileSizeBytes > 0L }) return items
        val appId = items.first().appId
        val fileSizesById = runCatching { loadFileSizes(appId, items) }.getOrDefault(emptyMap())
        if (fileSizesById.isEmpty()) return items
        return items.map { item ->
            fileSizesById[item.publishedFileId]?.let { size -> item.copy(fileSizeBytes = size) } ?: item
        }
    }

    private fun loadFileSizes(appId: UInt, items: List<WorkshopItemSummary>): Map<ULong, Long> {
        val missingSizeItems = items.filter { it.fileSizeBytes <= 0L }
        if (missingSizeItems.isEmpty()) return emptyMap()
        val requestBody = FormBody.Builder().apply {
            add("itemcount", missingSizeItems.size.toString())
            add("appid", appId.toString())
            missingSizeItems.forEachIndexed { index, item ->
                add("publishedfileids[$index]", item.publishedFileId.toString())
            }
        }.build()
        val request = Request.Builder()
            .url("https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/".toHttpUrl())
            .post(requestBody)
            .build()
        return browseDetailClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyMap()
            val payload = response.body?.string().orEmpty()
            runCatching {
                json.decodeFromString<PublishedFileDetailsEnvelope>(payload)
                    .response
                    .publishedFileDetails
                    .mapNotNull { detail ->
                        val publishedFileId = detail.publishedFileId.toULongOrNull()
                        val fileSize = detail.fileSize
                        if (publishedFileId != null && fileSize != null) publishedFileId to fileSize else null
                    }
                    .toMap()
            }.getOrDefault(emptyMap())
        }
    }

    private fun sanitizeFileName(value: String): String = value.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "workshop_mod" }

    private fun languagePreferenceFor(requestValue: String): SteamLanguagePreference =
        SteamLanguagePreference.entries.firstOrNull { it.requestValue == requestValue } ?: steamLanguagePreference

    private fun extractCommentCount(payload: String): Long? =
        totalCommentCountRegex.find(payload)?.groupValues?.getOrNull(1)?.toLongOrNull()
            ?: totalCommentCountLabelRegex.find(payload)
                ?.groupValues
                ?.getOrNull(1)
                ?.replace(",", "")
                ?.trim()
                ?.toLongOrNull()

    private fun extractCommentThreadContext(payload: String): WorkshopCommentThreadContext? {
        val commentInit = commentInitDataRegex.find(payload)?.groupValues?.getOrNull(1) ?: return null
        val commentInitObject = runCatching { json.parseToJsonElement(commentInit).jsonObject }.getOrNull() ?: return null
        val ownerId = commentInitObject.stringValue("owner").ifBlank { return null }
        val featureId = commentInitObject.stringValue("feature").ifBlank { return null }
        val feature2 = commentInitObject.stringValue("feature2").ifBlank { null }
        val extendedData = commentInitObject.stringValue("extended_data").ifBlank { null }
        val sessionId = sessionIdRegex.find(payload)?.groupValues?.getOrNull(1).orEmpty().ifBlank { null }
        return WorkshopCommentThreadContext(
            ownerId = ownerId,
            featureId = featureId,
            feature2 = feature2,
            extendedData = extendedData,
            sessionId = sessionId,
        )
    }

    private fun extractRequiredItemIds(payload: String): List<ULong> {
        val openingMatch = requiredItemsContainerOpeningRegex.find(payload) ?: return emptyList()
        val section = extractDivBlock(
            payload = payload,
            openingTagStart = openingMatch.range.first,
            openingTagLength = openingMatch.value.length,
        ) ?: return emptyList()
        return requiredItemLinkRegex.findAll(section)
            .mapNotNull { match -> match.groupValues.getOrNull(1)?.toULongOrNull() }
            .distinct()
            .toList()
    }

    private fun extractWorkshopAuthorName(payload: String): String =
        workshopAuthorAnchorRegex.find(payload)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(WorkshopServiceHtmlDecoder::stripTagsAndDecode)
            ?.ifBlank { null }
            ?: extractCreatorBlockAuthorName(payload)
                .ifBlank { null }
            ?: workshopBreadcrumbAuthorRegex.find(payload)
                ?.groupValues
                ?.getOrNull(1)
                ?.let(WorkshopServiceHtmlDecoder::stripTagsAndDecode)
                ?.removeSuffix("的创意工坊")
                ?.trim()
                ?.ifBlank { null }
            ?: workshopAuthorTextRegex.find(payload)
                ?.groupValues
                ?.getOrNull(1)
                ?.let(WorkshopServiceHtmlDecoder::stripTagsAndDecode)
                .orEmpty()

    private fun extractCreatorBlockAuthorName(payload: String): String {
        val openingMatch = creatorsBlockOpeningRegex.find(payload) ?: return ""
        val section = extractDivBlock(
            payload = payload,
            openingTagStart = openingMatch.range.first,
            openingTagLength = openingMatch.value.length,
        ) ?: return ""
        return creatorFriendBlockContentRegex.find(section)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(WorkshopServiceHtmlDecoder::stripTagsAndDecode)
            .orEmpty()
    }

    private fun extractComments(payload: String): List<WorkshopComment> =
        commentBlockOpeningRegex.findAll(payload)
            .mapNotNull { openingMatch ->
                val id = openingMatch.groupValues[1]
                val block = extractDivBlock(
                    payload = payload,
                    openingTagStart = openingMatch.range.first,
                    openingTagLength = openingMatch.value.length,
                ) ?: return@mapNotNull null
                val (profileUrl, authorName) = extractCommentAuthor(block)
                val timestampMatch = commentTimestampRegexes.asSequence()
                    .mapNotNull { regex -> regex.find(block) }
                    .firstOrNull()
                val postedEpochSeconds = timestampMatch?.groupValueOrNull("timestamp")?.toLongOrNull()
                val postedDisplayText = timestampMatch?.groupValueOrNull("text")
                    ?.let(WorkshopServiceHtmlDecoder::stripTagsAndDecode)
                    .orEmpty()
                val content = commentTextRegex.find(block)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.let(WorkshopServiceHtmlDecoder::decodeWorkshopComment)
                    .orEmpty()
                if (content.isBlank()) return@mapNotNull null
                WorkshopComment(
                    id = id,
                    authorName = authorName.ifBlank { "未知用户" },
                    profileUrl = profileUrl,
                    content = content,
                    postedEpochSeconds = postedEpochSeconds,
                    postedDisplayText = postedDisplayText,
                )
            }
            .distinctBy(WorkshopComment::id)
            .toList()

    private fun extractChangeNotesMarkdownBlocks(payload: String): List<String> =
        changeLogBlockOpeningRegex.findAll(payload)
            .mapNotNull { openingMatch ->
                val block = extractDivBlock(
                    payload = payload,
                    openingTagStart = openingMatch.range.first,
                    openingTagLength = openingMatch.value.length,
                ) ?: return@mapNotNull null
                val headline = changeLogHeadlineRegex.find(block)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.let(WorkshopServiceHtmlDecoder::stripTagsAndDecode)
                    .orEmpty()
                val body = changeLogBodyRegex.findAll(block)
                    .mapNotNull { match ->
                        match.groupValues
                            .getOrNull(1)
                            ?.let(WorkshopServiceHtmlDecoder::decodeWorkshopChangeNotes)
                            ?.takeIf(String::isNotBlank)
                    }
                    .joinToString("\n\n")
                buildString {
                    if (headline.isNotBlank()) {
                        append("### ")
                        append(headline)
                        append("\n\n")
                    }
                    if (body.isNotBlank()) {
                        append(body)
                    }
                }.trim().takeIf(String::isNotBlank)
            }
            .toList()

    private fun extractCommentAuthor(block: String): Pair<String, String> =
        commentAuthorLinkRegex.findAll(block)
            .map { match ->
                val profileUrl = match.groupValues.getOrNull(1)?.let(WorkshopServiceHtmlDecoder::decode)?.trim().orEmpty()
                val authorName = match.groupValues.getOrNull(2)?.let(WorkshopServiceHtmlDecoder::stripTagsAndDecode).orEmpty()
                profileUrl to authorName
            }
            .firstOrNull { (_, authorName) -> authorName.isNotBlank() }
            ?: ("" to "")

    private fun resolveCommentTotalPages(commentCount: Long?): Int? =
        commentCount?.let { count ->
            if (count <= 0L) 1 else ((count + COMMENT_PAGE_SIZE - 1) / COMMENT_PAGE_SIZE).toInt()
        }

    private fun buildWorkshopCommentsUrl(
        publishedFileId: ULong,
        languageRequestValue: String,
        page: Int,
    ): String = buildString {
        append("https://steamcommunity.com/sharedfiles/filedetails/comments/")
        append(publishedFileId)
        append("?l=")
        append(languageRequestValue)
        if (page > 1) {
            append("&ctp=")
            append(resolveSteamCommentsPage(page))
        }
    }

    private fun buildWorkshopChangeNotesUrl(
        publishedFileId: ULong,
        languageRequestValue: String,
    ): String = "https://steamcommunity.com/sharedfiles/filedetails/changelog/$publishedFileId?l=$languageRequestValue"

    private fun resolveSteamCommentsPage(appCommentPage: Int): Int =
        (((appCommentPage - 1) * COMMENT_PAGE_SIZE) / STEAM_COMMENTS_PAGE_SIZE) + 1

    private fun looksLikeCaptivePortal(html: String): Boolean {
        val sample = html.take(4096).lowercase()
        return sample.contains("eportal/index.jsp") ||
            sample.contains("wlanuserip=") ||
            sample.contains("wlanacname=") ||
            sample.contains("captive portal") ||
            sample.contains("wifi") && sample.contains("login") && !sample.contains("workshopitem")
    }

    private fun looksLikeSteamLoginPage(html: String): Boolean {
        val sample = html.take(8192).lowercase()
        return sample.contains("<form") && sample.contains("login_form") ||
            steamLoggedInFalseRegex.containsMatchIn(sample)
    }

    private fun looksLikeSteamLoginUrl(url: String): Boolean {
        val normalized = url.lowercase()
        return normalized.contains("steamcommunity.com/login/") ||
            normalized.contains("steamcommunity.com/login/home")
    }

    private fun summarizeSteamHtmlForLog(html: String): String = html
        .replace(Regex("""\s+"""), " ")
        .take(1200)

    private companion object {
        const val TAG = "WorkshopService"
        const val COMMENT_PAGE_SIZE = 5
        const val STEAM_COMMENTS_PAGE_SIZE = 50
        const val USER_AGENT = "SlayTheAmethyst/Workshop"
        val commentInitDataRegex = Regex(
            """InitializeCommentThread\(\s*"PublishedFile_Public"\s*,\s*"[^"]+"\s*,\s*(\{.*?\})\s*,\s*'https://steamcommunity\.com/comment/PublishedFile_Public/'""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val sessionIdRegex = Regex(
            """g_sessionID\s*=\s*"([^"]+)"""",
            RegexOption.IGNORE_CASE,
        )
        val totalCommentCountRegex = Regex(
            """"total_count"\s*:\s*(\d+)""",
            RegexOption.IGNORE_CASE,
        )
        val totalCommentCountLabelRegex = Regex(
            """id="commentthread_[^"]*_totalcount">([^<]+)<""",
            RegexOption.IGNORE_CASE,
        )
        val commentBlockOpeningRegex = Regex(
            """<div\b[^>]*class="[^"]*\bcommentthread_comment\b[^"]*"[^>]*id="comment_([^"]+)"[^>]*>""",
            RegexOption.IGNORE_CASE,
        )
        val commentAuthorLinkRegex = Regex(
            """<a\b(?=[^>]*class="[^"]*\bcommentthread_author_link\b[^"]*")(?=[^>]*href="([^"]*)")[^>]*>(.*?)</a>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val commentTimestampRegexes = listOf(
            Regex(
                """<(?:span|div)\b(?=[^>]*class="[^"]*\bcommentthread_comment_timestamp\b[^"]*")(?=[^>]*\bdata-timestamp="(?<timestamp>\d+)")[^>]*>(?<text>.*?)</(?:span|div)>""",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
            ),
            Regex(
                """<(?:span|div)\b(?=[^>]*class="[^"]*\bcommentthread_comment_timestamp\b[^"]*")[^>]*\btitle="(?<text>[^"]*)"[^>]*>""",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
            ),
            Regex(
                """<(?:span|div)\b(?=[^>]*class="[^"]*\bcommentthread_comment_timestamp\b[^"]*")[^>]*>(?<text>.*?)</(?:span|div)>""",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
            ),
        )
        val commentTextRegex = Regex(
            """<div\b[^>]*class="[^"]*\bcommentthread_comment_text\b[^"]*"[^>]*>(.*?)</div>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val changeLogBlockOpeningRegex = Regex(
            """<div\b[^>]*class="[^"]*\bchangeLogCtn\b[^"]*"[^>]*>""",
            RegexOption.IGNORE_CASE,
        )
        val changeLogHeadlineRegex = Regex(
            """<div\b[^>]*class="[^"]*\bheadline\b[^"]*"[^>]*>(.*?)</div>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val changeLogBodyRegex = Regex(
            """<p\b[^>]*>(.*?)</p>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val requiredItemsContainerOpeningRegex = Regex(
            """<div\b[^>]*\bid="RequiredItems"[^>]*>""",
            RegexOption.IGNORE_CASE,
        )
        val requiredItemLinkRegex = Regex(
            """<a\b[^>]*href="[^"]*(?:sharedfiles|workshop)/filedetails/\?id=(\d+)[^"]*"[^>]*>""",
            RegexOption.IGNORE_CASE,
        )
        val workshopAuthorAnchorRegex = Regex(
            """<div\b[^>]*class="[^"]*\bworkshopItemAuthorName\b[^"]*"[^>]*>.*?<a\b[^>]*>(.*?)</a>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val workshopAuthorTextRegex = Regex(
            """<div\b[^>]*class="[^"]*\bworkshopItemAuthorName\b[^"]*"[^>]*>(.*?)</div>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val creatorsBlockOpeningRegex = Regex(
            """<div\b[^>]*class="[^"]*\bcreatorsBlock\b[^"]*"[^>]*>""",
            RegexOption.IGNORE_CASE,
        )
        val creatorFriendBlockContentRegex = Regex(
            """<div\b[^>]*class="[^"]*\bfriendBlockContent\b[^"]*"[^>]*>\s*(.*?)(?:<br\s*/?>|<span\b[^>]*class="[^"]*\bfriendSmallText\b)""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val workshopBreadcrumbAuthorRegex = Regex(
            """<div\b[^>]*class="[^"]*\bbreadcrumbs\b[^"]*"[^>]*>.*?<a\b[^>]*myworkshopfiles/\?appid=\d+[^>]*>(.*?)</a>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val steamLoggedInFalseRegex = Regex("""g_bloggedin\s*=\s*false""", RegexOption.IGNORE_CASE)
    }
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
    val subscriptions: Long? = null,
    @SerialName("vote_data") val voteData: PublishedFileVoteDataDto? = null,
    val children: List<PublishedFileChildDto> = emptyList(),
)

@Serializable
private data class PublishedFileVoteDataDto(
    val score: Float? = null,
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
    downloadCount = subscriptions ?: 0L,
    rating = normalizedWorkshopRating(voteData?.score),
)

private fun SteamPublishedFileQueryResult.toBrowseParseResult(page: Int, pageSize: Int): WorkshopBrowseParseResult =
    WorkshopBrowseParseResult(
        items = items.map { item ->
            WorkshopItemSummary(
                publishedFileId = item.publishedFileId,
                appId = item.appId,
                title = item.title,
                previewUrl = item.previewUrl,
                description = item.description,
                authorName = "",
                fileSizeBytes = item.fileSizeBytes,
                updatedAtMillis = item.timeUpdatedEpochSeconds * 1000L,
                downloadCount = item.subscriptions.toLong(),
                rating = normalizedWorkshopRating(item.ratingScore),
            )
        },
        page = page,
        hasNextPage = total > page * pageSize || !nextCursor.isNullOrBlank(),
    )

private fun knownWorkshopDependencyTitle(publishedFileId: ULong): String? = when (publishedFileId) {
    1605060445uL -> "ModTheSpire"
    1605833019uL -> "BaseMod"
    1609158507uL -> "StSLib"
    1610056683uL -> "Downfall Expansion Mod"
    else -> null
}

private data class LocalizedWorkshopDetail(
    val description: String,
    val authorName: String = "",
    val requiredItemIds: List<ULong> = emptyList(),
    val commentThreadContext: WorkshopCommentThreadContext? = null,
    val commentCount: Long? = null,
)

private fun extractDivInnerHtml(
    payload: String,
    openingTag: String,
): String? {
    val start = payload.indexOf(openingTag)
    if (start < 0) return null
    var cursor = start + openingTag.length
    var depth = 1
    while (cursor < payload.length) {
        val nextOpen = payload.indexOf("<div", cursor, ignoreCase = true).takeIf { it >= 0 }
        val nextClose = payload.indexOf("</div", cursor, ignoreCase = true).takeIf { it >= 0 }
        val nextIndex = listOfNotNull(nextOpen, nextClose).minOrNull() ?: break
        if (nextIndex == nextOpen) {
            depth += 1
            cursor = nextIndex + 4
            continue
        }
        depth -= 1
        if (depth == 0) return payload.substring(start + openingTag.length, nextIndex)
        cursor = nextIndex + 5
    }
    return null
}

private fun extractDivBlock(
    payload: String,
    openingTagStart: Int,
    openingTagLength: Int,
): String? {
    var cursor = openingTagStart + openingTagLength
    var depth = 1
    while (cursor < payload.length) {
        val nextOpen = payload.indexOf("<div", cursor, ignoreCase = true).takeIf { it >= 0 }
        val nextClose = payload.indexOf("</div", cursor, ignoreCase = true).takeIf { it >= 0 }
        val nextIndex = listOfNotNull(nextOpen, nextClose).minOrNull() ?: break
        if (nextIndex == nextOpen) {
            depth += 1
            cursor = nextIndex + 4
            continue
        }
        depth -= 1
        if (depth == 0) {
            val closingTagEnd = payload.indexOf('>', nextIndex).takeIf { it >= 0 } ?: return null
            return payload.substring(openingTagStart, closingTagEnd + 1)
        }
        cursor = nextIndex + 5
    }
    return null
}

private fun JsonObject.stringValue(key: String): String =
    this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

private fun JsonObject.longValue(key: String): Long? =
    this[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull()

private fun JsonObject.intValue(key: String): Int? =
    this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

private fun MatchResult.groupValueOrNull(name: String): String? =
    runCatching { groups[name]?.value }.getOrNull()

private object WorkshopServiceHtmlDecoder {
    private val numericEntityRegex = Regex("""&#(x?[0-9A-Fa-f]+);""")
    private val htmlTagRegex = Regex("""<[^>]+>""")
    private val emoticonImageRegex = Regex("""(?is)<img\b[^>]*\balt="([^"]+)"[^>]*\bclass="[^"]*\bemoticon\b[^"]*"[^>]*>""")
    private val whitespaceRegex = Regex("""\s+""")
    private val inlineWhitespaceRegex = Regex("""[^\S\n]+""")

    fun stripTagsAndDecode(value: String): String = decode(value.replace(htmlTagRegex, " "))

    fun decode(value: String): String = decodeEntities(value)
        .replace(whitespaceRegex, " ")
        .trim()

    fun decodeWorkshopHtmlDescription(value: String): String {
        if (value.isBlank()) return ""
        return decodePreservingLineBreaks(
            value
                .replace(Regex("""(?i)<br\s*/?>"""), "\n")
                .replace(Regex("""(?i)<li[^>]*>"""), "- ")
                .replace(Regex("""(?i)</li\s*>"""), "\n")
                .replace(Regex("""(?i)</p\s*>"""), "\n\n")
                .replace(Regex("""(?i)</div\s*>"""), "\n")
                .replace(htmlTagRegex, " "),
        )
    }

    fun decodeWorkshopChangeNotes(value: String): String {
        if (value.isBlank()) return ""
        return decodePreservingLineBreaks(
            value
                .replace(Regex("""(?i)<br\s*/?>"""), "\n")
                .replace(Regex("""(?i)<li[^>]*>"""), "- ")
                .replace(Regex("""(?i)</li\s*>"""), "\n")
                .replace(Regex("""(?i)<(?:ul|ol)[^>]*>"""), "\n")
                .replace(Regex("""(?i)</(?:ul|ol)\s*>"""), "\n")
                .replace(Regex("""(?i)</p\s*>"""), "\n\n")
                .replace(Regex("""(?i)</div\s*>"""), "\n")
                .replace(htmlTagRegex, " "),
        )
    }

    fun decodeWorkshopComment(value: String): String {
        if (value.isBlank()) return ""
        return decodePreservingLineBreaks(
            value
                .replace(emoticonImageRegex) { match -> " ${match.groupValues[1]} " }
                .replace(Regex("""(?i)<br\s*/?>"""), "\n")
                .replace(Regex("""(?i)</p\s*>"""), "\n\n")
                .replace(Regex("""(?i)</div\s*>"""), "\n")
                .replace(htmlTagRegex, " "),
        )
    }

    private fun decodePreservingLineBreaks(value: String): String =
        decodeEntities(value)
            .replace(Regex("""\r\n?"""), "\n")
            .lines()
            .joinToString("\n") { line -> line.replace(inlineWhitespaceRegex, " ").trim() }
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()

    private fun decodeEntities(value: String): String {
        val withNumericEntities = numericEntityRegex.replace(value) { match ->
            val token = match.groupValues[1]
            val codePoint = if (token.startsWith("x", ignoreCase = true)) {
                token.substring(1).toIntOrNull(16)
            } else {
                token.toIntOrNull()
            }
            codePoint?.let { String(Character.toChars(it)) } ?: match.value
        }
        return withNumericEntities
            .replace("&nbsp;", " ", ignoreCase = true)
            .replace("&amp;", "&", ignoreCase = true)
            .replace("&quot;", "\"", ignoreCase = true)
            .replace("&#39;", "'")
            .replace("&#x27;", "'", ignoreCase = true)
            .replace("&lt;", "<", ignoreCase = true)
            .replace("&gt;", ">", ignoreCase = true)
    }
}
