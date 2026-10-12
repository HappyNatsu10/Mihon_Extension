package eu.kanade.tachiyomi.extension.all.deviantart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpGuardTest {

    @Test
    fun testClassifyCloudflareChallenge() {
        val msg = HttpGuard.classifyError(
            code = 403,
            bodySnippet = "<html><body>Just a moment... cf-mitigated: challenge</body></html>",
            endpointLabel = "gallery crawl",
        )
        assertTrue(msg.contains("browser check"))
        assertTrue(msg.contains("WebView"))
    }

    @Test
    fun testClassifyRateLimit() {
        val msg429 = HttpGuard.classifyError(
            code = 429,
            bodySnippet = "Too Many Requests",
            endpointLabel = "gallery crawl",
        )
        assertTrue(msg429.contains("rate limit reached"))

        val msg403 = HttpGuard.classifyError(
            code = 403,
            bodySnippet = "Rate limit exceeded",
            endpointLabel = "gallery crawl",
        )
        assertTrue(msg403.contains("rate limit reached"))
    }

    @Test
    fun testClassifyImageToken403() {
        val msg = HttpGuard.classifyError(
            code = 403,
            bodySnippet = "Access denied: token expired",
            endpointLabel = "image CDN",
        )
        assertTrue(msg.contains("Image token expired or invalid"))
    }

    @Test
    fun testClassifyMatureRestriction() {
        val msg = HttpGuard.classifyError(
            code = 403,
            bodySnippet = "{\"error\": \"mature-restricted content\"}",
            endpointLabel = "artwork fetch",
        )
        assertTrue(msg.contains("mature-restricted"))
        assertTrue(msg.contains("Log in via WebView"))
    }

    @Test
    fun testClassifyFolder403() {
        val msg = HttpGuard.classifyError(
            code = 403,
            bodySnippet = "Forbidden",
            endpointLabel = "gallery folder",
        )
        assertTrue(msg.contains("Gallery folder not found or invalid folder id"))
    }

    @Test
    fun testClassify404() {
        val msg = HttpGuard.classifyError(
            code = 404,
            bodySnippet = "Not Found",
            endpointLabel = "gallery metadata",
        )
        assertEquals("Gallery not found or unavailable", msg)
    }
}
