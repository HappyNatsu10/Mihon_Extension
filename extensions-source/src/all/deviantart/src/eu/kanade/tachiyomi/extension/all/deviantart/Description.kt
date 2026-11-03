package eu.kanade.tachiyomi.extension.all.deviantart

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashMap

data class ArtworkDescription(
    val deviationId: String,
    val title: String,
    val author: String,
    val date: String?,
    val text: String,
)

object DescriptionStore {
    private val cache = Collections.synchronizedMap(
        object : LinkedHashMap<String, ArtworkDescription>(50, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArtworkDescription>?): Boolean = size > 50
        },
    )

    fun put(deviationId: String, desc: ArtworkDescription) {
        cache[deviationId] = desc
    }

    fun get(deviationId: String): ArtworkDescription? = cache[deviationId]

    fun clear() {
        cache.clear()
    }
}

private val SHORTCODE_REGEX = Regex(""":(?:icon|dev)[a-zA-Z0-9_-]+:|:thumb\d+:""")
private val jsonParser = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/**
 * Extracts clean text or HTML from Draft.js / TipTap / ProseMirror JSON or AST dumps.
 */
fun extractTextOrHtmlFromDraftOrTiptap(raw: String): String {
    val trimmed = raw.trim()
    if (!trimmed.startsWith("{") && !trimmed.startsWith("{\\")) return raw
    if (trimmed.length < 10) return "" // Discard corrupt stubs like "{\" or "{\\"

    // 1. Check for 'excerpt' in JSON structure (only if no full rich document or blocks AST)
    val excerptRegex = Regex(""""excerpt"\s*:\s*"((?:\\.|[^"\\])*)"""")
    val excerptMatch = excerptRegex.find(trimmed)
    if (excerptMatch != null && !trimmed.contains("\"document\"") && !trimmed.contains("\"blocks\"")) {
        val excerpt = excerptMatch.groupValues[1]
            .replace("\\n", "\n")
            .replace("\\\"", "\"")
            .replace("\\'", "'")
            .replace("\\/", "/")
            .replace("\\\\", "\\")
        if (excerpt.isNotBlank() && !excerpt.startsWith("{")) return excerpt
    }

    try {
        val unescaped = if (trimmed.startsWith("{\\")) {
            trimmed.replace("\\\"", "\"").replace("\\'", "'").replace("\\/", "/").replace("\\\\", "\\")
        } else {
            trimmed
        }
        val element = jsonParser.parseToJsonElement(unescaped)
        val rootObj = element as? JsonObject

        // 2. Parse Draft.js AST (used by DeviantArt for Literature, Captions, Journals, & modern editor posts)
        val blocks = rootObj?.get("blocks")?.jsonArray
        if (blocks != null) {
            val entityMap = rootObj["entityMap"]?.jsonObject
            val sb = StringBuilder()
            for (blockEl in blocks) {
                val block = blockEl as? JsonObject ?: continue
                val text = block["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val blockType = block["type"]?.jsonPrimitive?.contentOrNull ?: "unstyled"
                val styleRanges = block["inlineStyleRanges"]?.jsonArray
                val entityRanges = block["entityRanges"]?.jsonArray

                if (text.isBlank() && entityRanges.isNullOrEmpty()) {
                    sb.append("<p><br/></p>")
                    continue
                }

                // Apply character-level styles
                val charStyles = Array(text.length) { mutableSetOf<String>() }
                styleRanges?.forEach { sr ->
                    val sObj = sr as? JsonObject ?: return@forEach
                    val style = sObj["style"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val offset = sObj["offset"]?.jsonPrimitive?.intOrNull ?: 0
                    val length = sObj["length"]?.jsonPrimitive?.intOrNull ?: 0
                    for (i in offset until minOf(text.length, offset + length)) {
                        charStyles[i].add(style)
                    }
                }

                // Apply entity ranges (LINK)
                val entityAtChar = arrayOfNulls<String>(text.length)
                entityRanges?.forEach { er ->
                    val eObj = er as? JsonObject ?: return@forEach
                    val eKey = eObj["key"]?.jsonPrimitive?.contentOrNull
                        ?: eObj["key"]?.jsonPrimitive?.intOrNull?.toString()
                    val offset = eObj["offset"]?.jsonPrimitive?.intOrNull ?: 0
                    val length = eObj["length"]?.jsonPrimitive?.intOrNull ?: 0
                    if (eKey != null) {
                        for (i in offset until minOf(text.length, offset + length)) {
                            entityAtChar[i] = eKey
                        }
                    }
                }

                val blockHtml = StringBuilder()
                var curIdx = 0
                while (curIdx < text.length) {
                    val styles = charStyles[curIdx]
                    val entKey = entityAtChar[curIdx]
                    var runEnd = curIdx + 1
                    while (runEnd < text.length && charStyles[runEnd] == styles && entityAtChar[runEnd] == entKey) {
                        runEnd++
                    }
                    val chunk = text.substring(curIdx, runEnd)
                        .replace("&", "&amp;")
                        .replace("<", "&lt;")
                        .replace(">", "&gt;")

                    var styledChunk = chunk
                    if ("BOLD" in styles) styledChunk = "<b>$styledChunk</b>"
                    if ("ITALIC" in styles) styledChunk = "<i>$styledChunk</i>"
                    if ("UNDERLINE" in styles) styledChunk = "<u>$styledChunk</u>"

                    if (entKey != null && entityMap != null) {
                        val ent = entityMap[entKey]?.jsonObject
                        val entType = ent?.get("type")?.jsonPrimitive?.contentOrNull.orEmpty()
                        if (entType.equals("LINK", ignoreCase = true)) {
                            styledChunk = "<font color=\"#0570e6\"><u>$styledChunk</u></font>"
                        }
                    }
                    blockHtml.append(styledChunk)
                    curIdx = runEnd
                }

                // Check for inline/block images in entityRanges
                val embeddedImages = mutableListOf<String>()
                entityRanges?.forEach { er ->
                    val eKey = er.jsonObject["key"]?.jsonPrimitive?.contentOrNull
                        ?: er.jsonObject["key"]?.jsonPrimitive?.intOrNull?.toString()
                    if (eKey != null && entityMap != null) {
                        val ent = entityMap[eKey]?.jsonObject
                        val eType = ent?.get("type")?.jsonPrimitive?.contentOrNull.orEmpty()
                        if (eType.equals("IMAGE", ignoreCase = true) || eType.contains("image", ignoreCase = true)) {
                            val src = ent?.get("data")?.jsonObject?.get("src")?.jsonPrimitive?.contentOrNull
                                ?: ent?.get("data")?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
                            if (!src.isNullOrBlank() && !embeddedImages.contains(src)) {
                                embeddedImages.add(src)
                            }
                        }
                    }
                }

                when (blockType) {
                    "header-one" -> sb.append("<h1>").append(blockHtml).append("</h1>")
                    "header-two" -> sb.append("<h2>").append(blockHtml).append("</h2>")
                    "unordered-list-item" -> sb.append("<li>").append(blockHtml).append("</li>")
                    "ordered-list-item" -> sb.append("<li>").append(blockHtml).append("</li>")
                    "blockquote" -> sb.append("<blockquote>").append(blockHtml).append("</blockquote>")
                    else -> sb.append("<p>").append(blockHtml).append("</p>")
                }

                for (imgSrc in embeddedImages) {
                    sb.append("<p><img src=\"$imgSrc\"/></p>")
                }
            }

            val res = sb.toString().trim()
            if (res.isNotBlank()) return res
        }

        // 3. Try parsing TipTap / ProseMirror AST
        val sb = StringBuilder()
        fun walk(node: JsonElement) {
            when (node) {
                is JsonObject -> {
                    val type = node["type"]?.jsonPrimitive?.contentOrNull
                    when (type) {
                        "text" -> {
                            val txt = node["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            val marks = node["marks"]?.jsonArray?.mapNotNull { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull }.orEmpty()
                            var styled = txt.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br/>")
                            if ("bold" in marks) styled = "<b>$styled</b>"
                            if ("italic" in marks) styled = "<i>$styled</i>"
                            if ("underline" in marks) styled = "<u>$styled</u>"
                            if ("link" in marks) styled = "<font color=\"#0570e6\"><u>$styled</u></font>"
                            sb.append(styled)
                        }
                        "image", "media", "deviation", "embed" -> {
                            val attrs = node["attrs"]?.jsonObject
                            val src = attrs?.get("src")?.jsonPrimitive?.contentOrNull
                                ?: attrs?.get("url")?.jsonPrimitive?.contentOrNull
                                ?: attrs?.get("originalUrl")?.jsonPrimitive?.contentOrNull
                                ?: attrs?.get("downloadUrl")?.jsonPrimitive?.contentOrNull
                            if (!src.isNullOrBlank()) {
                                sb.append("<p><img src=\"$src\"/></p>")
                            }
                        }
                        "heading" -> {
                            val level = node["attrs"]?.jsonObject?.get("level")?.jsonPrimitive?.intOrNull ?: 1
                            val tag = if (level == 1) "h1" else "h2"
                            sb.append("<$tag>")
                            node["content"]?.jsonArray?.forEach { walk(it) }
                            sb.append("</$tag>")
                        }
                        "blockquote" -> {
                            sb.append("<blockquote>")
                            node["content"]?.jsonArray?.forEach { walk(it) }
                            sb.append("</blockquote>")
                        }
                        "horizontalRule" -> sb.append("<hr/>")
                        "hardBreak" -> sb.append("<br/>")
                        "paragraph" -> {
                            val startLen = sb.length
                            node["content"]?.jsonArray?.forEach { walk(it) }
                            val endLen = sb.length
                            if (endLen > startLen) {
                                sb.insert(startLen, "<p>")
                                sb.append("</p>")
                            } else {
                                sb.append("<p><br/></p>")
                            }
                        }
                        "bulletList", "orderedList" -> {
                            val tag = if (type == "bulletList") "ul" else "ol"
                            sb.append("<$tag>")
                            node["content"]?.jsonArray?.forEach { walk(it) }
                            sb.append("</$tag>")
                        }
                        "listItem" -> {
                            sb.append("<li>")
                            node["content"]?.jsonArray?.forEach { walk(it) }
                            sb.append("</li>")
                        }
                        else -> {
                            node["content"]?.jsonArray?.forEach { walk(it) }
                                ?: node["document"]?.let { walk(it) }
                        }
                    }
                }
                is JsonArray -> node.forEach { walk(it) }
                else -> {}
            }
        }
        walk(element)
        val res = sb.toString().trim()
        if (res.isNotBlank()) return res
    } catch (_: Exception) {}

    return ""
}

/**
 * Cleans DeviantArt descriptions while preserving original formatting:
 * - Decodes Draft.js / TipTap AST if present
 * - Preserves <p>, <br>, <b>, <strong>, <i>, <em>, <u>, lists, and <img src="...">
 * - Preserves visible text of links, ignoring href
 * - Removes DA shortcodes (:icon...:, :dev...:, :thumb...:)
 * - Decodes HTML entities via Jsoup
 */
fun cleanDescriptionHtml(html: String): String {
    if (html.isBlank()) return ""
    var content = html.trim()
    if (content == "{\\" || content.startsWith("{\\") ||
        (content.startsWith("{") && (content.contains("\"type\"") || content.contains("\"version\"") || content.contains("\"excerpt\"") || content.contains("\"blocks\"") || content.contains("\"document\"")))
    ) {
        content = extractTextOrHtmlFromDraftOrTiptap(content)
        if (content.isBlank() || content == "{\\" || content.startsWith("{\\")) return ""
    }

    val doc = Jsoup.parseBodyFragment(content)
    doc.select("script, style, h2").remove()

    // Normalize image sources from lazy-loading attributes
    for (img in doc.select("img")) {
        val realSrc = img.attr("data-src").takeIf { it.isNotBlank() }
            ?: img.attr("data-orig-src").takeIf { it.isNotBlank() }
            ?: img.attr("src")
        if (!realSrc.isNullOrBlank()) {
            img.attr("src", realSrc)
        }
    }

    // Preserve images inside links and format link text with DeviantArt blue underline
    for (a in doc.select("a")) {
        val imgs = a.select("img")
        for (img in imgs) {
            a.before(img)
        }
        val text = a.text().trim()
        if (text.isNotBlank()) {
            val linkFont = org.jsoup.nodes.Element("font")
                .attr("color", "#0570e6")
                .append("<u>$text</u>")
            a.replaceWith(linkFont)
        } else {
            a.remove()
        }
    }

    // Clean text nodes from DA shortcodes
    for (el in doc.select("*")) {
        for (tn in el.textNodes()) {
            val original = tn.text()
            val cleaned = SHORTCODE_REGEX.replace(original, "")
            if (original != cleaned) {
                tn.text(cleaned)
            }
        }
    }

    return doc.body().html().trim()
}

/**
 * Converts DeviantArt description to clean plain text.
 */
fun descriptionToText(html: String): String {
    val cleanedHtml = cleanDescriptionHtml(html)
    if (cleanedHtml.isBlank()) return ""
    val doc = Jsoup.parseBodyFragment(cleanedHtml)

    for (br in doc.select("br")) {
        br.replaceWith(TextNode("\n"))
    }
    for (p in doc.select("p, div, li")) {
        p.after("\n\n")
    }
    for (img in doc.select("img")) {
        img.remove()
    }

    val text = doc.body().wholeText()
    return text.lines()
        .map { it.trim() }
        .joinToString("\n")
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()
}

/**
 * 8-hex-character SHA-256 hash over (cleaned text + title + artist + renderer version).
 */
fun computeDescriptionHash(text: String, title: String, author: String, version: Int = 1): String {
    val input = "$title\n$author\n$text\nv$version"
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
    return digest.take(4).joinToString("") { "%02x".format(it) }
}
