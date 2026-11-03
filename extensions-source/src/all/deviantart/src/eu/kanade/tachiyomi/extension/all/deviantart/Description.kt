package eu.kanade.tachiyomi.extension.all.deviantart

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
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

    // 1. Check for 'excerpt' in JSON structure
    val excerptRegex = Regex(""""excerpt"\s*:\s*"((?:\\.|[^"\\])*)"""")
    val excerptMatch = excerptRegex.find(trimmed)
    if (excerptMatch != null) {
        val excerpt = excerptMatch.groupValues[1]
            .replace("\\n", "\n")
            .replace("\\\"", "\"")
            .replace("\\'", "'")
            .replace("\\/", "/")
            .replace("\\\\", "\\")
        if (excerpt.isNotBlank()) return excerpt
    }

    // 2. Try parsing TipTap / ProseMirror AST
    try {
        val unescaped = if (trimmed.startsWith("{\\")) {
            trimmed.replace("\\\"", "\"").replace("\\'", "'").replace("\\/", "/").replace("\\\\", "\\")
        } else {
            trimmed
        }
        val element = jsonParser.parseToJsonElement(unescaped)
        val sb = StringBuilder()
        fun walk(node: JsonElement) {
            when (node) {
                is JsonObject -> {
                    val type = node["type"]?.jsonPrimitive?.contentOrNull
                    when (type) {
                        "text" -> {
                            val txt = node["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            val marks = node["marks"]?.jsonArray?.mapNotNull { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull }.orEmpty()
                            var styled = txt
                            if ("bold" in marks) styled = "<b>$styled</b>"
                            if ("italic" in marks) styled = "<i>$styled</i>"
                            if ("underline" in marks) styled = "<u>$styled</u>"
                            sb.append(styled)
                        }
                        "image" -> {
                            val src = node["attrs"]?.jsonObject?.get("src")?.jsonPrimitive?.contentOrNull
                            if (!src.isNullOrBlank()) {
                                sb.append("<p><img src=\"$src\"/></p>")
                            }
                        }
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
        (content.startsWith("{") && (content.contains("\"type\"") || content.contains("\"version\"") || content.contains("\"excerpt\"")))
    ) {
        content = extractTextOrHtmlFromDraftOrTiptap(content)
        if (content.isBlank()) return ""
    }

    val doc = Jsoup.parseBodyFragment(content)
    doc.select("script, style, h2").remove()

    for (a in doc.select("a")) {
        a.replaceWith(TextNode(a.text()))
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
