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
            val html = response.body.string()
            val doc = Jsoup.parse(html, fullUrl)
            val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" by ")?.trim()
                ?: defaultTitle
            val author = doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringAfter(" by ")?.substringBefore(" on DeviantArt")?.trim()
                ?: defaultAuthor
            val desc = doc.selectFirst("meta[property=og:description]")?.attr("content")
                ?: doc.selectFirst(".legacy-journal")?.wholeText()
            val thumb = doc.selectFirst("img[fetchpriority=high]")?.absUrl("src")?.takeIf { isArtworkUrl(it) }
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { isArtworkUrl(it) }

            val root = extractInitialStateJson(html)
            val devId = trailingIdRegex.find(path)?.groupValues?.get(1) ?: path.substringAfterLast("-").substringAfterLast("/")
            val devObj = root?.get("@@entities")?.jsonObject?.get("deviation")?.jsonObject?.get(devId)?.jsonObject
            val paywalled = devObj?.let { isPaywalled(it) } ?: (
                html.contains("\"isLocked\":true") ||
                    html.contains("\"isPaidContent\":true") ||
                    html.contains("\"isTier\":true") ||
                    html.contains("\"tierId\":") ||
                    html.contains("\"hasTierAccess\":false") ||
                    html.contains("\"isExclusive\":true") ||
                    html.contains("\"isEarlyAccess\":true") ||
                    html.contains("\"isSupporterOnly\":true")
                )

            ArtworkDetails(
                title = title,
                author = author,
                description = desc,
                thumbnailUrl = thumb,
                isPaywalled = paywalled,
            )
        } catch (_: Exception) {
            ArtworkDetails(
                title = defaultTitle,
                author = defaultAuthor,
                description = null,
                thumbnailUrl = null,
                isPaywalled = false,
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

        val galleryCoverFromLatest = run {
            try {
                val firstPage = fetchGalleryJson(ref.user, ref.folderId, ref.isFavourites, offset = 0, limit = 1, hidePaywalled = false)
                firstPage.items.firstOrNull()?.let { firstItem ->
                    val images = fetchDeviationImages(firstItem.path, QualityMode.BEST_AVAILABLE)
                    images.firstOrNull()?.let { ImageSelector.maximizeRendition(it) }
                }
            } catch (_: Exception) {
                null
            }
        }

        val ogImage = document.selectFirst("meta[property=og:image]")?.attr("content")
        val coverCandidate = if (!ogImage.isNullOrBlank() && isArtworkUrl(ogImage)) {
            ImageSelector.maximizeRendition(ogImage)
        } else {
            null
        }

        val coverUrl = galleryCoverFromLatest ?: coverCandidate ?: avatarUrl

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
        hidePaywalled: Boolean = false,
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
            if (hidePaywalled && (isPaywalled(dev) || isPaywalled(obj))) return@mapNotNull null
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
            val paywalled = isPaywalled(dev) || isPaywalled(obj)
            CachedItem(
                id = id.toString(),
                title = dev["title"]?.jsonPrimitive?.contentOrNull ?: "Untitled",
                path = link.removePrefix(WEB_BASE_URL),
                publishedMs = publishedMs,
                isPaywalled = paywalled,
            )
        }
        val next = root["nextOffset"]?.jsonPrimitive?.intOrNull
        val hasMore = root["hasMore"]?.jsonPrimitive?.booleanOrNull ?: false
        return RssPageResult(items, next?.let { "$WEB_BASE_URL/?offset=$it" }, hasMore)
    }

    fun isPaywalled(dev: JsonObject): Boolean {
        // 1. Explicit lock or paid flags
        if (dev["isLocked"]?.jsonPrimitive?.booleanOrNull == true) return true
        if (dev["isPaidContent"]?.jsonPrimitive?.booleanOrNull == true) return true

        // 2. Subscription tier flags
        if (dev["isTier"]?.jsonPrimitive?.booleanOrNull == true) return true
        if (dev["hasTierAccess"]?.jsonPrimitive?.booleanOrNull == false) return true
        if (dev["tierId"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true) return true
        val tier = dev["tier"]
        if (tier != null && tier !is kotlinx.serialization.json.JsonNull) {
            if (tier !is kotlinx.serialization.json.JsonPrimitive || tier.content.isNotBlank()) return true
        }

        // 3. Exclusives & supporter only
        if (dev["isExclusive"]?.jsonPrimitive?.booleanOrNull == true) return true
        val exclusive = dev["exclusive"]
        if (exclusive != null && exclusive !is kotlinx.serialization.json.JsonNull) return true
        if (dev["isSupporterOnly"]?.jsonPrimitive?.booleanOrNull == true) return true
        if (dev["supporterOnly"]?.jsonPrimitive?.booleanOrNull == true) return true

        // 4. Early access
        if (dev["isEarlyAccess"]?.jsonPrimitive?.booleanOrNull == true) return true
        val earlyAccess = dev["earlyAccess"]
        if (earlyAccess != null && earlyAccess !is kotlinx.serialization.json.JsonNull) return true

        // 5. Access string
        val access = dev["access"]?.jsonPrimitive?.contentOrNull?.lowercase()
        if (access in listOf("tier", "subscribers", "subscriber", "exclusive", "locked", "paywall")) return true

        // 6. Monetization object
        val monetization = dev["monetization"] as? JsonObject
        if (monetization != null) {
            if (monetization["isTier"]?.jsonPrimitive?.booleanOrNull == true) return true
            if (monetization["tierId"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true) return true
            if (monetization["hasTierAccess"]?.jsonPrimitive?.booleanOrNull == false) return true
            val mType = monetization["type"]?.jsonPrimitive?.contentOrNull?.lowercase()
            if (mType in listOf("tier", "subscription", "exclusive")) return true
        }

        // 7. Premium folder
        val premiumFolder = dev["premiumFolderData"]
        if (premiumFolder != null && premiumFolder !is kotlinx.serialization.json.JsonNull) return true

        // 8. Block reasons
        val blockReasons = dev["blockReasons"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        if (blockReasons.any { r ->
                r.contains("tier", ignoreCase = true) ||
                    r.contains("sub", ignoreCase = true) ||
                    r.contains("pay", ignoreCase = true) ||
                    r.contains("lock", ignoreCase = true) ||
                    r.contains("exclusive", ignoreCase = true) ||
                    r.contains("supporter", ignoreCase = true) ||
                    r.contains("early_access", ignoreCase = true)
            }
        ) {
            return true
        }

        // 9. Extended metadata if nested
        val ext = dev["extended"] as? JsonObject
        if (ext != null) {
            if (ext["isTier"]?.jsonPrimitive?.booleanOrNull == true) return true
            if (ext["tierId"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true) return true
            if (ext["hasTierAccess"]?.jsonPrimitive?.booleanOrNull == false) return true
            if (ext["isExclusive"]?.jsonPrimitive?.booleanOrNull == true) return true
            val extTier = ext["tier"]
            if (extTier != null && extTier !is kotlinx.serialization.json.JsonNull) return true
        }

        // 10. Purchasable without viewable fullview/preview
        val mediaObj = dev["media"] as? JsonObject
        val types = mediaObj?.get("types")?.jsonArray
        val hasViewable = types != null && types.isNotEmpty() && types.any {
            val t = (it as? JsonObject)?.get("t")?.jsonPrimitive?.contentOrNull
            t == "fullview" || t == "preview"
        }
        if (dev["isPurchasable"]?.jsonPrimitive?.booleanOrNull == true && !hasViewable) {
            return true
        }

        return false
    }

    private val qualityRegex = Regex(",q_\\d+")

    private fun findDownloadToken(tokens: List<String>): String? {
        for (tok in tokens) {
            val segments = tok.split('.')
            if (segments.size >= 2) {
                try {
                    val rawPayload = segments[1]
                    val padded = rawPayload + "=".repeat((4 - rawPayload.length % 4) % 4)
                    val decoded = String(java.util.Base64.getUrlDecoder().decode(padded))
                    if (decoded.contains("file.download")) {
                        return tok
                    }
                } catch (_: Exception) {}
            }
        }
        return null
    }

    /**
     * Resolves the highest quality image URL for the given Media object.
     * When raiseQuality is true (BEST_AVAILABLE):
     * 1. Uses the direct uncompressed original download file if a file.download token is present.
     * 2. Otherwise selects the largest available rendition from types (avoiding crops and tiny thumbnails).
     * 3. Maximizes rendition dimensions within the token's allowed constraints (ImageSelector.maximizeRendition).
     */
    fun fullviewUrl(m: Media, raiseQuality: Boolean = true): String? {
        val base = m.baseUri ?: return null

        if (raiseQuality && m.token.isNotEmpty()) {
            val dlToken = findDownloadToken(m.token)
            if (dlToken != null) {
                return "$base?token=$dlToken"
            }
        }

        val nonCrops = m.types.filter { !it.c.isNullOrBlank() && it.c?.contains("/v1/crop/") != true }
        val fv = nonCrops.firstOrNull { it.t == "fullview" }
            ?: nonCrops.maxByOrNull { (it.w ?: 0) * (it.h ?: 0) }
            ?: nonCrops.firstOrNull { it.t == "preview" }
            ?: m.types.maxByOrNull { (it.w ?: 0) * (it.h ?: 0) }
            ?: m.types.firstOrNull { !it.c.isNullOrBlank() }

        val path = fv?.c?.replace("<prettyName>", m.prettyName.orEmpty())
        var url = if (path != null) base + path else base
        if (raiseQuality) url = qualityRegex.replace(url, ",q_100")
        val token = m.token.firstOrNull()
        if (token != null) url = "$url?token=$token"

        return if (raiseQuality) ImageSelector.maximizeRendition(url) else url
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

        val devId = DEVIATION_ID_REGEX.find(artworkUrl)?.groupValues?.get(1)
            ?: artworkUrl.substringAfterLast("-").substringAfterLast("/")
        val root = extractInitialStateJson(pageHtml)
        val devObj = root?.get("@@entities")?.jsonObject?.get("deviation")?.jsonObject?.get(devId)?.jsonObject
        val dlUrlFromJson = devObj?.get("download")?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
        if (!dlUrlFromJson.isNullOrBlank()) {
            return dlUrlFromJson
        }

        val unescaped = pageHtml.replace("\\/", "/").replace("\\u002F", "/")
        val marker = "https://www.deviantart.com/download/"
        val start = unescaped.indexOf(marker).takeIf { it >= 0 } ?: return null
        val end = unescaped.indexOf('"', start).takeIf { it > start } ?: return null
        return Parser.unescapeEntities(unescaped.substring(start, end), false)
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

    fun decodeJsStringLiteral(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            if (c == '\\' && i + 1 < n) {
                when (val nxt = s[i + 1]) {
                    '"' -> {
                        sb.append('"')
                        i += 2
                    }
                    '\\' -> {
                        sb.append('\\')
                        i += 2
                    }
                    '/' -> {
                        sb.append('/')
                        i += 2
                    }
                    '\'' -> {
                        sb.append('\'')
                        i += 2
                    }
                    'n' -> {
                        sb.append('\n')
                        i += 2
                    }
                    'r' -> {
                        sb.append('\r')
                        i += 2
                    }
                    't' -> {
                        sb.append('\t')
                        i += 2
                    }
                    'b' -> {
                        sb.append('\b')
                        i += 2
                    }
                    'f' -> {
                        sb.append('\u000c')
                        i += 2
                    }
                    'u' -> {
                        if (i + 5 <= n) {
                            val hex = s.substring(i + 2, i + 6)
                            val code = hex.toIntOrNull(16)
                            if (code != null) {
                                sb.append(code.toChar())
                                i += 6
                            } else {
                                sb.append(nxt)
                                i += 2
                            }
                        } else {
                            sb.append(nxt)
                            i += 2
                        }
                    }
                    'x' -> {
                        if (i + 3 <= n) {
                            val hex = s.substring(i + 2, i + 4)
                            val code = hex.toIntOrNull(16)
                            if (code != null) {
                                sb.append(code.toChar())
                                i += 4
                            } else {
                                sb.append(nxt)
                                i += 2
                            }
                        } else {
                            sb.append(nxt)
                            i += 2
                        }
                    }
                    else -> {
                        sb.append(nxt)
                        i += 2
                    }
                }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    fun extractInitialStateJson(html: String): JsonObject? {
        val marker = "window.__INITIAL_STATE__ = JSON.parse(\""
        val start = html.indexOf(marker)
        if (start != -1) {
            val startIdx = start + marker.length
            val n = html.length
            var i = startIdx
            var endIdx = -1
            while (i < n) {
                val c = html[i]
                if (c == '\\') {
                    i += 2
                } else if (c == '"') {
                    if (i + 2 < n && html[i + 1] == ')' && html[i + 2] == ';') {
                        endIdx = i
                        break
                    }
                    i++
                } else {
                    i++
                }
            }
            if (endIdx != -1) {
                val raw = html.substring(startIdx, endIdx)
                val decoded = decodeJsStringLiteral(raw)
                return try {
                    json.parseToJsonElement(decoded).jsonObject
                } catch (_: Exception) {
                    null
                }
            }
        }

        val objMarker = "window.__INITIAL_STATE__ = {"
        val objStart = html.indexOf(objMarker)
        if (objStart != -1) {
            val startIdx = objStart + objMarker.length - 1
            val scriptEnd = html.indexOf("</script>", startIdx).takeIf { it != -1 } ?: html.length
            val endIdx = html.lastIndexOf("};", scriptEnd)
            if (endIdx != -1) {
                val raw = html.substring(startIdx, endIdx + 1)
                return try {
                    json.parseToJsonElement(raw).jsonObject
                } catch (_: Exception) {
                    null
                }
            }
        }
        return null
    }

    fun extractDeviationMediaFromHtml(html: String, devId: String): Pair<Media?, List<Media>> {
        var primary: Media? = null
        val additional = mutableListOf<Media>()

        // 1. Extract from window.__INITIAL_STATE__
        try {
            val root = extractInitialStateJson(html)
            val entities = root?.get("@@entities")?.jsonObject
            val devObj = entities?.get("deviation")?.jsonObject?.get(devId)?.jsonObject
            val extObj = entities?.get("deviationExtended")?.jsonObject?.get(devId)?.jsonObject
            if (devObj != null) {
                primary = parseMedia(devObj["media"])
                val extraJson = extObj?.get("additionalMedia")?.jsonArray
                    ?: devObj["extended"]?.jsonObject?.get("additionalMedia")?.jsonArray
                    ?: devObj["additionalMedia"]?.jsonArray
                if (extraJson != null) {
                    val sorted = extraJson.mapNotNull { it as? JsonObject }.sortedBy {
                        it["position"]?.jsonPrimitive?.intOrNull ?: 0
                    }
                    val extra = sorted.mapNotNull { parseMedia(it) }
                    additional.addAll(extra)
                }
                if (primary != null) {
                    return Pair(primary, additional)
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

                if (additional.isEmpty()) {
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
                        for (i in (aPos + aPrefixLen) until minOf(html.length, aPos + 60000)) {
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
                            val sorted = arr.mapNotNull { it as? JsonObject }.sortedBy {
                                it["position"]?.jsonPrimitive?.intOrNull ?: 0
                            }
                            val parsed = sorted.mapNotNull { parseMedia(it) }
                            additional.addAll(parsed)
                        }
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
            val root = extractInitialStateJson(html)
            val devExt = root?.get("@@entities")?.jsonObject?.get("deviationExtended")?.jsonObject?.get(devId)?.jsonObject
                ?: root?.get("@@entities")?.jsonObject?.get("deviation")?.jsonObject?.get(devId)?.jsonObject?.get("extended")?.jsonObject
            val devObj = root?.get("@@entities")?.jsonObject?.get("deviation")?.jsonObject?.get(devId)?.jsonObject

            val candidate = devExt?.get("descriptionText")?.jsonObject?.get("html")?.jsonObject?.get("markup")?.jsonPrimitive?.contentOrNull
                ?: devExt?.get("bodyText")?.jsonObject?.get("html")?.jsonObject?.get("markup")?.jsonPrimitive?.contentOrNull
                ?: devExt?.get("body")?.jsonObject?.get("markup")?.jsonPrimitive?.contentOrNull
                ?: devExt?.get("description")?.jsonObject?.get("markup")?.jsonPrimitive?.contentOrNull
                ?: devExt?.get("description")?.jsonObject?.get("draft")?.jsonPrimitive?.contentOrNull
                ?: devExt?.get("description")?.jsonObject?.get("html")?.jsonObject?.get("markup")?.jsonPrimitive?.contentOrNull
                ?: devExt?.get("bodyText")?.jsonObject?.get("html")?.jsonPrimitive?.contentOrNull
                ?: devExt?.get("descriptionText")?.jsonObject?.get("html")?.jsonPrimitive?.contentOrNull
                ?: devExt?.get("descriptionText")?.jsonObject?.get("excerpt")?.jsonPrimitive?.contentOrNull
                ?: devObj?.get("descriptionText")?.jsonObject?.get("html")?.jsonObject?.get("markup")?.jsonPrimitive?.contentOrNull
                ?: devObj?.get("descriptionText")?.jsonObject?.get("html")?.jsonPrimitive?.contentOrNull
                ?: devObj?.get("description")?.jsonObject?.get("markup")?.jsonPrimitive?.contentOrNull
                ?: devObj?.get("description")?.jsonObject?.get("html")?.jsonObject?.get("markup")?.jsonPrimitive?.contentOrNull
                ?: devObj?.get("descriptionText")?.jsonObject?.get("excerpt")?.jsonPrimitive?.contentOrNull

            if (!candidate.isNullOrBlank()) {
                rawText = cleanDescriptionHtml(candidate)
            }
        } catch (_: Exception) {}

        // 2. Direct regex search in HTML for description or body markup
        if (rawText.isBlank()) {
            try {
                val descMarkupRegex = Regex(""""descriptionText"\s*:\s*\{[^{}]*"html"\s*:\s*\{[^{}]*"markup"\s*:\s*"((?:\\.|[^"\\])*)"""")
                val bodyMarkupRegex = Regex(""""body"\s*:\s*\{\s*"markup"\s*:\s*"((?:\\.|[^"\\])*)"""")
                val descLegacyMarkupRegex = Regex(""""description"\s*:\s*\{\s*"markup"\s*:\s*"((?:\\.|[^"\\])*)"""")
                val match = descMarkupRegex.find(html) ?: bodyMarkupRegex.find(html) ?: descLegacyMarkupRegex.find(html)
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
            return listOf(bestVideo)
        }

        val isVideoDeviation = html.contains("\"isVideo\":true") ||
            html.contains("\"type\":\"film\"") ||
            html.contains("\"filetype\":\"video\"") ||
            doc.selectFirst("video") != null

        if (isVideoDeviation) {
            val isMatureBlocked = html.contains("mature_loggedout") ||
                html.contains("mature_filter") ||
                html.contains("\"isBlocked\":true")
            if (isMatureBlocked) {
                throw IOException("This video deviation is age-restricted (mature). Please log in to DeviantArt in the extension WebView to view/play it.")
            }
            throw IOException("This video deviation could not be loaded or requires login.")
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

        // Primary artwork image: ALWAYS use fullviewUrl (which handles file.download tokens for lossless originals)
        // Never use https://www.deviantart.com/download/ which returns HTML error/redirect and crashes decoders
        if (primaryMedia != null) {
            fullviewUrl(primaryMedia, raiseQuality)?.let { resultPages.add(it) }
        }

        // Add additional genuine pages from stack in order
        for (extra in additionalMedia) {
            fullviewUrl(extra, raiseQuality)?.let { resultPages.add(it) }
        }

        if (resultPages.isNotEmpty()) {
            return resultPages
        }

        // Check if locked behind paywall without viewable pages
        try {
            val root = extractInitialStateJson(html)
            val devObj = root?.get("@@entities")?.jsonObject?.get("deviation")?.jsonObject?.get(devId)?.jsonObject
            if (devObj != null && isPaywalled(devObj)) {
                throw IOException("This artwork is locked behind a paywall (subscription/tier).")
            }
        } catch (e: IOException) {
            throw e
        } catch (_: Exception) {}

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

        val mainImage = doc.selectFirst("div[data-hook=art_stage] img")
            ?: doc.selectFirst("img[fetchpriority=high]")
            ?: doc.selectFirst("img[property=contentUrl]")

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
            isDownloadAllowed = false,
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

        val result = mutableListOf<String>()

        // 1. Check InitialState JSON entity media.types if present
        try {
            val root = extractInitialStateJson(html)
            val devObj = devId?.let { root?.get("@@entities")?.jsonObject?.get("deviation")?.jsonObject?.get(it)?.jsonObject }
                ?: (if (root?.get("deviationId")?.jsonPrimitive?.contentOrNull == devId || root?.get("deviationId")?.jsonPrimitive?.longOrNull?.toString() == devId) root else null)
            val mediaObj = devObj?.get("media") as? JsonObject
            val types = mediaObj?.get("types")?.jsonArray?.mapNotNull { it as? JsonObject }.orEmpty()
            for (t in types) {
                val tName = t["t"]?.jsonPrimitive?.contentOrNull
                val b = t["b"]?.jsonPrimitive?.contentOrNull
                val c = t["c"]?.jsonPrimitive?.contentOrNull
                if (tName == "video" || b?.contains(".mp4", ignoreCase = true) == true || c?.contains(".mp4", ignoreCase = true) == true) {
                    val url = b ?: c
                    if (!url.isNullOrBlank() && url.contains(".mp4", ignoreCase = true)) {
                        result.add(url)
                    }
                }
            }
        } catch (_: Exception) {}

        // 2. Search for MP4 strictly within devId's entity JSON block
        if (result.isEmpty() && !devId.isNullOrBlank()) {
            val devMarkers = listOf(""""$devId":{""", """\"$devId\":{""", """"deviationId":$devId""", """"deviationId":"$devId"""")
            for (marker in devMarkers) {
                val pos = unescaped.indexOf(marker)
                if (pos != -1) {
                    var objStart = pos
                    if (marker.contains("deviationId")) {
                        var depth = 0
                        for (j in pos downTo 0) {
                            if (unescaped[j] == '}') {
                                depth++
                            } else if (unescaped[j] == '{') {
                                if (depth == 0) {
                                    objStart = j
                                    break
                                }
                                depth--
                            }
                        }
                    }
                    var open = 1
                    var endPos = -1
                    val startScan = objStart + 1
                    for (i in startScan until minOf(unescaped.length, startScan + 30000)) {
                        if (unescaped[i] == '{') {
                            open++
                        } else if (unescaped[i] == '}') {
                            open--
                            if (open == 0) {
                                endPos = i + 1
                                break
                            }
                        }
                    }
                    if (endPos != -1) {
                        val snippet = unescaped.substring(objStart, endPos)
                        val directVideos = Regex("""https?://[^\s"\'<>]+\.mp4[^\s"\'<>]*""", RegexOption.IGNORE_CASE)
                            .findAll(snippet)
                            .map { it.value }
                            .toList()
                        result.addAll(directVideos)
                        if (result.isNotEmpty()) break
                    }
                }
            }
        }

        // 3. HTML5 <video> / <source> tags
        val doc = Jsoup.parse(html)
        for (video in doc.select("video")) {
            video.absUrl("src").takeIf { it.isNotBlank() && it.contains(".mp4", ignoreCase = true) }?.let { result.add(it) }
            for (source in video.select("source")) {
                source.absUrl("src").takeIf { it.isNotBlank() && it.contains(".mp4", ignoreCase = true) }?.let { result.add(it) }
            }
        }

        return result.distinct()
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
        private val DEVIATION_ID_REGEX = Regex("""-(\d+)(?:[/?#].*)?$""")
        private val IMAGE_ORIGINAL_URL_REGEX = Regex("""/v1(/.*)?(?=\?)""")
    }
}
