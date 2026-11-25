package dev.stealthcompanion.core.social

import java.text.Normalizer
import java.util.Locale

data class ConversationMessage(
    val sender: String?,
    val text: String,
    val timestamp: Long,
    val contactId: String? = null,
    val messageId: String? = null,
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
    val conversationUpdate: ConversationUpdate? = null,
    val deliveryKey: String = key,
) {
    internal fun renderConversation(entries: List<ConversationEntry>): SocialMessage {
        val update = requireNotNull(conversationUpdate).copy(entries = entries)
        val subject = update.conversation ?: entries.last().sender ?: update.fallbackTitle
        val title = formatTitle(subject, update.app, update.fallbackText)
        val body = entries.takeLast(update.format.historyLines).asReversed().joinToString("\n") { entry ->
            formatBody(entry.text, entry.sender?.takeUnless { it == subject })
        }
        return copy(title = title, body = body, conversationUpdate = update)
    }
}

enum class SocialFormat(internal val historyLines: Int) {
    COMPACT(1), EXTENDED(3),
}

class ConversationEntry internal constructor(val identity: String, val sender: String?, val text: String)

@ConsistentCopyVisibility
data class ConversationUpdate internal constructor(
    val entries: List<ConversationEntry>,
    val conversation: String?,
    val fallbackTitle: String?,
    val app: String?,
    val format: SocialFormat,
    val fallbackText: String,
)

object SocialPolicy {
    private const val MAX_MESSAGE_WINDOW = 24
    private val whitespace = Regex("[\\p{Z}\\s]+")

    fun render(
        input: IncomingNotification,
        rule: AppRule,
        locked: Boolean = false,
        privateWhenLocked: Boolean = true,
        privateText: String = "New notification",
        format: SocialFormat = SocialFormat.COMPACT,
    ): SocialMessage? {
        if (!rule.enabled || input.sensitive || input.groupSummary) return null
        if ((input.silent && !rule.allowSilent) || (input.ongoing && !rule.allowOngoing)) return null
        if (rule.allowedChannels.isNotEmpty() && input.channelId !in rule.allowedChannels) return null
        if (!matchesRule(input.conversation, input.conversationId, rule.allowedConversations, rule.blockedConversations)) {
            return null
        }

        val app = (clean(input.appName) ?: clean(input.packageName))?.takeIf { rule.includeAppName }
        val conversation = clean(input.conversation)
        val sender = clean(input.sender)
        val title = clean(input.title)
        val messages = input.messages.asSequence()
            .mapNotNull { message -> clean(message.text)?.let { message.copy(text = it, sender = clean(message.sender)) } }
            .filter { message ->
                matchesRule(message.sender, message.contactId, rule.allowedContacts, rule.blockedContacts)
            }
            .sortedBy(ConversationMessage::timestamp)
            .toList()
        if (input.messages.isNotEmpty() && messages.isEmpty()) return null
        if (input.messages.isEmpty() && !matchesRule(sender, input.contactId, rule.allowedContacts, rule.blockedContacts)) {
            return null
        }

        if (locked && privateWhenLocked) {
            return SocialMessage(input.key, app ?: privateText, privateText, input.packageName)
        }

        if (messages.isNotEmpty()) {
            val entries = conversationEntries(messages)
            val update = ConversationUpdate(entries, conversation, title ?: sender, app, format, privateText)
            return SocialMessage(input.key, "", "", input.packageName, update).renderConversation(entries)
        }

        val subject = conversation ?: title ?: sender
        val text = clean(input.text)
        if (subject == null && text == null) return null
        val body = text?.let { formatBody(it, sender?.takeUnless { it == subject }) }.orEmpty()
        return SocialMessage(input.key, formatTitle(subject, app, privateText), body, input.packageName)
    }

    private fun conversationEntries(messages: List<ConversationMessage>): List<ConversationEntry> {
        val occurrences = mutableMapOf<String, Int>()
        return messages.mapNotNull { message ->
            val stableId = message.messageId?.takeIf { it.isNotBlank() }
            val author = message.contactId?.takeIf { it.isNotBlank() } ?: normalizeLabel(message.sender).orEmpty()
            val base = if (stableId == null) {
                socialDigest("inferred", author, message.timestamp.toString(), message.text)
            } else {
                socialDigest("explicit", stableId, message.text)
            }
            val occurrence = occurrences.getOrDefault(base, 0) + 1
            occurrences[base] = occurrence
            if (stableId != null && occurrence > 1) null
            else ConversationEntry("$base:$occurrence", message.sender, message.text)
        }.takeLast(MAX_MESSAGE_WINDOW)
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

private fun formatTitle(subject: String?, app: String?, fallback: String): String = when {
    subject == null -> app ?: fallback
    app != null && !subject.equals(app, ignoreCase = true) -> "$subject · $app"
    else -> subject
}

private fun formatBody(text: String, sender: String?): String =
    if (sender == null) text else "$text — $sender"
