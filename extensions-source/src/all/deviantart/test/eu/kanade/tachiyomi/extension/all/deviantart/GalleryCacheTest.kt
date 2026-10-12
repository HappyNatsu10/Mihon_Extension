package eu.kanade.tachiyomi.extension.all.deviantart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryCacheTest {

    @Test
    fun testNewItemsFromPage() {
        val known = setOf("id-2", "id-1")

        // Page with 2 new items followed by a known item
        val page1 = listOf(
            CachedItem(id = "id-4", title = "Art 4", path = "/art/4", publishedMs = 400),
            CachedItem(id = "id-3", title = "Art 3", path = "/art/3", publishedMs = 300),
            CachedItem(id = "id-2", title = "Art 2", path = "/art/2", publishedMs = 200),
        )

        val (fresh1, reachedKnown1) = GalleryCache.newItemsFromPage(known, page1)
        assertTrue(reachedKnown1)
        assertEquals(2, fresh1.size)
        assertEquals("id-4", fresh1[0].id)
        assertEquals("id-3", fresh1[1].id)

        // Page with all new items
        val page2 = listOf(
            CachedItem(id = "id-6", title = "Art 6", path = "/art/6", publishedMs = 600),
            CachedItem(id = "id-5", title = "Art 5", path = "/art/5", publishedMs = 500),
        )
        val (fresh2, reachedKnown2) = GalleryCache.newItemsFromPage(known, page2)
        assertFalse(reachedKnown2)
        assertEquals(2, fresh2.size)

        // Page with first item already known
        val page3 = listOf(
            CachedItem(id = "id-2", title = "Art 2", path = "/art/2", publishedMs = 200),
        )
        val (fresh3, reachedKnown3) = GalleryCache.newItemsFromPage(known, page3)
        assertTrue(reachedKnown3)
        assertTrue(fresh3.isEmpty())
    }

    @Test
    fun testChapterNumber() {
        val total = 100
        // Newest item is index 0 -> chapter 100.0
        assertEquals(100.0f, GalleryCache.chapterNumber(0, total))
        // Oldest item is index 99 -> chapter 1.0
        assertEquals(1.0f, GalleryCache.chapterNumber(99, total))
        // Middle item is index 50 -> chapter 50.0
        assertEquals(50.0f, GalleryCache.chapterNumber(50, total))
    }
}
