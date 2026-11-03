package eu.kanade.tachiyomi.extension.all.deviantart

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException

class DescriptionInterceptor(
    private val fetchFallback: (devId: String) -> ArtworkDescription?,
) : Interceptor {

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

        val pngBytes = try {
            DescriptionRenderer.renderPage(desc, pageNum, maxPages = 3)
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
