package dev.stealthcompanion.core.protocol

enum class ProbeText { ASCII, ACCENTS, EMOJI, MULTILINE }

/** Deterministic samples for comparing bytes transferred with text visible on the controller. */
data class LengthProbe(
    val bytes: Int = 32,
    val delayMs: Int = 0,
    val text: ProbeText = ProbeText.ASCII,
    val paged: Boolean = false,
) {
    init {
        require(bytes in SUPPORTED_SIZES)
        require(delayMs in SUPPORTED_DELAYS)
    }

    val finalMarker: String get() = "#$bytes"

    fun body(): String = buildString {
        var remaining = bytes - finalMarker.length
        var section = 1
        while (remaining > 0) {
            val token = section.toString().padStart(2, '0') + sectionText(section)
            val size = token.toByteArray(Charsets.UTF_8).size
            if (size > remaining) break
            append(token)
            remaining -= size
            section++
        }
        append(".".repeat(remaining))
        append(finalMarker)
    }

    /** Split synthetic text without changing the body or cutting a grapheme across pages. */
    fun pages(title: String, body: String, bodyBudget: Int = LEGACY_TEXT_BYTES): List<SocialPayload> {
        require(bodyBudget > 0)
        val slices = mutableListOf<String>()
        val current = StringBuilder()
        var used = 0
        val hasMarker = body.endsWith(finalMarker) && finalMarker.length <= bodyBudget
        val pieces = if (hasMarker) {
            SocialCodec.graphemes(body.dropLast(finalMarker.length)) + sequenceOf(finalMarker)
        } else {
            SocialCodec.graphemes(body)
        }
        for (grapheme in pieces) {
            val size = grapheme.toByteArray(Charsets.UTF_8).size
            require(size <= bodyBudget) { "A grapheme exceeds the page byte budget." }
            if (size > bodyBudget - used) {
                slices.add(current.toString())
                current.clear()
                used = 0
            }
            current.append(grapheme)
            used += size
        }
        slices.add(current.toString())
        return slices.mapIndexed { index, slice ->
            val prefix = "${index + 1}/${slices.size} "
            require(prefix.length <= LEGACY_TEXT_BYTES)
            val shortTitle = SocialCodec.encodeUtf8Within(title, LEGACY_TEXT_BYTES - prefix.length)
                .toString(Charsets.UTF_8)
            SocialPayload(prefix + shortTitle, slice)
        }
    }

    private fun sectionText(section: Int): String = when (text) {
        ProbeText.ASCII -> " abc "
        ProbeText.ACCENTS -> if (section % 2 == 0) " città " else "caffè "
        ProbeText.EMOJI -> if (section % 2 == 0) " 👋🏽 " else " 🙂 "
        ProbeText.MULTILINE -> " riga\n"
    }

    companion object {
        val SUPPORTED_SIZES: List<Int> = listOf(12, 20, 32, 64, 128, 200, 255)
        val SUPPORTED_DELAYS: List<Int> = listOf(0, 10, 25, 50, 100, 300)
        const val LEGACY_TEXT_BYTES = 12
    }
}
