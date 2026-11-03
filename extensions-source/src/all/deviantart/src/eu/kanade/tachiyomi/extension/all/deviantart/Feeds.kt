package eu.kanade.tachiyomi.extension.all.deviantart

import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.ByteString.Companion.encodeUtf8
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.IOException

class Feeds(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val api: DeviantArtApi,
    private val mangaFactory: () -> SManga = { SManga.create() },
) {

    private val backendBaseUrl = "https://backend.deviantart.com"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Popular feed: backed by DeviantArt backend RSS feed.
     */
    fun popular(page: Int, hidePaywalled: Boolean = false): MangasPage {
        val offset = (page - 1) * FEED_PAGE_SIZE
        val url = backendBaseUrl.toHttpUrl().newBuilder()
            .addPathSegment("rss.xml")
            .addQueryParameter("offset", offset.toString())
            .build()

        return fetchRssFeed(url.toString())
    }

    /**
     * Latest feed: returns the user's home feed (what they see when opening DeviantArt),
     * personalized if logged in, or the recommended feed if logged out.
     */
    fun latest(page: Int, hidePaywalled: Boolean = false): MangasPage = try {
        fetchNetworkbarFeed("rfy/deviations", page, hidePaywalled)
    } catch (e: Exception) {
        if (page == 1) {
            try {
                fetchHomeHtmlFeed(hidePaywalled)
            } catch (_: Exception) {
                fetchRssLatest(page)
            }
        } else {
            fetchRssLatest(page)
        }
    }

    /**
     * Followed feed: Deviations from artists the user watches ("dyw/deviations").
     * Requires authentication. If logged out, throws a clear login prompt.
     * If following nobody or no deviations, returns an empty list without error.
     */
    fun followed(page: Int, hidePaywalled: Boolean = false): MangasPage {
        if (!isLoggedIn()) {
            throw IOException(LOGIN_REQUIRED_MESSAGE)
        }

        return try {
            fetchNetworkbarFeed("dyw/deviations", page, hidePaywalled)
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            if (msg.contains("unauthorized", ignoreCase = true) ||
                msg.contains("session", ignoreCase = true) ||
                msg.contains("Log in", ignoreCase = true) ||
                msg.contains("401") ||
                msg.contains("403")
            ) {
                throw IOException(LOGIN_REQUIRED_MESSAGE)
            }
            throw e
        }
    }

    /**
     * Keyword search backed by backend RSS query.
     */
    fun search(query: String, page: Int, sortByNewest: Boolean = false, hidePaywalled: Boolean = false): MangasPage {
        val offset = (page - 1) * FEED_PAGE_SIZE
        val q = if (sortByNewest) "sort:time $query" else query
        val url = backendBaseUrl.toHttpUrl().newBuilder()
            .addPathSegment("rss.xml")
            .addQueryParameter("q", q)
            .addQueryParameter("offset", offset.toString())
            .build()

        return fetchRssFeed(url.toString())
    }

    private fun fetchRssLatest(page: Int): MangasPage {
        val offset = (page - 1) * FEED_PAGE_SIZE
        val url = backendBaseUrl.toHttpUrl().newBuilder()
            .addPathSegment("rss.xml")
            .addQueryParameter("type", "deviation")
            .addQueryParameter("offset", offset.toString())
            .build()

        return fetchRssFeed(url.toString())
    }

    /**
     * Fetches a feed from DeviantArt's web networkbar endpoint (rfy/deviations or dyw/deviations).
     * Handles CSRF refreshing, cursor pagination, and item de-duplication.
     */
    fun fetchNetworkbarFeed(endpoint: String, page: Int, hidePaywalled: Boolean = false, maxAttempts: Int = 3): MangasPage {
        var currentAttempt = 0
        var currentPage = page
        val accumulatedMangas = mutableListOf<SManga>()
        val seenUrls = mutableSetOf<String>()
        var hasMoreResults = false

        while (currentAttempt < maxAttempts) {
            currentAttempt++
            val csrf = api.csrfToken()
            val offset = (currentPage - 1) * NETWORKBAR_PAGE_SIZE

            fun buildUrl(token: String): HttpUrl {
                val builder = "$baseUrl/_puppy/dabrowse/networkbar/$endpoint".toHttpUrl().newBuilder()
                    .addQueryParameter("csrf_token", token)
                val cursorKey = "$endpoint-$currentPage"
                val cachedCursor = cursorCache[cursorKey]
                if (!cachedCursor.isNullOrBlank()) {
                    builder.addQueryParameter("cursor", cachedCursor)
                } else if (currentPage > 1) {
                    val cursorJson = "{\"offset\":$offset,\"vespa_content_group\":0}"
                    val cursor = cursorJson.encodeUtf8().base64().trimEnd('=')
                    builder.addQueryParameter("cursor", cursor)
                }
                return builder.build()
            }

            fun execute(token: String): Response {
                val req = Request.Builder().url(buildUrl(token)).header("Referer", "$baseUrl/").build()
                return HttpGuard.executeWithRetry("feed $endpoint") {
                    client.newCall(req).execute()
                }
            }

            var response = execute(csrf)
            var body = response.use { resp ->
                if (endpoint.contains("dyw") && (resp.code == 401 || resp.code == 403 || resp.request.url.toString().contains("/users/login"))) {
                    throw IOException(LOGIN_REQUIRED_MESSAGE)
                }
                resp.body.string()
            }

            if (body.contains("\"csrf\"") && body.contains("invalid")) {
                val freshCsrf = api.csrfToken(force = true)
                response = execute(freshCsrf)
                body = response.use { resp ->
                    if (endpoint.contains("dyw") && (resp.code == 401 || resp.code == 403 || resp.request.url.toString().contains("/users/login"))) {
                        throw IOException(LOGIN_REQUIRED_MESSAGE)
                    }
                    resp.body.string()
                }
            }

            if (endpoint.contains("dyw") && body.contains("\"unauthorized\"")) {
                throw IOException(LOGIN_REQUIRED_MESSAGE)
            }

            val pageResult = parseNetworkbarJsonWithCursor(body, hidePaywalled)
            hasMoreResults = pageResult.second || !pageResult.third.isNullOrBlank()
            if (!pageResult.third.isNullOrBlank()) {
                cursorCache["$endpoint-${currentPage + 1}"] = pageResult.third!!
            }

            for (manga in pageResult.first) {
                if (seenUrls.add(manga.url)) {
                    accumulatedMangas.add(manga)
                }
            }

            if (accumulatedMangas.isNotEmpty() || !hasMoreResults) {
                break
            }
            currentPage++
        }

        return MangasPage(accumulatedMangas, hasNextPage = hasMoreResults && accumulatedMangas.isNotEmpty())
    }

    /**
     * Parses the deviations JSON returned by networkbar endpoints with cursor extraction.
     */
    fun parseNetworkbarJsonWithCursor(body: String, hidePaywalled: Boolean = false): Triple<List<SManga>, Boolean, String?> {
        val root = try {
            json.parseToJsonElement(body).jsonObject
        } catch (_: Exception) {
            return Triple(emptyList(), false, null)
        }

        val deviationsArray = root["deviations"]?.jsonArray
            ?: root["results"]?.jsonArray
            ?: return Triple(emptyList(), false, null)

        val hasMore = root["hasMore"]?.jsonPrimitive?.booleanOrNull ?: false
        val nextCursor = root["cursor"]?.jsonPrimitive?.contentOrNull
            ?: root["nextCursor"]?.jsonPrimitive?.contentOrNull
            ?: root["next_cursor"]?.jsonPrimitive?.contentOrNull
        val mangas = mutableListOf<SManga>()

        for (el in deviationsArray) {
            val obj = el.jsonObject
            val dev = (obj["deviation"] as? JsonObject) ?: obj
            if (hidePaywalled && api.isPaywalled(dev)) continue
            val devId = dev["deviationId"]?.jsonPrimitive?.longOrNull ?: continue
            val url = dev["url"]?.jsonPrimitive?.contentOrNull ?: continue
            val title = dev["title"]?.jsonPrimitive?.contentOrNull?.ifBlank { "Artwork" } ?: "Artwork"

            val authorObj = dev["author"] as? JsonObject
            val author = authorObj?.get("username")?.jsonPrimitive?.contentOrNull
                ?: url.removePrefix("https://www.deviantart.com/").removePrefix("http://www.deviantart.com/").removePrefix("/").substringBefore("/art/").substringBefore("/")
            if (author.isBlank()) continue

            val media = api.parseMedia(dev["media"])
            val cover = media?.let { api.fullviewUrl(it, raiseQuality = false) }

            val path = if (url.startsWith("http")) {
                url.removePrefix("https://www.deviantart.com").removePrefix("http://www.deviantart.com")
            } else {
                url
            }

            mangas.add(
                mangaFactory().apply {
                    this.url = path
                    this.title = title
                    this.author = author
                    this.artist = author
                    this.thumbnail_url = cover
                    this.status = SManga.COMPLETED
                    this.genre = "Artwork"
                },
            )
        }

        return Triple(mangas, hasMore, nextCursor)
    }

    /**
     * Parses the deviations JSON returned by networkbar endpoints.
     */
    fun parseNetworkbarJson(body: String): Pair<List<SManga>, Boolean> {
        val (mangas, hasMore, _) = parseNetworkbarJsonWithCursor(body)
        return Pair(mangas, hasMore)
    }

    /**
     * Secondary fallback: extracts initial deviations from the homepage HTML SSR.
     */
    fun fetchHomeHtmlFeed(hidePaywalled: Boolean = false): MangasPage {
        val req = Request.Builder().url("$baseUrl/").build()
        val response = HttpGuard.executeWithRetry("home html") {
            client.newCall(req).execute()
        }
        val html = response.use { it.body.string() }
        api.updateCsrfFromHtml(html)
        val root = api.extractInitialStateJson(html) ?: throw IOException("Failed to load home page state")
        val entities = root["@@entities"]?.jsonObject
        val devMap = entities?.get("deviation")?.jsonObject ?: throw IOException("No deviations in home page")
        val userMap = entities["user"]?.jsonObject

        val streamItems = root["@@streams"]?.jsonObject?.get("@@BROWSE_PAGE_STREAM")?.jsonObject?.get("items")?.jsonArray
        val devIds = streamItems?.mapNotNull { it.jsonPrimitive.longOrNull }
            ?: devMap.keys.mapNotNull { it.toLongOrNull() }

        val mangas = mutableListOf<SManga>()
        for (id in devIds) {
            val dev = devMap[id.toString()]?.jsonObject ?: continue
            if (hidePaywalled && api.isPaywalled(dev)) continue
            val url = dev["url"]?.jsonPrimitive?.contentOrNull ?: continue
            val title = dev["title"]?.jsonPrimitive?.contentOrNull?.ifBlank { "Artwork" } ?: "Artwork"
            val authorId = dev["author"]?.jsonPrimitive?.contentOrNull
            val author = authorId?.let { userMap?.get(it)?.jsonObject?.get("username")?.jsonPrimitive?.contentOrNull }
                ?: url.removePrefix("https://www.deviantart.com/").removePrefix("http://www.deviantart.com/").removePrefix("/").substringBefore("/art/").substringBefore("/")

            val media = api.parseMedia(dev["media"])
            val cover = media?.let { api.fullviewUrl(it, raiseQuality = false) }
            val path = if (url.startsWith("http")) {
                url.removePrefix("https://www.deviantart.com").removePrefix("http://www.deviantart.com")
            } else {
                url
            }

            mangas.add(
                mangaFactory().apply {
                    this.url = path
                    this.title = title
                    this.author = author
                    this.artist = author
                    this.thumbnail_url = cover
                    this.status = SManga.COMPLETED
                    this.genre = "Artwork"
                },
            )
        }
        return MangasPage(mangas, hasNextPage = mangas.isNotEmpty())
    }

    private fun fetchRssFeed(url: String): MangasPage {
        val request = Request.Builder().url(url).build()
        val response = HttpGuard.executeWithRetry("rss feed") { client.newCall(request).execute() }
        val xmlText = response.use { it.body.string() }

        val xml = Jsoup.parse(xmlText, url, Parser.xmlParser())
        val items = xml.select("item")
        if (items.isEmpty()) {
            return MangasPage(emptyList(), false)
        }

        val mangas = mutableListOf<SManga>()

        for (item in items) {
            val link = item.selectFirst("link")?.text().orEmpty()
            val author = item.getElementsByTag("media:credit").firstOrNull {
                it.attr("role") == "author" && !it.text().startsWith("http")
            }?.text()
                ?: if (link.contains("/art/")) {
                    link.removePrefix("https://www.deviantart.com/").removePrefix("http://www.deviantart.com/").substringBefore("/art/").substringBefore("/")
                } else {
                    null
                }

            if (author.isNullOrEmpty() || link.isEmpty()) {
                continue
            }

            val title = item.selectFirst("title")?.text().orEmpty().ifBlank { "Untitled" }
            val path = if (link.startsWith("http")) {
                link.removePrefix("https://www.deviantart.com").removePrefix("http://www.deviantart.com")
            } else {
                link
            }

            val thumbnail = item.getElementsByTag("media:thumbnail").firstOrNull()?.attr("url")
                ?: item.getElementsByTag("media:content").firstOrNull()?.attr("url")
                ?: item.selectFirst("media|thumbnail")?.attr("url")
                ?: item.selectFirst("media|content")?.attr("url")

            mangas.add(
                mangaFactory().apply {
                    this.url = path
                    this.title = title
                    this.author = author
                    this.artist = author
                    this.thumbnail_url = thumbnail
                    this.status = SManga.COMPLETED
                    this.genre = "Artwork"
                },
            )
        }

        val nextLink = xml.getElementsByTag("atom:link")
            .firstOrNull { it.attr("rel") == "next" }
            ?.attr("href")
            ?.takeIf { it.isNotBlank() }
            ?: xml.selectFirst("[rel=next]")?.attr("href")?.takeIf { it.isNotBlank() }
        val hasNext = nextLink != null || items.size >= FEED_PAGE_SIZE

        return MangasPage(mangas, hasNextPage = hasNext)
    }

    fun isLoggedIn(): Boolean {
        val cookies = client.cookieJar.loadForRequest(baseUrl.toHttpUrl())
        if (cookies.isEmpty()) return false

        val hasAuthCookie = cookies.any { cookie: Cookie ->
            val name = cookie.name.lowercase()
            (name == "user_credentials" || name == "auth" || name == "auth_secure" || name == "tdv" || name.contains("session")) &&
                cookie.value.isNotBlank()
        }

        val hasUserInfoWithUsername = cookies.any { cookie: Cookie ->
            cookie.name.equals("userinfo", ignoreCase = true) &&
                cookie.value.isNotBlank() &&
                !cookie.value.contains("%22username%22%3A%22%22") &&
                !cookie.value.contains("\"username\":\"\"") &&
                (cookie.value.contains("%22username%22%3A%22") || cookie.value.contains("\"username\":\""))
        }

        return hasAuthCookie || hasUserInfoWithUsername
    }

    companion object {
        const val LOGIN_REQUIRED_MESSAGE = "Log in to DeviantArt: open this source in WebView (globe icon), sign in, then try again."
        private const val FEED_PAGE_SIZE = 60
        private const val NETWORKBAR_PAGE_SIZE = 24
        private val cursorCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    }
}
