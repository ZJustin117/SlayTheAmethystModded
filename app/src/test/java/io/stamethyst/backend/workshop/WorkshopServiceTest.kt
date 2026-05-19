package io.stamethyst.backend.workshop

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WorkshopServiceTest {
    private lateinit var browseServer: MockWebServer
    private lateinit var detailsServer: MockWebServer
    private lateinit var downloadServer: MockWebServer

    @Before
    fun setUp() {
        browseServer = MockWebServer()
        detailsServer = MockWebServer()
        downloadServer = MockWebServer()
        browseServer.start()
        detailsServer.start()
        downloadServer.start()
    }

    @After
    fun tearDown() {
        browseServer.close()
        detailsServer.close()
        downloadServer.close()
    }

    @Test
    fun browseParsesWorkshopItems() {
        browseServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    <div class="workshopItem" data-publishedfileid="123456">
                      <a class="ugc" data-publishedfileid="123456">
                        <img class="workshopItemPreviewImage" src="https://cdn.example/preview.jpg" />
                        <div class="workshopItemTitle">Test Mod</div>
                        <div class="workshopItemAuthorName">Author</div>
                      </a>
                    </div>
                    """.trimIndent(),
                )
                .build(),
        )
        detailsServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "response": {
                        "publishedfiledetails": [
                          { "publishedfileid": "123456", "file_size": 1234 }
                        ]
                      }
                    }
                    """.trimIndent(),
                )
                .build(),
        )

        val service = newService()
        val result = runBlocking {
            service.browse(WorkshopBrowseQuery(searchText = "test", timeFilter = WorkshopBrowseTimeFilter.ThirtyDays))
        }

        assertEquals(1, result.items.size)
        assertEquals("Test Mod", result.items.single().title)
        assertEquals(123456uL, result.items.single().publishedFileId)
        assertEquals(1234L, result.items.single().fileSizeBytes)
        assertTrue(!result.hasNextPage)
        assertEquals(1, browseServer.requestCount)
        val browseRequest = browseServer.takeRequest()
        assertEquals("/workshop/browse/", browseRequest.url.encodedPath)
        assertEquals("646570", browseRequest.url.queryParameter("appid"))
        assertEquals("test", browseRequest.url.queryParameter("searchtext"))
        assertEquals("schinese", browseRequest.url.queryParameter("l"))
        assertEquals("zh-CN,zh;q=0.9", browseRequest.headers["Accept-Language"])
        assertEquals("trend", browseRequest.url.queryParameter("browsesort"))
        assertEquals("trend", browseRequest.url.queryParameter("actualsort"))
        assertEquals("readytouseitems", browseRequest.url.queryParameter("section"))
        assertEquals("30", browseRequest.url.queryParameter("numperpage"))
        assertEquals("30", browseRequest.url.queryParameter("days"))
        assertEquals(1, detailsServer.requestCount)
        assertEquals("/ISteamRemoteStorage/GetPublishedFileDetails/v1/", detailsServer.takeRequest().url.encodedPath)
    }

    @Test
    fun getDetailsParsesPublishedFileDetails() {
        detailsServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "response": {
                        "publishedfiledetails": [
                          {
                            "publishedfileid": "123456",
                            "title": "Detailed Mod",
                            "file_url": "${downloadServer.url("/mod.jar")}",
                            "file_size": 1234,
                            "hcontent_file": 9999,
                            "consumer_app_id": 646570,
                            "creator_name": "Author",
                            "description": "Details",
                            "time_updated": 1710000000,
                            "subscriptions": 42,
                            "preview_url": "https://cdn.example/preview.jpg",
                            "children": [
                              { "publishedfileid": "1605833019" }
                            ]
                          }
                        ]
                      }
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        browseServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    <script>g_sessionID = "session123";</script>
                    <script>InitializeCommentThread("PublishedFile_Public", "0", {"owner":"123","feature":"456","feature2":"-1"}, 'https://steamcommunity.com/comment/PublishedFile_Public/');</script>
                    <span id="commentthread_123_totalcount">7</span>
                    <div class="workshopItemDescription" id="highlightContent">Localized Details</div>
                    """.trimIndent(),
                )
                .build(),
        )
        detailsServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "response": {
                        "publishedfiledetails": [
                          {
                            "publishedfileid": "1605833019",
                            "title": "BaseMod",
                            "consumer_app_id": 646570,
                            "creator_name": "Maintainer",
                            "description": "Required dependency",
                            "time_updated": 1710000100,
                            "preview_url": "https://cdn.example/basemod.jpg"
                          }
                        ]
                      }
                    }
                    """.trimIndent(),
                )
                .build(),
        )

        val service = newService()
        val details = runBlocking {
            service.getDetails(646570u, 123456uL)
        }

        assertEquals("Detailed Mod", details.summary.title)
        assertEquals("Localized Details", details.summary.description)
        assertEquals("Author", details.summary.authorName)
        assertEquals(42L, details.summary.downloadCount)
        assertEquals(downloadServer.url("/mod.jar").toString(), details.fileUrl)
        assertEquals(7L, details.commentCount)
        assertTrue(details.hasNextCommentPage)
        assertEquals(1, details.dependencies.size)
        assertEquals("BaseMod", details.dependencies.single().title)
        assertEquals(1605833019uL, details.dependencies.single().publishedFileId)
        assertEquals(1, browseServer.requestCount)
        assertEquals(2, detailsServer.requestCount)
        assertEquals("/ISteamRemoteStorage/GetPublishedFileDetails/v1/", detailsServer.takeRequest().url.encodedPath)
        assertEquals("/ISteamRemoteStorage/GetPublishedFileDetails/v1/", detailsServer.takeRequest().url.encodedPath)
    }

    @Test
    fun getDetailsFallsBackToCommunityPageAuthor() {
        detailsServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "response": {
                        "publishedfiledetails": [
                          {
                            "publishedfileid": "123456",
                            "title": "Detailed Mod",
                            "consumer_app_id": 646570,
                            "description": "Details"
                          }
                        ]
                      }
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        browseServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    <div class="workshopItemAuthorName">By <a href="https://steamcommunity.com/id/author">Community Author</a></div>
                    <div class="workshopItemDescription" id="highlightContent">Localized Details</div>
                    """.trimIndent(),
                )
                .build(),
        )

        val service = newService()
        val details = runBlocking { service.getDetails(646570u, 123456uL) }

        assertEquals("Community Author", details.summary.authorName)
        assertEquals(1, browseServer.requestCount)
        assertEquals(1, detailsServer.requestCount)
    }

    @Test
    fun getDetailsFallsBackToCreatorBlockAuthor() {
        detailsServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "response": {
                        "publishedfiledetails": [
                          {
                            "publishedfileid": "1605833019",
                            "title": "BaseMod",
                            "consumer_app_id": 646570,
                            "description": "Details"
                          }
                        ]
                      }
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        browseServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    <div class="rightSectionTopTitle condensed">创建者</div>
                    <div class="rightDetailsBlock">
                      <div class="creatorsBlock">
                        <div class="friendBlock persona offline">
                          <a class="friendBlockLinkOverlay" href="https://steamcommunity.com/profiles/76561197996637426"></a>
                          <div class="playerAvatar offline"><img src="avatar.jpg"></div>
                          <div class="friendBlockContent">
                            Bug Kiooeht<br>
                            <span class="friendSmallText">离线</span>
                          </div>
                        </div>
                      </div>
                    </div>
                    <div class="workshopItemDescription" id="highlightContent">Localized Details</div>
                    """.trimIndent(),
                )
                .build(),
        )

        val service = newService()
        val details = runBlocking { service.getDetails(646570u, 1605833019uL) }

        assertEquals("Bug Kiooeht", details.summary.authorName)
        assertEquals(1, browseServer.requestCount)
        assertEquals(1, detailsServer.requestCount)
    }

    @Test
    fun getDetailsParsesRequiredItemsWhenApiChildrenAreMissing() {
        detailsServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "response": {
                        "publishedfiledetails": [
                          {
                            "publishedfileid": "3651739735",
                            "title": "SpearAndShield",
                            "consumer_app_id": 646570,
                            "description": "Expansion details"
                          }
                        ]
                      }
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        browseServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    <div class="workshopItemDescription" id="highlightContent">Expansion details</div>
                    <div class="panel">
                      <div class="rightSectionTopTitle condensed">Required items</div>
                      <div class="requiredItemsContainer" id="RequiredItems">
                        <a href="https://steamcommunity.com/workshop/filedetails/?id=1609158507" target="_blank">
                          <div class="requiredItem">StSLib</div>
                        </a>
                        <a href="https://steamcommunity.com/workshop/filedetails/?id=1605833019" target="_blank">
                          <div class="requiredItem">BaseMod</div>
                        </a>
                        <a href="https://steamcommunity.com/workshop/filedetails/?id=1610056683" target="_blank">
                          <div class="requiredItem">Downfall Expansion Mod - 6.0</div>
                        </a>
                      </div>
                    </div>
                    """.trimIndent(),
                )
                .build(),
        )
        detailsServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "response": {
                        "publishedfiledetails": [
                          { "publishedfileid": "1609158507", "title": "StSLib", "consumer_app_id": 646570 },
                          { "publishedfileid": "1605833019", "title": "BaseMod", "consumer_app_id": 646570 },
                          { "publishedfileid": "1610056683", "title": "Downfall Expansion Mod - 6.0", "consumer_app_id": 646570 }
                        ]
                      }
                    }
                    """.trimIndent(),
                )
                .build(),
        )

        val service = newService()
        val details = runBlocking {
            service.getDetails(646570u, 3651739735uL)
        }

        assertEquals(
            listOf(1609158507uL, 1605833019uL, 1610056683uL),
            details.dependencies.map { it.publishedFileId },
        )
        assertEquals("Downfall Expansion Mod - 6.0", details.dependencies.last().title)
        assertEquals(1, browseServer.requestCount)
        assertEquals(2, detailsServer.requestCount)
    }

    @Test
    fun getCommentsPageParsesCurrentCommentAuthorAndTimestampMarkup() {
        browseServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "total_count": 1,
                      "pagesize": 5,
                      "start": 0,
                      "comments_html": "<div class=\"commentthread_comment\" id=\"comment_1\"><div class=\"commentthread_comment_content\"><div class=\"commentthread_comment_author\"><div class=\"commentthread_comment_avatar playerAvatar offline\"><a href=\"https://steamcommunity.com/id/alice\"><img src=\"avatar.jpg\"></a></div><div class=\"author_name_group\"><div class=\"flex_row\"><a class=\"hoverunderline commentthread_author_link\" href=\"https://steamcommunity.com/id/alice\" data-miniprofile=\"1\"><bdi>Alice</bdi></a></div><div class=\"commentthread_comment_timestamp\" title=\"2026 年 4 月 3 日 上午 1:27:15 PDT\" data-timestamp=\"1775204835\">4 月 3 日 上午 1:27&nbsp;</div></div></div><div class=\"commentthread_comment_text\">Current markup</div></div></div>"
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        val details = WorkshopItemDetails(
            summary = WorkshopItemSummary(
                publishedFileId = 123456uL,
                appId = 646570u,
                title = "Commented Mod",
                previewUrl = "",
                description = "",
            ),
            commentThreadContext = WorkshopCommentThreadContext(
                ownerId = "123",
                featureId = "456",
            ),
        )

        val service = newService()
        val page = runBlocking { service.getCommentsPage(details, page = 1) }

        assertEquals(1, page.comments.size)
        assertEquals("Alice", page.comments.single().authorName)
        assertEquals(1775204835L, page.comments.single().postedEpochSeconds)
        assertEquals("4 月 3 日 上午 1:27", page.comments.single().postedDisplayText)
        assertEquals("Current markup", page.comments.single().content)
    }

    @Test
    fun getCommentsPageParsesFiveComments() {
        browseServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "total_count": 6,
                      "pagesize": 5,
                      "start": 5,
                      "comments_html": "<div class=\"commentthread_comment\" id=\"comment_1\"><a class=\"commentthread_author_link\" href=\"https://steamcommunity.com/id/a\">Alice</a><span class=\"commentthread_comment_timestamp\" data-timestamp=\"1710000000\">Mar 9</span><div class=\"commentthread_comment_text\">Second page<br>comment</div></div>"
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        val details = WorkshopItemDetails(
            summary = WorkshopItemSummary(
                publishedFileId = 123456uL,
                appId = 646570u,
                title = "Commented Mod",
                previewUrl = "",
                description = "",
            ),
            commentThreadContext = WorkshopCommentThreadContext(
                ownerId = "123",
                featureId = "456",
            ),
        )

        val service = newService()
        val page = runBlocking { service.getCommentsPage(details, page = 2) }

        assertEquals(2, page.page)
        assertEquals(2, page.totalPages)
        assertTrue(page.hasPreviousPage)
        assertTrue(!page.hasNextPage)
        assertEquals(1, page.comments.size)
        assertEquals("Alice", page.comments.single().authorName)
        assertEquals("Second page\ncomment", page.comments.single().content)
        val request = browseServer.takeRequest()
        assertEquals("/comment/PublishedFile_Public/render/123/456/", request.url.encodedPath)
        assertTrue(requireNotNull(request.body).utf8().contains("start=5"))
    }

    @Test
    fun directDownloadWritesFile() {
        downloadServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("jar-bytes")
                .build(),
        )

        val service = newService()
        val outputDir = Files.createTempDirectory("workshop-download").toFile()
        val details = WorkshopItemDetails(
            summary = WorkshopItemSummary(
                publishedFileId = 123456uL,
                appId = 646570u,
                title = "Downloaded Mod",
                previewUrl = "",
                description = "",
            ),
            fileUrl = downloadServer.url("/mod.jar").toString(),
        )

        val events = mutableListOf<WorkshopDownloadEvent>()
        runBlocking {
            service.download(WorkshopDownloadRequest(details, outputDir)).collect { events += it }
        }

        assertTrue(events.any { it is WorkshopDownloadEvent.Completed })
        assertTrue(File(outputDir, "Downloaded Mod.jar").isFile)
        assertEquals("jar-bytes", File(outputDir, "Downloaded Mod.jar").readText(StandardCharsets.UTF_8))
        assertEquals(1, downloadServer.requestCount)
    }

    @Test
    fun hcontentDownloadUsesInjectedDownloader() {
        val service = newService(
            downloaderFactory = { _ ->
                WorkshopContentDownloader { details, _ ->
                    flowOf(
                        WorkshopDownloadEvent.StateChanged(WorkshopDownloadState.Resolving),
                        WorkshopDownloadEvent.StateChanged(WorkshopDownloadState.Downloading),
                        WorkshopDownloadEvent.Completed(
                            listOf(
                                WorkshopDownloadedArtifact(
                                    relativePath = "mod.jar",
                                    sizeBytes = 7,
                                    modifiedAtMillis = 1234L,
                                )
                            )
                        ),
                    )
                }
            }
        )
        val outputDir = Files.createTempDirectory("workshop-download-hcontent").toFile()
        val details = WorkshopItemDetails(
            summary = WorkshopItemSummary(
                publishedFileId = 99uL,
                appId = 646570u,
                title = "Hcontent Mod",
                previewUrl = "",
                description = "",
            ),
            hcontentFile = 5555uL,
        )

        val events = mutableListOf<WorkshopDownloadEvent>()
        runBlocking {
            service.download(WorkshopDownloadRequest(details, outputDir)).collect { events += it }
        }

        assertTrue(events.any { it is WorkshopDownloadEvent.StateChanged })
        assertTrue(events.any { it is WorkshopDownloadEvent.Completed })
    }

    private fun newService(
        downloaderFactory: ((WorkshopService) -> WorkshopContentDownloader)? = null,
    ): WorkshopService {
        val context = TestRoots.create("workshop-service").context
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val target = when (request.url.host) {
                    "steamcommunity.com" -> browseServer.url(request.url.encodedPath + querySuffix(request))
                    "api.steampowered.com" -> detailsServer.url(request.url.encodedPath + querySuffix(request))
                    else -> downloadServer.url(request.url.encodedPath + querySuffix(request))
                }
                chain.proceed(
                    Request.Builder()
                        .url(target)
                        .method(request.method, request.body)
                        .headers(request.headers)
                        .build()
                )
            }
            .build()
        return WorkshopService(context, client, downloaderFactory)
    }

    private fun querySuffix(request: Request): String = request.url.encodedQuery?.let { "?$it" }.orEmpty()

    private class TestRoots private constructor(
        val rootDir: File,
        val context: Context,
    ) {
        companion object {
            fun create(prefix: String): TestRoots {
                val rootDir = Files.createTempDirectory(prefix).toFile()
                val filesDir = File(rootDir, "internal-files").apply { mkdirs() }
                val cacheDir = File(rootDir, "cache").apply { mkdirs() }
                return TestRoots(
                    rootDir = rootDir,
                    context = object : ContextWrapper(Application()) {
                        override fun getFilesDir(): File = filesDir

                        override fun getCacheDir(): File = cacheDir

                        override fun getApplicationContext(): Context = this

                        override fun getPackageName(): String = "io.stamethyst.test"
                    }
                )
            }
        }
    }
}
