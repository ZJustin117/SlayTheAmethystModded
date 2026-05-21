package top.apricityx.workshop.steam.protocol

import top.apricityx.workshop.steam.proto.CPublishedFile_QueryFiles_Request
import top.apricityx.workshop.steam.proto.CPublishedFile_QueryFiles_Response
import top.apricityx.workshop.steam.proto.CPublishedFile_GetUserFiles_Request
import top.apricityx.workshop.steam.proto.CPublishedFile_GetUserFiles_Response

data class SteamPublishedFileQuery(
    val appId: UInt,
    val searchText: String,
    val page: Int = 1,
    val pageSize: Int = 30,
    val queryType: Int = STEAM_PUBLISHED_FILE_QUERY_TYPE_RANKED_BY_TEXT_SEARCH,
    val language: Int = STEAM_LANGUAGE_ENGLISH,
)

data class SteamPublishedFileQueryResult(
    val total: Int,
    val items: List<SteamPublishedFileItem>,
    val nextCursor: String? = null,
)

data class SteamPublishedFileItem(
    val publishedFileId: ULong,
    val appId: UInt,
    val title: String,
    val description: String,
    val previewUrl: String,
    val creatorSteamId: Long,
    val fileSizeBytes: Long,
    val subscriptions: Int,
    val lifetimeSubscriptions: Int,
    val views: Int,
    val timeCreatedEpochSeconds: Long,
    val timeUpdatedEpochSeconds: Long,
)

class SteamPublishedFileClient(
    private val directoryClient: SteamDirectoryClient,
    private val sessionFactory: () -> SteamCmSession,
) {
    suspend fun getUserFiles(
        account: SteamAccountSession,
        appId: UInt,
        page: Int = 1,
        pageSize: Int = 30,
        type: String = "myfiles",
        sortMethod: String = "lastupdated",
        language: Int = STEAM_LANGUAGE_ENGLISH,
    ): SteamPublishedFileQueryResult {
        val cmServers = directoryClient.loadServers()
        return sessionFactory().use { session ->
            try {
                session.connectWithRefreshToken(cmServers, account)
                val response = session.callServiceMethod(
                    methodName = "PublishedFile.GetUserFiles#1",
                    request = CPublishedFile_GetUserFiles_Request.newBuilder()
                        .setSteamid(account.steamId)
                        .setAppid(appId.toInt())
                        .setCreatorAppid(appId.toInt())
                        .setPage(page)
                        .setNumperpage(pageSize)
                        .setType(type)
                        .setSortmethod(sortMethod)
                        .setLanguage(language)
                        .setReturnShortDescription(true)
                        .setStripDescriptionBbcode(true)
                        .build(),
                    parser = CPublishedFile_GetUserFiles_Response.parser(),
                )
                SteamPublishedFileQueryResult(
                    total = response.total,
                    items = response.publishedfiledetailsList.toSteamPublishedFileItems(),
                )
            } catch (error: Throwable) {
                throw when (error) {
                    is SteamProtocolException -> error
                    else -> SteamProtocolException("Failed to query Steam user published files", error)
                }
            }
        }
    }

    suspend fun queryFiles(
        account: SteamAccountSession,
        query: SteamPublishedFileQuery,
    ): SteamPublishedFileQueryResult {
        val cmServers = directoryClient.loadServers()
        return sessionFactory().use { session ->
            try {
                session.connectWithRefreshToken(cmServers, account)
                val response = session.callServiceMethod(
                    methodName = "PublishedFile.QueryFiles#1",
                    request = CPublishedFile_QueryFiles_Request.newBuilder()
                        .setQueryType(query.queryType)
                        .setPage(query.page)
                        .setNumperpage(query.pageSize)
                        .setAppid(query.appId.toInt())
                        .setSearchText(query.searchText)
                        .setLanguage(query.language)
                        .setReturnDetails(true)
                        .setReturnShortDescription(true)
                        .setStripDescriptionBbcode(true)
                        .build(),
                    parser = CPublishedFile_QueryFiles_Response.parser(),
                )
                SteamPublishedFileQueryResult(
                    total = response.total,
                    items = response.publishedfiledetailsList.toSteamPublishedFileItems(),
                    nextCursor = response.nextCursor.takeIf(String::isNotBlank),
                )
            } catch (error: Throwable) {
                throw when (error) {
                    is SteamProtocolException -> error
                    else -> SteamProtocolException("Failed to query Steam published files", error)
                }
            }
        }
    }
}

private fun List<top.apricityx.workshop.steam.proto.PublishedFileDetails>.toSteamPublishedFileItems(): List<SteamPublishedFileItem> =
    mapNotNull { detail ->
        detail.publishedfileid.takeIf { it > 0L }?.toULong()?.let { publishedFileId ->
            SteamPublishedFileItem(
                publishedFileId = publishedFileId,
                appId = detail.consumerAppid.toUInt(),
                title = detail.title,
                description = detail.shortDescription.takeIf(String::isNotBlank)
                    ?: detail.fileDescription,
                previewUrl = detail.previewUrl,
                creatorSteamId = detail.creator,
                fileSizeBytes = detail.fileSize,
                subscriptions = detail.subscriptions,
                lifetimeSubscriptions = detail.lifetimeSubscriptions,
                views = detail.views,
                timeCreatedEpochSeconds = detail.timeCreated.toLong(),
                timeUpdatedEpochSeconds = detail.timeUpdated.toLong(),
            )
        }
    }

const val STEAM_LANGUAGE_ENGLISH = 0
const val STEAM_LANGUAGE_SIMPLIFIED_CHINESE = 6
const val STEAM_PUBLISHED_FILE_QUERY_TYPE_RANKED_BY_TEXT_SEARCH = 12
