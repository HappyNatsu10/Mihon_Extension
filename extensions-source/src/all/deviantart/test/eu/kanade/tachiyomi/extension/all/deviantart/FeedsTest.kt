package eu.kanade.tachiyomi.extension.all.deviantart

import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException

class FeedsTest {

    private class TestSManga : SManga {
        override var url: String = ""
        override var title: String = ""
        override var artist: String? = null
        override var author: String? = null
        override var description: String? = null
        override var genre: String? = null
        override var status: Int = 0
        override var thumbnail_url: String? = null
        override var update_strategy: UpdateStrategy = UpdateStrategy.ALWAYS_UPDATE
        override var initialized: Boolean = false
        override var memo: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap())
    }

    private val sampleRfyJson = """
        {
          "hasMore": true,
          "nextCursor": "eyJvZmZzZXQiOjI0LCJ2ZXNwYV9jb250ZW50X2dyb3VwIjowfQ",
          "currentPage": 1,
          "biRankOffset": 0,
          "deviations": [
            {
              "deviationId": 1402146630,
              "title": "Sunrise Sunset",
              "url": "https://www.deviantart.com/valong/art/Sunrise-Sunset-1402146630",
              "author": {
                "userId": 12735699,
                "username": "Valong"
              },
              "media": {
                "baseUri": "https://images-wixmp-ed30a86b8c4ca887773594c2.wixmp.com/f/198524e7-9d12-46bf-80f2-cb5cfa3f7166/dn6sv9i.png",
                "prettyName": "sunrise_sunset_by_valong",
                "token": ["test_token"],
                "types": [
                  {
                    "t": "fullview",
                    "h": 539,
                    "w": 1280
                  },
                  {
                    "t": "preview",
                    "c": "/v1/fill/w_1280,h_539,q_80,strp/<prettyName>-fullview.jpg",
                    "h": 539,
                    "w": 1280
                  }
                ]
              }
            }
          ]
        }
    """.trimIndent()

    @Test
    fun testParseNetworkbarJson() {
        val client = OkHttpClient()
        val api = DeviantArtApi(client)
        val feeds = Feeds(client, "https://www.deviantart.com", api, mangaFactory = { TestSManga() })

        val (mangas, hasMore) = feeds.parseNetworkbarJson(sampleRfyJson)
        assertTrue(hasMore)
        assertEquals(1, mangas.size)

        val item = mangas[0]
        assertEquals("/valong/art/Sunrise-Sunset-1402146630", item.url)
        assertEquals("Sunrise Sunset", item.title)
        assertEquals("Valong", item.author)
        assertEquals("Valong", item.artist)
        assertNotNull(item.thumbnail_url)
        assertTrue(item.thumbnail_url!!.contains("/v1/fill/w_1280,h_539"))
        assertTrue(item.thumbnail_url!!.contains("token=test_token"))
    }

    @Test
    fun testFollowedFeedLoggedOutThrows() {
        val client = OkHttpClient()
        val api = DeviantArtApi(client)
        val feeds = Feeds(client, "https://www.deviantart.com", api, mangaFactory = { TestSManga() })

        try {
            feeds.followed(1)
            fail("Expected IOException when not logged in")
        } catch (e: IOException) {
            assertEquals(
                "Log in to DeviantArt: open this source in WebView (globe icon), sign in, then try again.",
                e.message,
            )
        }
    }

    @Test
    fun testFollowedFeedUnauthorizedResponseThrows() {
        val interceptingClient = OkHttpClient.Builder()
            .cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {}
                override fun loadForRequest(url: HttpUrl): List<Cookie> {
                    return listOf(
                        Cookie.Builder()
                            .domain("deviantart.com")
                            .path("/")
                            .name("auth_secure")
                            .value("dummy_session")
                            .build(),
                    )
                }
            })
            .addInterceptor { chain ->
                val req = chain.request()
                if (req.url.encodedPath.contains("csrf")) {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("window.__CSRF_TOKEN__ = 'test_token';".toResponseBody("text/html".toMediaType()))
                        .build()
                } else {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(403)
                        .message("Forbidden")
                        .body("""{"error":"unauthorized","errorDescription":"Not authorized"}""".toResponseBody("application/json".toMediaType()))
                        .build()
                }
            }
            .build()

        val api = DeviantArtApi(interceptingClient)
        val feeds = Feeds(interceptingClient, "https://www.deviantart.com", api, mangaFactory = { TestSManga() })

        try {
            feeds.followed(1)
            fail("Expected login required exception on 403 unauthorized")
        } catch (e: IOException) {
            assertEquals(
                "Log in to DeviantArt: open this source in WebView (globe icon), sign in, then try again.",
                e.message,
            )
        }
    }

    @Test
    fun testFollowedFeedEmptyWhenFollowingNobody() {
        val interceptingClient = OkHttpClient.Builder()
            .cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {}
                override fun loadForRequest(url: HttpUrl): List<Cookie> {
                    return listOf(
                        Cookie.Builder()
                            .domain("deviantart.com")
                            .path("/")
                            .name("auth_secure")
                            .value("dummy_session")
                            .build(),
                    )
                }
            })
            .addInterceptor { chain ->
                val req = chain.request()
                if (req.url.toString() == "https://www.deviantart.com/") {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("window.__CSRF_TOKEN__ = 'test_token';".toResponseBody("text/html".toMediaType()))
                        .build()
                } else {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{"hasMore":false,"deviations":[]}""".toResponseBody("application/json".toMediaType()))
                        .build()
                }
            }
            .build()

        val api = DeviantArtApi(interceptingClient)
        val feeds = Feeds(interceptingClient, "https://www.deviantart.com", api, mangaFactory = { TestSManga() })

        val page = feeds.followed(1)
        assertTrue(page.mangas.isEmpty())
        assertFalse(page.hasNextPage)
    }

    @Test
    fun testHomeHtmlFeedParsingWithFixture() {
        val fixtureFile = File("../../../files/da_home.html")
        if (!fixtureFile.exists()) return

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(fixtureFile.readText().toResponseBody("text/html".toMediaType()))
                    .build()
            }
            .build()

        val api = DeviantArtApi(client)
        val feeds = Feeds(client, "https://www.deviantart.com", api, mangaFactory = { TestSManga() })

        val page = feeds.fetchHomeHtmlFeed()
        assertEquals(24, page.mangas.size)
        assertTrue(page.hasNextPage)

        val first = page.mangas[0]
        assertEquals("2026 - 332", first.title)
        assertEquals("iSubmit", first.author)
        assertEquals("/isubmit/art/2026---332-1392028917", first.url)
        assertNotNull(first.thumbnail_url)
    }

    @Test
    fun testPaywalledDeviationFeedHandling() {
        val paywalledJson = """
            {
              "deviations": [
                {
                  "deviationId": 99999,
                  "title": "Exclusive Tier Artwork",
                  "url": "https://www.deviantart.com/artist/art/exclusive-99999",
                  "isLocked": true,
                  "author": { "username": "artist" }
                },
                {
                  "deviationId": 88888,
                  "title": "Free Art",
                  "url": "https://www.deviantart.com/artist/art/free-88888",
                  "isLocked": false,
                  "author": { "username": "artist" }
                }
              ]
            }
        """.trimIndent()

        val client = OkHttpClient()
        val api = DeviantArtApi(client)
        val feeds = Feeds(client, "https://www.deviantart.com", api, mangaFactory = { TestSManga() })

        // When hidePaywalled = false: both show, locked has 🔒 and genre = "Artwork, Paywalled"
        val (allMangas, _) = feeds.parseNetworkbarJsonWithCursor(paywalledJson, hidePaywalled = false)
        assertEquals(2, allMangas.size)
        val locked = allMangas.first { it.url.contains("exclusive") }
        assertEquals("🔒 Exclusive Tier Artwork", locked.title)
        assertEquals("Artwork, Paywalled", locked.genre)

        val free = allMangas.first { it.url.contains("free") }
        assertEquals("Free Art", free.title)
        assertEquals("Artwork", free.genre)

        // When hidePaywalled = true: locked is excluded
        val (filteredMangas, _) = feeds.parseNetworkbarJsonWithCursor(paywalledJson, hidePaywalled = true)
        assertEquals(1, filteredMangas.size)
        assertEquals("Free Art", filteredMangas[0].title)
    }
}
