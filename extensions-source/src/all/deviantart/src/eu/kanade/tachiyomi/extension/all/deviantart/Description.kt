package eu.kanade.tachiyomi.extension.all.deviantart

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

/**
 * Cleans DeviantArt descriptions:
 * - Strips raw JSON / AST tokens (e.g. `{"type":"doc"` or `{\\`)
 * - Converts paragraph, div, and list boundaries to newlines
 * - Preserves visible text of links, ignoring href
 * - Removes DA shortcodes (:icon...:, :dev...:, :thumb...:)
 * - Decodes HTML entities via Jsoup
 * - Collapses consecutive blank lines
 */
fun descriptionToText(html: String): String {
    if (html.isBlank()) return ""
    val trimmed = html.trim()
    // Guard against raw JSON AST dumps from internal React state
    if (trimmed == "{\\" || trimmed.startsWith("{\\") || (trimmed.startsWith("{\"") && trimmed.contains("\"type\":"))) {
        return ""
    }

    val doc = Jsoup.parseBodyFragment(html)
    doc.select("script, style, h2").remove()

    for (br in doc.select("br")) {
        br.replaceWith(TextNode("\n"))
    }
    for (p in doc.select("p, div, li")) {
        p.after("\n\n")
    }
    for (a in doc.select("a")) {
        a.replaceWith(TextNode(a.text()))
    }

    var text = doc.body().wholeText()
    text = SHORTCODE_REGEX.replace(text, "")

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
