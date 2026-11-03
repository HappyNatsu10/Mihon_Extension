package eu.kanade.tachiyomi.extension.all.pixiv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class UgoiraTest {

    @Test
    fun testUgoiraUrlGeneration() {
        val url = UgoiraInterceptor.createUrl("44298467")
        assertEquals("http://tachiyomi-pixiv-ugoira.internal/44298467.gif", url)
    }

    @Test
    fun testUgoiraMetaStore() {
        val meta = PixivUgoiraMeta(
            src = "https://i.pximg.net/img-zip-ugoira/test.zip",
            frames = listOf(
                PixivUgoiraFrame("000000.jpg", 125),
                PixivUgoiraFrame("000001.jpg", 125),
            ),
        )
        UgoiraMetaStore.put("44298467", meta)
        val retrieved = UgoiraMetaStore.get("44298467")
        assertNotNull(retrieved)
        assertEquals("https://i.pximg.net/img-zip-ugoira/test.zip", retrieved?.src)
        assertEquals(2, retrieved?.frames?.size)
    }

    @Test
    fun testAnimatedGifEncoderProducesValidGif() {
        val encoder = AnimatedGifEncoder()
        val out = ByteArrayOutputStream()

        val started = encoder.start(out)
        assertTrue("Encoder should start", started)

        encoder.setRepeat(0)
        encoder.setDelay(100)

        // Frame 1: 50x50 Red pixels (0xFFFF0000)
        val frame1 = IntArray(50 * 50) { -0x10000 }
        val added = encoder.addFrame(frame1, 50, 50)
        assertTrue("Frame 1 should be added", added)

        // Frame 2: 50x50 Blue pixels (0xFF0000FF)
        val frame2 = IntArray(50 * 50) { -0xffff01 }
        assertTrue("Frame 2 should be added", encoder.addFrame(frame2, 50, 50))

        assertTrue(encoder.finish())

        val bytes = out.toByteArray()
        assertTrue("GIF should have non-empty bytes", bytes.isNotEmpty())

        // Header check: GIF89a
        val header = String(bytes.sliceArray(0..5))
        assertEquals("GIF89a", header)

        // Trailer check: 0x3B (';')
        assertEquals(0x3B.toByte(), bytes.last())
    }
}
