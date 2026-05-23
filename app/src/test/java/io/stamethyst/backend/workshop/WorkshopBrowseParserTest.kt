package io.stamethyst.backend.workshop

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkshopBrowseParserTest {
    @Test
    fun parsesPublishedFileIdTitleAuthorAndPreview() {
        val html = """
            <div class="workshopItem" data-publishedfileid="123456">
              <a class="ugc" data-appid="646570" data-publishedfileid="123456">
                <img class="workshopItemPreviewImage" src="https://cdn.example/preview.jpg" />
                <div class="workshopItemTitle">Test Mod</div>
                <div class="workshopItemAuthorName">by &nbsp; Author</div>
              </a>
            </div>
        """.trimIndent()

        val page = WorkshopBrowseParser.parsePage(html, page = 1)
        val items = page.items

        assertFalse(page.hasNextPage)
        assertEquals(1, items.size)
        assertEquals(123456uL, items.single().publishedFileId)
        assertEquals(646570u, items.single().appId)
        assertEquals("Test Mod", items.single().title)
        assertEquals("Author", items.single().authorName)
        assertEquals("https://cdn.example/preview.jpg", items.single().previewUrl)
        assertTrue(items.single().description.isNotBlank())
    }

    @Test
    fun ignoresEntriesWithoutTitle() {
        val html = "<a data-publishedfileid=\"42\"></a>"

        val items = WorkshopBrowseParser.parse(html)

        assertTrue(items.isEmpty())
    }

    @Test
    fun parsesChineseMarkupDescriptionsAndNextPage() {
        val html = """
            <div data-panel="{&quot;type&quot;:&quot;PanelGroup&quot;}" class="workshopItem">
              <a href="https://steamcommunity.com/sharedfiles/filedetails/?id=3680514339&searchtext=" class="ugc" data-appid="646570" data-publishedfileid="3680514339">
                <div id="sharedfile_3680514339" class="workshopItemPreviewHolder ">
                  <img class="workshopItemPreviewImage " src="https://example.com/vibration.png">
                </div>
              </a>
              <a href="https://steamcommunity.com/sharedfiles/filedetails/?id=3680514339&searchtext=" class="item_link"><div class="workshopItemTitle ellipsis">手柄振动支持</div></a>
              <div class="workshopItemAuthorName ellipsis">作者：&nbsp;<a class="workshop_author_link" href="https://steamcommunity.com/profiles/1/myworkshopfiles/?appid=646570">Apricityx_</a></div>
            </div>
            <script>
              SharedFileBindMouseHover( "sharedfile_3680514339", false, {"id":"3680514339","title":"手柄振动支持","description":"中文描述"} );
            </script>
            <a class='pagebtn' href="https://steamcommunity.com/workshop/browse/?appid=646570&p=2">&gt;</a>
        """.trimIndent()

        val page = WorkshopBrowseParser.parsePage(html, page = 1)

        assertTrue(page.hasNextPage)
        assertEquals(1, page.items.size)
        assertEquals(3680514339uL, page.items.single().publishedFileId)
        assertEquals("手柄振动支持", page.items.single().title)
        assertEquals("Apricityx_", page.items.single().authorName)
        assertEquals("中文描述", page.items.single().description)
    }

    @Test
    fun parsesCurrentSteamLegacySearchMarkup() {
        val html = """
            <div class="workshopBrowseItems">
              <div data-panel="{&quot;type&quot;:&quot;PanelGroup&quot;}" class="workshopItem">
                <a href="https://steamcommunity.com/sharedfiles/filedetails/?id=1605833019&searchtext=BaseMod" class="ugc" data-appid="646570" data-publishedfileid="1605833019">
                  <div id="sharedfile_1605833019" class="workshopItemPreviewHolder ">
                    <img class="workshopItemPreviewImage " src="https://images.steamusercontent.com/ugc/base/preview.jpg">
                  </div>
                </a>
                <img class="fileRating" src="https://community.akamai.steamstatic.com/public/images/sharedfiles/4-star.png?v=2" />
                <a data-panel="{&quot;focusable&quot;:false}" href="https://steamcommunity.com/sharedfiles/filedetails/?id=1605833019&searchtext=BaseMod" class="item_link"><div class="workshopItemTitle ellipsis">BaseMod</div></a>
                <div class="workshopItemAuthorName ellipsis">作者：&nbsp;<a class="workshop_author_link" href="https://steamcommunity.com/profiles/76561197996637426/myworkshopfiles/?appid=646570">Bug Kiooeht</a></div>
                <div style="clear: both"></div>
              </div>
              <script>
                SharedFileBindMouseHover( "sharedfile_1605833019", false, {"id":"1605833019","title":"BaseMod","description":"BaseMod description","appid":646570} );
              </script>
            </div>
        """.trimIndent()

        val page = WorkshopBrowseParser.parsePage(html, page = 1)

        assertEquals(1, page.items.size)
        assertEquals(1605833019uL, page.items.single().publishedFileId)
        assertEquals("BaseMod", page.items.single().title)
        assertEquals("Bug Kiooeht", page.items.single().authorName)
        assertEquals("BaseMod description", page.items.single().description)
        assertEquals(4, page.items.single().rating?.score)
        assertEquals(5, page.items.single().rating?.maxScore)
    }

    @Test
    fun parsesSsrRenderContext() {
        val queryData = """
            {
              "mutations": [],
              "queries": [
                {
                  "state": {
                    "data": {
                      "public_data": {
                        "steamid": "76561198000000001",
                        "persona_name": "apricity"
                      }
                    }
                  },
                  "queryKey": ["PlayerLinkDetails", "76561198000000001"]
                },
                {
                  "state": {
                    "data": {
                      "current_page": 2,
                      "total_pages": 4,
                      "results": [
                        {
                          "publishedfileid": "3677098410",
                          "creator": "76561198000000001",
                          "consumer_appid": "646570",
                          "preview_url": "https://example.com/skip.png",
                          "title": "Skip The Spire",
                          "short_description": "A fun mod",
                          "file_size": "123456",
                          "vote_data": { "score": 0.72 }
                        }
                      ]
                    }
                  },
                  "queryKey": ["workshop_browse", 646570, "trend"]
                }
              ]
            }
        """.trimIndent()
        val renderContext = """{"queryData":${Json.encodeToString(queryData)}}"""
        val html = """
            <html>
              <head><script>window.SSR.renderContext=JSON.parse(${Json.encodeToString(renderContext)});</script></head>
              <body></body>
            </html>
        """.trimIndent()

        val page = WorkshopBrowseParser.parsePage(html, page = 1)

        assertEquals(2, page.page)
        assertTrue(page.hasNextPage)
        assertEquals(1, page.items.size)
        assertEquals(3677098410uL, page.items.single().publishedFileId)
        assertEquals("Skip The Spire", page.items.single().title)
        assertEquals("apricity", page.items.single().authorName)
        assertEquals("A fun mod", page.items.single().description)
        assertEquals(123456L, page.items.single().fileSizeBytes)
        assertEquals(4, page.items.single().rating?.score)
        assertEquals(5, page.items.single().rating?.maxScore)
    }
}
