package dev.stealthcompanion.core.protocol

import java.text.BreakIterator
import java.util.Locale

data class DisplayTextPlan(val pages: List<SocialPayload>, val truncated: Boolean)

/** Application-side limits for the extended display mode. The codec also checks the peer's request. */
class DisplayTextPolicy(
    private val bodyLimitBytes: Int = DEFAULT_BODY_LIMIT_BYTES,
    private val paged: Boolean = false,
    private val maxPages: Int = MAX_PAGES,
    private val compactLines: Boolean = false,
) {
    init {
        require(bodyLimitBytes in 1..BODY_LIMIT_CHOICES.last())
        require(maxPages in 1..MAX_PAGES)
    }

    fun render(payload: SocialPayload, sender: String? = null): DisplayTextPlan {
        val body = if (compactLines) flattenLines(payload.text) else payload.text
        val pageLimit = if (paged) maxPages else 1
        val boundaries = boundariesWithin(body, bodyLimitBytes * pageLimit)
        val wholeEnd = furthestReachable(boundaries, pageLimit, bodyLimitBytes)
        val truncated = boundaries[wholeEnd].offset < body.length
        val marker = if (truncated) ".".repeat(minOf(3, bodyLimitBytes)) else ""
        val finalBudget = bodyLimitBytes - marker.length
        val targetEnd = if (truncated) furthestReachable(boundaries, pageLimit, finalBudget) else wholeEnd
        if (targetEnd == 0 || boundaries[targetEnd].bytes <= finalBudget) {
            val text = body.substring(0, boundaries[targetEnd].offset) + marker
            return DisplayTextPlan(listOf(payload.copy(text = text)), truncated)
        }

        val lineBreaks = BreakIterator.getLineInstance(Locale.ROOT).apply { setText(body) }
        val ends = naturalPageEnds(boundaries, targetEnd, pageLimit, finalBudget, lineBreaks)
            ?: fallbackPageEnds(boundaries, targetEnd, pageLimit, finalBudget, lineBreaks)
        val pageCount = ends.size
        val labelBytes = "$pageCount/$pageCount ".length
        val heading = pageHeading(payload.title, sender, PAGE_TITLE_BYTES - labelBytes)
        var start = 0
        val pages = ends.mapIndexed { index, end ->
            val label = "${index + 1}/$pageCount "
            val text = body.substring(boundaries[start].offset, boundaries[end].offset)
            start = end
            payload.copy(title = label + heading, text = if (index == ends.lastIndex) text + marker else text)
        }
        return DisplayTextPlan(pages, truncated)
    }

    private fun naturalPageEnds(
        boundaries: List<Boundary>,
        targetEnd: Int,
        pageLimit: Int,
        finalBudget: Int,
        lineBreaks: BreakIterator,
    ): List<Int>? {
        val ends = mutableListOf<Int>()
        var start = 0
        repeat(pageLimit) { index ->
            if (boundaries[targetEnd].bytes - boundaries[start].bytes <= finalBudget) return ends + targetEnd
            if (index == pageLimit - 1) return null
            val furthest = endWithin(boundaries, start, targetEnd - 1, bodyLimitBytes)
            val end = (furthest downTo start + 1).firstOrNull { lineBreaks.isBoundary(boundaries[it].offset) }
                ?: return null
            ends += end
            start = end
        }
        return null
    }

    private fun fallbackPageEnds(
        boundaries: List<Boundary>,
        targetEnd: Int,
        pageLimit: Int,
        finalBudget: Int,
        lineBreaks: BreakIterator,
    ): List<Int> {
        val earliest = startsThatFit(boundaries, targetEnd, pageLimit, finalBudget)
        val pageCount = (1..pageLimit).first { earliest[it] == 0 }
        val ends = mutableListOf<Int>()
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
            ends += end
            start = end
        }
        return ends
    }

    private fun pageHeading(title: String, sender: String?, budget: Int): String {
        val context = flattenLines(title).trim()
        val name = sender?.let(::flattenLines)?.trim().orEmpty()
        val nameBytes = SocialCodec.encodeUtf8Within(name, budget)
        if (nameBytes.isEmpty()) return SocialCodec.encodeUtf8Within(context, budget).toString(Charsets.UTF_8)
        val preferred = nameBytes.toString(Charsets.UTF_8).trimEnd()
        if (preferred != name) return preferred
        val remainder = budget - nameBytes.size - TITLE_SEPARATOR.toByteArray(Charsets.UTF_8).size
        val otherLabels = context.split(TITLE_SEPARATOR).filterNot { it.equals(name, ignoreCase = true) }
        val shortenedContext = SocialCodec.encodeUtf8Within(otherLabels.joinToString(TITLE_SEPARATOR), remainder)
            .toString(Charsets.UTF_8).trimEnd()
        return if (shortenedContext.isEmpty()) preferred else preferred + TITLE_SEPARATOR + shortenedContext
    }

    private fun flattenLines(value: String): String {
        if (!LINE_SEPARATORS.containsMatchIn(value)) return value
        return LINE_SEPARATORS.splitToSequence(value)
            .map { line -> line.trim { it == '\t' || Character.isSpaceChar(it) } }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
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
        val BODY_LIMIT_CHOICES: List<Int> = listOf(64, 96, 110, 120, 128, 255)
        const val DEFAULT_BODY_LIMIT_BYTES = 110
        const val MAX_PAGES = 4
        private const val PAGE_TITLE_BYTES = 32
        private const val TITLE_SEPARATOR = " · "
        private val LINE_SEPARATORS = Regex("[\\r\\n\\u000B\\u000C\\u0085\\u2028\\u2029]+")
    }
}
