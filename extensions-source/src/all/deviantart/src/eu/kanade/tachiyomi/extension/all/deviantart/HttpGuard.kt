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

    /**
     * Executes an HTTP request with retries for 429/403/5xx errors using exponential backoff.
     */
    fun executeWithRetry(
        endpointLabel: String,
        debugLog: Boolean = false,
        maxRetries: Int = 3,
        callSupplier: () -> Response,
    ): Response {
        var attempt = 0
        var lastResponse: Response? = null

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

            lastResponse = response
            val code = response.code

            // Only retry 403, 429, 5xx
            if (code == 429 || code == 403 || code in 500..599) {
                val retryAfter = response.header("Retry-After")?.toLongOrNull() ?: (attempt * 1L)
                if (debugLog) {
                    Log.w(TAG, "[$endpointLabel] HTTP $code (attempt $attempt), backing off ${retryAfter}s")
                }
                response.close()
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(retryAfter * 1000L)
                    } catch (_: InterruptedException) {
                        break
                    }
                    continue
                }
            } else {
                // Non-retriable code (e.g. 404)
                break
            }
        }

        val resp = lastResponse ?: throw IOException("Failed to execute request at $endpointLabel")
        val bodySnippet = try {
            resp.peekBody(200).string().replace(TOKEN_REGEX, "token=[REDACTED]")
        } catch (_: Exception) {
            ""
        }
        val message = classifyError(resp.code, bodySnippet, endpointLabel)
        resp.close()
        throw IOException(message)
    }

    private val TOKEN_REGEX = Regex("""(?i)(?:token|auth|key)=[^&"'\s]+""")
}
