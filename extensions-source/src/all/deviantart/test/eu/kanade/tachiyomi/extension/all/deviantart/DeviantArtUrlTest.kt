package eu.kanade.tachiyomi.extension.all.deviantart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviantArtUrlTest {

    @Test
    fun testFr15Item9Table() {
        // 1. https://www.deviantart.com/artist/gallery/12345678/my-folder -> Gallery(artist, 12345678)
        assertEquals(
            Resolved.Gallery(GalleryRef("artist", "12345678")),
            DeviantArtUrl.resolve("https://www.deviantart.com/artist/gallery/12345678/my-folder"),
        )

        // 2. https://www.deviantart.com/artist/gallery/12345678 and with trailing /
        assertEquals(
            Resolved.Gallery(GalleryRef("artist", "12345678")),
            DeviantArtUrl.resolve("https://www.deviantart.com/artist/gallery/12345678"),
        )
        assertEquals(
            Resolved.Gallery(GalleryRef("artist", "12345678")),
            DeviantArtUrl.resolve("https://www.deviantart.com/artist/gallery/12345678/"),
        )

        // 3. deviantart.com/artist/gallery/12345678/my-folder (no scheme)
        assertEquals(
            Resolved.Gallery(GalleryRef("artist", "12345678")),
            DeviantArtUrl.resolve("deviantart.com/artist/gallery/12345678/my-folder"),
        )

        // 4. www.deviantart.com/Artist/Gallery/12345678/My-Folder?page=2#top
        assertEquals(
            Resolved.Gallery(GalleryRef("Artist", "12345678")),
            DeviantArtUrl.resolve("www.deviantart.com/Artist/Gallery/12345678/My-Folder?page=2#top"),
        )

        // 5. https://m.deviantart.com/artist/gallery/12345678/my-folder
        assertEquals(
            Resolved.Gallery(GalleryRef("artist", "12345678")),
            DeviantArtUrl.resolve("https://m.deviantart.com/artist/gallery/12345678/my-folder"),
        )

        // 6. https://artist.deviantart.com/gallery/12345678/my-folder
        assertEquals(
            Resolved.Gallery(GalleryRef("artist", "12345678")),
            DeviantArtUrl.resolve("https://artist.deviantart.com/gallery/12345678/my-folder"),
        )

        // 7. Look at this https://www.deviantart.com/artist/gallery/12345678/my-folder!
        assertEquals(
            Resolved.Gallery(GalleryRef("artist", "12345678")),
            DeviantArtUrl.resolve("Look at this https://www.deviantart.com/artist/gallery/12345678/my-folder!"),
        )

        // 8. Adjacent links mapping to All: /gallery, /gallery/all, /gallery/scraps, /favourites/555/x, /art/some-title-999
        assertEquals(
            Resolved.Gallery(GalleryRef("artist", null)),
            DeviantArtUrl.resolve("https://www.deviantart.com/artist/gallery"),
        )
        assertEquals(
            Resolved.Gallery(GalleryRef("artist", null)),
            DeviantArtUrl.resolve("https://www.deviantart.com/artist/gallery/all"),
        )
        assertEquals(
            Resolved.Gallery(GalleryRef("artist", null)),
            DeviantArtUrl.resolve("https://www.deviantart.com/artist/gallery/scraps"),
        )
        assertEquals(
            Resolved.Gallery(GalleryRef("artist", null)),
            DeviantArtUrl.resolve("https://www.deviantart.com/artist/favourites/555/x"),
        )
        assertEquals(
            Resolved.ArtworkAndGallery("/artist/art/some-title-999", GalleryRef("artist", null)),
            DeviantArtUrl.resolve("https://www.deviantart.com/artist/art/some-title-999"),
        )

        // 9. NeedsOembed: /art/some-title-999, /deviation/999, https://fav.me/dabc123
        val oembed1 = DeviantArtUrl.resolve("https://www.deviantart.com/art/some-title-999")
        assertTrue(oembed1 is Resolved.NeedsOembed)
        val oembed2 = DeviantArtUrl.resolve("https://www.deviantart.com/deviation/999")
        assertTrue(oembed2 is Resolved.NeedsOembed)
        val oembed3 = DeviantArtUrl.resolve("https://fav.me/dabc123")
        assertTrue(oembed3 is Resolved.NeedsOembed)

        // 10. Reserved word segment: https://www.deviantart.com/tag/gallery/123
        assertEquals(
            Resolved.Invalid,
            DeviantArtUrl.resolve("https://www.deviantart.com/tag/gallery/123"),
        )

        // 11. Stored entry URLs: normalizeEntryUrl
        assertEquals(
            GalleryRef("artist", "12345678"),
            normalizeEntryUrl("/artist/gallery/12345678"),
        )
        assertEquals(
            GalleryRef("artist", null),
            normalizeEntryUrl("/artist/gallery/all"),
        )
    }

    @Test
    fun testEntryUrlRoundTrip() {
        val allRef = GalleryRef("Yuumei", null)
        assertEquals("/Yuumei/gallery/all", entryUrl(allRef))
        assertEquals(allRef, normalizeEntryUrl(entryUrl(allRef)))

        val folderRef = GalleryRef("Yuumei", "456789")
        assertEquals("/Yuumei/gallery/456789", entryUrl(folderRef))
        assertEquals(folderRef, normalizeEntryUrl(entryUrl(folderRef)))
    }

    @Test
    fun testSeparateCacheAndLedgerFileNames() {
        val allRef = GalleryRef("Yuumei", null)
        val folderRef = GalleryRef("Yuumei", "456789")

        val allCache = GalleryCache.cacheFile(allRef.user, allRef.folderId)
        val folderCache = GalleryCache.cacheFile(folderRef.user, folderRef.folderId)
        assertNotEquals(allCache.name, folderCache.name)
        assertEquals("yuumei_all.json", allCache.name)
        assertEquals("yuumei_456789.json", folderCache.name)

        val allLedger = ChapterLedger.ledgerFile(allRef.user, allRef.folderId)
        val folderLedger = ChapterLedger.ledgerFile(folderRef.user, folderRef.folderId)
        assertNotEquals(allLedger.name, folderLedger.name)
        assertEquals("ledger_yuumei_all.json", allLedger.name)
        assertEquals("ledger_yuumei_456789.json", folderLedger.name)
    }
}
