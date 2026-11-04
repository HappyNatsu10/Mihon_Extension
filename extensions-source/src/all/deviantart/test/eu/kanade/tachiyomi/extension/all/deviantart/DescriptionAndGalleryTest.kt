package eu.kanade.tachiyomi.extension.all.deviantart

import eu.kanade.tachiyomi.source.model.SChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DescriptionAndGalleryTest {

    private class TestChapter : SChapter {
        override var url: String = ""
        override var name: String = ""
        override var date_upload: Long = 0L
        override var chapter_number: Float = 0f
        override var scanlator: String? = null
        override var memo: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap())
    }

    @Test
    fun testDescriptionToTextCleaning() {
        val html = """
            <div data-editor-viewer="1">
                <p>My six hundred and ninetieth caption. A quick office snooze leads to some blackmail for the new &#x27;girl&#x27;...</p>
                <p class="empty-p"><br/></p>
                <p>Art by <a href="https://example.com/artist">spammayoss</a> via Danbooru</p>
                <p>:iconblubyrdy: :devsomeone: :thumb12345:</p>
            </div>
        """.trimIndent()

        val text = descriptionToText(html)
        assertTrue(text.contains("My six hundred and ninetieth caption."))
        assertTrue(text.contains("new 'girl'..."))
        assertTrue(text.contains("Art by spammayoss via Danbooru"))
        assertFalse(text.contains("https://example.com/artist"))
        assertFalse(text.contains(":icon"))
        assertFalse(text.contains(":dev"))
        assertFalse(text.contains(":thumb"))
    }

    @Test
    fun testDescriptionToTextStripsRawJsonDumps() {
        // Raw ProseMirror AST dumps or corrupted JSON must return empty string
        val ast1 = """{"type":"doc","content":[{"type":"paragraph"}]}"""
        assertEquals("", descriptionToText(ast1))

        val ast2 = """{\\"type\":\"doc\"}"""
        assertEquals("", descriptionToText(ast2))

        val ast3 = "{\\"
        assertEquals("", descriptionToText(ast3))
    }

    @Test
    fun testDescriptionHash() {
        val hash1 = computeDescriptionHash("Some text", "Power Nap", "blubyrdy")
        assertEquals(8, hash1.length)

        val hash2 = computeDescriptionHash("Some text", "Power Nap", "blubyrdy")
        assertEquals(hash1, hash2)

        val hash3 = computeDescriptionHash("Different text", "Power Nap", "blubyrdy")
        assertNotEquals(hash1, hash3)
    }

    @Test
    fun testPaginateLines() {
        assertEquals(emptyList<IntRange>(), DescriptionRenderer.paginateLines(emptyList(), pageHeight = 250, maxPages = 3))

        // 10 lines of 40 px each. With pageHeight 250 and margin 64*2=128, available per page is 122 px -> 3 lines per page.
        // On the 3rd (max) page, 60 px is reserved for the truncation marker (122 - 60 = 62 px -> fits 1 line).
        // 10 lines partition into: page 1 (lines 0..2), page 2 (lines 3..5), page 3 (line 6..6 with truncation reserved).
        val lineHeights = List(10) { 40 }
        val pages = DescriptionRenderer.paginateLines(lineHeights, pageHeight = 250, maxPages = 3, headerHeight = 0)
        assertEquals(3, pages.size)
        assertEquals(0..2, pages[0])
        assertEquals(3..5, pages[1])
        assertEquals(6..6, pages[2])
    }

    @Test
    fun testFullviewUrlWithQ100AndToken() {
        val api = DeviantArtApi(okhttp3.OkHttpClient())
        val media = Media(
            baseUri = "https://images-wixmp.com/f/abc/xyz.jpg",
            prettyName = "sample_art",
            token = listOf("jwt.token.here"),
            types = listOf(
                MediaType(
                    t = "fullview",
                    c = "/v1/fill/w_1280,h_753,q_75,strp,blur_55/<prettyName>-fullview.jpg",
                    w = 1280,
                    h = 753,
                ),
            ),
        )

        val url = api.fullviewUrl(media, raiseQuality = true)
        assertEquals(
            "https://images-wixmp.com/f/abc/xyz.jpg/v1/fill/w_1280,h_753,q_100,strp,blur_55/sample_art-fullview.jpg?token=jwt.token.here",
            url,
        )
    }

    @Test
    fun testFullviewUrlPicksDownloadTokenForLosslessOriginal() {
        val api = DeviantArtApi(okhttp3.OkHttpClient())
        // base64 for {"aud":["urn:service:file.download"]}
        val dlPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("""{"aud":["urn:service:file.download"]}""".toByteArray())
        val dlToken = "header.$dlPayload.sig"
        val opPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("""{"aud":["urn:service:image.operations"],"width":"<=1216"}""".toByteArray())
        val opToken = "header.$opPayload.sig"

        val media = Media(
            baseUri = "https://images-wixmp.com/f/abc/xyz.png",
            prettyName = "sample",
            token = listOf(opToken, dlToken),
            types = listOf(
                MediaType(t = "150", c = "/v1/fit/w_150,h_150/sample-150.jpg", w = 150, h = 150),
            ),
        )

        // With raiseQuality = true, should pick the file.download token and return direct baseUri without /v1/ thumbnail downscale
        val url = api.fullviewUrl(media, raiseQuality = true)
        assertEquals("https://images-wixmp.com/f/abc/xyz.png?token=$dlToken", url)
    }

    @Test
    fun testFullviewUrlPicksLargestTypeWhenFullviewMissing() {
        val api = DeviantArtApi(okhttp3.OkHttpClient())
        val media = Media(
            baseUri = "https://images-wixmp.com/f/abc/xyz.png",
            prettyName = "sample",
            token = listOf("tok"),
            types = listOf(
                MediaType(t = "150", c = "/v1/fit/w_150,h_150/sample-150.jpg", w = 150, h = 150),
                MediaType(t = "375W", c = "/v1/fit/w_375,h_548/sample-375w.jpg", w = 375, h = 548),
                MediaType(t = "1216W", c = "/v1/fit/w_1216,h_832/sample-1216w.jpg", w = 1216, h = 832),
            ),
        )

        val url = api.fullviewUrl(media, raiseQuality = true)
        // Must NOT pick 150! Must pick the largest resolution (1216W)
        assertTrue(url != null && url.contains("w_1216,h_832"))
    }

    @Test
    fun testMergeWithExisting() {
        // (a) Existing 500 chapters and crawl of 60 returns >= 500
        val existing = (1..500).map { i ->
            TestChapter().apply {
                url = "/artist/art/old-piece-$i"
                name = "Old Piece $i"
                date_upload = 1000L * i
            }
        }
        val crawl = (490..550).map { i ->
            CachedItem(
                id = i.toString(),
                title = "Piece $i",
                path = "/artist/art/piece-$i",
                publishedMs = 1000L * i,
            )
        }
        val merged = mergeWithExisting(crawl, existing, sortById = true, chapterFactory = { TestChapter() })
        assertTrue(merged.size >= 500)
        assertEquals(550, merged.size)

        // (b) Crawl of 0 returns exactly the existing list
        val mergedZero = mergeWithExisting(emptyList(), existing, sortById = true, chapterFactory = { TestChapter() })
        assertEquals(500, mergedZero.size)

        // (c) Stored URLs are preserved byte-for-byte
        val existingMap = existing.associateBy { artworkId(it.url) }
        for (ch in merged) {
            val id = artworkId(ch.url)
            val old = existingMap[id]
            if (old != null) {
                assertEquals(old.url, ch.url)
            }
        }

        // (d) Chapter numbers are 1..N from the oldest
        val total = merged.size
        merged.forEachIndexed { index, chapter ->
            assertEquals((total - index).toFloat(), chapter.chapter_number, 0.001f)
        }
    }

    @Test
    fun testDraftJsParsing() {
        val draftJs = """
            {
              "blocks": [
                {
                  "key": "abc",
                  "text": "My six hundred and ninetieth caption. A quick snooze...",
                  "type": "unstyled",
                  "inlineStyleRanges": [
                    {"offset": 0, "length": 2, "style": "BOLD"}
                  ],
                  "entityRanges": []
                }
              ],
              "entityMap": {}
            }
        """.trimIndent()
        val cleaned = cleanDescriptionHtml(draftJs)
        assertTrue(cleaned.contains("<b>My</b> six hundred and ninetieth caption."))
    }

    @Test
    fun testParseMediaUnwrapsNestedMedia() {
        val api = DeviantArtApi(okhttp3.OkHttpClient())
        val jsonStr = """
            {
                "fileId": 12345,
                "media": {
                    "baseUri": "https://images-wixmp.com/f/abc/xyz.jpg",
                    "prettyName": "nested_sample",
                    "token": ["tok1"],
                    "types": [
                        {"t": "fullview", "c": "/v1/fill/<prettyName>.jpg", "w": 1000, "h": 800}
                    ]
                }
            }
        """.trimIndent()
        val el = kotlinx.serialization.json.Json.parseToJsonElement(jsonStr)
        val parsed = api.parseMedia(el)
        org.junit.Assert.assertNotNull(parsed)
        assertEquals("https://images-wixmp.com/f/abc/xyz.jpg", parsed?.baseUri)
        assertEquals("nested_sample", parsed?.prettyName)
    }

    @Test
    fun testExtractInitialStateWithEmbeddedScriptsAndEscapedQuotes() {
        val api = DeviantArtApi(okhttp3.OkHttpClient())
        val html = """
            <html>
            <head><title>Test</title></head>
            <body>
            <script>
            window.__INITIAL_STATE__ = JSON.parse("{\"@@entities\":{\"deviation\":{\"123\":{\"title\":\"Test Art\",\"media\":{\"baseUri\":\"https:\/\/images-wixmp.com\/f\/art.jpg\",\"types\":[{\"t\":\"fullview\",\"c\":\"\/v1\/fill\/art.jpg\"}]}}},\"subScript\":\"createElement(\\\"script\\\");s.src=foo;\",\"quote\":\"blackmail for the new \'girl\'...\"}}");
            window.__URL_CONFIG__ = JSON.parse("{}");
            </script>
            </body>
            </html>
        """.trimIndent()

        val root = api.extractInitialStateJson(html)
        org.junit.Assert.assertNotNull(root)
        val dev = root?.get("@@entities")?.let { it as? kotlinx.serialization.json.JsonObject }
            ?.get("deviation")?.let { it as? kotlinx.serialization.json.JsonObject }
            ?.get("123")?.let { it as? kotlinx.serialization.json.JsonObject }
        org.junit.Assert.assertNotNull(dev)
        assertEquals("Test Art", dev?.get("title")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content })

        val (primary, _) = api.extractDeviationMediaFromHtml(html, "123")
        org.junit.Assert.assertNotNull(primary)
        assertEquals("https://images-wixmp.com/f/art.jpg", primary?.baseUri)
    }

    @Test
    fun testTipTapParsingWithFormattingAndLinks() {
        val tiptapJson = """
            {
              "type": "doc",
              "content": [
                {
                  "type": "paragraph",
                  "content": [
                    {"type": "text", "text": "Hello "},
                    {"type": "text", "marks": [{"type": "bold"}], "text": "World"},
                    {"type": "text", "text": "! Visit "},
                    {"type": "text", "marks": [{"type": "link", "attrs": {"href": "https://deviantart.com"}}], "text": "DeviantArt"}
                  ]
                }
              ]
            }
        """.trimIndent()
        val cleaned = cleanDescriptionHtml(tiptapJson)
        assertTrue(cleaned.contains("<b>World</b>"))
        assertTrue(cleaned.contains("<font color=\"#0570e6\"><u>DeviantArt</u></font>"))
    }

    @Test
    fun testMergeWithExistingPaywalledIndicatorAndScanlator() {
        val crawled = listOf(
            CachedItem(id = "1", title = "Free Artwork", path = "/art/free-1", publishedMs = 1000L, isPaywalled = false),
            CachedItem(id = "2", title = "Paid Artwork", path = "/art/paid-2", publishedMs = 2000L, isPaywalled = true),
        )
        val chapters = mergeWithExisting(crawled, emptyList(), sortById = false) { TestChapter() }
        assertEquals(2, chapters.size)

        val freeChap = chapters.first { it.url == "/art/free-1" }
        assertEquals("Free Artwork", freeChap.name)
        assertEquals(null, freeChap.scanlator)

        val paidChap = chapters.first { it.url == "/art/paid-2" }
        assertEquals("🔒 Paid Artwork", paidChap.name)
        assertEquals("Paywalled", paidChap.scanlator)
    }

    @Test
    fun testTipTapDaDeviationAndMentionFormatting() {
        val tiptapJson = """
            {
                "version": "1",
                "document": {
                    "type": "doc",
                    "content": [
                        {
                            "type": "paragraph",
                            "attrs": { "textAlign": "center" },
                            "content": [
                                { "type": "text", "text": "Artwork by " },
                                {
                                    "type": "da-mention",
                                    "attrs": {
                                        "user": {
                                            "username": "BeaNat"
                                        }
                                    }
                                }
                            ]
                        },
                        {
                            "type": "da-deviation",
                            "attrs": {
                                "deviation": {
                                    "media": {
                                        "baseUri": "https://images-wixmp.com/f/test/model.jpg",
                                        "prettyName": "model_test",
                                        "token": ["dl_token_123"],
                                        "types": [
                                            { "t": "preview", "c": "/v1/fill/w_692,h_1154/test-pre.jpg" },
                                            { "t": "fullview", "c": "/v1/fill/w_1152,h_1920/test-full.jpg" }
                                        ]
                                    }
                                },
                                "alignment": "center"
                            }
                        },
                        {
                            "type": "horizontalRule"
                        }
                    ]
                }
            }
        """.trimIndent()

        val cleaned = cleanDescriptionHtml(tiptapJson)
        assertTrue(cleaned.contains("<font color=\"#0570e6\"><u>@BeaNat</u></font>"))
        assertTrue(cleaned.contains("<img src=\"https://images-wixmp.com/f/test/model.jpg?token=dl_token_123\""))
        assertTrue(cleaned.contains("<hr>"))
    }
}
