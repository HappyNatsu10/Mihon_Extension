package eu.kanade.tachiyomi.extension.all.deviantart

import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

class Feeds(
    private val client: OkHttpClient,
    private val baseUrl: String,
) {

    private val backendBaseUrl = "https://backend.deviantart.com"

    fun popular(page: Int): MangasPage {
        val offset = (page - 1) * FEED_PAGE_SIZE
        val url = backendBaseUrl.toHttpUrl().newBuilder()
            .addPathSegment("rss.xml")
            .addQueryParameter("offset", offset.toString())
            .build()

        return fetchFeed(url.toString())
    }

    fun latest(page: Int): MangasPage {
        val offset = (page - 1) * FEED_PAGE_SIZE
        val url = backendBaseUrl.toHttpUrl().newBuilder()
            .addPathSegment("rss.xml")
            .addQueryParameter("type", "deviation")
            .addQueryParameter("offset", offset.toString())
            .build()

        return fetchFeed(url.toString())
    }

    fun search(query: String, page: Int, sortByNewest: Boolean = false): MangasPage {
        val offset = (page - 1) * FEED_PAGE_SIZE
        val q = if (sortByNewest) "sort:time $query" else query
        val url = backendBaseUrl.toHttpUrl().newBuilder()
            .addPathSegment("rss.xml")
            .addQueryParameter("q", q)
            .addQueryParameter("offset", offset.toString())
            .build()

        return fetchFeed(url.toString())
    }

    fun followed(page: Int): MangasPage {
        // Detect logged out state
        if (!isLoggedIn()) {
            throw Exception("Log in to DeviantArt: open this source in WebView (globe icon), sign in, then try again.")
        }

        // Fetch watch feed from website
        val offset = (page - 1) * 24
        val watchUrl = "$baseUrl/watch/deviations?offset=$offset"
        val request = Request.Builder().url(watchUrl).build()

        return try {
            val response = client.newCall(request).execute()
            if (response.code == 401 || response.code == 403 || response.request.url.toString().contains("/users/login")) {
                response.close()
                throw Exception("Log in to DeviantArt: open this source in WebView (globe icon), sign in, then try again.")
            }
            if (!response.isSuccessful) {
                response.close()
                return MangasPage(emptyList(), false)
            }
            val html = response.body.string()
            val doc = Jsoup.parse(html, watchUrl)

            val seenAuthors = HashSet<String>()
            val mangas = mutableListOf<SManga>()

            for (tile in doc.select("a[data-hook=deviation_link]")) {
                val href = tile.attr("href")
                val author = href.removePrefix("https://www.deviantart.com/").removePrefix("/").substringBefore("/art/")
                if (author.isNotEmpty() && !author.contains("/") && seenAuthors.add(author.lowercase())) {
                    val cover = tile.selectFirst("img")?.absUrl("src")
                    mangas.add(
                        SManga.create().apply {
                            url = "/$author/gallery/all"
                            title = author
                            this.author = author
                            this.artist = author
                            thumbnail_url = cover
                            status = SManga.ONGOING
                            genre = "Gallery"
                        },
                    )
                }
            }
            MangasPage(mangas, hasNextPage = mangas.isNotEmpty())
        } catch (e: Exception) {
            if (e.message?.contains("Log in to DeviantArt") == true) throw e
            MangasPage(emptyList(), false)
        }
    }

    private fun fetchFeed(url: String): MangasPage {
        val request = Request.Builder().url(url).build()
        val response = try {
            client.newCall(request).execute()
        } catch (_: Exception) {
            return MangasPage(emptyList(), false)
        }

        if (!response.isSuccessful) {
            response.close()
            return MangasPage(emptyList(), false)
        }

        val xml = Jsoup.parse(response.body.string(), url, Parser.xmlParser())
        val items = xml.select("item")
        if (items.isEmpty()) {
            return MangasPage(emptyList(), false)
        }

        val seenAuthors = HashSet<String>()
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

            if (author.isNullOrEmpty() || !seenAuthors.add(author.lowercase())) {
                continue
            }

            val thumbnail = item.getElementsByTag("media:thumbnail").firstOrNull()?.attr("url")
                ?: item.getElementsByTag("media:content").firstOrNull()?.attr("url")
                ?: item.selectFirst("media|thumbnail")?.attr("url")
                ?: item.selectFirst("media|content")?.attr("url")

            mangas.add(
                SManga.create().apply {
                    this.url = "/$author/gallery/all"
                    title = author
                    this.author = author
                    this.artist = author
                    thumbnail_url = thumbnail
                    status = SManga.ONGOING
                    genre = "Gallery"
                },
            )
        }

        val nextLink = xml.selectFirst("atom|link[rel=next], [rel=next]")?.attr("href")
        val hasNext = nextLink != null || items.size >= FEED_PAGE_SIZE

        return MangasPage(mangas, hasNextPage = hasNext)
    }

    private fun isLoggedIn(): Boolean {
        val cookies = client.cookieJar.loadForRequest(baseUrl.toHttpUrl())
        return cookies.any { cookie: Cookie ->
            val name = cookie.name.lowercase()
            name == "user_credentials" || name == "auth" || name == "auth_secure"
        }
    }

    companion object {
        private const val FEED_PAGE_SIZE = 60
    }
}
