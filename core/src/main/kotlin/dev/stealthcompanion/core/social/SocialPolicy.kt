package dev.stealthcompanion.core.social

import java.text.Normalizer
import java.util.Locale

data class ConversationMessage(
    val sender: String?,
    val text: String,
    val timestamp: Long,
    val contactId: String? = null,
)

data class IncomingNotification(
    val key: String,
    val packageName: String,
    val appName: String,
    val title: String? = null,
    val text: String? = null,
    val sender: String? = null,
    val conversation: String? = null,
    val channelId: String? = null,
    val messages: List<ConversationMessage> = emptyList(),
    val groupSummary: Boolean = false,
    val ongoing: Boolean = false,
    val silent: Boolean = false,
    val sensitive: Boolean = false,
    val timestamp: Long = 0,
    val conversationId: String? = null,
    val contactId: String? = null,
)

data class AppRule(
    val enabled: Boolean = false,
    val allowSilent: Boolean = false,
    val allowedChannels: Set<String> = emptySet(),
    val blockedContacts: Set<String> = emptySet(),
    val blockedConversations: Set<String> = emptySet(),
    val allowedContacts: Set<String> = emptySet(),
    val allowedConversations: Set<String> = emptySet(),
    val includeAppName: Boolean = true,
    val allowOngoing: Boolean = false,
)

data class SocialMessage(
    val key: String,
    val title: String,
    val body: String,
    val packageName: String? = null,
)

object SocialPolicy {
    private const val MAX_MESSAGES = 3
    private val whitespace = Regex("[\\p{Z}\\s]+")

    fun render(
        input: IncomingNotification,
        rule: AppRule,
        locked: Boolean = false,
        privateWhenLocked: Boolean = true,
        privateText: String = "New notification",
    ): SocialMessage? {
        if (!rule.enabled || input.sensitive || input.groupSummary) return null
        if ((input.silent && !rule.allowSilent) || (input.ongoing && !rule.allowOngoing)) return null
        if (rule.allowedChannels.isNotEmpty() && input.channelId !in rule.allowedChannels) return null
        if (!matchesRule(input.conversation, input.conversationId, rule.allowedConversations, rule.blockedConversations)) {
            return null
        }

        val app = clean(input.appName) ?: clean(input.packageName) ?: privateText
        val conversation = clean(input.conversation)
        val sender = clean(input.sender)
        val title = clean(input.title)
        val messages = input.messages.asSequence()
            .mapNotNull { message -> clean(message.text)?.let { message.copy(text = it, sender = clean(message.sender)) } }
            .filter { message ->
                matchesRule(message.sender, message.contactId, rule.allowedContacts, rule.blockedContacts)
            }
            .distinct()
            .sortedBy(ConversationMessage::timestamp)
            .toList()
            .takeLast(MAX_MESSAGES)
        if (input.messages.isNotEmpty() && messages.isEmpty()) return null
        if (input.messages.isEmpty() && !matchesRule(sender, input.contactId, rule.allowedContacts, rule.blockedContacts)) {
            return null
        }

        if (locked && privateWhenLocked) {
            return SocialMessage(input.key, app.takeIf { rule.includeAppName } ?: privateText, privateText, input.packageName)
        }

        val subject = conversation ?: messages.lastOrNull()?.sender ?: title ?: sender
        val displayTitle = when {
            subject == null -> app
            rule.includeAppName && subject != app -> "$subject · $app"
            else -> subject
        }
        val body = if (messages.isEmpty()) {
            val text = clean(input.text)
            if (text != null && sender != null && sender != subject) "$sender: $text" else text
        } else {
            // The display may truncate the field after a few bytes. Keep the latest text up front.
            messages.asReversed().joinToString("\n") { message ->
                message.sender?.takeUnless { it == subject }?.let { "${message.text} — $it" } ?: message.text
            }
        }
        if (subject == null && body == null) return null
        return SocialMessage(input.key, displayTitle, body.orEmpty(), input.packageName)
    }

    private fun matchesRule(label: String?, id: String?, allowed: Set<String>, blocked: Set<String>): Boolean {
        if (allowed.isEmpty() && blocked.isEmpty()) return true
        val normalizedLabel = normalizeLabel(label)
        fun matches(filter: String): Boolean = filter == id || normalizeLabel(filter)?.let { it == normalizedLabel } == true
        return blocked.none(::matches) && (allowed.isEmpty() || allowed.any(::matches))
    }

    private fun normalizeLabel(value: String?): String? = clean(value)?.replace(whitespace, " ")?.lowercase(Locale.ROOT)

    private fun clean(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val text = value.replace("\r\n", "\n").replace('\r', '\n')
            .filter { it == '\n' || it == '\t' || !it.isISOControl() }
            .trim()
        return Normalizer.normalize(text, Normalizer.Form.NFC).takeIf(String::isNotEmpty)
    }
}
