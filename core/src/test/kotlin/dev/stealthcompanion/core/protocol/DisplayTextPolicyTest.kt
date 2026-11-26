package dev.stealthcompanion.core.protocol

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayTextPolicyTest {
    @Test
    fun defaultRetains112BytesAndSignalsClippingInsideTheLimit() {
        val title = "Titolo originale"
        val exact = SocialPayload(title, "x".repeat(112))
        assertEquals(DisplayTextPlan(listOf(exact), false), DisplayTextPolicy().render(exact))
        val overflow = exact.copy(text = exact.text + "!")
        val result = DisplayTextPolicy().render(overflow)
        assertTrue(result.truncated)
        assertEquals(SocialPayload(title, "x".repeat(109) + "..."), result.pages.single())
        assertEquals(112, result.pages.single().text.toByteArray().size)
    }

    @Test
    fun eachConfiguredLimitRetainsOnlyWholeGraphemesBeforeAnAsciiMarker() {
        val cluster = "👩🏽‍💻"
        val body = cluster.repeat(30)
        for (limit in DisplayTextPolicy.BODY_LIMIT_CHOICES) {
            val page = DisplayTextPolicy(limit).render(SocialPayload("Lab", body))
            val clustersThatFit = (limit - 3) / cluster.toByteArray().size
            assertEquals(cluster.repeat(clustersThatFit) + "...", page.pages.single().text)
            assertTrue(page.truncated)
        }
    }

    @Test
    fun paginationPrefersWordsPreservesMetadataAndNumbersCompactTitles() {
        val payload = SocialPayload("Città 🙂 editoriale", "alpha beta gamma delta", "dev.app", "App")
        val result = DisplayTextPolicy(12, paged = true).render(payload)
        assertFalse(result.truncated)
        assertEquals(listOf("alpha beta ", "gamma delta"), result.pages.map { it.text })
        assertEquals(listOf("1/2 Città ", "2/2 Città "), result.pages.map { it.title })
        assertTrue(result.pages.all { it.title.toByteArray().size <= 12 })
        assertTrue(result.pages.all { it.appIdentifier == "dev.app" && it.applicationName == "App" })
    }

    @Test
    fun fullWordPreferenceDoesNotSacrificeTextThatFitsAcrossThePages() {
        val body = "alpha " + "b".repeat(14)
        val result = DisplayTextPolicy(10, paged = true, maxPages = 2).render(SocialPayload("Titolo", body))
        assertFalse(result.truncated)
        assertEquals(listOf("alpha bbbb", "b".repeat(10)), result.pages.map { it.text })
    }

    @Test
    fun orderedPaginationPreservesUnicodeSpacesLineBreaksAndEmoji() {
        val body = "uno due\ntré  quattro\r\ncinque\tsei sette 😀🇮🇹"
        val result = DisplayTextPolicy(16, paged = true).render(SocialPayload("", body))
        assertFalse(result.truncated)
        assertEquals(body, result.pages.joinToString("") { it.text })
        assertTrue(result.pages.none { it.text.endsWith("\r") })
        assertEquals((1..result.pages.size).map { "$it/${result.pages.size} " }, result.pages.map { it.title })
    }

    @Test
    fun boundedPagesPreserveTheLongestPrefixAndMarkOnlyTheFinalPage() {
        val body = "aa ".repeat(100)
        val result = DisplayTextPolicy(20, paged = true, maxPages = 4).render(SocialPayload("Titolo", body))
        assertTrue(result.truncated)
        assertEquals(4, result.pages.size)
        assertEquals(body.take(77) + "...", result.pages.joinToString("") { it.text })
        assertTrue(result.pages.all { it.text.toByteArray().size <= 20 })
        assertTrue(result.pages.take(3).none { it.text.endsWith("...") })
        assertTrue(result.pages.last().text.endsWith("..."))
    }

    @Test
    fun tinyPeerLimitsAndOversizedGraphemesRemainBounded() {
        val payload = SocialPayload("Titolo", "12345")
        assertEquals(".", DisplayTextPolicy(1).render(payload).pages.single().text)
        assertEquals("..", DisplayTextPolicy(2).render(payload).pages.single().text)
        assertEquals(listOf("1", "2", "."), DisplayTextPolicy(1, paged = true, maxPages = 3).render(payload).pages.map { it.text })
        val oversized = "a" + "\u0301".repeat(128)
        val source = "prima " + oversized + " dopo"
        assertEquals("prima ...", DisplayTextPolicy(255, paged = true).render(payload.copy(text = source)).pages.single().text)
        assertEquals("...", DisplayTextPolicy(64, paged = true).render(payload.copy(text = oversized)).pages.single().text)
        assertEquals(DisplayTextPlan(listOf(payload.copy(text = "")), false), DisplayTextPolicy(1, paged = true).render(payload.copy(text = "")))
        assertThrows(IllegalArgumentException::class.java) { DisplayTextPolicy(0) }
    }

    @Test
    fun generatedUnicodeCasesRetainExactlyTheReachablePrefixWithoutDuplication() {
        val random = Random(300)
        val alphabet = listOf("x", "ù", "👋🏽", " ", "\n", " ", "🇮🇹", "e\u0300", "z")
        repeat(200) {
            val limit = random.nextInt(1, 40)
            val pageLimit = random.nextInt(1, 5)
            val payload = SocialPayload("Titolo", List(random.nextInt(0, 80)) { alphabet.random(random) }.joinToString(""))
            val plan = DisplayTextPolicy(limit, paged = true, maxPages = pageLimit).render(payload)
            val sourceClusters = SocialCodec.graphemes(payload.text).toList()
            fun expectedPrefix(markerSize: Int): String = buildString {
                val input = sourceClusters.iterator()
                var next = if (input.hasNext()) input.next() else null
                repeat(pageLimit) { page ->
                    var remaining = limit - if (page + 1 == pageLimit) markerSize else 0
                    while (next != null) {
                        val cluster = requireNotNull(next)
                        val size = cluster.toByteArray().size
                        if (size > remaining) break
                        append(cluster)
                        remaining -= size
                        next = if (input.hasNext()) input.next() else null
                    }
                }
            }

            val expectedTruncation = expectedPrefix(0) != payload.text
            val marker = if (expectedTruncation) ".".repeat(minOf(3, limit)) else ""
            assertTrue(plan.pages.size in 1..pageLimit)
            assertTrue(plan.pages.all { it.text.toByteArray().size <= limit })
            assertEquals(expectedPrefix(marker.length) + marker, plan.pages.joinToString("") { it.text })
            assertEquals(expectedTruncation, plan.truncated)
        }
    }
}
