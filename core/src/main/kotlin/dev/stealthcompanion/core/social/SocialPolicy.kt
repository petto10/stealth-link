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
        val title = formatTitle(subject, update.app, update.fallbackText, update.format)
        val newestFirst = entries.takeLast(update.format.historyLines).asReversed()
        val body = if (update.format == SocialFormat.EXTENDED) {
            newestFirst.mapIndexed { index, entry ->
                formatExtendedBody(entry.text, entry.sender, subject, update.app, newestFirst.getOrNull(index - 1)?.sender)
            }.joinToString(" · ")
        } else {
            val entry = newestFirst.first()
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

        val app = (clean(input.appName, format) ?: clean(input.packageName, format))?.takeIf { rule.includeAppName }
        val conversation = clean(input.conversation, format)
        val sender = clean(input.sender, format)
        val title = clean(input.title, format)
        val messages = input.messages.asSequence()
            .mapNotNull { message ->
                clean(message.text, format, preserveLines = true)?.let { text ->
                    message.copy(text = text, sender = clean(message.sender, format))
                }
            }
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
        val text = clean(input.text, format, preserveLines = true)
        if (subject == null && text == null) return null
        val displayTitle = formatTitle(subject, app, privateText, format)
        val body = when {
            text == null -> ""
            format == SocialFormat.EXTENDED -> formatExtendedBody(text, sender, subject, app)
            else -> formatBody(text, sender?.takeUnless { it == subject })
        }
        return SocialMessage(input.key, displayTitle, body, input.packageName)
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

    private fun normalizeLabel(value: String?): String? = clean(value)?.let(::displayLabelKey)

    private fun clean(
        value: String?,
        format: SocialFormat = SocialFormat.COMPACT,
        preserveLines: Boolean = false,
    ): String? {
        if (value.isNullOrBlank()) return null
        val text = value.replace("\r\n", "\n").replace('\r', '\n')
            .filter { it == '\n' || it == '\t' || !it.isISOControl() }
            .trim()
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFC)
        if (format == SocialFormat.COMPACT) return normalized.takeIf(String::isNotEmpty)
        val separator = if (preserveLines) "\n" else " "
        return displayLineBreak.splitToSequence(normalized)
            .map { displayHorizontalWhitespace.replace(it.trim(), " ") }
            .filter(String::isNotEmpty)
            .joinToString(separator)
            .takeIf(String::isNotEmpty)
    }
}

private fun formatTitle(subject: String?, app: String?, fallback: String, format: SocialFormat): String = when {
    subject == null -> app ?: fallback
    app != null && format == SocialFormat.EXTENDED && hasDisplayLabel(subject, app) -> subject
    app != null && !subject.equals(app, ignoreCase = true) -> "$subject · $app"
    else -> subject
}

private fun formatBody(text: String, sender: String?): String =
    if (sender == null) text else "$text — $sender"

private fun formatExtendedBody(
    text: String,
    sender: String?,
    subject: String?,
    app: String?,
    previousSender: String? = null,
): String {
    if (sender == null || (subject != null && hasDisplayLabel(subject, sender)) ||
        (app != null && hasDisplayLabel(app, sender)) || hasDisplayLabel(text, sender) ||
        (previousSender != null && displayLabelKey(previousSender) == displayLabelKey(sender))) {
        return text
    }
    val firstLineEnd = text.indexOf('\n')
    if (firstLineEnd < 0) return formatBody(text, sender)
    return formatBody(text.substring(0, firstLineEnd), sender) + text.substring(firstLineEnd)
}

private const val HORIZONTAL_SPACE = "[\\p{Z}\\t]"
private val inlineLabelSeparator = Regex("\\n|$HORIZONTAL_SPACE*[:·|]$HORIZONTAL_SPACE+|$HORIZONTAL_SPACE+[-–—]$HORIZONTAL_SPACE+")
private val displayLabelWhitespace = Regex("[\\p{Z}\\s]+")
private val displayHorizontalWhitespace = Regex("[\\p{Zs}\\t]+")
private val displayLineBreak = Regex("[\\n\\u2028\\u2029]")

private fun hasDisplayLabel(value: String, label: String): Boolean {
    val expected = displayLabelKey(label)
    if (displayLabelKey(value) == expected) return true
    return inlineLabelSeparator.splitToSequence(value).any {
        val candidate = displayLabelKey(it)
        candidate == expected || candidate.removePrefix("— ") == expected
    }
}

private fun displayLabelKey(value: String): String = value.trim().replace(displayLabelWhitespace, " ").lowercase(Locale.ROOT)
