package dev.stealthcompanion.core.protocol

import java.text.BreakIterator
import java.util.Locale

data class DisplayTextPlan(val pages: List<SocialPayload>, val truncated: Boolean)

/** Application-side limits for the extended display mode. The codec also checks the peer's request. */
class DisplayTextPolicy(
    private val bodyLimitBytes: Int = DEFAULT_BODY_LIMIT_BYTES,
    private val paged: Boolean = false,
    private val maxPages: Int = MAX_PAGES,
) {
    init {
        require(bodyLimitBytes in 1..BODY_LIMIT_CHOICES.last())
        require(maxPages in 1..MAX_PAGES)
    }

    fun render(payload: SocialPayload): DisplayTextPlan {
        val pageLimit = if (paged) maxPages else 1
        val boundaries = boundariesWithin(payload.text, bodyLimitBytes * pageLimit)
        val wholeEnd = furthestReachable(boundaries, pageLimit, bodyLimitBytes)
        val truncated = boundaries[wholeEnd].offset < payload.text.length
        val marker = if (truncated) ".".repeat(minOf(3, bodyLimitBytes)) else ""
        val finalBudget = bodyLimitBytes - marker.length
        val targetEnd = if (truncated) furthestReachable(boundaries, pageLimit, finalBudget) else wholeEnd
        if (targetEnd == 0 || boundaries[targetEnd].bytes <= finalBudget) {
            return DisplayTextPlan(listOf(payload.copy(text = payload.text.substring(0, boundaries[targetEnd].offset) + marker)), truncated)
        }

        val earliest = startsThatFit(boundaries, targetEnd, pageLimit, finalBudget)
        val pageCount = (1..pageLimit).first { earliest[it] == 0 }
        val lineBreaks = BreakIterator.getLineInstance(Locale.ROOT).apply { setText(payload.text) }
        val pages = mutableListOf<SocialPayload>()
        var start = 0
        for (index in 1..pageCount) {
            val finalPage = index == pageCount
            val end = if (finalPage) {
                targetEnd
            } else {
                val remainingPages = pageCount - index
                val firstEndThatFits = maxOf(start + 1, earliest[remainingPages])
                val furthestEnd = endWithin(boundaries, start, targetEnd, bodyLimitBytes)
                val lastEnd = if (furthestEnd == targetEnd && firstEndThatFits < targetEnd) targetEnd - 1 else furthestEnd
                (lastEnd downTo firstEndThatFits).firstOrNull { lineBreaks.isBoundary(boundaries[it].offset) } ?: lastEnd
            }
            val label = "$index/$pageCount "
            val shortTitle = SocialCodec.encodeUtf8Within(payload.title, PAGE_TITLE_BYTES - label.length).toString(Charsets.UTF_8)
            val text = payload.text.substring(boundaries[start].offset, boundaries[end].offset)
            pages += payload.copy(title = label + shortTitle, text = if (finalPage) text + marker else text)
            start = end
        }
        return DisplayTextPlan(pages, truncated)
    }

    private data class Boundary(val offset: Int, val bytes: Int)

    private fun boundariesWithin(text: String, maximum: Int): List<Boundary> {
        val boundaries = mutableListOf(Boundary(0, 0))
        for (grapheme in SocialCodec.graphemes(text)) {
            val previous = boundaries.last()
            val size = grapheme.toByteArray(Charsets.UTF_8).size
            if (size > bodyLimitBytes || size > maximum - previous.bytes) break
            boundaries += Boundary(previous.offset + grapheme.length, previous.bytes + size)
        }
        return boundaries
    }

    private fun furthestReachable(boundaries: List<Boundary>, pageLimit: Int, finalBudget: Int): Int {
        var end = 0
        repeat(pageLimit - 1) { end = endWithin(boundaries, end, boundaries.lastIndex, bodyLimitBytes) }
        return endWithin(boundaries, end, boundaries.lastIndex, finalBudget)
    }

    private fun endWithin(boundaries: List<Boundary>, start: Int, maximumEnd: Int, budget: Int): Int {
        var end = start
        while (end < maximumEnd && boundaries[end + 1].bytes - boundaries[start].bytes <= budget) end++
        return end
    }

    // Pack backwards to find how little each page may carry without losing text on later pages.
    private fun startsThatFit(boundaries: List<Boundary>, targetEnd: Int, pageLimit: Int, finalBudget: Int): IntArray {
        val starts = IntArray(pageLimit + 1)
        starts[0] = targetEnd
        for (pages in 1..pageLimit) {
            val end = starts[pages - 1]
            val budget = if (pages == 1) finalBudget else bodyLimitBytes
            var start = end
            while (start > 0 && boundaries[end].bytes - boundaries[start - 1].bytes <= budget) start--
            starts[pages] = start
        }
        return starts
    }

    companion object {
        val BODY_LIMIT_CHOICES: List<Int> = listOf(64, 96, 112, 120, 128, 255)
        const val DEFAULT_BODY_LIMIT_BYTES = 112
        const val MAX_PAGES = 4
        private const val PAGE_TITLE_BYTES = 12
    }
}
