package dev.stealthcompanion

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import dev.stealthcompanion.core.social.ConversationMessage
import dev.stealthcompanion.core.social.IncomingNotification

class PhoneNotificationListener : NotificationListenerService() {
    private val companion get() = (application as StealthApplication).companion

    override fun onListenerConnected() {
        companion.listenerChanged(true)
    }

    override fun onListenerDisconnected() {
        companion.listenerChanged(false)
        requestRebind(ComponentName(this, PhoneNotificationListener::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        if (sbn.packageName == packageName) return
        val ranking = Ranking()
        val ranked = rankingMap.getRanking(sbn.key, ranking)
        try {
            companion.accept(extract(sbn, ranking.takeIf { ranked }))
        } catch (_: RuntimeException) {
            // Bundles from third-party processes can be incomplete or unreadable.
            companion.recordUnreadable()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        companion.remove(sbn.key)
    }

    private fun extract(sbn: StatusBarNotification, rank: Ranking?): IncomingNotification {
        val notification = sbn.notification
        val extras = notification.extras
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        val ordered = style?.messages.orEmpty().sortedBy { it.timestamp }.takeLast(12)
        val messages = ordered.mapNotNull { message ->
            message.text?.bounded()?.let { text ->
                ConversationMessage(message.person?.name.bounded(), text, message.timestamp, message.person?.key)
            }
        }
        val latest = ordered.lastOrNull()?.person
        val senderName = latest?.name.bounded()
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE).bounded()
        val body = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT).bounded()
            ?: extras?.getCharSequence(Notification.EXTRA_TEXT).bounded()
            ?: extras?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                ?.takeLast(12)?.mapNotNull { it.bounded() }?.joinToString("\n").bounded()
            ?: extras?.getCharSequence(Notification.EXTRA_SUB_TEXT).bounded()
        val conversation = style?.conversationTitle.bounded()
            ?: extras?.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE).bounded()
            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && rank?.isConversation == true) {
                rank.conversationShortcutInfo?.shortLabel.bounded()
            } else null
        val channel = if (Build.VERSION.SDK_INT >= 26) notification.channelId else null
        val messageParts = sequenceOf(title, body) + messages.asSequence().map { it.text }
        val sensitive = notification.visibility == Notification.VISIBILITY_SECRET ||
            notification.category == Notification.CATEGORY_SYSTEM ||
            messageParts.filterNotNull().any(SensitiveNotification::containsCode)

        return IncomingNotification(
            key = sbn.key,
            packageName = sbn.packageName,
            appName = appLabel(sbn.packageName),
            title = title,
            text = body,
            sender = senderName ?: title,
            conversation = conversation,
            channelId = channel,
            messages = messages,
            groupSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            ongoing = sbn.isOngoing,
            silent = rank != null && rank.importance in NotificationManager.IMPORTANCE_MIN..NotificationManager.IMPORTANCE_LOW,
            sensitive = sensitive,
            timestamp = sbn.postTime,
            conversationId = if (Build.VERSION.SDK_INT >= 26) notification.shortcutId else null,
            contactId = latest?.key,
            senderIsExplicit = senderName != null,
        )
    }

    @Suppress("DEPRECATION")
    private fun appLabel(sourcePackage: String): String = try {
        val info = packageManager.getApplicationInfo(sourcePackage, 0)
        packageManager.getApplicationLabel(info).bounded() ?: sourcePackage
    } catch (_: PackageManager.NameNotFoundException) {
        sourcePackage
    }

    private fun CharSequence?.bounded(): String? {
        if (this == null) return null
        val end = length.coerceAtMost(4096)
        val adjustedEnd = if (end < length && this[end - 1].isHighSurrogate()) end - 1 else end
        return subSequence(0, adjustedEnd).toString().takeIf(String::isNotBlank)
    }
}
