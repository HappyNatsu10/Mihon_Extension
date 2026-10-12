package eu.kanade.tachiyomi.extension.all.deviantart

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File

class MihonSyncSimulatorTest {

    private lateinit var tempDir: File

    data class DbChapter(
        val url: String,
        val chapterNumber: Float,
        var isRead: Boolean,
    )

    data class SyncResult(
        val removedChapters: List<DbChapter>,
        val addedChapters: List<CachedItem>,
        val lostReadChapters: List<DbChapter>,
    )

    private fun simulateMihonSync(
        dbChapters: List<DbChapter>,
        returnedChapters: List<CachedItem>,
    ): SyncResult {
        val returnedUrls = returnedChapters.map { it.path }.toSet()
        val dbUrls = dbChapters.map { it.url }.toSet()

        val removed = dbChapters.filter { it.url !in returnedUrls }
        val added = returnedChapters.filter { it.path !in dbUrls }

        val total = returnedChapters.size
        val returnedByNumber = returnedChapters.mapIndexed { idx, item ->
            GalleryCache.chapterNumber(idx, total) to item
        }.toMap()

        // Mihon safety net: re-added chapter inherits read state if positive chapter_number matches a deleted read chapter
        val lostRead = removed.filter { rem ->
            rem.isRead && (rem.chapterNumber <= 0 || !returnedByNumber.containsKey(rem.chapterNumber))
        }

        return SyncResult(
            removedChapters = removed,
            addedChapters = added,
            lostReadChapters = lostRead,
        )
    }

    @Before
    fun setUp() {
        tempDir = File.createTempFile("test_sim_", "").apply {
            delete()
            mkdirs()
        }
        ChapterLedger.customStorageDir = tempDir
        GalleryCache.customStorageDir = tempDir
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
        ChapterLedger.customStorageDir = null
        GalleryCache.customStorageDir = null
    }

    @Test
    fun testZeroRemovedAndZeroReadLostOnRefresh() {
        // 1. Existing database state in Mihon (user added gallery in earlier build)
        val initialItems = listOf(
            CachedItem("10", "Old Art 10", "/artist/art/old-art-10", 1000L),
            CachedItem("20", "Old Art 20", "/artist/art/old-art-20", 2000L),
            CachedItem("30", "Old Art 30", "/artist/art/old-art-30", 3000L),
        )

        // Seed initial crawl & ledger
        ChapterLedger.recordChapters("artist", null, initialItems)

        val totalInitial = initialItems.size
        val dbChapters = initialItems.mapIndexed { idx, item ->
            DbChapter(
                url = item.path,
                chapterNumber = GalleryCache.chapterNumber(idx, totalInitial),
                isRead = true, // User has marked all 3 as read
            )
        }

        // 2. New crawl happens (artist uploaded new art 40, and 20 was mature-gated and not returned in fresh unauth crawl)
        val freshCrawl = listOf(
            CachedItem("40", "New Art 40", "/artist/art/new-art-40", 4000L),
            CachedItem("30", "Old Art 30", "/artist/art/old-art-30", 3000L),
            CachedItem("10", "Old Art 10", "/artist/art/old-art-10", 1000L),
            // Notice: 20 is missing from fresh crawl!
        )

        // Reconcile via ChapterLedger
        val reconciledList = ChapterLedger.recordChapters("artist", null, freshCrawl)

        // 3. Simulate Mihon's sync
        val syncResult = simulateMihonSync(dbChapters, reconciledList)

        // Acceptance (FR-13 item 5): zero removed chapters and zero read chapters lost!
        assertEquals("No chapters should be removed from Mihon database", 0, syncResult.removedChapters.size)
        assertEquals("Zero read progress should be lost", 0, syncResult.lostReadChapters.size)
        assertEquals(1, syncResult.addedChapters.size)
        assertEquals("40", syncResult.addedChapters[0].id)
    }

    @Test
    fun testClearCacheDoesNotLoseReadHistory() {
        // Seed items
        val initialItems = listOf(
            CachedItem("1", "Art 1", "/artist/art/art-1", 1000L),
            CachedItem("2", "Art 2", "/artist/art/art-2", 2000L),
        )
        ChapterLedger.recordChapters("artist", null, initialItems)

        val dbChapters = initialItems.mapIndexed { idx, item ->
            DbChapter(url = item.path, chapterNumber = GalleryCache.chapterNumber(idx, 2), isRead = true)
        }

        // User clears crawl cache from settings
        GalleryCache.clearCache()

        // Refresh happens: crawled with edited slug
        val freshCrawl = listOf(
            CachedItem("2", "Art 2 Renamed", "/artist/art/art-2-renamed", 2000L),
            CachedItem("1", "Art 1", "/artist/art/art-1", 1000L),
        )
        val reconciled = ChapterLedger.recordChapters("artist", null, freshCrawl)

        val syncResult = simulateMihonSync(dbChapters, reconciled)
        assertEquals(0, syncResult.removedChapters.size)
        assertEquals(0, syncResult.lostReadChapters.size)
    }
}
