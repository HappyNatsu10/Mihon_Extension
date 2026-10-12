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
     * Guarded quality upgrade: upgrades `q_<num>` to `q_100` and maximizes dimensions
     * within the token's authorized constraints without invalidating the signed JWT (FR-16).
     */
    fun upgradeRendition(url: String): String? {
        var modified = false
        var result = url

        // 1. Upgrade quality to q_100
        val qRegex = Regex("""([/,])q_([0-9]+)([/,])""")
        val qMatch = qRegex.find(result)
        if (qMatch != null) {
            val prefix = qMatch.groupValues[1]
            val currentQ = qMatch.groupValues[2].toIntOrNull() ?: 70
            if (currentQ < 100) {
                result = result.replaceFirst(qMatch.value, "${prefix}q_100${qMatch.groupValues[3]}")
                modified = true
            }
        }

        // 2. Upgrade dimensions if Wix token constraints permit
        if (result.contains("token=") && result.contains("/v1/")) {
            try {
                val tokenPart = result.substringAfter("token=").substringBefore("&")
                val segments = tokenPart.split('.')
                if (segments.size >= 2) {
                    val rawPayload = segments[1]
                    val padded = rawPayload + "=".repeat((4 - rawPayload.length % 4) % 4)
                    val decoded = String(java.util.Base64.getUrlDecoder().decode(padded))
                    val widthMatch = Regex(""""width"\s*:\s*"<=?(\d+)"""").find(decoded)
                    val heightMatch = Regex(""""height"\s*:\s*"<=?(\d+)"""").find(decoded)
                    val maxW = widthMatch?.groupValues?.get(1)?.toIntOrNull()
                    val maxH = heightMatch?.groupValues?.get(1)?.toIntOrNull()

                    val curW = Regex("""w_(\d+)""").find(result)?.groupValues?.get(1)?.toIntOrNull()
                    val curH = Regex("""h_(\d+)""").find(result)?.groupValues?.get(1)?.toIntOrNull()

                    if (maxW != null && curW != null && maxW > curW) {
                        result = result.replaceFirst("w_$curW", "w_$maxW")
                        modified = true
                    }
                    if (maxH != null && curH != null && maxH > curH) {
                        result = result.replaceFirst("h_$curH", "h_$maxH")
                        modified = true
                    }
                }
            } catch (_: Throwable) {
                // Keep safe unmodified URL on decode exception
            }
        }

        return if (modified) result else null
    }

    fun maximizeRendition(url: String): String = upgradeRendition(url) ?: url
}
