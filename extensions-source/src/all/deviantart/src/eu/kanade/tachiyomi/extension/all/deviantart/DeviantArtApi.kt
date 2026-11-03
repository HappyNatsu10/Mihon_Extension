package eu.kanade.tachiyomi.extension.all.deviantart

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.io.IOException
import java.time.DateTimeException
import java.time.OffsetDateTime
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
    fun resolveOembed(url: String): String? = fetchOembedDto(url)?.authorName

    /**
     * Fetches image URL via oEmbed for a specific deviation.
     */
    fun resolveOembedImage(url: String): String? {
        val dto = fetchOembedDto(url) ?: return null
        return dto.url ?: dto.thumbnailUrl
    }

    /**
     * Fetches complete oEmbed DTO.
     */
    fun fetchOembedDto(url: String): OEmbedDto? {
        val oembedUrl = BACKEND_BASE_URL.toHttpUrl().newBuilder()
            .addPathSegment("oembed")
            .addQueryParameter("url", url)
            .build()

        return try {
            val response = HttpGuard.executeWithRetry("oEmbed", debugLog) {
                client.newCall(Request.Builder().url(oembedUrl).build()).execute()
            }
            json.decodeFromString<OEmbedDto>(response.body.string())
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Validates whether a URL points to actual artwork media, filtering out DeviantArt
     * site icons, placeholders, avatars, and internal download redirects.
     */
    fun isArtworkUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val u = url.lowercase()
        if (u.contains("st.deviantart.net")) return false
        if (u.contains("touch-icon") || u.contains("favicon") || u.contains("da_logo") || u.contains("deviantart-logo")) return false
        if (u.contains("default_avatar") || u.contains("placeholder") || u.contains("/avatars/")) return false
        if (u.contains("/download/")) return false
        return u.contains("wixmp.com") || (u.contains("deviantart.net") && !u.contains("st.deviantart.net"))
    }

    /**
     * Fetches artwork metadata for standalone artwork library entries and search results.
     */
    fun fetchArtworkDetails(relativeOrFullUrl: String): ArtworkDetails {
        val fullUrl = if (relativeOrFullUrl.startsWith("http")) relativeOrFullUrl else "$WEB_BASE_URL$relativeOrFullUrl"
        val path = fullUrl.removePrefix(WEB_BASE_URL)
        val defaultAuthor = path.removePrefix("/").substringBefore("/art/").substringBefore("/")
        val defaultTitle = path.substringAfter("/art/").substringBeforeLast("-").replace("-", " ").ifBlank { "Artwork" }

        val oembed = fetchOembedDto(fullUrl)
        if (oembed != null) {
            val author = oembed.authorName?.takeIf { it.isNotBlank() } ?: defaultAuthor
            val title = oembed.title?.takeIf { it.isNotBlank() } ?: defaultTitle
            val thumb = (oembed.thumbnailUrl ?: oembed.url)?.takeIf { isArtworkUrl(it) }
            return ArtworkDetails(
                title = title,
                author = author,
                description = null,
                thumbnailUrl = thumb,
            )
        }

        return try {
            val response = HttpGuard.executeWithRetry("artwork metadata", debugLog) {
                client.newCall(Request.Builder().url(fullUrl).build()).execute()
            }
            val doc = Jsoup.parse(response.body.string(), fullUrl)
            val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" by ")?.trim()
                ?: defaultTitle
            val author = doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringAfter(" by ")?.substringBefore(" on DeviantArt")?.trim()
                ?: defaultAuthor
            val desc = doc.selectFirst("meta[property=og:description]")?.attr("content")
                ?: doc.selectFirst(".legacy-journal")?.wholeText()
            val thumb = doc.selectFirst("img[fetchpriority=high]")?.absUrl("src")?.takeIf { isArtworkUrl(it) }
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { isArtworkUrl(it) }

            ArtworkDetails(
                title = title,
                author = author,
                description = desc,
                thumbnailUrl = thumb,
            )
        } catch (_: Exception) {
            ArtworkDetails(
                title = defaultTitle,
                author = defaultAuthor,
                description = null,
                thumbnailUrl = null,
            )
        }
    }

    /**
     * Fetches artist and gallery metadata (All or specific folder, or favourites).
     */
    fun fetchArtistProfile(ref: GalleryRef): ArtistProfile {
        val section = if (ref.isFavourites) "favourites" else "gallery"
        val key = "${ref.user.lowercase()}_${if (ref.isFavourites) "fav_" else ""}${ref.folderId ?: "all"}"
        val now = System.currentTimeMillis()
        profileCache[key]?.let { (cached, time) ->
            if (now - time < CACHE_TTL_MS) return cached
        }

        val galleryUrl = if (ref.isAll) {
            "$WEB_BASE_URL/${ref.user}/$section/all"
        } else {
            "$WEB_BASE_URL/${ref.user}/$section/${ref.folderId}"
        }

        val response = HttpGuard.executeWithRetry("$section metadata", debugLog) {
            client.newCall(Request.Builder().url(galleryUrl).build()).execute()
        }

        val html = response.body.string()
        updateCsrfFromHtml(html)
        val document = Jsoup.parse(html, galleryUrl)

        val totalDeviations = extractTotalFromHtml(document)
        val avatarUrl = extractAvatarUrl(document)
        val folderName = if (!ref.isAll) extractFolderName(document, ref.folderId!!) else null
        val description = extractDescription(document, totalDeviations, folderName, ref.isFavourites)
        val canonicalName = extractCanonicalUsername(document, ref.user)

        val ogImage = document.selectFirst("meta[property=og:image]")?.attr("content")
        val coverCandidate = if (!ogImage.isNullOrBlank() && isArtworkUrl(ogImage)) {
            ImageSelector.maximizeRendition(ogImage)
        } else {
            null
        }

        val coverUrl = coverCandidate ?: run {
            try {
                val firstPage = fetchGalleryJson(ref.user, ref.folderId, ref.isFavourites, offset = 0, limit = 1)
                firstPage.items.firstOrNull()?.let { firstItem ->
                    val images = fetchDeviationImages(firstItem.path, QualityMode.STANDARD)
                    images.firstOrNull()?.let { ImageSelector.maximizeRendition(it) }
                }
            } catch (_: Exception) {
                null
            }
        }

        val profile = ArtistProfile(
            username = canonicalName,
            totalDeviations = totalDeviations,
            avatarUrl = avatarUrl,
            description = description,
            folderName = folderName,
            coverUrl = coverUrl,
        )

        profileCache[key] = Pair(profile, now)
        return profile
    }

    fun getCachedTotal(ref: GalleryRef): Int? {
        val key = "${ref.user.lowercase()}_${if (ref.isFavourites) "fav_" else ""}${ref.folderId ?: "all"}"
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

    private fun extractDescription(
        document: Document,
        total: Int,
        folderName: String?,
        isFavourites: Boolean = false,
    ): String {
        val folderPrefix = if (folderName != null) "Folder: $folderName\n" else ""
        val sectionName = if (isFavourites) "Favourites" else "Gallery"
        val countInfo = if (total > 0) "$sectionName: ~$total artworks\n\n" else "\n"
        val bio = document.selectFirst("meta[property=og:description]")?.attr("content")
            ?: document.selectFirst(".legacy-journal")?.wholeText()
            ?: document.selectFirst("meta[name=description]")?.attr("content")
            ?: ""
        return "$folderPrefix$countInfo$bio".trim()
    }

    private fun extractCanonicalUsername(document: Document, fallback: String): String {
        val ogTitle = document.selectFirst("meta[property=og:title]")?.attr("content")
        if (!ogTitle.isNullOrEmpty()) {
            val user = ogTitle.substringBefore("'s Gallery").substringBefore("'s Favourites").substringBefore("'s Favorites").trim()
            if (user.isNotEmpty()) return user
        }
        val pageTitle = document.title()
        if (pageTitle.isNotEmpty()) {
            val user = pageTitle.substringBefore("'s Gallery").substringBefore("'s Favourites").substringBefore("'s Favorites").substringBefore(" ").trim()
            if (user.isNotEmpty()) return user
        }
        return fallback
    }

    /**
     * Fetches a single RSS page of deviations (for crawling/incremental sync).
     * Completeness invariant (FR-14 item 1): Nothing is silently dropped.
     */
    fun fetchRssPageResult(
        user: String,
        folderId: String?,
        isFavourites: Boolean = false,
        offset: Int = 0,
    ): RssPageResult {
        val query = if (isFavourites) {
            if (folderId == null || folderId.equals("all", true)) {
                "favby:$user"
            } else {
                "favby:$user/$folderId"
            }
        } else {
            if (folderId == null || folderId.equals("all", true)) {
                "gallery:$user"
            } else {
                "gallery:$user/$folderId"
            }
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
            return RssPageResult(emptyList(), null, false)
        }

        val xmlDocument = Jsoup.parse(response.body.string(), rssUrl.toString(), Parser.xmlParser())
        val items = xmlDocument.select("item")
        val nextLink = xmlDocument.getElementsByTag("atom:link")
            .firstOrNull { it.attr("rel") == "next" }
            ?.attr("href")
            ?.takeIf { it.isNotBlank() }
            ?: xmlDocument.selectFirst("[rel=next]")?.attr("href")?.takeIf { it.isNotBlank() }
        val hasNext = nextLink != null || items.size >= 60

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

        return RssPageResult(result, nextLink, hasNext)
    }

    fun fetchRssPage(
        user: String,
        folderId: String?,
        isFavourites: Boolean = false,
        offset: Int = 0,
    ): List<CachedItem> = fetchRssPageResult(user, folderId, isFavourites, offset).items

    fun fetchRssPage(
        user: String,
        folderId: String?,
        offset: Int,
    ): List<CachedItem> = fetchRssPageResult(user, folderId, false, offset).items

    private var csrf: String? = null

    fun updateCsrfFromHtml(html: String) {
        val t = Regex("""__CSRF_TOKEN__\s*=\s*'([^']+)'""").find(html)?.groupValues?.get(1)
            ?: Regex(""""csrf_token"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
        if (t != null && t.isNotBlank()) {
            csrf = t
        }
    }

    fun csrfToken(force: Boolean = false): String {
        csrf?.takeIf { !force }?.let { return it }
        val html = HttpGuard.executeWithRetry("csrf", debugLog) {
            client.newCall(Request.Builder().url("$WEB_BASE_URL/").build()).execute()
        }.use { it.body.string() }
        val t = Regex("""__CSRF_TOKEN__\s*=\s*'([^']+)'""").find(html)?.groupValues?.get(1)
            ?: throw IOException("DeviantArt: could not read the CSRF token. Open the source in WebView and retry.")
        csrf = t
        return t
    }

    /** Returns RssPageResult type so GalleryCache keeps working. */
    fun fetchGalleryJson(
        user: String,
        folderId: String?,
        isFavourites: Boolean = false,
        offset: Int = 0,
        limit: Int = 60,
    ): RssPageResult {
        fun call(force: Boolean): String {
            val type = if (isFavourites) "collection" else "gallery"
            val url = "$WEB_BASE_URL/_puppy/dashared/gallection/contents".toHttpUrl().newBuilder()
                .addQueryParameter("username", user.lowercase())
                .addQueryParameter("type", type)
                .addQueryParameter("offset", offset.toString())
                .addQueryParameter("limit", limit.toString())
                .apply {
                    if (folderId == null || folderId.equals("all", true)) {
                        addQueryParameter("all_folder", "true")
                    } else {
                        addQueryParameter("folderid", folderId)
                    }
                }
                .addQueryParameter("csrf_token", csrfToken(force))
                .build()
            return HttpGuard.executeWithRetry("gallery list", debugLog) {
                client.newCall(Request.Builder().url(url).header("Referer", "$WEB_BASE_URL/").build()).execute()
            }.use { it.body.string() }
        }

        var body = call(force = false)
        if (body.contains("\"csrf\"") && body.contains("invalid")) {
            body = call(force = true) // stale token: refresh once
        }

        val root = json.parseToJsonElement(body).jsonObject
        val error = root["error"]?.jsonPrimitive?.contentOrNull ?: root["errorDescription"]?.jsonPrimitive?.contentOrNull
        if (error != null && !error.equals("success", ignoreCase = true)) {
            throw IOException("DeviantArt gallery error: $error")
        }

        val raw = root["results"]?.jsonArray.orEmpty()
        val items = raw.mapNotNull { el ->
            val obj = el.jsonObject
            val dev = (obj["deviation"] as? JsonObject) ?: obj
            val id = dev["deviationId"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val link = dev["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val publishedTime = dev["publishedTime"]?.jsonPrimitive?.contentOrNull
            val publishedMs = if (publishedTime != null) {
                try {
                    OffsetDateTime.parse(publishedTime).toInstant().toEpochMilli()
                } catch (_: Exception) {
                    0L
                }
            } else {
                0L
            }
            CachedItem(
                id = id.toString(),
                title = dev["title"]?.jsonPrimitive?.contentOrNull ?: "Untitled",
                path = link.removePrefix(WEB_BASE_URL),
                publishedMs = publishedMs,
            )
        }
        val next = root["nextOffset"]?.jsonPrimitive?.intOrNull
        val hasMore = root["hasMore"]?.jsonPrimitive?.booleanOrNull ?: false
        return RssPageResult(items, next?.let { "$WEB_BASE_URL/?offset=$it" }, hasMore)
    }

    private val qualityRegex = Regex(",q_\\d+")

    /** Full-view URL exactly as DeviantArt composes it; only the quality value is raised. Never edit w_/h_. */
    fun fullviewUrl(m: Media, raiseQuality: Boolean = true): String? {
        val base = m.baseUri ?: return null
        val fv = m.types.firstOrNull { it.t == "fullview" && !it.c.isNullOrBlank() }
            ?: m.types.firstOrNull { it.t == "preview" && !it.c.isNullOrBlank() }
            ?: m.types.firstOrNull { !it.c.isNullOrBlank() }
        val path = fv?.c?.replace("<prettyName>", m.prettyName.orEmpty())
        var url = if (path != null) base + path else base
        if (raiseQuality) url = qualityRegex.replace(url, ",q_100")
        val token = m.token.firstOrNull()
        return if (token != null) "$url?token=$token" else url
    }

    /** Original file; only for isDownloadable artworks, usually needs login cookies. */
    fun originalUrl(artworkUrl: String, html: String? = null): String? {
        val pageHtml = html ?: try {
            HttpGuard.executeWithRetry("artwork page", debugLog) {
                client.newCall(Request.Builder().url(artworkUrl).build()).execute()
            }.use { it.body.string() }
        } catch (_: Exception) {
            return null
        }
        val marker = "https://www.deviantart.com/download/"
        val start = pageHtml.indexOf(marker).takeIf { it >= 0 } ?: return null
        val end = pageHtml.indexOf('"', start).takeIf { it > start } ?: return null
        return Parser.unescapeEntities(pageHtml.substring(start, end), false)
    }

    fun parseMedia(element: JsonElement?): Media? {
        val rootObj = element as? JsonObject ?: return null
        val obj = (rootObj["media"] as? JsonObject) ?: rootObj
        val baseUri = obj["baseUri"]?.jsonPrimitive?.contentOrNull ?: return null
        val prettyName = obj["prettyName"]?.jsonPrimitive?.contentOrNull
        val token = obj["token"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        val types = obj["types"]?.jsonArray?.mapNotNull { typeEl ->
            val tObj = typeEl as? JsonObject ?: return@mapNotNull null
            val t = tObj["t"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val c = tObj["c"]?.jsonPrimitive?.contentOrNull
            val h = tObj["h"]?.jsonPrimitive?.intOrNull
            val w = tObj["w"]?.jsonPrimitive?.intOrNull
            MediaType(t = t, c = c, h = h, w = w)
        }.orEmpty()
        return Media(baseUri = baseUri, prettyName = prettyName, token = token, types = types)
    }

    fun fetchAdditionalMedia(devId: String): List<Media> {
        val csrf = try {
            csrfToken()
        } catch (_: Exception) {
            return emptyList()
        }
        val url = "$WEB_BASE_URL/_puppy/dadeviation/media".toHttpUrl().newBuilder()
            .addQueryParameter("deviationid", devId)
            .addQueryParameter("csrf_token", csrf)
            .build()
        val body = try {
            HttpGuard.executeWithRetry("additional media", debugLog) {
                client.newCall(Request.Builder().url(url).build()).execute()
            }.use { it.body.string() }
        } catch (_: Exception) {
            return emptyList()
        }

        return try {
            val root = json.parseToJsonElement(body).jsonObject
            val list = root["additionalMedia"]?.jsonArray
                ?: root["results"]?.jsonArray
                ?: root["media"]?.jsonArray
            list?.mapNotNull { parseMedia(it) }.orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun extractDeviationMediaFromHtml(html: String, devId: String): Pair<Media?, List<Media>> {
        var primary: Media? = null
        val additional = mutableListOf<Media>()

        // 1. Extract from window.__INITIAL_STATE__
        try {
            val marker = "window.__INITIAL_STATE__ = JSON.parse(\""
            val start = html.indexOf(marker)
            if (start != -1) {
                val startIdx = start + marker.length
                var endIdx = html.indexOf("\");\n", startIdx)
                if (endIdx == -1) endIdx = html.indexOf("\");\r\n", startIdx)
                if (endIdx == -1) endIdx = html.indexOf("\");window.__", startIdx)
                if (endIdx == -1) endIdx = html.indexOf("\");", startIdx).takeIf { it > startIdx } ?: html.length

                val escaped = html.substring(startIdx, endIdx).replace("\\'", "'")
                val rawJson = json.decodeFromString<String>("\"$escaped\"")
                val root = json.parseToJsonElement(rawJson).jsonObject
                val devObj = root["@@entities"]?.jsonObject?.get("deviation")?.jsonObject?.get(devId)?.jsonObject
                if (devObj != null) {
                    primary = parseMedia(devObj["media"])
                    val extra = devObj["additionalMedia"]?.jsonArray?.mapNotNull { parseMedia(it) }.orEmpty()
                    additional.addAll(extra)
                    if (primary != null) {
                        return Pair(primary, additional)
                    }
                }
            }
        } catch (_: Exception) {}

        // 2. Direct substring extraction fallback (resilient against state parser crashes)
        try {
            val devToken = "\"$devId\":"
            val devTokenEsc = "\\\"$devId\\\":"
            var devPos = html.indexOf(devTokenEsc)
            if (devPos == -1) devPos = html.indexOf(devToken)

            if (devPos != -1) {
                if (primary == null) {
                    val mediaMarker = "\\\"media\\\":{"
                    val mediaMarkerUnesc = "\"media\":{"
                    var mPos = html.indexOf(mediaMarker, devPos)
                    var prefixLen = mediaMarker.length
                    if (mPos == -1 || mPos > devPos + 15000) {
                        mPos = html.indexOf(mediaMarkerUnesc, devPos)
                        prefixLen = mediaMarkerUnesc.length
                    }
                    if (mPos != -1 && mPos < devPos + 15000) {
                        var open = 1
                        var endM = -1
                        for (i in (mPos + prefixLen) until minOf(html.length, mPos + 10000)) {
                            if (html[i] == '{') {
                                open++
                            } else if (html[i] == '}') {
                                open--
                                if (open == 0) {
                                    endM = i + 1
                                    break
                                }
                            }
                        }
                        if (endM != -1) {
                            val rawM = html.substring(mPos + prefixLen - 1, endM)
                            val unescaped = rawM.replace("\\\"", "\"").replace("\\'", "'").replace("\\/", "/").replace("\\\\", "\\")
                            val el = json.parseToJsonElement(unescaped)
                            primary = parseMedia(el)
                        }
                    }
                }

                val addMarker = "\\\"additionalMedia\\\":["
                val addMarkerUnesc = "\"additionalMedia\":["
                var aPos = html.indexOf(addMarker, devPos)
                var aPrefixLen = addMarker.length
                if (aPos == -1 || aPos > devPos + 25000) {
                    aPos = html.indexOf(addMarkerUnesc, devPos)
                    aPrefixLen = addMarkerUnesc.length
                }
                if (aPos != -1 && aPos < devPos + 25000) {
                    var open = 1
                    var endA = -1
                    for (i in (aPos + aPrefixLen) until minOf(html.length, aPos + 30000)) {
                        if (html[i] == '[') {
                            open++
                        } else if (html[i] == ']') {
                            open--
                            if (open == 0) {
                                endA = i + 1
                                break
                            }
                        }
                    }
                    if (endA != -1) {
                        val rawA = html.substring(aPos + aPrefixLen - 1, endA)
                        val unescaped = rawA.replace("\\\"", "\"").replace("\\'", "'").replace("\\/", "/").replace("\\\\", "\\")
                        val arr = json.parseToJsonElement(unescaped).jsonArray
                        val parsed = arr.mapNotNull { parseMedia(it) }
                        additional.addAll(parsed)
                    }
                }
            }
        } catch (_: Exception) {}

        return Pair(primary, additional)
    }

    fun fetchArtworkDescription(relativeOrFullUrl: String): ArtworkDescription? {
        val fullUrl = if (relativeOrFullUrl.startsWith("http")) relativeOrFullUrl else "$WEB_BASE_URL$relativeOrFullUrl"
        val devId = DEVIATION_ID_REGEX.find(relativeOrFullUrl)?.groupValues?.get(1)
            ?: relativeOrFullUrl.substringAfterLast("-").substringAfterLast("/")

        return try {
            val response = HttpGuard.executeWithRetry("artwork description", debugLog) {
                client.newCall(Request.Builder().url(fullUrl).build()).execute()
            }
            val html = response.body.string()
            val doc = Jsoup.parse(html, fullUrl)
            extractAndStoreDescription(devId, doc, html, relativeOrFullUrl)
        } catch (_: Exception) {
            null
        }
    }

    fun fetchArtworkDescriptionById(devId: String): ArtworkDescription? {
        DescriptionStore.get(devId)?.let { return it }
        val url = "$WEB_BASE_URL/deviation/$devId"
        return fetchArtworkDescription(url)
    }

    fun extractAndStoreDescription(
        devId: String,
        doc: Document,
        html: String,
        relativeUrl: String,
    ): ArtworkDescription? {
        updateCsrfFromHtml(html)
        val defaultAuthor = relativeUrl.removePrefix("/").substringBefore("/art/").substringBefore("/")
        val defaultTitle = relativeUrl.substringAfter("/art/").substringBeforeLast("-").replace("-", " ").ifBlank { "Artwork" }

        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" by ")?.trim()
            ?: doc.title().substringBefore(" by ").trim().ifBlank { defaultTitle }

        val author = doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringAfter(" by ")?.substringBefore(" on DeviantArt")?.trim()
            ?: defaultAuthor

        val date = doc.selectFirst("time")?.text()
            ?: doc.selectFirst("meta[property=article:published_time]")?.attr("content")?.let {
                try {
                    val zdt = OffsetDateTime.parse(it)
                    DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).format(zdt)
                } catch (_: Exception) {
                    null
                }
            }

        // Description extraction priority:
        // 1. Try extracting rich description or literature body from window.__INITIAL_STATE__
        var rawText = ""
        try {
            val marker = "window.__INITIAL_STATE__ = JSON.parse(\""
            val start = html.indexOf(marker)
            if (start != -1) {
                val startIdx = start + marker.length
                var endIdx = html.indexOf("\");\n", startIdx)
                if (endIdx == -1) endIdx = html.indexOf("\");\r\n", startIdx)
                if (endIdx == -1) endIdx = html.indexOf("\");window.__", startIdx)
                if (endIdx == -1) endIdx = html.indexOf("\");", startIdx).takeIf { it > startIdx } ?: html.length
                val escaped = html.substring(startIdx, endIdx).replace("\\'", "'")
                val rawJson = json.decodeFromString<String>("\"$escaped\"")
                val root = json.parseToJsonElement(rawJson).jsonObject
                val devExt = root["@@entities"]?.jsonObject?.get("deviationExtended")?.jsonObject?.get(devId)?.jsonObject
                    ?: root["@@entities"]?.jsonObject?.get("deviation")?.jsonObject?.get(devId)?.jsonObject?.get("extended")?.jsonObject
                if (devExt != null) {
                    val markup = devExt["body"]?.jsonObject?.get("markup")?.jsonPrimitive?.contentOrNull
                        ?: devExt["description"]?.jsonObject?.get("markup")?.jsonPrimitive?.contentOrNull
                    val htmlContent = devExt["bodyText"]?.jsonObject?.get("html")?.jsonPrimitive?.contentOrNull
                        ?: devExt["descriptionText"]?.jsonObject?.get("html")?.jsonPrimitive?.contentOrNull
                    val candidate = markup ?: htmlContent
                    if (!candidate.isNullOrBlank()) {
                        rawText = cleanDescriptionHtml(candidate)
                    }
                }
            }
        } catch (_: Exception) {}

        // 2. Direct regex search in HTML for description or body markup
        if (rawText.isBlank()) {
            try {
                val bodyMarkupRegex = Regex(""""body"\s*:\s*\{\s*"markup"\s*:\s*"((?:\\.|[^"\\])*)"""")
                val descMarkupRegex = Regex(""""description"\s*:\s*\{\s*"markup"\s*:\s*"((?:\\.|[^"\\])*)"""")
                val match = bodyMarkupRegex.find(html) ?: descMarkupRegex.find(html)
                if (match != null) {
                    val unescaped = match.groupValues[1]
                        .replace("\\\"", "\"")
                        .replace("\\'", "'")
                        .replace("\\/", "/")
                        .replace("\\\\", "\\")
                    rawText = cleanDescriptionHtml(unescaped)
                }
            } catch (_: Exception) {}
        }

        // 3. Rendered DOM inside #description
        if (rawText.isBlank()) {
            val descEl = doc.selectFirst("#description [data-editor-viewer]")
                ?: doc.selectFirst("#description")
                ?: doc.selectFirst(".legacy-journal")
                ?: doc.selectFirst("[data-hook=art_stage] + div")
            val domHtml = descEl?.html().orEmpty()
            if (domHtml.isNotBlank() && domHtml != "{\\" && !domHtml.startsWith("{\\")) {
                rawText = cleanDescriptionHtml(domHtml)
            }
        }

        // 4. Fallback to meta tags if DOM had no description
        if (rawText.isBlank()) {
            val metaDesc = doc.selectFirst("meta[property=og:description]")?.attr("content")
                ?: doc.selectFirst("meta[name=description]")?.attr("content")
            if (!metaDesc.isNullOrBlank() && metaDesc != "{\\" && !metaDesc.startsWith("{\\")) {
                var cleaned = metaDesc
                val onDaIdx = cleaned.indexOf(" on DeviantArt.")
                if (onDaIdx != -1) {
                    cleaned = cleaned.substring(onDaIdx + " on DeviantArt.".length).trim()
                }
                val pubIdx = cleaned.indexOf(" · Published:")
                if (pubIdx != -1) {
                    cleaned = cleaned.substring(0, pubIdx).trim()
                }
                if (cleaned != "{\\" && !cleaned.startsWith("{\\")) {
                    rawText = cleanDescriptionHtml(cleaned)
                }
            }
        }

        if (rawText.isBlank()) return null

        val desc = ArtworkDescription(
            deviationId = devId,
            title = title,
            author = author,
            date = date,
            text = rawText,
        )
        DescriptionStore.put(devId, desc)
        return desc
    }

    /**
     * Fetches fresh full-size image URL(s) for a single deviation (FR-5, FR-6, FR-14, FR-16).
     * Selects uncropped, maximum resolution images, supports video deviations, and preserves multi-page deviations.
     */
    fun fetchDeviationImages(
        relativeUrl: String,
        qualityMode: QualityMode = QualityMode.BEST_AVAILABLE,
    ): List<String> {
        val fullUrl = if (relativeUrl.startsWith("http")) relativeUrl else "$WEB_BASE_URL$relativeUrl"
        val devId = DEVIATION_ID_REGEX.find(relativeUrl)?.groupValues?.get(1)
            ?: relativeUrl.substringAfterLast("-").substringAfterLast("/")

        // 1. Scrape deviation webpage
        val response = try {
            HttpGuard.executeWithRetry("artwork fetch", debugLog) {
                client.newCall(Request.Builder().url(fullUrl).build()).execute()
            }
        } catch (e: Exception) {
            resolveOembedImage(fullUrl)?.takeIf { isArtworkUrl(it) }?.let { return listOf(it) }
            throw e
        }

        val html = response.body.string()
        val doc = Jsoup.parse(html, fullUrl)

        // 2. Extract and store description for DescriptionStore
        extractAndStoreDescription(devId, doc, html, relativeUrl)

        // 3. Check for video deviation (Wix MP4 qualities or HTML5 video)
        val videoUrls = extractVideoUrls(html, devId)
        val bestVideo = pickBestVideoUrl(videoUrls, qualityMode)
        if (bestVideo != null) {
            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { isArtworkUrl(it) }
                ?: doc.selectFirst("img[fetchpriority=high]")?.absUrl("src")?.takeIf { isArtworkUrl(it) }
            val maximizedPoster = poster?.let { ImageSelector.maximizeRendition(it) }
            return if (maximizedPoster != null) {
                listOf(maximizedPoster, bestVideo)
            } else {
                listOf(bestVideo)
            }
        }

        // Check if literature / text deviation
        val isLiterature = doc.selectFirst(".legacy-journal") != null || doc.selectFirst("[data-hook=art_stage] p") != null

        // 4. Extract primary media & additionalMedia for multi-page deviations
        // NO DRAGGABLE / CAROUSEL SCRAPING! This guarantees no other artworks appear as pages!
        val (primaryMedia, htmlAdditional) = extractDeviationMediaFromHtml(html, devId)
        val additionalMedia = if (htmlAdditional.isNotEmpty()) {
            htmlAdditional
        } else {
            fetchAdditionalMedia(devId)
        }

        val raiseQuality = (qualityMode == QualityMode.BEST_AVAILABLE)
        val resultPages = mutableListOf<String>()

        // Check original download URL if BEST_AVAILABLE
        val isDownloadable = doc.selectFirst("a[data-hook=download_button]") != null ||
            doc.selectFirst("a[aria-label=Download]") != null ||
            doc.selectFirst("a[data-hook=action_download]") != null ||
            html.contains("\"isDownloadable\":true")

        val origUrl = if (isDownloadable && qualityMode == QualityMode.BEST_AVAILABLE) {
            originalUrl(fullUrl, html)
        } else {
            null
        }

        if (origUrl != null) {
            resultPages.add(origUrl)
        } else if (primaryMedia != null) {
            fullviewUrl(primaryMedia, raiseQuality)?.let { resultPages.add(it) }
        }

        // Add additional genuine pages from stack in order
        for (extra in additionalMedia) {
            fullviewUrl(extra, raiseQuality)?.let { resultPages.add(it) }
        }

        if (resultPages.isNotEmpty()) {
            return resultPages
        }

        // 5. Fallback candidate collection if media JSON was missing
        val candidates = mutableListOf<CandidateRendition>()
        val origW = doc.selectFirst("meta[property=og:image:width]")?.attr("content")?.toIntOrNull()
            ?: extractLdJsonDimension(doc, "width")
        val origH = doc.selectFirst("meta[property=og:image:height]")?.attr("content")?.toIntOrNull()
            ?: extractLdJsonDimension(doc, "height")
        val originalDim = if (origW != null && origH != null && origW > 0 && origH > 0) {
            ImageDimension(origW, origH)
        } else {
            null
        }

        val mainImage = doc.selectFirst("img[fetchpriority=high]")
            ?: doc.selectFirst("img[property=contentUrl]")
            ?: doc.selectFirst("img[data-hook=deviation_link]")
            ?: doc.selectFirst("div[data-hook=art_stage] img")

        mainImage?.let { img ->
            val src = img.absUrl("src")
            if (isArtworkUrl(src)) {
                val w = img.attr("width").toIntOrNull() ?: origW ?: 1920
                val h = img.attr("height").toIntOrNull() ?: origH ?: 1080
                candidates.add(CandidateRendition(name = "fullview", width = w, height = h, url = src))
            }
        }

        val ogImage = doc.selectFirst("meta[property=og:image]")?.attr("content")
        if (!ogImage.isNullOrEmpty() && isArtworkUrl(ogImage) && candidates.none { it.url == ogImage }) {
            candidates.add(CandidateRendition(name = "og:image", width = origW ?: 1200, height = origH ?: 630, url = ogImage))
        }

        val selected = ImageSelector.pickRendition(
            candidates = candidates,
            original = originalDim,
            isDownloadAllowed = origUrl != null,
            qualityMode = qualityMode,
        )

        if (selected != null) {
            return listOf(ImageSelector.maximizeRendition(selected.url))
        }

        // 6. Last resort: oEmbed lookup
        resolveOembedImage(fullUrl)?.takeIf { isArtworkUrl(it) }?.let {
            return listOf(ImageSelector.maximizeRendition(it))
        }

        if (isLiterature) {
            throw IOException("This deviation is a text/literature post and cannot be viewed as an image")
        }

        throw IOException("This artwork is mature-restricted or requires login. Open this entry in WebView (globe icon) to view it.")
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

    /**
     * Extracts direct MP4 video URLs for a deviation if it is a video/film deviation.
     */
    fun extractVideoUrls(html: String, devId: String?): List<String> {
        val unescaped = html
            .replace("\\u002F", "/")
            .replace("\\/", "/")
            .replace("\\\"", "\"")

        if (!devId.isNullOrBlank()) {
            val m = Regex(""""deviationId"\s*:\s*$devId""").find(unescaped)
            if (m != null) {
                val idx = m.range.first
                val snippet = unescaped.substring(idx, (idx + 8000).coerceAtMost(unescaped.length))
                val isFilm = Regex(""""(?:type|filetype)"\s*:\s*"(?:film|video)"|"(?:isVideo|isMovie)"\s*:\s*true|"video"|VideoQualities""", RegexOption.IGNORE_CASE).containsMatchIn(snippet)
                if (isFilm) {
                    val directVideos = Regex("""https?://[^\s"\'<>]+\.mp4[^\s"\'<>]*""", RegexOption.IGNORE_CASE)
                        .findAll(snippet)
                        .map { it.value }
                        .distinct()
                        .toList()
                    if (directVideos.isNotEmpty()) {
                        return directVideos
                    }
                }
            }
        }

        // HTML5 <video> / <source> tags
        val doc = Jsoup.parse(html)
        val html5Videos = mutableListOf<String>()
        for (video in doc.select("video")) {
            video.absUrl("src").takeIf { it.isNotBlank() && it.contains(".mp4", ignoreCase = true) }?.let { html5Videos.add(it) }
            for (source in video.select("source")) {
                source.absUrl("src").takeIf { it.isNotBlank() && it.contains(".mp4", ignoreCase = true) }?.let { html5Videos.add(it) }
            }
        }
        if (html5Videos.isNotEmpty()) {
            return html5Videos.distinct()
        }

        return emptyList()
    }

    /**
     * Selects the highest quality or appropriate resolution video URL based on QualityMode.
     */
    fun pickBestVideoUrl(videoUrls: List<String>, qualityMode: QualityMode): String? {
        if (videoUrls.isEmpty()) return null
        if (videoUrls.size == 1) return videoUrls.first()

        fun rank(url: String): Int = when {
            url.contains("res_1080p", ignoreCase = true) || url.contains("1080p", ignoreCase = true) -> 1080
            url.contains("res_720p", ignoreCase = true) || url.contains("720p", ignoreCase = true) -> 720
            url.contains("res_480p", ignoreCase = true) || url.contains("480p", ignoreCase = true) -> 480
            url.contains("res_360p", ignoreCase = true) || url.contains("360p", ignoreCase = true) -> 360
            else -> 0
        }

        val sorted = videoUrls.sortedByDescending { rank(it) }
        return when (qualityMode) {
            QualityMode.STANDARD -> sorted.firstOrNull { rank(it) <= 720 } ?: sorted.last()
            QualityMode.BEST_AVAILABLE -> sorted.first()
        }
    }

    companion object {
        const val WEB_BASE_URL = "https://www.deviantart.com"
        const val BACKEND_BASE_URL = "https://backend.deviantart.com"
        private const val CACHE_TTL_MS = 10 * 60 * 1000L

        private val NUMBER_OF_ITEMS_REGEX = Regex(""""numberOfItems"\s*:\s*(\d+)""")
        private val WORKS_OF_ART_REGEX = Regex("""(\d[\d,]*)\s+works of art""", RegexOption.IGNORE_CASE)
        private val DEVIATION_ID_REGEX = Regex("""-(\d+)$""")
        private val IMAGE_ORIGINAL_URL_REGEX = Regex("""/v1(/.*)?(?=\?)""")
    }
}
