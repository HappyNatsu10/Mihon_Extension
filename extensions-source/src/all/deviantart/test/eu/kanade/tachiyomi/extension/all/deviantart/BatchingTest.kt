package eu.kanade.tachiyomi.extension.all.deviantart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class BatchingTest {

    @Test
    fun testChapterCountBoundaries() {
        val size = 48
        assertEquals(0, Batching.chapterCount(0, size))
        assertEquals(1, Batching.chapterCount(1, size))
        assertEquals(1, Batching.chapterCount(47, size))
        assertEquals(1, Batching.chapterCount(48, size))
        assertEquals(2, Batching.chapterCount(49, size))
        assertEquals(417, Batching.chapterCount(20000, size)) // 20000 / 48 = 416.666 -> 417
        assertEquals(1042, Batching.chapterCount(50001, size)) // 50001 / 48 = 1041.68 -> 1042
    }

    @Test
    fun testSmallGalleryWindow() {
        val total = 50
        val size = 48
        assertEquals(2, Batching.chapterCount(total, size))

        // Batch 0 (Chapter 1, oldest 48 items: 0 to 48)
        val w0 = Batching.windowFor(0, size, total)
        assertEquals(48, w0.count)
        assertEquals(2, w0.newestOffset) // items at newest offsets [2..50)
        assertEquals("Artworks 1–48", Batching.chapterName(0, size, total))

        // Batch 1 (Chapter 2, newest 2 items: 48 to 50)
        val w1 = Batching.windowFor(1, size, total)
        assertEquals(2, w1.count)
        assertEquals(0, w1.newestOffset) // items at newest offsets [0..2)
        assertEquals("Artworks 49–50", Batching.chapterName(1, size, total))
    }

    @Test
    fun testLargeGalleryWindowNoGaps() {
        val total = 20000
        val size = 48
        val chapters = Batching.chapterCount(total, size)
        assertEquals(417, chapters)

        var totalItemsCovered = 0
        var expectedNewestOffset = 0

        // Check chapters from newest down to oldest
        for (batchIndex in (chapters - 1) downTo 0) {
            val window = Batching.windowFor(batchIndex, size, total)
            assertEquals(expectedNewestOffset, window.newestOffset)
            totalItemsCovered += window.count
            expectedNewestOffset += window.count
        }

        assertEquals(total, totalItemsCovered)
    }

    @Test
    fun testChapterUrlParsing() {
        val url = Batching.chapterUrl("rossdraws", 5, 48)
        assertEquals("/rossdraws/gallery?batch=5&size=48", url)

        val parsed = Batching.parseChapterUrl(url)
        assertNotNull(parsed)
        assertEquals(5, parsed!!.first)
        assertEquals(48, parsed.second)
    }
}
