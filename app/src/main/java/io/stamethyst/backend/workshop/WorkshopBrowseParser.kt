package io.stamethyst.backend.workshop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal object WorkshopBrowseParser {
    private val json = Json { ignoreUnknownKeys = true }
    private val workshopItemStartRegex = Regex(
        """<div\b[^>]*class="[^"]*\bworkshopItem\b[^"]*"[^>]*>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val dataPublishedFileIdRegex = Regex("""data-publishedfileid="(\d+)""", RegexOption.IGNORE_CASE)
    private val hrefPublishedFileIdRegex = Regex("""filedetails/\?[^\"]*\bid=(\d+)""", RegexOption.IGNORE_CASE)
    private val appIdRegex = Regex("""data-appid="(\d+)""", RegexOption.IGNORE_CASE)
    private val itemPreviewRegex = Regex(
        """class="[^"]*\bworkshopItemPreviewImage\b[^"]*"\s+src="([^"]+)""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val itemTitleRegex = Regex(
        """class="[^"]*\bworkshopItemTitle\b[^"]*">(.*?)</div>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val itemAuthorAnchorRegex = Regex(
        """class="[^"]*\bworkshopItemAuthorName\b[^"]*">.*?<a\b[^>]*>(.*?)</a>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val itemAuthorRegex = Regex(
        """class="[^"]*\bworkshopItemAuthorName\b[^"]*">(.*?)</div>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val hoverRegex = Regex(
        """SharedFileBindMouseHover\(\s*"sharedfile_(\d+)"\s*,\s*false\s*,\s*(\{.*?\})\s*\);""",
        setOf(RegexOption.DOT_MATCHES_ALL),
    )
    private val ssrRenderContextRegex = Regex(
        """window\.SSR\.renderContext\s*=\s*JSON\.parse\(\s*"(.+?)"\s*\)\s*;?""",
        setOf(RegexOption.DOT_MATCHES_ALL),
    )
    private val pageButtonRegex = Regex("""class=['"][^'"]*\bpagebtn\b""", RegexOption.IGNORE_CASE)

    fun parse(html: String): List<WorkshopItemSummary> = parsePage(html, page = 1).items

    fun parsePage(html: String, page: Int): WorkshopBrowseParseResult {
        val descriptions = hoverRegex.findAll(html)
            .associate { match ->
                val fileId = match.groupValues[1].toULong()
                val description = runCatching {
                    json.parseToJsonElement(match.groupValues[2])
                        .jsonObject["description"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        .orEmpty()
                }.getOrDefault("")
                fileId to SteamHtmlDecoder.stripTagsAndDecode(description)
            }

        val items = parseLegacyItems(html, descriptions)
            .distinctBy(WorkshopItemSummary::publishedFileId)
        val hasNextPage = html.contains("&p=${page + 1}") && pageButtonRegex.containsMatchIn(html)
        val legacyPage = WorkshopBrowseParseResult(
            items = items,
            page = page,
            hasNextPage = hasNextPage,
        )
        if (legacyPage.items.isNotEmpty() || legacyPage.hasNextPage) {
            return legacyPage
        }
        return parseSsrRenderContext(html, legacyPage)
    }

    private fun parseLegacyItems(
        html: String,
        descriptions: Map<ULong, String>,
    ): List<WorkshopItemSummary> {
        val idMatches = dataPublishedFileIdRegex.findAll(html).toList()
        if (idMatches.isEmpty()) return emptyList()
        return idMatches.mapIndexedNotNull { index, match ->
            val blockStart = html.lastIndexOf("<div", startIndex = match.range.first)
                .takeIf { candidate ->
                    candidate >= 0 && workshopItemStartRegex.containsMatchIn(
                        html.substring(candidate, match.range.first.coerceAtMost(html.length))
                    )
                }
                ?: match.range.first
            val nextStart = idMatches.getOrNull(index + 1)?.range?.first ?: html.length
            val blockEnd = nextStart.coerceAtLeast(match.range.last + 1)
            parseLegacyItemBlock(html.substring(blockStart, blockEnd), descriptions)
        }
    }

    private fun parseLegacyItemBlock(
        block: String,
        descriptions: Map<ULong, String>,
    ): WorkshopItemSummary? {
        val publishedFileId = dataPublishedFileIdRegex.find(block)?.groupValues?.getOrNull(1)?.toULongOrNull()
            ?: hrefPublishedFileIdRegex.find(block)?.groupValues?.getOrNull(1)?.toULongOrNull()
            ?: return null
        val appId = appIdRegex.find(block)?.groupValues?.getOrNull(1)?.toUIntOrNull() ?: 646570u
        val title = itemTitleRegex.find(block)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(SteamHtmlDecoder::stripTagsAndDecode)
            .orEmpty()
        if (title.isBlank()) return null
        val authorName = itemAuthorAnchorRegex.find(block)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(SteamHtmlDecoder::stripTagsAndDecode)
            ?: itemAuthorRegex.find(block)
                ?.groupValues
                ?.getOrNull(1)
                ?.let(::stripAuthor)
                .orEmpty()
        val description = descriptions[publishedFileId].orEmpty().ifBlank { authorName.ifBlank { title } }
        return WorkshopItemSummary(
            publishedFileId = publishedFileId,
            appId = appId,
            title = title,
            previewUrl = itemPreviewRegex.find(block)?.groupValues?.getOrNull(1).orEmpty(),
            description = description,
            authorName = authorName,
        )
    }

    private fun parseSsrRenderContext(
        html: String,
        fallbackPage: WorkshopBrowseParseResult,
    ): WorkshopBrowseParseResult {
        val encodedRenderContext = ssrRenderContextRegex.find(html)?.groupValues?.getOrNull(1) ?: return fallbackPage
        val renderContext = decodeJsonStringLiteral(encodedRenderContext) ?: return fallbackPage
        val renderContextObject = runCatching {
            json.parseToJsonElement(renderContext) as? JsonObject
        }.getOrNull() ?: return fallbackPage
        val queryData = runCatching {
            renderContextObject.stringValueOrNull("queryData")
        }.getOrNull() ?: return fallbackPage
        val queryEntries = runCatching {
            json.parseToJsonElement(queryData)
                .asJsonObject()
                ?.arrayValueOrNull("queries")
                .orEmpty()
        }.getOrNull()
            ?: return fallbackPage

        val creatorNames = buildMap {
            queryEntries.forEach { entry ->
                val queryObject = entry.asJsonObject() ?: return@forEach
                val queryKey = queryObject.arrayValueOrNull("queryKey").orEmpty()
                val keyName = queryKey.firstOrNull()?.stringContentOrNull()
                if (keyName != "PlayerLinkDetails") return@forEach
                val steamId = queryKey.getOrNull(1)?.stringContentOrNull() ?: return@forEach
                val personaName = queryObject.objectValue("state")
                    ?.objectValue("data")
                    ?.objectValue("public_data")
                    ?.stringValueOrNull("persona_name")
                    .orEmpty()
                if (personaName.isNotBlank()) put(steamId, personaName)
            }
        }

        val browseData = queryEntries.firstNotNullOfOrNull { entry ->
            entry.asJsonObject()
                ?.objectValue("state")
                ?.objectValue("data")
                ?.takeIf { data ->
                    data.intValueOrNull("current_page") != null &&
                        data.intValueOrNull("total_pages") != null &&
                        data.arrayValueOrNull("results") != null
                }
        } ?: return fallbackPage

        val currentPage = browseData.intValueOrNull("current_page") ?: fallbackPage.page
        val totalPages = browseData.intValueOrNull("total_pages") ?: currentPage
        val items = browseData.arrayValueOrNull("results")
            .orEmpty()
            .mapNotNull { result ->
                val item = result.asJsonObject() ?: return@mapNotNull null
                val publishedFileId = item.ulongValueOrNull("publishedfileid") ?: return@mapNotNull null
                val appId = item.uintValueOrNull("consumer_appid") ?: return@mapNotNull null
                val creatorSteamId = item.stringValueOrNull("creator")
                WorkshopItemSummary(
                    appId = appId,
                    publishedFileId = publishedFileId,
                    previewUrl = item.stringValueOrNull("preview_url").orEmpty(),
                    title = item.stringValueOrNull("title").orEmpty(),
                    authorName = creatorSteamId?.let(creatorNames::get).orEmpty(),
                    description = item.stringValueOrNull("short_description").orEmpty(),
                    fileSizeBytes = item.longValueOrNull("file_size") ?: 0L,
                )
            }

        return WorkshopBrowseParseResult(
            items = items,
            page = currentPage,
            hasNextPage = currentPage < totalPages,
        )
    }

    private fun stripAuthor(value: String): String = SteamHtmlDecoder.stripTagsAndDecode(value)
        .replace(Regex("^by\\s+", RegexOption.IGNORE_CASE), "")
        .replace(Regex("^作者[：:]\\s*", RegexOption.IGNORE_CASE), "")
        .trim()

    private fun decodeJsonStringLiteral(encoded: String): String? =
        runCatching {
            json.parseToJsonElement("\"$encoded\"")
                .jsonPrimitive
                .contentOrNull
        }.getOrNull()
}

internal data class WorkshopBrowseParseResult(
    val items: List<WorkshopItemSummary>,
    val page: Int,
    val hasNextPage: Boolean,
)

private object SteamHtmlDecoder {
    private val numericEntityRegex = Regex("""&#(x?[0-9A-Fa-f]+);""")
    private val htmlTagRegex = Regex("""<[^>]+>""")
    private val whitespaceRegex = Regex("""\s+""")

    fun stripTagsAndDecode(value: String): String = decode(value.replace(htmlTagRegex, " "))

    private fun decode(value: String): String = decodeEntities(value)
        .replace(whitespaceRegex, " ")
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

private fun JsonElement?.asJsonObject(): JsonObject? = this as? JsonObject

private fun JsonElement?.stringContentOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull

private fun JsonObject.objectValue(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.arrayValueOrNull(key: String): JsonArray? = this[key] as? JsonArray

private fun JsonObject.stringValueOrNull(key: String): String? = this[key].stringContentOrNull()

private fun JsonObject.intValueOrNull(key: String): Int? = stringValueOrNull(key)?.toIntOrNull()

private fun JsonObject.longValueOrNull(key: String): Long? = stringValueOrNull(key)?.toLongOrNull()

private fun JsonObject.uintValueOrNull(key: String): UInt? = stringValueOrNull(key)?.toUIntOrNull()

private fun JsonObject.ulongValueOrNull(key: String): ULong? = stringValueOrNull(key)?.toULongOrNull()
