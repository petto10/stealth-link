package dev.stealthcompanion

import dev.stealthcompanion.core.protocol.SocialPayload

/** Keep each logical notification together and stop at the first delivery with an ambiguous result. */
internal suspend fun deliverInFeedOrder(
    pages: List<SocialPayload>,
    newestFirst: Boolean,
    delivery: suspend (index: Int, page: SocialPayload) -> Boolean,
): Boolean {
    val order = if (newestFirst) pages.indices.reversed() else pages.indices
    for (index in order) {
        if (!delivery(index, pages[index])) return false
    }
    return true
}
