package eu.kanade.tachiyomi.extension.all.deviantart

data class GalleryRef(val user: String, val folderId: String? = null) {
    val isAll: Boolean get() = folderId == null || folderId.equals("all", ignoreCase = true)
}

sealed interface Resolved {
    data class Gallery(val ref: GalleryRef) : Resolved
    data class NeedsOembed(val url: String) : Resolved
    data object Invalid : Resolved
}

/** The ONLY place that builds a stored SManga.url (FR-15 item 2). */
fun entryUrl(ref: GalleryRef): String = "/${ref.user}/gallery/${ref.folderId ?: "all"}"

/** The ONLY place that reads a stored SManga.url (any legacy shape) or a pasted link. Null = unrecognised. */
fun normalizeEntryUrl(stored: String): GalleryRef? {
    val s = stored.trim()
    val candidate = if (s.startsWith("/")) "https://www.deviantart.com$s" else s
    return (DeviantArtUrl.resolve(candidate) as? Resolved.Gallery)?.ref
}

object DeviantArtUrl {
    private val RESERVED = setOf(
        "tag", "search", "topic", "topics", "watch", "shop", "join", "about", "developers",
        "checkout", "settings", "messages", "notifications", "daily-deviations", "popular",
        "newest", "core-membership", "studio", "users", "today", "deviation", "art", "journal",
    )
    private val OEMBED_FIRST_SEGMENTS = setOf("art", "deviation") // no username in the path
    private val NON_USER_SUBDOMAINS = setOf("www", "m", "backend", "api")
    private val USER = Regex("^[A-Za-z0-9][A-Za-z0-9-]{1,}$")

    // scheme optional; host (deviantart.com / fav.me with any subdomains); optional path; optional ?query / #fragment
    private val LINK = Regex(
        """^(?:https?://)?((?:[a-z0-9-]+\.)*(?:deviantart\.com|fav\.me))(/[^?#\s]*)?(?:[?#].*)?$""",
        RegexOption.IGNORE_CASE,
    )

    /** First whitespace-separated token that mentions deviantart.com or fav.me, punctuation trimmed. */
    private fun extractLink(q: String): String? = q.split(Regex("\\s+"))
        .firstOrNull { it.contains("deviantart.com", true) || it.contains("fav.me", true) }
        ?.trimStart('(', '<', '"', '\'')
        ?.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '>', '"', '\'')

    private fun withScheme(u: String) = if (u.startsWith("http", true)) u else "https://$u"

    fun resolve(raw: String): Resolved {
        val q = raw.trim()
        if (q.isEmpty()) return Resolved.Invalid
        if (q.startsWith("user:", true)) return gallery(q.substring(5).trim(), emptyList())
        if (q.startsWith("@")) return gallery(q.substring(1).trim(), emptyList())

        val link = extractLink(q)
        if (link != null) {
            val m = LINK.find(link) ?: return Resolved.Invalid
            val host = m.groupValues[1].lowercase()
            val rawPath = m.groupValues.getOrElse(2) { "" }
            val segs = rawPath.split('/').filter { it.isNotEmpty() }

            if (host == "fav.me") return Resolved.NeedsOembed(withScheme(link))
            if (host == "deviantart.com" || host == "www.deviantart.com" || host == "m.deviantart.com") {
                val first = segs.firstOrNull() ?: return Resolved.Invalid
                if (first.lowercase() in OEMBED_FIRST_SEGMENTS) return Resolved.NeedsOembed(withScheme(link))
                if (first.lowercase() in RESERVED) return Resolved.Invalid
                return gallery(first, segs.drop(1))
            }
            // legacy {user}.deviantart.com/... : the path is already relative to the user
            val sub = host.removeSuffix(".deviantart.com")
            if (host.endsWith(".deviantart.com") && '.' !in sub && sub !in NON_USER_SUBDOMAINS) {
                return gallery(sub, segs)
            }
            return Resolved.Invalid
        }
        return if (USER.matches(q)) gallery(q, emptyList()) else Resolved.Invalid
    }

    // `rest` = path segments after the username, e.g. ["gallery","12345","slug"], ["art","foo-999"], or [].
    private fun gallery(user: String, rest: List<String>): Resolved {
        if (!USER.matches(user)) return Resolved.Invalid
        val folder = rest.getOrNull(1)?.takeIf {
            rest.getOrNull(0)?.equals("gallery", ignoreCase = true) == true &&
                it.isNotEmpty() &&
                it.all(Char::isDigit)
        }
        // artwork links, /gallery, /gallery/all, /gallery/scraps and /favourites all map to the All gallery
        return Resolved.Gallery(GalleryRef(user, folder))
    }
}
