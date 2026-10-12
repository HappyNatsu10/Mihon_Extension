package eu.kanade.tachiyomi.extension.all.deviantart

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class ChapterLedgerTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = File.createTempFile("test_ledger_", "").apply {
            delete()
            mkdirs()
        }
        ChapterLedger.customStorageDir = tempDir
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
        ChapterLedger.customStorageDir = null
    }

    @Test
    fun testSupersetInvariant() {
        // Initial crawl records 3 items
        val initialItems = listOf(
            CachedItem("101", "Artwork 101", "/artist/art/art-101", 1000L),
            CachedItem("102", "Artwork 102", "/artist/art/art-102", 2000L),
            CachedItem("103", "Artwork 103", "/artist/art/art-103", 3000L),
        )
        val initialSaved = ChapterLedger.recordChapters("artist", null, initialItems)
        assertEquals(3, initialSaved.size)

        // Subsequent crawl where 101 is missing (e.g. mature content when logged out)
        // and 104 is newly uploaded
        val freshCrawl = listOf(
            CachedItem("104", "Artwork 104", "/artist/art/art-104", 4000L),
            CachedItem("103", "Artwork 103", "/artist/art/art-103", 3000L),
            CachedItem("102", "Artwork 102", "/artist/art/art-102", 2000L),
        )

        val reconciled = ChapterLedger.recordChapters("artist", null, freshCrawl)
        assertEquals(4, reconciled.size)

        val ids = reconciled.map { it.id }.toSet()
        assertTrue(ids.contains("101"))
        assertTrue(ids.contains("102"))
        assertTrue(ids.contains("103"))
        assertTrue(ids.contains("104"))
    }

    @Test
    fun testPreservesFirstSeenPath() {
        // Seed artwork with initial path
        val initialItems = listOf(
            CachedItem("201", "Initial Title", "/artist/art/initial-title-201", 5000L),
        )
        ChapterLedger.recordChapters("artist", "folder1", initialItems)

        // DA user later edits deviation slug on website
        val renamedCrawl = listOf(
            CachedItem("201", "Updated Title", "/artist/art/updated-title-201", 5000L),
        )

        val result = ChapterLedger.recordChapters("artist", "folder1", renamedCrawl)
        assertEquals(1, result.size)
        // First-seen path must be preserved to prevent duplicate chapters or lost read history in Mihon
        assertEquals("/artist/art/initial-title-201", result[0].path)
    }

    @Test
    fun testCorruptLedgerRebuilt() {
        val ledgerFile = ChapterLedger.ledgerFile("artist", "all")
        ledgerFile.writeText("{ this is corrupt json !!!")

        val ledger = ChapterLedger.loadLedger("artist", "all")
        assertEquals(0, ledger.entries.size)

        val badFile = File(tempDir, "${ledgerFile.name}.bad")
        assertTrue(badFile.exists())
    }
}
