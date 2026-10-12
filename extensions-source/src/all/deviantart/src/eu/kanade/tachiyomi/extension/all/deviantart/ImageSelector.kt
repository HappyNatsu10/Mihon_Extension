package eu.kanade.tachiyomi.extension.all.deviantart

import kotlin.math.abs

object ImageSelector {

    // Maximum safe pixel guard to prevent OutOfMemory in Mihon's image decoder
    const val MAX_PIXELS_GUARD = 100_000_000L // e.g. 10,000 x 10,000 px

    /**
     * Checks if candidate aspect ratio matches original within tolerance (~2%).
     */
    fun isAspectRatioValid(
        candW: Int,
        candH: Int,
        origW: Int,
        origH: Int,
        tolerance: Double = 0.02,
    ): Boolean {
        if (candW <= 0 || candH <= 0 || origW <= 0 || origH <= 0) return true
        val candRatio = candW.toDouble() / candH.toDouble()
        val origRatio = origW.toDouble() / origH.toDouble()
        val diff = abs(candRatio - origRatio) / origRatio
        return diff <= tolerance
    }

    /**
     * Selects the highest quality, uncropped image rendition based on FR-14 and FR-16.
     * - Discards candidates failing the aspect-ratio check.
     * - Preserves /v1/fill/ when aspect ratio matches original (not an actual crop).
     * - Discards /v1/crop/ and small square thumbnails.
     * - Prioritizes download file if allowed and in BEST_AVAILABLE mode.
     * - Ranks remaining valid candidates by largest pixel count (width * height).
     */
    fun pickRendition(
        candidates: List<CandidateRendition>,
        original: ImageDimension?,
        isDownloadAllowed: Boolean,
        qualityMode: QualityMode = QualityMode.BEST_AVAILABLE,
    ): CandidateRendition? {
        if (candidates.isEmpty()) return null

        val validCandidates = candidates.filter { cand ->
            // Discard crops and square thumbnail renditions
            if (cand.url.contains("/v1/crop/") || cand.name.contains("crop", ignoreCase = true)) {
                return@filter false
            }
            if (cand.width in 1..299 && cand.height in 1..299 && cand.name.contains("thumb", ignoreCase = true)) {
                return@filter false
            }

            // Aspect ratio verification against original
            if (original != null && original.width > 0 && original.height > 0) {
                if (!isAspectRatioValid(cand.width, cand.height, original.width, original.height)) {
                    return@filter false
                }
            }

            // Guard against excessive pixel count
            val pixels = cand.width.toLong() * cand.height.toLong()
            if (pixels > MAX_PIXELS_GUARD) {
                return@filter false
            }

            true
        }

        if (validCandidates.isEmpty()) {
            return candidates.firstOrNull()
        }

        // In BEST_AVAILABLE mode, prefer download file if allowed by artist
        if (qualityMode == QualityMode.BEST_AVAILABLE && isDownloadAllowed) {
            val downloadCandidate = validCandidates.firstOrNull { it.isDownload }
            if (downloadCandidate != null) {
                return downloadCandidate
            }
        }

        // In STANDARD mode, do not prioritize or pick the download original
        val candidatesToRank = if (qualityMode == QualityMode.STANDARD) {
            val nonDownloads = validCandidates.filter { !it.isDownload }
            if (nonDownloads.isNotEmpty()) nonDownloads else validCandidates
        } else {
            validCandidates
        }

        // Rank by most pixels (width * height)
        return candidatesToRank.maxWithOrNull(
            compareBy<CandidateRendition> { it.width.toLong() * it.height.toLong() }
                .thenBy { if (it.isDownload) 1 else 0 }
                .thenBy { it.name },
        )
    }

    /**
     * Guarded quality upgrade: replaces `q_<num>` with `q_100` only in transform segment.
     * Leaves width, height, and tokens completely untouched (FR-16 item 3).
     */
    fun upgradeRendition(url: String): String? {
        val qRegex = Regex("""([/,])q_([0-9]+)([/,])""")
        val match = qRegex.find(url) ?: return null
        val prefix = match.groupValues[1]
        val currentQ = match.groupValues[2].toIntOrNull() ?: return null
        if (currentQ >= 100) return null

        return url.replaceFirst(match.value, "${prefix}q_100${match.groupValues[3]}")
    }
}
