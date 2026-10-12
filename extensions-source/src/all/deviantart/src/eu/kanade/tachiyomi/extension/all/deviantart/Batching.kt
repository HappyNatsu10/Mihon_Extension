package eu.kanade.tachiyomi.extension.all.deviantart

data class BatchWindow(
    val newestOffset: Int,
    val count: Int,
)

object Batching {
    fun chapterCount(total: Int, size: Int): Int = if (total <= 0 || size <= 0) 0 else (total + size - 1) / size

    /**
     * batchIndex is 0-based from the OLDEST batch (Chapter 1 is batchIndex 0).
     *
     * In DA, the API/RSS offset is newest-first (offset 0 = newest deviations).
     *
     * oldestStart = batchIndex * size
     * oldestEnd   = minOf((batchIndex + 1) * size, total)
     * count       = oldestEnd - oldestStart
     * newestOffsetStart = total - oldestEnd
     */
    fun windowFor(batchIndex: Int, size: Int, total: Int): BatchWindow {
        if (total <= 0 || size <= 0 || batchIndex < 0) return BatchWindow(0, 0)
        val oldestStart = batchIndex * size
        if (oldestStart >= total) return BatchWindow(0, 0)
        val oldestEnd = minOf((batchIndex + 1) * size, total)
        val count = oldestEnd - oldestStart
        val newestOffset = total - oldestEnd
        return BatchWindow(newestOffset = newestOffset, count = count)
    }

    /**
     * 1-based oldest-first numbering: "Artworks {from}–{to}"
     */
    fun chapterName(batchIndex: Int, size: Int, total: Int): String {
        val from = batchIndex * size + 1
        val to = minOf((batchIndex + 1) * size, total)
        return "Artworks $from–$to"
    }

    /**
     * Build synthetic chapter URL: "/{user}/gallery?batch={i}&size={B}"
     */
    fun chapterUrl(username: String, batchIndex: Int, size: Int): String = "/$username/gallery?batch=$batchIndex&size=$size"

    private val BATCH_URL_REGEX = Regex("""[?&]batch=(\d+)""")
    private val SIZE_URL_REGEX = Regex("""[?&]size=(\d+)""")

    fun parseChapterUrl(url: String): Pair<Int, Int>? {
        val batchMatch = BATCH_URL_REGEX.find(url) ?: return null
        val sizeMatch = SIZE_URL_REGEX.find(url) ?: return null
        val batch = batchMatch.groupValues[1].toIntOrNull() ?: return null
        val size = sizeMatch.groupValues[1].toIntOrNull() ?: return null
        return Pair(batch, size)
    }
}
