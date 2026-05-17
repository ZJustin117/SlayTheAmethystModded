package io.stamethyst.backend.workshop

internal object WorkshopBrowseParser {
    private val publishedFileIdRegex = Regex("data-publishedfileid=\"(\\d+)\"", RegexOption.IGNORE_CASE)
    private val titleRegex = Regex("class=\"workshopItemTitle[^\"]*\">([\\s\\S]*?)</div>", RegexOption.IGNORE_CASE)
    private val authorRegex = Regex("class=\"workshopItemAuthorName[^\"]*\">([\\s\\S]*?)</div>", RegexOption.IGNORE_CASE)
    private val previewRegex = Regex("class=\"workshopItemPreviewImage[^\"]*\" src=\"([^\"]+)\"", RegexOption.IGNORE_CASE)

    fun parse(html: String): List<WorkshopItemSummary> {
        return publishedFileIdRegex.findAll(html).mapNotNull { idMatch ->
            val id = idMatch.groupValues[1].toULongOrNull() ?: return@mapNotNull null
            val start = idMatch.range.first.coerceAtLeast(0)
            val searchSlice = html.substring(start, minOf(html.length, start + 6000))
            val title = titleRegex.find(searchSlice)?.groupValues?.getOrNull(1)?.let(::stripTags).orEmpty()
            val author = authorRegex.find(searchSlice)?.groupValues?.getOrNull(1)?.let(::stripAuthor).orEmpty()
            val preview = previewRegex.find(searchSlice)?.groupValues?.getOrNull(1).orEmpty()
            if (title.isBlank()) return@mapNotNull null
            WorkshopItemSummary(
                publishedFileId = id,
                appId = 646570u,
                title = title,
                previewUrl = preview,
                description = author.ifBlank { title },
                authorName = author,
            )
        }.distinctBy { it.publishedFileId }.toList()
    }

    private fun stripTags(text: String): String = decodeHtml(text)
        .replace(Regex("<[^>]+>"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun stripAuthor(text: String): String = stripTags(text)
        .replace(Regex("^by\\s+", RegexOption.IGNORE_CASE), "")
        .trim()

    private fun decodeHtml(text: String): String = text
        .replace("&nbsp;", " ", ignoreCase = true)
        .replace("&#160;", " ")
        .replace("&amp;", "&", ignoreCase = true)
        .replace("&lt;", "<", ignoreCase = true)
        .replace("&gt;", ">", ignoreCase = true)
        .replace("&quot;", "\"", ignoreCase = true)
        .replace("&#39;", "'")
}
