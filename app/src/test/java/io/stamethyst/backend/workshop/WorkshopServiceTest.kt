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

        val service = newService()
        val result = runBlocking {
            service.browse(WorkshopBrowseQuery(searchText = "test"))
        }

        assertEquals(1, result.items.size)
        assertEquals("Test Mod", result.items.single().title)
        assertEquals(123456uL, result.items.single().publishedFileId)
        assertEquals(1, browseServer.requestCount)
        assertEquals("/workshop/browse/", browseServer.takeRequest().url.encodedPath)
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
                            "title": "Detailed Mod",
                            "file_url": "${downloadServer.url("/mod.jar")}",
                            "file_size": 1234,
                            "hcontent_file": 9999,
                            "consumer_app_id": 646570,
                            "creator_name": "Author",
                            "description": "Details",
                            "time_updated": 1710000000,
                            "preview_url": "https://cdn.example/preview.jpg"
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
        assertEquals(downloadServer.url("/mod.jar").toString(), details.fileUrl)
        assertEquals(1, detailsServer.requestCount)
        assertEquals("/ISteamRemoteStorage/GetPublishedFileDetails/v1/", detailsServer.takeRequest().url.encodedPath)
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
                return TestRoots(
                    rootDir = rootDir,
                    context = object : ContextWrapper(Application()) {
                        override fun getFilesDir(): File = filesDir

                        override fun getPackageName(): String = "io.stamethyst.test"
                    }
                )
            }
        }
    }
}
