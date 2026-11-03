package eu.kanade.tachiyomi.extension.all.deviantart

import android.util.Log
import okhttp3.Response
import java.io.IOException

object HttpGuard {

    private const val TAG = "DeviantArtHttpGuard"

    fun classifyError(code: Int, bodySnippet: String, endpointLabel: String): String {
        val lowerBody = bodySnippet.lowercase()

        if (lowerBody.contains("just a moment") || lowerBody.contains("cf-mitigated") || lowerBody.contains("cloudflare")) {
            return "DeviantArt asked for a browser check. Open the source in WebView (globe icon), let it load, then retry."
        }

        if (code == 429 || (code == 403 && lowerBody.contains("rate limit"))) {
            return "DeviantArt rate limit reached at $endpointLabel. Please wait a moment."
        }

        if (code == 403) {
            return when {
                lowerBody.contains("token") || endpointLabel.contains("image", ignoreCase = true) ->
                    "Image token expired or invalid at $endpointLabel."
                lowerBody.contains("mature") || lowerBody.contains("agegate") ->
                    "This is mature-restricted. Log in via WebView and enable mature content in your DeviantArt settings."
                lowerBody.contains("private") || lowerBody.contains("restricted") ->
                    "This gallery is private or restricted."
                endpointLabel.contains("folder", ignoreCase = true) ->
                    "Gallery folder not found or invalid folder id."
                lowerBody.contains("session") || lowerBody.contains("unauthorized") ->
                    "Your DeviantArt session looks invalid. Log out and back in via WebView."
                else ->
                    "DeviantArt returned 403 (unknown cause) at $endpointLabel."
            }
        }

        if (code == 404) {
            return "Gallery not found or unavailable"
        }

        return "DeviantArt returned HTTP $code at $endpointLabel."
    }

    private val HINT_HEADERS = listOf("server", "via", "x-cache", "x-amz-cf-id", "cf-ray", "cf-mitigated", "retry-after")

    /**
     * Executes an HTTP request with retries for 429/5xx (and ONE retry for 403) using backoff.
     * The response body and CDN hint headers are read BEFORE the response is closed.
     */
    fun executeWithRetry(
        endpointLabel: String,
        debugLog: Boolean = false,
        maxRetries: Int = 3,
        callSupplier: () -> Response,
    ): Response {
        var attempt = 0
        var lastCode = 0
        var lastSnippet = ""
        var lastHints = ""

        while (attempt < maxRetries) {
            attempt++
            val response = try {
                callSupplier()
            } catch (e: IOException) {
                if (attempt >= maxRetries) throw e
                try {
                    Thread.sleep(attempt * 1000L)
                } catch (_: InterruptedException) {
                    throw e
                }
                continue
            }

            if (response.isSuccessful) {
                if (debugLog) {
                    Log.d(TAG, "[$endpointLabel] HTTP ${response.code} (attempt $attempt)")
                }
                return response
            }

            lastCode = response.code
            lastSnippet = try {
                response.peekBody(2048).string()
            } catch (_: Exception) {
                ""
            }
            lastHints = HINT_HEADERS.mapNotNull { name -> response.header(name)?.let { "$name=$it" } }.joinToString(", ")
            val retryAfter = response.header("Retry-After")?.toLongOrNull() ?: (attempt * 2L)
            response.close()

            // A 403 from a CDN/WAF is rarely transient: retrying it repeatedly only makes the block worse.
            val retryable = lastCode == 429 || lastCode in 500..599 || (lastCode == 403 && attempt < 2)
            if (debugLog) {
                Log.w(TAG, "[$endpointLabel] HTTP $lastCode (attempt $attempt) hints=[$lastHints] retry=$retryable")
            }
            if (!retryable || attempt >= maxRetries) break
            try {
                Thread.sleep(retryAfter * 1000L)
            } catch (_: InterruptedException) {
                break
            }
        }

        if (lastCode == 0) throw IOException("Failed to execute request at $endpointLabel")
        val redacted = lastSnippet.replace(TOKEN_REGEX, "token=[REDACTED]")
        var message = classifyError(lastCode, redacted, endpointLabel)
        if (lastCode == 403 && message.contains("unknown cause") && lastHints.isNotEmpty()) {
            message += " ($lastHints)"
        }
        throw IOException(message)
    }

    private val TOKEN_REGEX = Regex("""(?i)(?:token|auth|key)=[^&"'\s]+""")
}
