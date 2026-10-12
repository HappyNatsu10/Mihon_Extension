package eu.kanade.tachiyomi.extension.all.deviantart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageSelectorTest {

    @Test
    fun testAspectRatioValidation() {
        // Original 1920x1080 (ratio 1.7777)
        assertTrue(ImageSelector.isAspectRatioValid(1920, 1080, 1920, 1080))
        assertTrue(ImageSelector.isAspectRatioValid(960, 540, 1920, 1080))
        // 1% difference (within 2% tolerance)
        assertTrue(ImageSelector.isAspectRatioValid(1930, 1080, 1920, 1080))
        // 5% difference (square crop 1080x1080 vs 1920x1080) -> rejected
        assertFalse(ImageSelector.isAspectRatioValid(1080, 1080, 1920, 1080))
    }

    @Test
    fun testPickRenditionPreservesV1FillWhenAspectMatches() {
        // DeviantArt fullview URLs use /v1/fill/ with full original dimensions.
        // It must NOT be rejected as a crop if aspect ratio matches (FR-14 item 6 & FR-16).
        val original = ImageDimension(3000, 2000)
        val fillCandidate = CandidateRendition(
            name = "fullview",
            width = 3000,
            height = 2000,
            url = "https://images-wixmp.com/f/abc/v1/fill/w_3000,h_2000,q_85/art.jpg?token=abc",
        )
        val smallCandidate = CandidateRendition(
            name = "preview",
            width = 1200,
            height = 800,
            url = "https://images-wixmp.com/f/abc/v1/fit/w_1200,h_800/art.jpg?token=abc",
        )

        val picked = ImageSelector.pickRendition(listOf(smallCandidate, fillCandidate), original, isDownloadAllowed = false)
        assertNotNull(picked)
        assertEquals("fullview", picked?.name)
        assertEquals(3000, picked?.width)
    }

    @Test
    fun testPickRenditionRejectsV1Crop() {
        val original = ImageDimension(1920, 1080)
        val cropCandidate = CandidateRendition(
            name = "crop",
            width = 1080,
            height = 1080,
            url = "https://images-wixmp.com/f/abc/v1/crop/w_1080,h_1080/art.jpg",
        )
        val preview = CandidateRendition(
            name = "preview",
            width = 1280,
            height = 720,
            url = "https://images-wixmp.com/f/abc/v1/fit/w_1280,h_720/art.jpg",
        )

        val picked = ImageSelector.pickRendition(listOf(cropCandidate, preview), original, isDownloadAllowed = false)
        assertNotNull(picked)
        assertEquals("preview", picked?.name)
    }

    @Test
    fun testLargestPixelsWins() {
        val original = ImageDimension(4000, 3000)
        val c1 = CandidateRendition("1200w", 1200, 900, "url1")
        val c2 = CandidateRendition("2400w", 2400, 1800, "url2")
        val c3 = CandidateRendition("4000w", 4000, 3000, "url3")

        val picked = ImageSelector.pickRendition(listOf(c1, c2, c3), original, isDownloadAllowed = false)
        assertEquals("4000w", picked?.name)
    }

    @Test
    fun testDownloadOriginalPriorityInBestAvailable() {
        val original = ImageDimension(6000, 4000)
        val fullview = CandidateRendition("fullview", 3000, 2000, "https://wixmp/fullview.jpg")
        val downloadOriginal = CandidateRendition("download", 6000, 4000, "https://wixmp/original.png", isDownload = true)

        // In BEST_AVAILABLE mode: download file is top candidate
        val best = ImageSelector.pickRendition(
            listOf(fullview, downloadOriginal),
            original,
            isDownloadAllowed = true,
            qualityMode = QualityMode.BEST_AVAILABLE,
        )
        assertEquals("download", best?.name)

        // In STANDARD mode: skips download file
        val standard = ImageSelector.pickRendition(
            listOf(fullview, downloadOriginal),
            original,
            isDownloadAllowed = true,
            qualityMode = QualityMode.STANDARD,
        )
        assertEquals("fullview", standard?.name)
    }

    @Test
    fun testUpgradeRendition() {
        val lowQ = "https://images-wixmp.com/f/abc/v1/fill/w_1200,h_800,q_70/art.jpg?token=123"
        val upgraded = ImageSelector.upgradeRendition(lowQ)
        assertEquals(
            "https://images-wixmp.com/f/abc/v1/fill/w_1200,h_800,q_100/art.jpg?token=123",
            upgraded,
        )

        val already100 = "https://images-wixmp.com/f/abc/v1/fill/w_1200,h_800,q_100/art.jpg?token=123"
        assertNull(ImageSelector.upgradeRendition(already100))
    }
}
