package dev.stealthcompanion.core.social

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class SocialPolicyTest {
    private val allowed = AppRule(enabled = true)
    private val source = IncomingNotification("note", "dev.example.unlisted", "Esempio", "Prove", "Ciao")

    @Test
    fun requiresOptInAndWorksWithAnyPackage() {
        assertNull(SocialPolicy.render(source, AppRule()))
        assertEquals(SocialMessage("note", "Prove · Esempio", "Ciao", source.packageName), SocialPolicy.render(source, allowed))
    }

    @Test
    fun silentAndOngoingEventsHaveIndependentSettings() {
        assertNull(SocialPolicy.render(source.copy(silent = true), allowed))
        assertNull(SocialPolicy.render(source.copy(ongoing = true), allowed.copy(allowSilent = true)))
        assertEquals("Ciao", SocialPolicy.render(source.copy(silent = true), allowed.copy(allowSilent = true))?.body)
        assertEquals("Ciao", SocialPolicy.render(source.copy(ongoing = true), allowed.copy(allowOngoing = true))?.body)
    }

    @Test
    fun sensitiveEventsAndGroupSummariesAreExcluded() {
        assertNull(SocialPolicy.render(source.copy(sensitive = true), allowed))
        assertNull(SocialPolicy.render(source.copy(groupSummary = true), allowed))
    }

    @Test
    fun supportsTitleOnlyBodyOnlyAndEmptyNotifications() {
        assertEquals("", SocialPolicy.render(source.copy(text = null), allowed)?.body)
        assertEquals("Esempio", SocialPolicy.render(source.copy(title = null), allowed)?.title)
        assertNull(SocialPolicy.render(source.copy(text = " \n ", title = null), allowed))
    }

    @Test
    fun filterUsesExactChannelIdsAndNormalizedConversationLabels() {
        val conversation = source.copy(channelId = "chat", conversation = " Squadra   A", conversationId = "room42")
        val rule = allowed.copy(allowedChannels = setOf("chat"), allowedConversations = setOf("squadra a"))
        assertEquals("Squadra   A · Esempio", SocialPolicy.render(conversation, rule)?.title)
        assertNull(SocialPolicy.render(conversation.copy(channelId = null), rule))
        assertNull(SocialPolicy.render(conversation, rule.copy(blockedConversations = setOf("room42"))))
    }

    @Test
    fun contactFilterUsesNormalizedLabelsAndExplicitId() {
        val notification = source.copy(sender = "Andre\u0301a", contactId = "contact:42")
        val rule = allowed.copy(allowedContacts = setOf("ANDRÉA"))
        assertEquals("Ciao — Andréa", SocialPolicy.render(notification, rule)?.body)
        assertNull(SocialPolicy.render(notification, rule.copy(blockedContacts = setOf("contact:42"))))
        assertNull(SocialPolicy.render(source, rule))
    }

    @Test
    fun groupConversationRetainsOrderedMessagesAndHidesBlockedSenders() {
        val latest = ConversationMessage("Gabriella", "Pronti? 🏁", 4)
        val notification = source.copy(
            conversation = "Gruppo",
            messages = listOf(
                latest,
                ConversationMessage("Ada", "Voilà", 3),
                ConversationMessage("Blocked", "private", 2),
                ConversationMessage("Zoe", "Prima", 1),
                latest,
            ),
        )
        val compact = SocialPolicy.render(notification, allowed.copy(blockedContacts = setOf("blocked")))
        assertEquals("Gruppo · Esempio", compact?.title)
        assertEquals("Pronti? 🏁 — Gabriella", compact?.body)
        assertNull(SocialPolicy.render(notification, allowed.copy(allowedContacts = setOf("elsewhere"))))
    }

    @Test
    fun retainsLatestThreeInGroupEvenWhenInputIsOutOfOrder() {
        val notification = source.copy(
            conversation = "Gruppo",
            messages = (5 downTo 1).map { ConversationMessage(null, "Msg $it", it.toLong()) },
        )
        assertEquals("Msg 5\nMsg 4\nMsg 3", SocialPolicy.render(notification, allowed, format = SocialFormat.EXTENDED)?.body)
    }

    @Test
    fun explicitMessageIdentitySuppressesRepeatedCopiesButPreservesEdits() {
        val note = ConversationMessage("Ada", "Prima", 1, messageId = "message-1")
        val notification = source.copy(messages = listOf(note, note, note.copy(text = "Modificata", timestamp = 2)))
        val message = SocialPolicy.render(notification, allowed, format = SocialFormat.EXTENDED)
        assertEquals("Ada · Esempio", message?.title)
        assertEquals("Modificata\nPrima", message?.body)
        assertEquals(2, message?.conversationUpdate?.entries?.size)
    }

    @Test
    fun bodyOnlyCanHideTheApplicationNameWithLocalizedTitleFallback() {
        val notification = source.copy(title = null, text = "Ciao")
        val rendered = SocialPolicy.render(notification, allowed.copy(includeAppName = false), privateText = "Avviso")
        assertEquals("Avviso", rendered?.title)
        assertEquals("Ciao", rendered?.body)
    }

    @Test
    fun lockedModeShowsOnlyGenericTextAndOptionallyAppName() {
        val privateText = "Nuova notifica"
        val group = source.copy(sender = "Alessia", conversation = "Discussione privata")
        val masked = SocialPolicy.render(group, allowed, locked = true, privateText = privateText)
        assertEquals(SocialMessage("note", "Esempio", privateText, source.packageName), masked)
        val noAppName = SocialPolicy.render(group, allowed.copy(includeAppName = false), locked = true, privateText = privateText)
        assertEquals(SocialMessage("note", privateText, privateText, source.packageName), noAppName)
        assertEquals("Discussione privata · Esempio", SocialPolicy.render(group, allowed, locked = true, privateWhenLocked = false)?.title)
    }

    @Test
    fun stripsTransportControlCharactersAndPreservesUtf8Text() {
        val notification = source.copy(title = "Cafe\u0301 👩🏽‍💻", text = "日本語\r\nÈ qui\u0000")
        val rendered = SocialPolicy.render(notification, allowed.copy(includeAppName = false))
        assertEquals("Café 👩🏽‍💻", rendered?.title)
        assertEquals("日本語\nÈ qui", rendered?.body)
    }
}
