package eu.kanade.tachiyomi.extension.all.deviantart

import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.io.IOException
import java.time.DateTimeException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class DeviantArtApi(
    private val client: OkHttpClient,
    private val debugLog: Boolean = false,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val profileCache = ConcurrentHashMap<String, Pair<ArtistProfile, Long>>()

    private val rfcDateFormatter by lazy {
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss z", Locale.ENGLISH)
    }

    /**
     * Resolves author name via oEmbed for non-user URLs.
     */
    fun resolveOembed(url: String): String? {
        val oembedUrl = BACKEND_BASE_URL.toHttpUrl().newBuilder()
            .addPathSegment("oembed")
            .addQueryParameter("url", url)
            .build()

        return try {
            val response = HttpGuard.executeWithRetry("oEmbed", debugLog) {
                client.newCall(Request.Builder().url(oembedUrl).build()).execute()
            }
            val dto = json.decodeFromString<OEmbedDto>(response.body.string())
            dto.authorName
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Fetches image URL via oEmbed for a specific deviation.
     */
    fun resolveOembedImage(url: String): String? {
        val oembedUrl = BACKEND_BASE_URL.toHttpUrl().newBuilder()
            .addPathSegment("oembed")
            .addQueryParameter("url", url)
            .build()

        return try {
            val response = HttpGuard.executeWithRetry("oEmbed image", debugLog) {
                client.newCall(Request.Builder().url(oembedUrl).build()).execute()
            }
            val dto = json.decodeFromString<OEmbedDto>(response.body.string())
            dto.url ?: dto.thumbnailUrl
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Fetches artist and gallery metadata (All or specific folder).
     */
    fun fetchArtistProfile(ref: GalleryRef): ArtistProfile {
        val key = "${ref.user.lowercase()}_${ref.folderId ?: "all"}"
        val now = System.currentTimeMillis()
        profileCache[key]?.let { (cached, time) ->
            if (now - time < CACHE_TTL_MS) return cached
        }

        val galleryUrl = if (ref.isAll) {
            "$WEB_BASE_URL/${ref.user}/gallery/all"
        } else {
            "$WEB_BASE_URL/${ref.user}/gallery/${ref.folderId}"
        }

        val response = HttpGuard.executeWithRetry("gallery metadata", debugLog) {
            client.newCall(Request.Builder().url(galleryUrl).build()).execute()
        }

        val html = response.body.string()
        val document = Jsoup.parse(html, galleryUrl)

        val totalDeviations = extractTotalFromHtml(document)
        val avatarUrl = extractAvatarUrl(document)
        val folderName = if (!ref.isAll) extractFolderName(document, ref.folderId!!) else null
        val description = extractDescription(document, totalDeviations, folderName)
        val canonicalName = extractCanonicalUsername(document, ref.user)

        val profile = ArtistProfile(
            username = canonicalName,
            totalDeviations = totalDeviations,
            avatarUrl = avatarUrl,
            description = description,
            folderName = folderName,
        )

        profileCache[key] = Pair(profile, now)
        return profile
    }

    fun getCachedTotal(ref: GalleryRef): Int? {
        val key = "${ref.user.lowercase()}_${ref.folderId ?: "all"}"
        val now = System.currentTimeMillis()
        return profileCache[key]?.let { (cached, time) ->
            if (now - time < CACHE_TTL_MS) cached.totalDeviations else null
        }
    }

    fun extractTotalFromHtml(document: Document): Int {
        for (element in document.select("script[type=application/ld+json]")) {
            val scriptContent = element.data()
            NUMBER_OF_ITEMS_REGEX.find(scriptContent)?.let { match ->
                val count = match.groupValues[1].toIntOrNull()
                if (count != null && count >= 0) return count
            }
        }

        val metaDesc = document.selectFirst("meta[name=description]")?.attr("content").orEmpty()
        WORKS_OF_ART_REGEX.find(metaDesc)?.let { match ->
            val count = match.groupValues[1].replace(",", "").toIntOrNull()
            if (count != null && count >= 0) return count
        }

        val ogDesc = document.selectFirst("meta[property=og:description]")?.attr("content").orEmpty()
        WORKS_OF_ART_REGEX.find(ogDesc)?.let { match ->
            val count = match.groupValues[1].replace(",", "").toIntOrNull()
            if (count != null && count >= 0) return count
        }

        return 0
    }

    private fun extractAvatarUrl(document: Document): String? = document.selectFirst("meta[property=og:image]")?.attr("content")
        ?.takeIf { it.isNotEmpty() }
        ?: document.selectFirst("img[property=contentUrl]")?.absUrl("src")

    private fun extractFolderName(document: Document, fallbackId: String): String {
        val gallery = document.selectFirst("#content")
        val name = gallery?.selectFirst(".DWReDc")?.ownText()
            ?: gallery?.selectFirst("[aria-haspopup=listbox] > div")?.ownText()
        if (!name.isNullOrBlank() && name != "All") return name

        val pageTitle = document.title()
        if (pageTitle.contains(" - ")) {
            val candidate = pageTitle.substringAfter(" - ").substringBefore(" on DeviantArt").trim()
            if (candidate.isNotEmpty()) return candidate
        }

        return "Folder $fallbackId"
    }

    private fun extractDescription(document: Document, total: Int, folderName: String?): String {
        val folderPrefix = if (folderName != null) "Folder: $folderName\n" else ""
        val countInfo = if (total > 0) "Gallery: ~$total artworks\n\n" else "\n"
        val bio = document.selectFirst("meta[property=og:description]")?.attr("content")
            ?: document.selectFirst(".legacy-journal")?.wholeText()
            ?: document.selectFirst("meta[name=description]")?.attr("content")
            ?: ""
        return "$folderPrefix$countInfo$bio".trim()
    }

    private fun extractCanonicalUsername(document: Document, fallback: String): String {
        val ogTitle = document.selectFirst("meta[property=og:title]")?.attr("content")
        if (!ogTitle.isNullOrEmpty()) {
            val user = ogTitle.substringBefore("'s Gallery").trim()
            if (user.isNotEmpty()) return user
        }
        val pageTitle = document.title()
        if (pageTitle.isNotEmpty()) {
            val user = pageTitle.substringBefore("'s Gallery").substringBefore(" ").trim()
            if (user.isNotEmpty()) return user
        }
        return fallback
    }

    /**
     * Fetches a single RSS page of deviations (for crawling/incremental sync).
     * Completeness invariant (FR-14 item 1): Nothing is silently dropped.
     */
    fun fetchRssPage(
        user: String,
        folderId: String?,
        offset: Int,
    ): List<CachedItem> {
        val query = if (folderId == null || folderId.equals("all", true)) {
            "gallery:$user"
        } else {
            "gallery:$user/$folderId"
        }

        val rssUrl = BACKEND_BASE_URL.toHttpUrl().newBuilder()
            .addPathSegment("rss.xml")
            .addQueryParameter("type", "deviation")
            .addQueryParameter("q", query)
            .addQueryParameter("offset", offset.toString())
            .build()

        val response = try {
            HttpGuard.executeWithRetry("gallery crawl", debugLog) {
                client.newCall(Request.Builder().url(rssUrl).build()).execute()
            }
        } catch (_: Exception) {
            return emptyList()
        }

        val xmlDocument = Jsoup.parse(response.body.string(), rssUrl.toString(), Parser.xmlParser())
        val items = xmlDocument.select("item")
        if (items.isEmpty()) return emptyList()

        val result = mutableListOf<CachedItem>()

        for (item in items) {
            val title = item.selectFirst("title")?.text().orEmpty().ifBlank { "Untitled" }
            val link = item.selectFirst("link")?.text().orEmpty()
            if (link.isEmpty()) continue

            val pubDate = item.selectFirst("pubDate")?.text().orEmpty()
            val dateUpload = parseDate(pubDate)

            // Extract deviation ID from link
            val id = DEVIATION_ID_REGEX.find(link)?.groupValues?.get(1)
                ?: link.substringAfterLast("-").substringAfterLast("/")

            val relativePath = link.removePrefix(WEB_BASE_URL)

            result.add(
                CachedItem(
                    id = id,
                    title = title,
                    path = relativePath,
                    publishedMs = dateUpload,
                ),
            )
        }

        return result
    }

    /**
     * Fetches fresh full-size image URL(s) for a single deviation (FR-5, FR-6, FR-14, FR-16).
     * Selects uncropped, maximum resolution images via ImageSelector.
     */
    fun fetchDeviationImages(
        relativeUrl: String,
        qualityMode: QualityMode = QualityMode.BEST_AVAILABLE,
    ): List<String> {
        val fullUrl = if (relativeUrl.startsWith("http")) relativeUrl else "$WEB_BASE_URL$relativeUrl"

        // 1. Try oEmbed lookup
        resolveOembedImage(fullUrl)?.let { return listOf(it) }

        // 2. Scrape deviation webpage
        val response = HttpGuard.executeWithRetry("artwork fetch", debugLog) {
            client.newCall(Request.Builder().url(fullUrl).build()).execute()
        }

        val html = response.body.string()
        val doc = Jsoup.parse(html, fullUrl)

        // Check if literature / text deviation
        val isLiterature = doc.selectFirst(".legacy-journal") != null || doc.selectFirst("[data-hook=art_stage] p") != null
        val candidates = mutableListOf<CandidateRendition>()

        // Check original dimensions
        val origW = doc.selectFirst("meta[property=og:image:width]")?.attr("content")?.toIntOrNull()
            ?: extractLdJsonDimension(doc, "width")
        val origH = doc.selectFirst("meta[property=og:image:height]")?.attr("content")?.toIntOrNull()
            ?: extractLdJsonDimension(doc, "height")
        val originalDim = if (origW != null && origH != null && origW > 0 && origH > 0) {
            ImageDimension(origW, origH)
        } else {
            null
        }

        // Check download button (true original)
        val downloadBtn = doc.selectFirst("a[data-hook=download_button]")
            ?: doc.selectFirst("a[aria-label=Download]")
        val downloadUrl = downloadBtn?.absUrl("href")?.takeIf { it.isNotEmpty() }
        if (downloadUrl != null) {
            candidates.add(
                CandidateRendition(
                    name = "download",
                    width = origW ?: 4000,
                    height = origH ?: 4000,
                    url = downloadUrl,
                    isDownload = true,
                ),
            )
        }

        // Multi-image deviation buttons
        val buttons = doc.selectFirst("[draggable=false]")?.children()
        if (buttons != null && buttons.isNotEmpty()) {
            val urls = buttons.mapNotNull { btn ->
                btn.selectFirst("img")?.absUrl("src")
            }
            if (urls.isNotEmpty()) return urls
        }

        // Single image fullview candidates
        val mainImage = doc.selectFirst("img[fetchpriority=high]")
            ?: doc.selectFirst("img[property=contentUrl]")
            ?: doc.selectFirst("img[data-hook=deviation_link]")

        mainImage?.let { img ->
            val src = img.absUrl("src")
            if (src.isNotEmpty()) {
                val w = img.attr("width").toIntOrNull() ?: origW ?: 1920
                val h = img.attr("height").toIntOrNull() ?: origH ?: 1080
                candidates.add(CandidateRendition(name = "fullview", width = w, height = h, url = src))
            }
        }

        // OpenGraph fallback
        val ogImage = doc.selectFirst("meta[property=og:image]")?.attr("content")
        if (!ogImage.isNullOrEmpty() && candidates.none { it.url == ogImage }) {
            candidates.add(
                CandidateRendition(
                    name = "og:image",
                    width = origW ?: 1200,
                    height = origH ?: 630,
                    url = ogImage,
                ),
            )
        }

        val selected = ImageSelector.pickRendition(
            candidates = candidates,
            original = originalDim,
            isDownloadAllowed = downloadUrl != null,
            qualityMode = qualityMode,
        )

        if (selected != null) {
            return listOf(selected.url)
        }

        if (isLiterature) {
            throw IOException("This deviation is a text/literature post and cannot be viewed as an image")
        }

        throw IOException("This artwork is not available without login")
    }

    private fun extractLdJsonDimension(doc: Document, dimName: String): Int? {
        val regex = Regex(""""$dimName"\s*:\s*(\d+)""")
        for (el in doc.select("script[type=application/ld+json]")) {
            val m = regex.find(el.data())
            if (m != null) return m.groupValues[1].toIntOrNull()
        }
        return null
    }

    private fun parseDate(dateStr: String): Long {
        if (dateStr.isEmpty()) return 0L
        return try {
            ZonedDateTime.parse(dateStr, rfcDateFormatter).toInstant().toEpochMilli()
        } catch (_: DateTimeException) {
            0L
        }
    }

    companion object {
        const val WEB_BASE_URL = "https://www.deviantart.com"
        const val BACKEND_BASE_URL = "https://backend.deviantart.com"
        private const val CACHE_TTL_MS = 10 * 60 * 1000L

        private val NUMBER_OF_ITEMS_REGEX = Regex(""""numberOfItems"\s*:\s*(\d+)""")
        private val WORKS_OF_ART_REGEX = Regex("""(\d[\d,]*)\s+works of art""", RegexOption.IGNORE_CASE)
        private val DEVIATION_ID_REGEX = Regex("""-(\d+)$""")
    }
}
