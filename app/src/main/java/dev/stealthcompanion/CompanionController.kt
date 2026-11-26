package dev.stealthcompanion

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import dev.stealthcompanion.ble.BleDeviceRole
import dev.stealthcompanion.ble.BlePhase
import dev.stealthcompanion.ble.BleProbe
import dev.stealthcompanion.ble.BleRuntime
import dev.stealthcompanion.core.protocol.ControllerSimulator
import dev.stealthcompanion.core.protocol.DisplayTextPolicy
import dev.stealthcompanion.core.protocol.LengthProbe
import dev.stealthcompanion.core.protocol.ProtocolOptions
import dev.stealthcompanion.core.protocol.SocialCodec
import dev.stealthcompanion.core.protocol.SocialPayload
import dev.stealthcompanion.core.protocol.SocialSession
import dev.stealthcompanion.core.social.AppRule
import dev.stealthcompanion.core.social.IncomingNotification
import dev.stealthcompanion.core.social.SocialFormat
import dev.stealthcompanion.core.social.SocialMessage
import dev.stealthcompanion.core.social.SocialPolicy
import dev.stealthcompanion.core.social.SocialQueue
import dev.stealthcompanion.profiles.ProfileStore
import dev.stealthcompanion.ui.CompanionMode
import dev.stealthcompanion.ui.ConfigurationUi
import dev.stealthcompanion.ui.ConnectionState
import dev.stealthcompanion.ui.ControllerRole
import dev.stealthcompanion.ui.ControllerUi
import dev.stealthcompanion.ui.DiagnosticsUi
import dev.stealthcompanion.ui.DisplayCapability
import dev.stealthcompanion.ui.DisplayPreviewUi
import dev.stealthcompanion.ui.LanguageMode
import dev.stealthcompanion.ui.LengthProbeOutcome
import dev.stealthcompanion.ui.ObservedAppUi
import dev.stealthcompanion.ui.ProbeObservation
import dev.stealthcompanion.ui.ProfileUi
import dev.stealthcompanion.ui.RuleUi
import dev.stealthcompanion.ui.StealthUiState
import dev.stealthcompanion.ui.ThemeMode
import dev.stealthcompanion.ui.UiFeedback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal class CompanionController(private val context: Context) {
    private data class Synthetic(
        val title: String,
        val body: String,
        val capability: DisplayCapability? = null,
        val extended: Boolean? = null,
        val lengthRun: LengthRun? = null,
    )

    private data class LengthRun(val id: Long, val spec: LengthProbe, val pages: List<SocialPayload>)

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val profiles = ProfileStore(context)
    private val preferences = LocalPreferences(context)
    private val rules = preferences.rules().toMutableMap()
    private val apps = LinkedHashMap<String, ObservedAppUi>()
    private val queue = SocialQueue()
    private val tests = ArrayDeque<Synthetic>()
    private val lengthHistory = LengthProbeHistory()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val mutableState = MutableStateFlow(
        StealthUiState(
            mode = preferences.mode,
            isRunning = preferences.mode == CompanionMode.SIMULATOR,
            connection = if (preferences.mode == CompanionMode.SIMULATOR) ConnectionState.READY else ConnectionState.IDLE,
            selectedDeviceId = preferences.configurationPeer,
            selectedSocialDeviceId = preferences.socialPeer,
            theme = preferences.theme,
            language = preferences.language,
            privacyWhileLocked = preferences.privacyWhileLocked,
            extendedText = preferences.extendedText,
            fragmentDelayMs = preferences.fragmentDelayMs,
            bodyByteLimit = preferences.bodyByteLimit,
            paginateNotifications = preferences.paginateNotifications,
            notificationsOnly = preferences.notificationsOnly,
            observations = preferences.observations(),
        ),
    )
    val state = mutableState.asStateFlow()
    private var activeDelivery: Job? = null
    private var activePersonalKey: String? = null
    private var serial = 0L
    private var sent = 0
    private var transportDrops = 0
    private var unreadable = 0
    private var personalPagesSent = 0L
    private var truncatedPlans = 0L
    private var simulatedSample: DiagnosticsUi? = null

    init {
        BleRuntime.setLongTextEnabled(preferences.extendedText)
        BleRuntime.setExtendedFragmentDelayMs(preferences.fragmentDelayMs)
        BleRuntime.setNotificationsOnly(preferences.notificationsOnly)
        rules.forEach { (id, rule) -> apps[id] = ObservedAppUi(id, id, rule = rule) }
        updateApps()
        refreshProfiles()
        scope.launch {
            BleRuntime.state.collect { status ->
                if (state.value.mode == CompanionMode.CONTROLLER) {
                    if (!status.socialReady && state.value.connection == ConnectionState.READY) {
                        discardQueuedTests { it.lengthRun != null }
                    }
                    update { ui ->
                        val settings = status.settings
                        ui.copy(
                            isRunning = status.phase !in setOf(BlePhase.IDLE, BlePhase.PERMISSION_REQUIRED),
                            connection = when {
                                status.socialReady -> ConnectionState.READY
                                status.phase == BlePhase.IDLE -> ConnectionState.IDLE
                                status.phase == BlePhase.RETRYING -> ConnectionState.RETRYING
                                status.phase == BlePhase.WAITING_FOR_SUBSCRIPTION -> ConnectionState.WAITING_FOR_SUBSCRIPTION
                                status.phase in setOf(BlePhase.UNAVAILABLE, BlePhase.BLUETOOTH_OFF, BlePhase.PERMISSION_REQUIRED) ->
                                    ConnectionState.UNAVAILABLE
                                else -> ConnectionState.CONNECTING
                            },
                            configuration = ConfigurationUi(
                                connected = status.settingsConnected,
                                reading = settings.reading,
                                complete = settings.complete,
                                powerMode = settings.powerManagement?.powerMode ?: settings.system?.powerMode,
                                displayBrightness = settings.powerManagement?.screenBrightness ?: settings.system?.screenBrightness,
                                ledBrightness = settings.system?.ledBrightness,
                                screenTimeoutMinutes = settings.powerManagement?.screenTimeoutMinutes ?: settings.system?.screenTimeoutMinutes,
                                sleepTimeoutMinutes = settings.powerManagement?.sleepTimeoutMinutes ?: settings.system?.sleepTimeoutMinutes,
                                chargeLedBrightness = settings.chargeLedBrightness ?: settings.system?.chargeLedBrightness,
                                batteryPercent = settings.deviceStatus.batteryPercent,
                                firmwareRevision = settings.deviceStatus.firmwareRevision,
                                hardwareRevision = settings.deviceStatus.hardwareRevision,
                                softwareRevision = settings.deviceStatus.softwareRevision,
                            ),
                            profiles = ui.profiles.copy(
                                activeSlot = settings.activeSlot,
                                slotNames = settings.profiles,
                            ),
                        )
                    }
                }
                signal()
            }
        }
        scope.launch { drain() }
    }

    fun refreshPermissions() {
        val granted = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        if (!granted && state.value.notificationAccess) discardPrivateData()
        update { it.copy(notificationAccess = granted) }
    }

    fun listenerChanged(connected: Boolean) {
        scope.launch {
            if (!connected) discardPrivateData()
            refreshPermissions()
        }
    }

    fun refreshDevices(bluetoothAllowed: Boolean) {
        val devices = if (!bluetoothAllowed) emptyList() else BleRuntime.bondedDevices().map {
            ControllerUi(
                id = it.address,
                name = it.displayName,
                role = when (it.role) {
                    BleDeviceRole.CONFIGURATION -> ControllerRole.CONFIGURATION
                    BleDeviceRole.SOCIAL -> ControllerRole.SOCIAL
                    BleDeviceRole.BOTH -> ControllerRole.BOTH
                },
            )
        }
        update { it.copy(bluetoothPermission = bluetoothAllowed, devices = devices) }
    }

    fun accept(input: IncomingNotification) {
        scope.launch {
            val previous = apps[input.packageName]
            val channels = previous?.observedChannels.orEmpty()
            val observed = input.channelId?.takeIf { channels.size < 64 }?.let { channels + it } ?: channels
            val rule = rules[input.packageName]
            val row = ObservedAppUi(input.packageName, input.appName, observed, rule ?: RuleUi())
            if (previous != row) {
                apps[input.packageName] = row
                updateApps()
            }
            val ui = state.value
            if (rule == null || ui.userPaused || ui.probesEnabled || !ui.isRunning) return@launch

            val message = SocialPolicy.render(
                input, rule.toDomain(),
                locked = phoneLocked(),
                privateWhenLocked = ui.privacyWhileLocked,
                privateText = privateText(),
                format = if (ui.extendedText) SocialFormat.EXTENDED else SocialFormat.COMPACT,
            )
            if (message == null) {
                removeQueuedNotification(input.key)
            } else {
                queue.offer(message, SystemClock.elapsedRealtime())
            }
            signal()
        }
    }

    fun remove(key: String) {
        scope.launch {
            removeQueuedNotification(key)
            signal()
        }
    }

    private fun removeQueuedNotification(key: String) {
        queue.remove(key)
        if (activePersonalKey == key) activeDelivery?.cancel()
    }

    fun recordUnreadable() {
        scope.launch { unreadable++ }
    }

    fun setMode(mode: CompanionMode) {
        if (state.value.mode == mode) return
        stop()
        preferences.mode = mode
        update { ui ->
            ui.copy(
                mode = mode,
                isRunning = mode == CompanionMode.SIMULATOR,
                connection = if (mode == CompanionMode.SIMULATOR) ConnectionState.READY else ConnectionState.IDLE,
                lastLengthProbeId = null,
                lastLengthProbeOutcome = null,
                configuration = ConfigurationUi(),
                profiles = ui.profiles.copy(activeSlot = null, slotNames = emptyMap()),
            )
        }
        signal()
    }

    fun selectConfiguration(id: String) {
        if (state.value.devices.none { it.id == id && it.role != ControllerRole.SOCIAL }) return
        stop()
        preferences.configurationPeer = id
        update { it.copy(selectedDeviceId = id) }
    }

    fun selectSocial(id: String?) {
        if (id != null && state.value.devices.none { it.id == id && it.role != ControllerRole.CONFIGURATION }) return
        stop()
        preferences.socialPeer = id
        update { it.copy(selectedSocialDeviceId = id) }
    }

    fun start() {
        val ui = state.value
        when (ui.mode) {
            CompanionMode.SIMULATOR -> update { it.copy(isRunning = true, connection = ConnectionState.READY) }
            CompanionMode.CONTROLLER -> {
                val approvedConfiguration = ui.selectedDeviceId?.takeIf { id ->
                    ui.devices.any { it.id == id && it.role != ControllerRole.SOCIAL }
                }
                val approvedSocial = ui.selectedSocialDeviceId?.takeIf { id ->
                    ui.devices.any { it.id == id && it.role != ControllerRole.CONFIGURATION }
                }
                val primary = approvedConfiguration ?: approvedSocial
                if (!ui.bluetoothPermission || primary == null) {
                    update { it.copy(connection = ConnectionState.UNAVAILABLE) }
                    return
                }
                BleRuntime.start(primary, approvedSocial)
            }
        }
        signal()
    }

    fun stop() {
        discardQueuedTests { true }
        simulatedSample = null
        activeDelivery?.cancel()
        discardPrivateData()
        BleRuntime.stop()
        BleRuntime.setProbeOptions()
        update { ui ->
            ui.copy(
                isRunning = false, connection = ConnectionState.IDLE, preview = null,
                probesEnabled = false, relaxedSynthetic = false, configuration = ConfigurationUi(),
                profiles = ui.profiles.copy(activeSlot = null, slotNames = emptyMap()),
            )
        }
        signal()
    }

    fun recoverConnection() {
        val ui = state.value
        if (ui.mode != CompanionMode.CONTROLLER || !ui.bluetoothPermission) return
        if (!ui.isRunning) {
            start()
            return
        }
        activeDelivery?.cancel()
        discardQueuedTests { true }
        BleRuntime.recoverConnection()
        signal()
    }

    fun pause(value: Boolean) {
        if (value) discardPrivateData()
        update { it.copy(userPaused = value) }
        signal()
    }

    fun setPrivacy(value: Boolean) {
        discardPrivateData()
        preferences.privacyWhileLocked = value
        update { it.copy(privacyWhileLocked = value) }
    }

    fun setExtendedText(value: Boolean) {
        if (state.value.extendedText == value) return
        discardPrivateData()
        preferences.extendedText = value
        BleRuntime.setLongTextEnabled(value)
        update { it.copy(extendedText = value) }
        signal()
    }

    fun setFragmentDelay(value: Int) {
        if (value !in LengthProbe.SUPPORTED_DELAYS) return
        preferences.fragmentDelayMs = value
        BleRuntime.setExtendedFragmentDelayMs(value)
        update { it.copy(fragmentDelayMs = value) }
    }

    fun setBodyByteLimit(value: Int) {
        if (value !in DisplayTextPolicy.BODY_LIMIT_CHOICES) return
        preferences.bodyByteLimit = value
        update { it.copy(bodyByteLimit = value) }
    }

    fun setPaginateNotifications(value: Boolean) {
        preferences.paginateNotifications = value
        update { it.copy(paginateNotifications = value) }
    }

    fun setNotificationsOnly(value: Boolean) {
        if (state.value.notificationsOnly == value) return
        preferences.notificationsOnly = value
        BleRuntime.setNotificationsOnly(value)
        update { it.copy(notificationsOnly = value) }
        signal()
    }

    fun setSimulatorMtu(value: Int) {
        if (value !in setOf(23, 64, 185) || state.value.simulatorMtu == value) return
        simulatedSample = null
        update { it.copy(simulatorMtu = value, preview = null) }
        signal()
    }

    fun screenTurnedOff() {
        if (state.value.privacyWhileLocked) discardPrivateData()
    }

    fun setRule(sourcePackage: String, rule: RuleUi) {
        if (sourcePackage !in apps) return
        discardPrivateData()
        rules[sourcePackage] = rule
        preferences.saveRules(rules)
        apps.computeIfPresent(sourcePackage) { _, row -> row.copy(rule = rule) }
        updateApps()
    }

    fun setTheme(value: ThemeMode) {
        preferences.theme = value
        update { it.copy(theme = value) }
    }

    fun setLanguage(value: LanguageMode) {
        preferences.language = value
        update { it.copy(language = value) }
    }

    fun setProbeMode(enabled: Boolean) {
        activeDelivery?.cancel()
        if (enabled) discardPrivateData() else {
            discardQueuedTests { it.capability != null || it.lengthRun != null }
            setRelaxedSynthetic(false)
        }
        update { it.copy(probesEnabled = enabled) }
        signal()
    }

    fun setRelaxedSynthetic(enabled: Boolean) {
        val ui = state.value
        if (enabled && (ui.mode != CompanionMode.CONTROLLER || !ui.probesEnabled)) return
        if (enabled == ui.relaxedSynthetic) return
        activeDelivery?.cancel()
        discardPrivateData()
        update { it.copy(relaxedSynthetic = enabled) }
        BleRuntime.setProbeOptions(syntheticOnlyUnencrypted = enabled)
    }

    fun setObservation(capability: DisplayCapability, observation: ProbeObservation) {
        preferences.saveObservation(capability, observation)
        update { it.copy(observations = it.observations + (capability to observation)) }
    }

    fun queueSynthetic(title: String, body: String) {
        if (title.isBlank() && body.isBlank()) return
        queueTest(Synthetic(title.take(1024), body.take(4096)))
    }

    fun runProbe(capability: DisplayCapability, extended: Boolean? = null) {
        if (!state.value.probesEnabled) return
        val longSample = capability == DisplayCapability.LONG_TEXT || capability == DisplayCapability.SCROLL
        val title = if (longSample) "Notifica estesa · prova Social" else "Lab · ${capability.name}"
        val body = if (longSample) {
            "Caffè pronto · già qui 🎮\n" +
                (1..12).joinToString("\n") { "${it.toString().padStart(2, '0')} · Città, perché — 🕹️" }
        } else {
            "Social test · À È Ì Ò Ù · 🔔"
        }
        queueTest(Synthetic(title, body, capability, extended))
    }

    fun sendLengthProbe(spec: LengthProbe, extended: Boolean) {
        val ui = state.value
        if (!ui.probesEnabled || ui.userPaused || !canQueueTest(ui)) {
            tell(UiFeedback.PROBE_UNAVAILABLE)
            return
        }
        val title = "Length ${spec.bytes}"
        val body = spec.body()
        val pages = if (spec.paged) spec.pages(title, body) else listOf(SocialPayload(title, body))
        val run = LengthRun(preferences.nextLengthProbeId(), spec, pages)
        val fragmented = extended && !spec.paged
        lengthHistory.queued(run.id, spec, fragmented, ui.mode, pages.size)
        update { it.copy(lastLengthProbeId = run.id, lastLengthProbeOutcome = LengthProbeOutcome.QUEUED) }
        queueTest(Synthetic(title, body, extended = fragmented, lengthRun = run))
    }

    fun markLengthProbeDetail() {
        val ui = state.value
        if (ui.mode != CompanionMode.CONTROLLER || ui.lastLengthProbeOutcome !in setOf(
                LengthProbeOutcome.GATT_COMPLETE, LengthProbeOutcome.INCOMPLETE,
            )) return
        ui.lastLengthProbeId?.let(BleRuntime::markProbeDetail)
    }

    fun readSettings() {
        if (state.value.mode != CompanionMode.CONTROLLER || !BleRuntime.state.value.settingsConnected) {
            tell(UiFeedback.SETTINGS_UNAVAILABLE)
        } else {
            BleRuntime.refreshSettings()
            tell(UiFeedback.SETTINGS_REQUESTED)
        }
    }

    fun refreshProfiles() {
        scope.launch {
            val rows = withContext(Dispatchers.IO) { runCatching { profiles.snapshot() } }
            rows.onSuccess { catalog ->
                update { ui ->
                    ui.copy(
                        profiles = ui.profiles.copy(
                            local = catalog.profiles.map { ProfileUi(it.id, it.name) },
                            selectedLocalProfileId = catalog.activeProfileId,
                        ),
                    )
                }
            }.onFailure { tell(UiFeedback.FILE_ERROR) }
        }
    }

    fun addProfile(name: String) = editProfiles { profiles.create(name) }

    fun removeProfile(id: String) = editProfiles { profiles.delete(id) }

    fun renameProfile(id: String, name: String) = editProfiles { check(profiles.rename(id, name)) }

    fun selectLocalProfile(id: String?) = editProfiles { check(profiles.setActive(id)) }

    fun tell(message: UiFeedback?) = update { it.copy(feedback = message) }

    fun diagnostics(): String {
        val current = queue.stats
        return buildString {
            appendLine("Stealth Link / diagnostics v4")
            appendLine("mode=${state.value.mode}")
            appendLine("extended_text=${state.value.extendedText}")
            appendLine("normal_fragment_delay_ms=${state.value.fragmentDelayMs}")
            appendLine("normal_body_limit_bytes=${state.value.bodyByteLimit}")
            appendLine("normal_pagination=${state.value.paginateNotifications}")
            appendLine("normal_pages_completed=$personalPagesSent")
            appendLine("normal_truncated_plans=$truncatedPlans")
            if (state.value.mode == CompanionMode.SIMULATOR) {
                appendLine("simulator_mtu=${state.value.simulatorMtu}")
                simulatedSample?.let { sample ->
                    appendLine("simulator_attribute_requests=${sample.attributeRequests}")
                    appendLine("simulator_fragments_sent=${sample.fragmentsSent}")
                    appendLine("simulator_sent_title_bytes=${sample.lastSentTitleBytes}")
                    appendLine("simulator_sent_body_bytes=${sample.lastSentBodyBytes}")
                }
            }
            appendLine("notification_access=${state.value.notificationAccess}")
            appendLine("accepted=${current.accepted}")
            appendLine("coalesced=${current.coalesced}")
            appendLine("duplicates=${current.duplicates}")
            appendLine("expired=${current.expired}")
            appendLine("overflow=${current.overflow}")
            appendLine("transport_drops=$transportDrops")
            appendLine("unreadable_notifications=$unreadable")
            appendLine("transport_completed=$sent")
            lengthHistory.appendTo(this)
            append(BleRuntime.exportDiagnostics())
        }
    }

    private fun editProfiles(action: () -> Any) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching(action) }
            if (result.isSuccess) refreshProfiles() else tell(UiFeedback.FILE_ERROR)
        }
    }

    private fun discardPrivateData() {
        if (activePersonalKey != null) activeDelivery?.cancel()
        queue.clear()
        BleRuntime.discardPersonal()
        signal()
    }

    private fun queueTest(value: Synthetic) {
        val probe = value.capability != null || value.lengthRun != null
        if (!canQueueTest(state.value)) {
            tell(if (probe) UiFeedback.PROBE_UNAVAILABLE else UiFeedback.TEST_UNAVAILABLE)
            return
        }
        tests.addLast(value)
        tell(if (probe) UiFeedback.PROBE_QUEUED else UiFeedback.TEST_QUEUED)
        signal()
    }

    private fun canQueueTest(ui: StealthUiState) =
        ui.isRunning && ui.connection == ConnectionState.READY && tests.size < 12

    private fun discardQueuedTests(predicate: (Synthetic) -> Boolean) {
        tests.removeAll { test ->
            if (!predicate(test)) return@removeAll false
            test.lengthRun?.let { finishLengthRun(it, 0, LengthProbeOutcome.INCOMPLETE) }
            true
        }
    }

    private fun finishLengthRun(run: LengthRun, completed: Int, outcome: LengthProbeOutcome) {
        lengthHistory.finish(run.id, completed, outcome)
        update { ui ->
            if (ui.lastLengthProbeId == run.id) ui.copy(lastLengthProbeOutcome = outcome) else ui
        }
    }

    private suspend fun drain() {
        while (true) {
            val now = SystemClock.elapsedRealtime()
            queue.expire(now)
            val ui = state.value
            val ready = ui.isRunning && ui.connection == ConnectionState.READY
            val synthetic = if (ready) tests.removeFirstOrNull() else null
            if (synthetic != null) {
                runDelivery { deliverTest(synthetic, ui.mode) }
                signal()
                continue
            }
            if (ready && !ui.userPaused && !ui.probesEnabled) {
                val next = queue.poll(now)
                if (next != null) {
                    runDelivery {
                        activePersonalKey = next.key
                        try {
                            if (deliverPersonal(next, ui)) sent++ else transportDrops++
                        } finally {
                            activePersonalKey = null
                        }
                    }
                    signal()
                    continue
                }
            }
            val nextReadyAt = if (ready && !ui.userPaused && !ui.probesEnabled) queue.nextReadyAt(now) else null
            val nextExpirationAt = queue.nextExpirationAt(now)
            val nextAt = listOfNotNull(nextReadyAt, nextExpirationAt).minOrNull()
            if (nextAt == null) wake.receive()
            else withTimeoutOrNull((nextAt - now).coerceAtLeast(1)) { wake.receive() }
            updateDiagnostics()
        }
    }

    private suspend fun runDelivery(action: suspend () -> Unit) = coroutineScope {
        val job = launch { action() }
        activeDelivery = job
        job.join()
        if (activeDelivery == job) activeDelivery = null
    }

    private suspend fun deliverPersonal(message: SocialMessage, ui: StealthUiState): Boolean {
        val lockedAtStart = ui.privacyWhileLocked && phoneLocked()
        val hiddenText = if (lockedAtStart) privateText() else null
        val payload = SocialPayload(hiddenText ?: message.title, hiddenText ?: message.body)
        val peerLimit = if (ui.mode == CompanionMode.CONTROLLER) BleRuntime.state.value.currentMessageLimitBytes else null
        val bodyLimit = minOf(ui.bodyByteLimit, peerLimit?.coerceIn(1, 255) ?: ui.bodyByteLimit)
        val pages = if (ui.extendedText) {
            DisplayTextPolicy(bodyLimitBytes = bodyLimit, paged = ui.paginateNotifications && !lockedAtStart)
                .render(payload).also { if (it.truncated) truncatedPlans++ }.pages
        } else {
            listOf(payload)
        }
        for ((index, page) in pages.withIndex()) {
            if (!lockedAtStart && ui.privacyWhileLocked && phoneLocked()) return false
            if (state.value.connection != ConnectionState.READY) return false
            val deliveryKey = if (pages.size == 1) message.deliveryKey else {
                "${message.deliveryKey.length}:${message.deliveryKey}:${index + 1}/${pages.size}"
            }
            val completed = when (ui.mode) {
                CompanionMode.SIMULATOR -> simulate(
                    message.copy(title = page.title, body = page.text, deliveryKey = deliveryKey),
                    ProtocolOptions(), 4, false, ui.extendedText,
                ) != null
                CompanionMode.CONTROLLER -> BleRuntime.send(deliveryKey, page.title, page.text, message.packageName)
            }
            if (!completed) return false
            personalPagesSent++
        }
        return true
    }

    private suspend fun deliverTest(test: Synthetic, mode: CompanionMode) {
        test.lengthRun?.let { return deliverLengthRun(it, mode, test.extended == true) }
        val capability = test.capability
        val extended = test.extended ?: when (capability) {
            DisplayCapability.LONG_TEXT, DisplayCapability.SCROLL -> true
            null -> state.value.extendedText
            else -> false
        }
        val options = when (capability) {
            DisplayCapability.APP_ICONS -> ProtocolOptions(
                allowApplicationIdentifier = true, allowMultipleAttributes = true,
                allowFragmentation = true, maxAttributeBytes = 1024,
            )
            DisplayCapability.CATEGORIES -> ProtocolOptions(allowCustomCategories = true)
            DisplayCapability.UPDATE -> ProtocolOptions(allowUpdates = true)
            DisplayCapability.REMOVAL -> ProtocolOptions(allowRemovals = true)
            else -> ProtocolOptions()
        }
        val category = if (capability == DisplayCapability.CATEGORIES) 6 else 4
        val message = SocialMessage("synthetic-${++serial}", test.title, test.body, SYNTHETIC_APP)
        val result = when (mode) {
            CompanionMode.SIMULATOR -> {
                val preview = simulate(message, options, category, true, extended, capability)
                update { it.copy(preview = preview) }
                preview != null || capability == DisplayCapability.REMOVAL
            }
            CompanionMode.CONTROLLER -> deliverBleTest(message, options, category, extended, capability)
        }
        if (result) sent++ else transportDrops++
        if (capability != null) tell(if (result) UiFeedback.PROBE_MANUAL else UiFeedback.PROBE_UNAVAILABLE)
        else if (!result) tell(UiFeedback.TEST_UNAVAILABLE)
    }

    private suspend fun deliverLengthRun(run: LengthRun, mode: CompanionMode, extended: Boolean) {
        var completed = 0
        val previews = mutableListOf<DisplayPreviewUi>()
        try {
            for ((index, page) in run.pages.withIndex()) {
                val key = "length-${run.id}-${index + 1}"
                val message = SocialMessage(key, page.title, page.text, SYNTHETIC_APP)
                val success = when (mode) {
                    CompanionMode.SIMULATOR -> {
                        val preview = simulate(message, ProtocolOptions(), 4, true, extended, requestedBytes = 255)
                        if (preview != null) previews += preview
                        preview != null
                    }
                    CompanionMode.CONTROLLER -> BleRuntime.send(
                        key, page.title, page.text, SYNTHETIC_APP, synthetic = true,
                        longTextOverride = extended,
                        probe = BleProbe(run.id, if (run.spec.paged) 0 else run.spec.delayMs, index + 1, run.pages.size),
                    )
                }
                if (!success) break
                completed++
            }
        } finally {
            if (mode == CompanionMode.SIMULATOR && state.value.mode == mode) {
                val preview = if (run.spec.paged && previews.isNotEmpty()) {
                    DisplayPreviewUi("${previews.size}/${run.pages.size}", previews.joinToString("\n") { "${it.title}\n${it.body}" })
                } else previews.firstOrNull()
                update { it.copy(preview = preview) }
            }
            val outcome = when {
                completed != run.pages.size -> LengthProbeOutcome.INCOMPLETE
                mode == CompanionMode.SIMULATOR -> LengthProbeOutcome.SIMULATOR_COMPLETE
                else -> LengthProbeOutcome.GATT_COMPLETE
            }
            if (outcome == LengthProbeOutcome.INCOMPLETE) transportDrops++ else sent++
            finishLengthRun(run, completed, outcome)
        }
    }

    private suspend fun deliverBleTest(
        message: SocialMessage,
        options: ProtocolOptions,
        category: Int,
        extended: Boolean,
        capability: DisplayCapability?,
    ): Boolean {
        val usesExperiment = options != ProtocolOptions()
        if (usesExperiment) BleRuntime.setProbeOptions(options, state.value.relaxedSynthetic)
        try {
            if (usesExperiment) {
                withTimeoutOrNull(15_000) {
                    BleRuntime.state.first { it.socialReady && it.syntheticOnly }
                } ?: return false
            }
            val initial = BleRuntime.send(
                message.key, message.title, message.body, SYNTHETIC_APP, true, category,
                applicationName = SYNTHETIC_APP_NAME.takeIf { options.allowApplicationIdentifier },
                longTextOverride = extended,
            )
            if (!initial) return false
            if (capability == DisplayCapability.UPDATE || capability == DisplayCapability.REMOVAL) delay(1500)
            if (state.value.mode != CompanionMode.CONTROLLER || !state.value.isRunning) return false
            val result = when (capability) {
                DisplayCapability.UPDATE ->
                    BleRuntime.send(message.key, "Lab · updated", "2 · Updated synthetic text", SYNTHETIC_APP, true, category)
                DisplayCapability.REMOVAL -> BleRuntime.remove(message.key)
                else -> true
            }
            // Leave time for optional App Attributes and detail-view requests before restoring the session.
            if (result && usesExperiment) delay(PROBE_OBSERVATION_MS)
            return result
        } finally {
            if (usesExperiment) BleRuntime.setProbeOptions(syntheticOnlyUnencrypted = state.value.relaxedSynthetic)
        }
    }

    private fun simulate(
        message: SocialMessage,
        options: ProtocolOptions,
        category: Int,
        synthetic: Boolean,
        extended: Boolean,
        capability: DisplayCapability? = null,
        requestedBytes: Int = 1024,
    ): DisplayPreviewUi? {
        val simulator = ControllerSimulator(
            mtu = state.value.simulatorMtu,
            requestedTitleBytes = requestedBytes,
            requestedMessageBytes = requestedBytes,
            requestApplicationIdentifier = options.allowApplicationIdentifier,
        )
        val session = SocialSession(options)
        val now = SystemClock.elapsedRealtime()
        var requests = 0
        var fragments = 0
        fun feed(title: String, body: String) {
            val payload = SocialPayload(title, body, if (synthetic) SYNTHETIC_APP else null, SYNTHETIC_APP_NAME)
            val event = session.publish(message.deliveryKey, payload, now, category, allowLongResponse = extended) ?: return
            val result = simulator.receive(event) { request, mtu ->
                requests++
                session.respond(request, mtu, now).also { fragments += it.size }
            }
            if (synthetic) {
                simulatedSample = DiagnosticsUi(
                    attributeRequests = requests,
                    fragmentsSent = fragments,
                    lastRequestedTitleBytes = requestedBytes,
                    lastRequestedBodyBytes = requestedBytes,
                    lastSentTitleBytes = result?.title?.toByteArray(Charsets.UTF_8)?.size,
                    lastSentBodyBytes = result?.text?.toByteArray(Charsets.UTF_8)?.size,
                )
            }
        }
        feed(message.title, message.body)
        when (capability) {
            DisplayCapability.UPDATE -> feed("Lab · updated", "2 · Updated synthetic text")
            DisplayCapability.REMOVAL -> session.remove(message.key, now)?.let { event ->
                simulator.receive(event) { request, mtu -> session.respond(request, mtu, now) }
            }
            else -> Unit
        }
        return simulator.displayed.lastOrNull()?.let { DisplayPreviewUi(it.title, it.text) }
    }

    private fun phoneLocked() = keyguard.isDeviceLocked || keyguard.isKeyguardLocked || !power.isInteractive

    private fun privateText(): String {
        val language = state.value.language
        val localeName = if (language == LanguageMode.ITALIAN) "it" else "en"
        if (language == LanguageMode.SYSTEM) return context.getString(R.string.private_notification_text)
        val config = android.content.res.Configuration(context.resources.configuration)
        config.setLocale(java.util.Locale.forLanguageTag(localeName))
        return context.createConfigurationContext(config).getString(R.string.private_notification_text)
    }

    private fun updateApps() =
        update { it.copy(apps = apps.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, ObservedAppUi::label))) }

    private fun updateDiagnostics() {
        val stats = queue.stats
        val bluetooth = BleRuntime.state.value.takeIf { state.value.mode == CompanionMode.CONTROLLER }
        update { ui ->
            ui.copy(diagnostics = DiagnosticsUi(
                queued = queue.size + tests.size,
                sent = sent,
                dropped = (stats.expired + stats.overflow).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() + transportDrops,
                reconnects = bluetooth?.let {
                    (it.socialServerRetries + it.settingsRetries).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                } ?: 0,
                socialMtu = bluetooth?.mtu ?: ui.simulatorMtu.takeIf { ui.mode == CompanionMode.SIMULATOR },
                socialMtuKnown = bluetooth?.socialMtuKnown == true,
                configurationMtu = bluetooth?.clientMtu,
                attributeRequests = bluetooth?.attributeRequests ?: simulatedSample?.attributeRequests,
                fragmentsSent = bluetooth?.fragmentsSent ?: simulatedSample?.fragmentsSent,
                lastRequestedTitleBytes = bluetooth?.lastRequestedTitleBytes ?: simulatedSample?.lastRequestedTitleBytes,
                lastRequestedBodyBytes = bluetooth?.lastRequestedBodyBytes ?: simulatedSample?.lastRequestedBodyBytes,
                lastSentTitleBytes = bluetooth?.lastSentTitleBytes ?: simulatedSample?.lastSentTitleBytes,
                lastSentBodyBytes = bluetooth?.lastSentBodyBytes ?: simulatedSample?.lastSentBodyBytes,
                notificationSourceSubscribed = bluetooth?.notificationSourceSubscribed,
                dataSourceSubscribed = bluetooth?.dataSourceSubscribed,
                requestedBytesTotal = bluetooth?.requestedBytesTotal,
                declaredBytesTotal = bluetooth?.declaredBytesTotal,
                sentBytesTotal = bluetooth?.sentBytesTotal,
                lastDeclaredTitleBytes = bluetooth?.lastDeclaredTitleBytes ?: simulatedSample?.lastSentTitleBytes,
                lastDeclaredBodyBytes = bluetooth?.lastDeclaredBodyBytes ?: simulatedSample?.lastSentBodyBytes,
            ))
        }
    }

    private fun signal() {
        updateDiagnostics()
        wake.trySend(Unit)
    }

    private fun update(transform: (StealthUiState) -> StealthUiState) = mutableState.update(transform)

    private fun RuleUi.toDomain() = AppRule(
        enabled = enabled,
        allowSilent = allowSilent,
        includeAppName = includeAppName,
        allowedChannels = allowedChannels,
        allowedContacts = allowedContacts,
        blockedContacts = blockedContacts,
        allowedConversations = allowedConversations,
        blockedConversations = blockedConversations,
    )

    private companion object {
        // An ANCS Get App Attributes command for this identifier fits in one ATT write at MTU 23.
        const val SYNTHETIC_APP = "dev.stealth.lab"
        const val SYNTHETIC_APP_NAME = "Stealth Link"
        const val PROBE_OBSERVATION_MS = 4_000L
    }
}
