package io.stamethyst.backend.workshop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkshopBrowseParserTest {
    @Test
    fun parsesPublishedFileIdTitleAuthorAndPreview() {
        val html = """
            <div class="workshopItem" data-publishedfileid="123456">
              <a class="ugc" data-publishedfileid="123456">
                <img class="workshopItemPreviewImage" src="https://cdn.example/preview.jpg" />
                <div class="workshopItemTitle">Test Mod</div>
                <div class="workshopItemAuthorName">by &nbsp; Author</div>
              </a>
            </div>
        """.trimIndent()

        val items = WorkshopBrowseParser.parse(html)

        assertEquals(1, items.size)
        assertEquals(123456uL, items.single().publishedFileId)
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
}
