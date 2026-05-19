package io.stamethyst.backend.workshop

import androidx.test.core.app.ApplicationProvider
import io.stamethyst.backend.steamcloud.SteamCloudAcceleratedHttp
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test

class WorkshopBrowseInstrumentedTest {
    @Test
    fun searchesWorkshopItemsOnDeviceNetwork() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val result = runCatching {
            WorkshopService(context).browse(
                WorkshopBrowseQuery(
                    appId = 646570u,
                    searchText = "BaseMod",
                    sort = WorkshopBrowseSort.MostPopular,
                    timeFilter = WorkshopBrowseTimeFilter.AllTime,
                    page = 1,
                    pageSize = 30,
                )
            )
        }.getOrElse { error ->
            assumeNoException("Device network cannot reach Steam Workshop during smoke test", error)
            return@runBlocking
        }

        val diagnostics = buildString {
            appendLine("count=${result.items.size}")
            result.items.take(10).forEach { item ->
                appendLine("${item.publishedFileId}\t${item.title}\t${item.fileSizeBytes}")
            }
            if (result.items.isEmpty()) {
                val communityDiagnostics = fetchCommunityDiagnostics(context)
                appendLine(communityDiagnostics)
                if (communityDiagnostics.contains("eportal/index.jsp") || communityDiagnostics.contains("wlanuserip=")) {
                    assumeNoException(
                        "Device network returned a captive portal instead of Steam Workshop HTML",
                        IllegalStateException(communityDiagnostics),
                    )
                }
            }
        }
        assertTrue(diagnostics, result.items.isNotEmpty())
        assertTrue(diagnostics, result.items.any { it.publishedFileId > 0uL && it.title.isNotBlank() })
    }

    private fun fetchCommunityDiagnostics(context: android.content.Context): String {
        val client = SteamCloudAcceleratedHttp.createClient(
            context = context,
            connectTimeoutMs = 15_000L,
            readTimeoutMs = 60_000L,
            callTimeoutMs = 120_000L,
            enabled = true,
        )
        val url = "https://steamcommunity.com/workshop/browse/".toHttpUrl().newBuilder()
            .addQueryParameter("appid", "646570")
            .addQueryParameter("searchtext", "BaseMod")
            .addQueryParameter("childpublishedfileid", "0")
            .addQueryParameter("l", "schinese")
            .addQueryParameter("browsesort", "trend")
            .addQueryParameter("section", "readytouseitems")
            .addQueryParameter("actualsort", "trend")
            .addQueryParameter("p", "1")
            .addQueryParameter("numperpage", "30")
            .addQueryParameter("days", "-1")
            .build()
        val html = client.newCall(
            Request.Builder()
                .url(url)
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .header("User-Agent", "SlayTheAmethyst/Workshop")
                .build()
        ).execute().use { response ->
            val body = response.body.string()
            "status=${response.code} finalHost=${response.request.url.host} htmlLength=${body.length} " +
                "markers=${Regex("data-publishedfileid").findAll(body).count()} " +
                "titles=${Regex("workshopItemTitle").findAll(body).count()} " +
                "parsed=${WorkshopBrowseParser.parsePage(body, 1).items.size} " +
                "prefix=${body.take(120).replace('\n', ' ')}"
        }
        return html
    }
}
