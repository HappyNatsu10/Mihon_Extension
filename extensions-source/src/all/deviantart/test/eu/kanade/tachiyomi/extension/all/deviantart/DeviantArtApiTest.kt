package eu.kanade.tachiyomi.extension.all.deviantart

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviantArtApiTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Test
    fun testOEmbedDtoParsing() {
        val jsonString = """
            {
              "version": "1.0",
              "type": "photo",
              "title": "LAST CHANCE Shop Event !",
              "author_name": "rossdraws",
              "author_url": "https://www.deviantart.com/rossdraws",
              "thumbnail_url": "https://images-wixmp-ed30a86b8c4ca887773594c2.wixmp.com/test.jpg"
            }
        """.trimIndent()

        val dto = json.decodeFromString<OEmbedDto>(jsonString)
        assertEquals("rossdraws", dto.authorName)
        assertEquals("LAST CHANCE Shop Event !", dto.title)
    }

    @Test
    fun testExtractTotalFromHtmlJsonLd() {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <script type="application/ld+json">
                {
                  "@context": "https://schema.org",
                  "@graph": [
                    {
                      "@type": "CollectionPage",
                      "mainEntity": {
                        "@type": "Collection",
                        "numberOfItems": 535
                      }
                    }
                  ]
                }
                </script>
            </head>
            <body></body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val api = DeviantArtApi(OkHttpClient())
        val total = api.extractTotalFromHtml(doc)
        assertEquals(535, total)
    }

    @Test
    fun testExtractTotalFromMetaFallback() {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="description" content="Browse rossdraws's artwork on DeviantArt. 26,842 works of art across all galleries."/>
            </head>
            <body></body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val api = DeviantArtApi(OkHttpClient())
        val total = api.extractTotalFromHtml(doc)
        assertEquals(26842, total)
    }

    @Test
    fun testIsArtworkUrl() {
        val api = DeviantArtApi(OkHttpClient())
        // True artwork media
        org.junit.Assert.assertTrue(api.isArtworkUrl("https://images-wixmp-ed30a86b8c4ca887773594c2.wixmp.com/f/abc/test.jpg"))
        org.junit.Assert.assertTrue(api.isArtworkUrl("https://img00.deviantart.net/fs70/i/2015/012/3/4/sample.png"))

        // False: site placeholders, logos, avatars, download redirects
        org.junit.Assert.assertFalse(api.isArtworkUrl("https://st.deviantart.net/minish/touch-icons/apple-touch-icon-180.png"))
        org.junit.Assert.assertFalse(api.isArtworkUrl("https://st.deviantart.net/emoticons/smile.gif"))
        org.junit.Assert.assertFalse(api.isArtworkUrl("https://www.deviantart.com/download/12345/test.zip"))
        org.junit.Assert.assertFalse(api.isArtworkUrl("https://a.deviantart.net/avatars/u/s/user.jpg"))
    }
}
