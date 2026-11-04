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

    @Test
    fun testExtractVideoUrls() {
        val api = DeviantArtApi(OkHttpClient())
        val html = """
            <!DOCTYPE html>
            <html>
            <script>
            window.__INITIAL_STATE__ = {
                "deviationId": 1249044758,
                "type": "film",
                "isVideo": true,
                "media": {
                    "types": [
                        {"t": "video", "h": 360, "q": "360p", "b": "https:\/\/wixmp-abc.wixmp.com\/v\/mp4\/test.VideoQualities.res_360p.mp4"},
                        {"t": "video", "h": 720, "q": "720p", "b": "https:\/\/wixmp-abc.wixmp.com\/v\/mp4\/test.VideoQualities.res_720p.mp4"},
                        {"t": "video", "h": 1080, "q": "1080p", "b": "https:\/\/wixmp-abc.wixmp.com\/v\/mp4\/test.VideoQualities.res_1080p.mp4"}
                    ]
                }
            };
            </script>
            </html>
        """.trimIndent()

        val videos = api.extractVideoUrls(html, "1249044758")
        assertEquals(3, videos.size)

        val best = api.pickBestVideoUrl(videos, QualityMode.BEST_AVAILABLE)
        assertEquals("https://wixmp-abc.wixmp.com/v/mp4/test.VideoQualities.res_1080p.mp4", best)

        val std = api.pickBestVideoUrl(videos, QualityMode.STANDARD)
        assertEquals("https://wixmp-abc.wixmp.com/v/mp4/test.VideoQualities.res_720p.mp4", std)
    }

    @Test
    fun testExtractDeviationMediaFromRealHtml() {
        val file = java.io.File("../../../files/power_nap.html")
        if (!file.exists()) return

        val html = file.readText()
        val api = DeviantArtApi(OkHttpClient())
        val (media, additional) = api.extractDeviationMediaFromHtml(html, "1386561425")
        org.junit.Assert.assertNotNull(media)
        assertEquals("https://images-wixmp-ed30a86b8c4ca887773594c2.wixmp.com/f/bf0887a4-d807-413f-b5e8-8ccf17b46e46/dmxitn5-e8796898-78a9-42b1-9909-b0696f6e36ba.jpg", media!!.baseUri)
        val fv = media.types.firstOrNull { it.t == "fullview" }
        org.junit.Assert.assertNotNull(fv)

        val fullUrl = api.fullviewUrl(media, raiseQuality = true)
        org.junit.Assert.assertNotNull(fullUrl)
        org.junit.Assert.assertTrue(fullUrl!!.contains("token="))
        org.junit.Assert.assertTrue(fullUrl.contains("q_100"))
    }

    @Test
    fun testExtractAdditionalMediaFromMultiPageArtwork() {
        val api = DeviantArtApi(OkHttpClient())
        val html = """
            <!DOCTYPE html>
            <html>
            <script>
            window.__INITIAL_STATE__ = JSON.parse("{\"@@entities\":{\"deviation\":{\"999\":{\"deviationId\":999,\"media\":{\"baseUri\":\"https:\\/\\/images-wixmp.com\\/f\\/page1.jpg\",\"prettyName\":\"p1\",\"token\":[\"tok1\"],\"types\":[{\"t\":\"fullview\",\"c\":\"\\/v1\\/fill\\/w_1200,h_800,q_75\\/<prettyName>.jpg\"}]},\"additionalMedia\":[{\"baseUri\":\"https:\\/\\/images-wixmp.com\\/f\\/page2.jpg\",\"prettyName\":\"p2\",\"token\":[\"tok2\"],\"types\":[{\"t\":\"fullview\",\"c\":\"\\/v1\\/fill\\/w_1200,h_800,q_75\\/<prettyName>.jpg\"}],\"isVideo\":false},{\"baseUri\":\"https:\\/\\/images-wixmp.com\\/f\\/page3.jpg\",\"prettyName\":\"p3\",\"token\":[\"tok3\"],\"types\":[{\"t\":\"fullview\",\"c\":\"\\/v1\\/fill\\/w_1200,h_800,q_75\\/<prettyName>.jpg\"}],\"isVideo\":false}]}}}}");
            </script>
            </html>
        """.trimIndent()

        val (primary, additional) = api.extractDeviationMediaFromHtml(html, "999")
        org.junit.Assert.assertNotNull(primary)
        assertEquals("https://images-wixmp.com/f/page1.jpg", primary!!.baseUri)
        assertEquals(2, additional.size)
        assertEquals("https://images-wixmp.com/f/page2.jpg", additional[0].baseUri)
        assertEquals("https://images-wixmp.com/f/page3.jpg", additional[1].baseUri)

        val p1Url = api.fullviewUrl(primary, raiseQuality = true)
        val p2Url = api.fullviewUrl(additional[0], raiseQuality = true)
        val p3Url = api.fullviewUrl(additional[1], raiseQuality = true)
        org.junit.Assert.assertTrue(p1Url!!.contains("page1.jpg/v1/fill/w_1200,h_800,q_100/p1.jpg?token=tok1"))
        org.junit.Assert.assertTrue(p2Url!!.contains("page2.jpg/v1/fill/w_1200,h_800,q_100/p2.jpg?token=tok2"))
        org.junit.Assert.assertTrue(p3Url!!.contains("page3.jpg/v1/fill/w_1200,h_800,q_100/p3.jpg?token=tok3"))
    }

    @Test
    fun testCleanDescriptionHtmlPreservesFormattingAndImages() {
        val html = """
            <div data-editor-viewer="1">
                <p>Hello <b>World</b> and <i>Welcome</i>!</p>
                <p><img src="https://example.com/banner.png"/></p>
                <p>Art by <a href="https://example.com/artist">Author</a></p>
            </div>
        """.trimIndent()

        val cleaned = cleanDescriptionHtml(html)
        org.junit.Assert.assertTrue(cleaned.contains("<b>World</b>"))
        org.junit.Assert.assertTrue(cleaned.contains("<i>Welcome</i>"))
        org.junit.Assert.assertTrue(cleaned.contains("<img src=\"https://example.com/banner.png\""))
        org.junit.Assert.assertTrue(cleaned.contains("Author"))
        org.junit.Assert.assertTrue(cleaned.contains("#0570e6"))
    }

    @Test
    fun testExtractAdditionalMediaFromDeviationExtended() {
        val api = DeviantArtApi(OkHttpClient())
        // DeviantArt puts primary in deviation and additionalMedia in deviationExtended!
        val html = """
            <!DOCTYPE html>
            <html>
            <script>
            window.__INITIAL_STATE__ = JSON.parse("{\"@@entities\":{\"deviation\":{\"1258128980\":{\"deviationId\":1258128980,\"media\":{\"baseUri\":\"https:\\/\\/images-wixmp.com\\/f\\/page0.png\",\"prettyName\":\"p0\",\"token\":[\"tok0\"],\"types\":[{\"t\":\"fullview\",\"c\":\"\\/v1\\/fill\\/<prettyName>.png\"}]}}},\"deviationExtended\":{\"1258128980\":{\"deviationId\":1258128980,\"additionalMedia\":[{\"position\":1,\"media\":{\"baseUri\":\"https:\\/\\/images-wixmp.com\\/f\\/page1.png\",\"prettyName\":\"p1\",\"token\":[\"tok1\"],\"types\":[{\"t\":\"fullview\",\"c\":\"\\/v1\\/fill\\/<prettyName>.png\"}]}},{\"position\":2,\"media\":{\"baseUri\":\"https:\\/\\/images-wixmp.com\\/f\\/page2.png\",\"prettyName\":\"p2\",\"token\":[\"tok2\"],\"types\":[{\"t\":\"fullview\",\"c\":\"\\/v1\\/fill\\/<prettyName>.png\"}]}}]}}}}");
            </script>
            </html>
        """.trimIndent()

        val (primary, additional) = api.extractDeviationMediaFromHtml(html, "1258128980")
        org.junit.Assert.assertNotNull(primary)
        assertEquals("https://images-wixmp.com/f/page0.png", primary!!.baseUri)
        assertEquals(2, additional.size)
        assertEquals("https://images-wixmp.com/f/page1.png", additional[0].baseUri)
        assertEquals("https://images-wixmp.com/f/page2.png", additional[1].baseUri)
    }

    @Test
    fun testCleanDescriptionHtmlHoistsImagesInsideLinks() {
        val html = """
            <p>Check out my art: <a href="https://www.deviantart.com/art/123"><img src="https://example.com/art.png" alt="Art"/></a></p>
        """.trimIndent()

        val cleaned = cleanDescriptionHtml(html)
        org.junit.Assert.assertTrue(cleaned.contains("<img src=\"https://example.com/art.png\""))
    }

    @Test
    fun testIsPaywalledDetection() {
        val api = DeviantArtApi(OkHttpClient())

        // 1. Paywalled: tier locked
        val tierLocked = json.parseToJsonElement("""{"isLocked": true, "tier": {"tierId": 123}}""").let {
            api.isPaywalled(it as kotlinx.serialization.json.JsonObject)
        }
        org.junit.Assert.assertTrue(tierLocked)

        // 2. Paywalled: premium folder
        val premiumFolderLocked = json.parseToJsonElement("""{"premiumFolderData": {"type": "premium"}}""").let {
            api.isPaywalled(it as kotlinx.serialization.json.JsonObject)
        }
        org.junit.Assert.assertTrue(premiumFolderLocked)

        // 3. Subscriber paywall: isTier = true or tierId
        val tierSubscriberLocked = json.parseToJsonElement("""{"isTier": true, "tierId": "tier_99"}""").let {
            api.isPaywalled(it as kotlinx.serialization.json.JsonObject)
        }
        org.junit.Assert.assertTrue(tierSubscriberLocked)

        // 4. Subscriber paywall: hasTierAccess = false
        val noTierAccess = json.parseToJsonElement("""{"hasTierAccess": false}""").let {
            api.isPaywalled(it as kotlinx.serialization.json.JsonObject)
        }
        org.junit.Assert.assertTrue(noTierAccess)

        // 5. Exclusive / Supporter-only / Early access
        val exclusive = json.parseToJsonElement("""{"isExclusive": true}""").let {
            api.isPaywalled(it as kotlinx.serialization.json.JsonObject)
        }
        org.junit.Assert.assertTrue(exclusive)

        val supporterOnly = json.parseToJsonElement("""{"isSupporterOnly": true}""").let {
            api.isPaywalled(it as kotlinx.serialization.json.JsonObject)
        }
        org.junit.Assert.assertTrue(supporterOnly)

        val earlyAccess = json.parseToJsonElement("""{"isEarlyAccess": true}""").let {
            api.isPaywalled(it as kotlinx.serialization.json.JsonObject)
        }
        org.junit.Assert.assertTrue(earlyAccess)

        val subscriberAccess = json.parseToJsonElement("""{"access": "subscribers"}""").let {
            api.isPaywalled(it as kotlinx.serialization.json.JsonObject)
        }
        org.junit.Assert.assertTrue(subscriberAccess)

        // 6. Block reasons containing tier / sub / supporter
        val blockReasonLocked = json.parseToJsonElement("""{"blockReasons": ["supporter_only_tier"]}""").let {
            api.isPaywalled(it as kotlinx.serialization.json.JsonObject)
        }
        org.junit.Assert.assertTrue(blockReasonLocked)

        // 7. Purchasable for download/print BUT has public fullview preview -> NOT paywalled!
        val purchasableWithFullview = json.parseToJsonElement("""
            {
                "isPurchasable": true,
                "media": {
                    "types": [{"t": "fullview", "c": "/test.png"}]
                }
            }
        """.trimIndent()).let {
            api.isPaywalled(it as kotlinx.serialization.json.JsonObject)
        }
        org.junit.Assert.assertFalse(purchasableWithFullview)
    }
}
