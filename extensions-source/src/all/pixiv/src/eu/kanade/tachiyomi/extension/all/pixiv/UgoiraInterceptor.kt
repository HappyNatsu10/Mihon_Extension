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

        if (cacheFile.exists()) {
            if (isValidGif(cacheFile)) {
                return buildGifResponse(request, cacheFile)
            } else {
                cacheFile.delete()
            }
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

            val frameList = if (meta.frames.isNotEmpty()) {
                meta.frames
            } else {
                framesMap.keys.sorted().map { PixivUgoiraFrame(file = it, delay = 125) }
            }

            var canvasWidth = 0
            var canvasHeight = 0
            for (f in frameList) {
                val b = framesMap[f.file] ?: continue
                val bmp = decodeAndScaleBitmap(b, 0, 0) ?: continue
                canvasWidth = bmp.width
                canvasHeight = bmp.height
                bmp.recycle()
                break
            }

            if (canvasWidth == 0 || canvasHeight == 0) {
                throw IOException("Pixiv Ugoira: failed to determine canvas dimensions for $illustId")
            }

            // Sample keyframes across animation to compute optimal global palette
            val sampleCount = minOf(6, frameList.size)
            val sampleIndices = if (frameList.size <= 6) {
                frameList.indices.toList()
            } else {
                val step = (frameList.size - 1).toFloat() / (sampleCount - 1).toFloat()
                (0 until sampleCount).map { (it * step).toInt().coerceIn(0, frameList.size - 1) }.distinct()
            }

            val sampledPixels = mutableListOf<Int>()
            for (idx in sampleIndices) {
                val frame = frameList[idx]
                val frameBytes = framesMap[frame.file] ?: continue
                val bmp = decodeAndScaleBitmap(frameBytes, canvasWidth, canvasHeight) ?: continue
                try {
                    val w = bmp.width
                    val h = bmp.height
                    val totalPix = w * h
                    val stride = (totalPix / 15000).coerceAtLeast(1)
                    val pix = IntArray(totalPix)
                    bmp.getPixels(pix, 0, w, 0, 0, w, h)
                    for (p in 0 until totalPix step stride) {
                        sampledPixels.add(pix[p])
                    }
                } finally {
                    bmp.recycle()
                }
            }

            val encoder = AnimatedGifEncoder()
            encoder.setRepeat(0) // loop indefinitely
            encoder.setQuality(2) // high precision palette
            if (sampledPixels.isNotEmpty()) {
                encoder.prepareGlobalPalette(sampledPixels.toIntArray())
            }

            var framesAdded = 0
            FileOutputStream(tempFile).use { fos ->
                if (!encoder.start(fos)) {
                    throw IOException("Pixiv Ugoira: failed to initialize GIF encoder")
                }

                for (frame in frameList) {
                    val frameBytes = framesMap[frame.file] ?: continue
                    val bitmap = decodeAndScaleBitmap(frameBytes, canvasWidth, canvasHeight) ?: continue
                    try {
                        encoder.setDelay(frame.delay.coerceAtLeast(10))
                        val added = encoder.addFrame(bitmap)
                        if (added) {
                            framesAdded++
                        } else {
                            throw IOException("Pixiv Ugoira: failed to encode frame ${frame.file}")
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }

                if (!encoder.finish()) {
                    throw IOException("Pixiv Ugoira: failed to finalize GIF trailer")
                }
            }

            if (framesAdded == 0 || !isValidGif(tempFile)) {
                throw IOException("Pixiv Ugoira: generated GIF was incomplete or corrupt for $illustId")
            }

            if (cacheFile.exists()) cacheFile.delete()
            if (!tempFile.renameTo(cacheFile)) {
                tempFile.copyTo(cacheFile, overwrite = true)
                tempFile.delete()
            }
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }

        return buildGifResponse(request, cacheFile)
    }

    private fun isValidGif(file: File): Boolean {
        if (!file.exists() || file.length() < 100) return false
        return try {
            java.io.RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < 100) return false
                val header = ByteArray(6)
                raf.readFully(header)
                val headerStr = String(header)
                if (headerStr != "GIF89a" && headerStr != "GIF87a") return false
                raf.seek(raf.length() - 1)
                raf.readByte() == 0x3B.toByte()
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun decodeAndScaleBitmap(bytes: ByteArray, targetW: Int = 0, targetH: Int = 0): Bitmap? {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
        if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return null

        val maxDim = 1080
        var sampleSize = 1
        var w = boundsOpts.outWidth
        var h = boundsOpts.outHeight
        val desiredW = if (targetW > 0) targetW else maxDim
        val desiredH = if (targetH > 0) targetH else maxDim
        while (w > desiredW * 2 || h > desiredH * 2) {
            sampleSize *= 2
            w /= 2
            h /= 2
        }

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts) ?: return null

        val finalW: Int
        val finalH: Int
        if (targetW > 0 && targetH > 0) {
            finalW = targetW
            finalH = targetH
        } else if (decoded.width > maxDim || decoded.height > maxDim) {
            val scale = maxDim.toFloat() / maxOf(decoded.width, decoded.height)
            finalW = (decoded.width * scale).toInt().coerceAtLeast(1)
            finalH = (decoded.height * scale).toInt().coerceAtLeast(1)
        } else {
            finalW = decoded.width
            finalH = decoded.height
        }

        if (decoded.width != finalW || decoded.height != finalH) {
            val scaled = Bitmap.createScaledBitmap(decoded, finalW, finalH, true)
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
        val fileLength = file.length()
        val responseBody = file.source().buffer().asResponseBody(mediaType, fileLength)
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("Content-Type", "image/gif")
            .header("Content-Length", fileLength.toString())
            .header("Cache-Control", "no-transform")
            .body(responseBody)
            .build()
    }

    companion object {
        const val HOST = "tachiyomi-pixiv-ugoira.internal"

        fun createUrl(illustId: String): String = "http://$HOST/$illustId.gif"
    }
}
