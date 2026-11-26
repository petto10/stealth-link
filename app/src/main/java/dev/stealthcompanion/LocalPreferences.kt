package dev.stealthcompanion

import android.content.Context
import androidx.core.content.edit
import dev.stealthcompanion.ui.CompanionMode
import dev.stealthcompanion.ui.DisplayCapability
import dev.stealthcompanion.ui.LanguageMode
import dev.stealthcompanion.ui.ProbeObservation
import dev.stealthcompanion.ui.RuleUi
import dev.stealthcompanion.ui.ThemeMode
import org.json.JSONArray
import org.json.JSONObject

internal class LocalPreferences(context: Context) {
    private val data = context.getSharedPreferences("companion_preferences", Context.MODE_PRIVATE)

    var mode: CompanionMode
        get() = setting("mode", CompanionMode.SIMULATOR)
        set(value) = set("mode", value.name)
    var theme: ThemeMode
        get() = setting("theme", ThemeMode.SYSTEM)
        set(value) = set("theme", value.name)
    var language: LanguageMode
        get() = setting("language", LanguageMode.SYSTEM)
        set(value) = set("language", value.name)
    var privacyWhileLocked: Boolean
        get() = data.getBoolean("privacy", true)
        set(value) { data.edit { putBoolean("privacy", value) } }
    var extendedText: Boolean
        get() = data.getBoolean("extended_text", false)
        set(value) { data.edit { putBoolean("extended_text", value) } }
    var notificationsOnly: Boolean
        get() = data.getBoolean("notifications_only", false)
        set(value) { data.edit { putBoolean("notifications_only", value) } }

    fun nextLengthProbeId(): Long {
        val next = data.getLong("last_length_probe_id", 0L) + 1
        data.edit { putLong("last_length_probe_id", next) }
        return next
    }
    var configurationPeer: String?
        get() = data.getString("configuration_peer", null)
        set(value) = set("configuration_peer", value)
    var socialPeer: String?
        get() = data.getString("social_peer", null)
        set(value) = set("social_peer", value)

    fun rules(): Map<String, RuleUi> = readObject("rules").let { root ->
        root.keys().asSequence().mapNotNull { key ->
            root.optJSONObject(key)?.let { key to it.toRule() }
        }.toMap()
    }

    fun saveRules(rules: Map<String, RuleUi>) {
        val root = JSONObject()
        rules.forEach { (key, rule) ->
            root.put(key, JSONObject().apply {
                put("enabled", rule.enabled)
                put("silent", rule.allowSilent)
                put("appName", rule.includeAppName)
                put("channels", JSONArray(rule.allowedChannels.toList()))
                put("allowContacts", JSONArray(rule.allowedContacts.toList()))
                put("blockContacts", JSONArray(rule.blockedContacts.toList()))
                put("allowConversations", JSONArray(rule.allowedConversations.toList()))
                put("blockConversations", JSONArray(rule.blockedConversations.toList()))
            })
        }
        set("rules", root.toString())
    }

    fun observations(): Map<DisplayCapability, ProbeObservation> =
        DisplayCapability.entries.associateWith { setting("probe_${it.name}", ProbeObservation.UNTESTED) }

    fun saveObservation(capability: DisplayCapability, observation: ProbeObservation) =
        set("probe_${capability.name}", observation.name)

    private fun set(key: String, value: String?) {
        data.edit { putString(key, value) }
    }

    private inline fun <reified T : Enum<T>> setting(key: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == data.getString(key, null) } ?: fallback

    private fun readObject(key: String): JSONObject =
        data.getString(key, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()

    private fun JSONObject.toRule() = RuleUi(
        enabled = optBoolean("enabled"),
        allowSilent = optBoolean("silent"),
        includeAppName = optBoolean("appName", true),
        allowedChannels = strings("channels"),
        allowedContacts = strings("allowContacts"),
        blockedContacts = strings("blockContacts"),
        allowedConversations = strings("allowConversations"),
        blockedConversations = strings("blockConversations"),
    )

    private fun JSONObject.strings(key: String): Set<String> {
        val items = optJSONArray(key) ?: return emptySet()
        return List(items.length()) { items.optString(it) }.filter(String::isNotBlank).toSet()
    }
}
