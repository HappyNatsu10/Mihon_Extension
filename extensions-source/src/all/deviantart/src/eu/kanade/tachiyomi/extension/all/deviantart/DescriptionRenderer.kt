package eu.kanade.tachiyomi.extension.all.deviantart

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.ByteArrayOutputStream

object DescriptionRenderer {

    const val PAGE_WIDTH = 1080
    const val MAX_PAGE_HEIGHT = 1920
    const val MARGIN = 64
    const val CONTENT_WIDTH = PAGE_WIDTH - (MARGIN * 2) // 952 px

    /**
     * Pure function to partition line indices into pages based on available heights.
     */
    fun paginateLines(
        lineHeights: List<Int>,
        pageHeight: Int,
        maxPages: Int,
        headerHeight: Int = 0,
        truncationMarkerHeight: Int = 60,
    ): List<IntRange> {
        if (lineHeights.isEmpty()) return emptyList()

        val pages = mutableListOf<IntRange>()
        var lineIndex = 0
        var currentPage = 1

        while (lineIndex < lineHeights.size && currentPage <= maxPages) {
            val isFirstPage = (currentPage == 1)
            val isLastPossiblePage = (currentPage == maxPages)

            val baseAvail = if (isFirstPage) {
                pageHeight - (MARGIN * 2) - headerHeight
            } else {
                pageHeight - (MARGIN * 2)
            }

            var usedHeight = 0
            val startLine = lineIndex

            while (lineIndex < lineHeights.size) {
                val lh = lineHeights[lineIndex]
                val limit = if (isLastPossiblePage) baseAvail - truncationMarkerHeight else baseAvail

                if (usedHeight + lh <= limit || lineIndex == startLine) {
                    usedHeight += lh
                    lineIndex++
                } else {
                    break
                }
            }

            pages.add(startLine until lineIndex)
            currentPage++
        }

        return pages
    }

    /**
     * Estimates or computes the number of description pages required for [desc].
     */
    fun computePageCount(desc: ArtworkDescription, maxPages: Int): Int {
        if (desc.text.isBlank()) return 0
        val textPaint = TextPaint().apply {
            textSize = 40f
        }
        val layout = try {
            StaticLayout.Builder.obtain(desc.text, 0, desc.text.length, textPaint, CONTENT_WIDTH)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.35f)
                .setIncludePad(false)
                .build()
        } catch (_: Throwable) {
            // JVM fallback / legacy fallback
            @Suppress("DEPRECATION")
            StaticLayout(desc.text, textPaint, CONTENT_WIDTH, Layout.Alignment.ALIGN_NORMAL, 1.35f, 0f, false)
        }

        val lineHeights = (0 until layout.lineCount).map { i -> layout.getLineBottom(i) - layout.getLineTop(i) }
        val headerHeight = 52 + 20 + 32 + 24 + 2 + 32 // ~162 px
        val pages = paginateLines(lineHeights, MAX_PAGE_HEIGHT, maxPages, headerHeight)
        return pages.size.coerceIn(1, maxPages)
    }

    /**
     * Renders page [pageNum] (1-based) to PNG byte array.
     */
    fun renderPage(desc: ArtworkDescription, pageNum: Int, maxPages: Int): ByteArray {
        val titlePaint = TextPaint().apply {
            color = Color.parseColor("#111111")
            textSize = 52f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val bylinePaint = TextPaint().apply {
            color = Color.parseColor("#666666")
            textSize = 32f
            typeface = Typeface.DEFAULT
            isAntiAlias = true
        }

        val dividerPaint = Paint().apply {
            color = Color.parseColor("#CCCCCC")
            strokeWidth = 2f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

        val bodyPaint = TextPaint().apply {
            color = Color.parseColor("#1A1A1A")
            textSize = 40f
            typeface = Typeface.DEFAULT
            isAntiAlias = true
        }

        val markerPaint = TextPaint().apply {
            color = Color.parseColor("#888888")
            textSize = 34f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
            isAntiAlias = true
        }

        val bodyLayout = try {
            StaticLayout.Builder.obtain(desc.text, 0, desc.text.length, bodyPaint, CONTENT_WIDTH)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.35f)
                .setIncludePad(false)
                .build()
        } catch (_: Throwable) {
            @Suppress("DEPRECATION")
            StaticLayout(desc.text, bodyPaint, CONTENT_WIDTH, Layout.Alignment.ALIGN_NORMAL, 1.35f, 0f, false)
        }

        val titleLayout = try {
            StaticLayout.Builder.obtain(desc.title, 0, desc.title.length, titlePaint, CONTENT_WIDTH)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .build()
        } catch (_: Throwable) {
            @Suppress("DEPRECATION")
            StaticLayout(desc.title, titlePaint, CONTENT_WIDTH, Layout.Alignment.ALIGN_NORMAL, 1.0f, 0f, false)
        }

        val bylineText = buildString {
            append("by ")
            append(desc.author)
            if (!desc.date.isNullOrBlank()) {
                append(" · ")
                append(desc.date)
            }
        }
        val bylineLayout = try {
            StaticLayout.Builder.obtain(bylineText, 0, bylineText.length, bylinePaint, CONTENT_WIDTH)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .build()
        } catch (_: Throwable) {
            @Suppress("DEPRECATION")
            StaticLayout(bylineText, bylinePaint, CONTENT_WIDTH, Layout.Alignment.ALIGN_NORMAL, 1.0f, 0f, false)
        }

        val headerHeight = titleLayout.height + 16 + bylineLayout.height + 24 + 2 + 24
        val lineHeights = (0 until bodyLayout.lineCount).map { i -> bodyLayout.getLineBottom(i) - bodyLayout.getLineTop(i) }
        val pages = paginateLines(lineHeights, MAX_PAGE_HEIGHT, maxPages, headerHeight)

        val targetPageIdx = (pageNum - 1).coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        val lineRange = pages.getOrElse(targetPageIdx) { 0 until 0 }
        val isFirstPage = (targetPageIdx == 0)
        val isLastPage = (targetPageIdx == pages.size - 1)
        val isTruncated = isLastPage && (lineRange.last < bodyLayout.lineCount - 1)

        // Compute needed height for this page
        var contentHeight = if (isFirstPage) headerHeight else 0
        if (!lineRange.isEmpty()) {
            val topOfFirstLine = bodyLayout.getLineTop(lineRange.first)
            val bottomOfLastLine = bodyLayout.getLineBottom(lineRange.last)
            contentHeight += (bottomOfLastLine - topOfFirstLine)
        }
        if (isTruncated) {
            contentHeight += 60
        }

        val pageHeight = (contentHeight + (MARGIN * 2)).coerceIn(800, MAX_PAGE_HEIGHT)
        val bitmap = Bitmap.createBitmap(PAGE_WIDTH, pageHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        var y = MARGIN.toFloat()

        if (isFirstPage) {
            // Draw title
            canvas.save()
            canvas.translate(MARGIN.toFloat(), y)
            titleLayout.draw(canvas)
            canvas.restore()
            y += titleLayout.height + 16

            // Draw byline
            canvas.save()
            canvas.translate(MARGIN.toFloat(), y)
            bylineLayout.draw(canvas)
            canvas.restore()
            y += bylineLayout.height + 24

            // Draw divider
            canvas.drawLine(MARGIN.toFloat(), y, (PAGE_WIDTH - MARGIN).toFloat(), y, dividerPaint)
            y += 24
        }

        // Draw body lines for this page
        if (!lineRange.isEmpty()) {
            val topOfFirstLine = bodyLayout.getLineTop(lineRange.first)
            canvas.save()
            canvas.translate(MARGIN.toFloat(), y - topOfFirstLine)
            canvas.clipRect(0, topOfFirstLine, CONTENT_WIDTH, bodyLayout.getLineBottom(lineRange.last))
            bodyLayout.draw(canvas)
            canvas.restore()
            y += (bodyLayout.getLineBottom(lineRange.last) - topOfFirstLine)
        }

        if (isTruncated) {
            y += 30
            val marker = "… (description continues on DeviantArt)"
            canvas.drawText(marker, MARGIN.toFloat(), y + 30, markerPaint)
        }

        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return out.toByteArray()
    }
}
