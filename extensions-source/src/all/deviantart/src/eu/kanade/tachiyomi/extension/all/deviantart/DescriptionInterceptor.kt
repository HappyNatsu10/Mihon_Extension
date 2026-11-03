package eu.kanade.tachiyomi.extension.all.deviantart

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.util.Collections
import java.util.LinkedHashMap

class DescriptionInterceptor(
    private val clientProvider: () -> OkHttpClient,
    private val fetchFallback: (devId: String) -> ArtworkDescription?,
    private val maxPagesProvider: () -> Int = { 10 },
) : Interceptor {

    private val imageBytesCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, ByteArray>(30, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean = size > 30
        },
    )

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url

        if (url.host != "da-description.invalid") {
            return chain.proceed(request)
        }

        // Format: https://da-description.invalid/{deviationId}/{hash}/{n}.png
        val segments = url.pathSegments
        if (segments.size < 3) {
            throw IOException("Invalid description page URL: $url")
        }

        val devId = segments[0]
        val pageFileName = segments[2]
        val pageNum = pageFileName.substringBefore(".").toIntOrNull() ?: 1

        var desc = DescriptionStore.get(devId)
        if (desc == null) {
            desc = fetchFallback(devId)
            if (desc != null) {
                DescriptionStore.put(devId, desc)
            }
        }

        if (desc == null) {
            throw IOException("Could not load the artwork description")
        }

        val fetchImage: (String) -> ByteArray? = { rawUrl ->
            val imgUrl = rawUrl.trim()
            val cached = imageBytesCache[imgUrl]
            if (cached != null) {
                cached
            } else {
                try {
                    val resolvedUrl = when {
                        imgUrl.startsWith("//") -> "https:$imgUrl"
                        imgUrl.startsWith("/") -> "https://www.deviantart.com$imgUrl"
                        else -> imgUrl
                    }
                    if (resolvedUrl.startsWith("data:")) {
                        val base64 = resolvedUrl.substringAfter("base64,")
                        val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                        imageBytesCache[imgUrl] = bytes
                        bytes
                    } else {
                        val imgReq = Request.Builder()
                            .url(resolvedUrl)
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                            .header("Referer", "https://www.deviantart.com/")
                            .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                            .build()
                        val imgResp = clientProvider().newCall(imgReq).execute()
                        if (imgResp.isSuccessful) {
                            val bytes = imgResp.body.bytes()
                            imageBytesCache[imgUrl] = bytes
                            bytes
                        } else {
                            imgResp.close()
                            null
                        }
                    }
                } catch (_: Exception) {
                    null
                }
            }
        }

        val maxPages = maxPagesProvider()
        val pngBytes = try {
            DescriptionRenderer.renderPage(desc, pageNum, maxPages = maxPages, fetchImage = fetchImage)
        } catch (e: Exception) {
            throw IOException("Could not load the artwork description", e)
        }

        val body = pngBytes.toResponseBody("image/png".toMediaType())
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body)
            .build()
    }
}
