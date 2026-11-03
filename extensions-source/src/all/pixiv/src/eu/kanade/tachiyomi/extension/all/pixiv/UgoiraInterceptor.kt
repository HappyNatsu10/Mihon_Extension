package eu.kanade.tachiyomi.extension.all.pixiv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import keiyoushi.utils.applicationContext
import keiyoushi.utils.parseAs
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.buffer
import okio.source
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Collections
import java.util.LinkedHashMap
import java.util.zip.ZipInputStream

internal object UgoiraMetaStore {
    private val cache = Collections.synchronizedMap(
        object : LinkedHashMap<String, PixivUgoiraMeta>(50, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PixivUgoiraMeta>?): Boolean = size > 50
        },
    )

    fun get(illustId: String): PixivUgoiraMeta? = cache[illustId]
    fun put(illustId: String, meta: PixivUgoiraMeta) {
        cache[illustId] = meta
    }
}

internal class UgoiraInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        if (url.host != HOST) return chain.proceed(request)

        val illustId = url.pathSegments.firstOrNull()?.removeSuffix(".gif")
            ?: return chain.proceed(request)

        val cacheDir = File(applicationContext.cacheDir, "pixiv_ugoira_cache").apply { mkdirs() }
        val cacheFile = File(cacheDir, "$illustId.gif")

        if (cacheFile.exists() && cacheFile.length() > 0) {
            return buildGifResponse(request, cacheFile)
        }

        val meta = UgoiraMetaStore.get(illustId) ?: fetchMeta(illustId, chain)
            ?: throw IOException("Pixiv Ugoira: failed to fetch metadata for illustration $illustId")

        val zipUrl = meta.src ?: meta.originalSrc
            ?: throw IOException("Pixiv Ugoira: no zip source found for illustration $illustId")

        val zipRequest = Request.Builder()
            .url(zipUrl)
            .header("Referer", "https://www.pixiv.net/")
            .build()

        val zipResponse = chain.proceed(zipRequest)
        if (!zipResponse.isSuccessful) {
            zipResponse.close()
            throw IOException("Pixiv Ugoira: failed to download zip from $zipUrl (HTTP ${zipResponse.code})")
        }

        val tempFile = File.createTempFile("ugoira_$illustId", ".tmp", cacheDir)
        try {
            val framesMap = mutableMapOf<String, ByteArray>()
            ZipInputStream(zipResponse.body.byteStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && (entry.name.endsWith(".jpg") || entry.name.endsWith(".png"))) {
                        framesMap[entry.name] = zis.readBytes()
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            if (framesMap.isEmpty()) {
                throw IOException("Pixiv Ugoira: empty zip archive for $illustId")
            }

            val encoder = AnimatedGifEncoder()
            encoder.setRepeat(0) // loop indefinitely
            encoder.setQuality(10) // good visual quality

            FileOutputStream(tempFile).use { fos ->
                if (!encoder.start(fos)) {
                    throw IOException("Pixiv Ugoira: failed to initialize GIF encoder")
                }

                val frameList = if (meta.frames.isNotEmpty()) {
                    meta.frames
                } else {
                    framesMap.keys.sorted().map { PixivUgoiraFrame(file = it, delay = 125) }
                }

                for (frame in frameList) {
                    val frameBytes = framesMap[frame.file] ?: continue
                    val bitmap = decodeAndScaleBitmap(frameBytes) ?: continue
                    try {
                        encoder.setDelay(frame.delay.coerceAtLeast(10))
                        encoder.addFrame(bitmap)
                    } finally {
                        bitmap.recycle()
                    }
                }

                encoder.finish()
            }

            if (tempFile.exists() && tempFile.length() > 0) {
                if (cacheFile.exists()) cacheFile.delete()
                tempFile.renameTo(cacheFile)
            } else {
                throw IOException("Pixiv Ugoira: failed to encode GIF for $illustId")
            }
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }

        return buildGifResponse(request, cacheFile)
    }

    private fun decodeAndScaleBitmap(bytes: ByteArray): Bitmap? {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)

        val maxDim = 800
        var sampleSize = 1
        var w = boundsOpts.outWidth
        var h = boundsOpts.outHeight
        while (w > maxDim * 2 || h > maxDim * 2) {
            sampleSize *= 2
            w /= 2
            h /= 2
        }

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts) ?: return null

        // If still exceeds maxDim, scale smoothly
        if (decoded.width > maxDim || decoded.height > maxDim) {
            val scale = maxDim.toFloat() / maxOf(decoded.width, decoded.height)
            val targetW = (decoded.width * scale).toInt().coerceAtLeast(1)
            val targetH = (decoded.height * scale).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(decoded, targetW, targetH, true)
            if (scaled != decoded) decoded.recycle()
            return scaled
        }

        return decoded
    }

    private fun fetchMeta(illustId: String, chain: Interceptor.Chain): PixivUgoiraMeta? {
        val metaRequest = Request.Builder()
            .url("https://www.pixiv.net/ajax/illust/$illustId/ugoira_meta")
            .header("Referer", "https://www.pixiv.net/")
            .header("Accept", "application/json")
            .build()

        val resp = chain.proceed(metaRequest)
        if (!resp.isSuccessful) {
            resp.close()
            return null
        }

        return try {
            val apiResp = resp.parseAs<PixivApiResponse>()
            if (apiResp.error || apiResp.body == null) {
                null
            } else {
                apiResp.body.parseAs<PixivUgoiraMeta>()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun buildGifResponse(request: Request, file: File): Response {
        val mediaType = "image/gif".toMediaType()
        val responseBody = file.source().buffer().asResponseBody(mediaType, file.length())
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(responseBody)
            .build()
    }

    companion object {
        const val HOST = "tachiyomi-pixiv-ugoira.internal"

        fun createUrl(illustId: String): String = "http://$HOST/$illustId.gif"
    }
}
