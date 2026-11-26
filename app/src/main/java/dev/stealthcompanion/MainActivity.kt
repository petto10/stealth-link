package dev.stealthcompanion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.stealthcompanion.ui.LanguageMode
import dev.stealthcompanion.ui.StealthApp
import dev.stealthcompanion.ui.UiAction
import dev.stealthcompanion.ui.UiFeedback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private val companion get() = (application as StealthApplication).companion

    private var requestedServiceNotification = false
    private val postServiceNotification = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val bluetoothAccess = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshAccess()
    }
    private val notificationAccess = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refreshAccess()
    }
    private val exportDiagnostics = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) result.data?.data?.let { uri ->
            val snapshot = companion.diagnostics()
            writeDocument(uri, UiFeedback.DIAGNOSTICS_EXPORTED) { stream ->
                stream.write(snapshot.toByteArray(Charsets.UTF_8))
            }
        }
    }
    private val exportProfiles = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) result.data?.data?.let { uri ->
            writeDocument(uri, UiFeedback.PROFILES_EXPORTED, companion.profiles::exportProfiles)
        }
    }
    private val importProfiles = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) result.data?.data?.let { uri ->
            companion.scope.launch {
                val outcome = withContext(Dispatchers.IO) {
                    runCatching {
                        contentResolver.openInputStream(uri)?.use { companion.profiles.importProfiles(it) }
                            ?: error("File unavailable")
                    }
                }
                companion.tell(if (outcome.isSuccess) UiFeedback.PROFILES_IMPORTED else UiFeedback.FILE_ERROR)
                if (outcome.isSuccess) companion.refreshProfiles()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StealthApp(companion.state.collectAsStateWithLifecycle().value, ::onAction)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAccess()
    }

    private fun refreshAccess() {
        companion.refreshPermissions()
        val allowed = Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(
            this, Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
        companion.refreshDevices(allowed)
    }

    private fun onAction(action: UiAction) {
        when (action) {
            is UiAction.SetMode -> companion.setMode(action.value)
            UiAction.OpenNotificationAccess -> notificationAccess.launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            UiAction.RequestBluetooth -> {
                if (Build.VERSION.SDK_INT >= 31) bluetoothAccess.launch(Manifest.permission.BLUETOOTH_CONNECT)
                else refreshAccess()
            }
            UiAction.RefreshDevices -> refreshAccess()
            is UiAction.SelectDevice -> companion.selectConfiguration(action.id)
            is UiAction.SelectSocialDevice -> companion.selectSocial(action.id)
            UiAction.StartCompanion -> {
                companion.start()
                requestServiceNotification()
            }
            UiAction.StopCompanion -> companion.stop()
            UiAction.RecoverConnection -> {
                companion.recoverConnection()
                requestServiceNotification()
            }
            is UiAction.SetPaused -> companion.pause(action.value)
            is UiAction.SetLockPrivacy -> companion.setPrivacy(action.value)
            is UiAction.SetRule -> companion.setRule(action.packageName, action.rule)
            is UiAction.SendSynthetic -> companion.queueSynthetic(action.title, action.body)
            is UiAction.SetProbesEnabled -> companion.setProbeMode(action.value)
            is UiAction.SetRelaxedSynthetic -> companion.setRelaxedSynthetic(action.value)
            is UiAction.SetExtendedText -> companion.setExtendedText(action.value)
            is UiAction.SetFragmentDelay -> companion.setFragmentDelay(action.value)
            is UiAction.SetBodyByteLimit -> companion.setBodyByteLimit(action.value)
            is UiAction.SetPaginateNotifications -> companion.setPaginateNotifications(action.value)
            is UiAction.SetNotificationsOnly -> companion.setNotificationsOnly(action.value)
            is UiAction.SetSimulatorMtu -> companion.setSimulatorMtu(action.value)
            is UiAction.RunProbe -> companion.runProbe(action.capability, action.extended)
            is UiAction.SendLengthProbe -> companion.sendLengthProbe(action.spec, action.extended)
            UiAction.MarkLengthProbeDetail -> companion.markLengthProbeDetail()
            is UiAction.SetObservation -> companion.setObservation(action.capability, action.value)
            UiAction.ExportDiagnostics -> exportDiagnostics.launch(createFile("text/plain", "stealth-diagnostics.txt"))
            UiAction.ReadSettings -> companion.readSettings()
            is UiAction.AddProfile -> companion.addProfile(action.name)
            is UiAction.RemoveProfile -> companion.removeProfile(action.id)
            is UiAction.RenameProfile -> companion.renameProfile(action.id, action.name)
            is UiAction.SelectLocalProfile -> companion.selectLocalProfile(action.id)
            UiAction.ImportProfiles -> importProfiles.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            })
            UiAction.ExportProfiles -> exportProfiles.launch(createFile("application/json", "stealth-profiles.json"))
            is UiAction.SetTheme -> companion.setTheme(action.value)
            is UiAction.SetLanguage -> {
                companion.setLanguage(action.value)
                val tags = when (action.value) {
                    LanguageMode.SYSTEM -> ""
                    LanguageMode.ITALIAN -> "it"
                    LanguageMode.ENGLISH -> "en"
                }
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tags))
            }
            UiAction.ClearFeedback -> companion.tell(null)
        }
    }

    private fun requestServiceNotification() {
        if (Build.VERSION.SDK_INT < 33 || requestedServiceNotification) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        requestedServiceNotification = true
        postServiceNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun createFile(mime: String, name: String) = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = mime
        putExtra(Intent.EXTRA_TITLE, name)
        putExtra(Intent.EXTRA_LOCAL_ONLY, true)
    }

    private fun writeDocument(uri: Uri, success: UiFeedback, write: (java.io.OutputStream) -> Any) {
        companion.scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openOutputStream(uri, "wt")?.use(write) ?: error("File unavailable")
                }
            }
            companion.tell(if (result.isSuccess) success else UiFeedback.FILE_ERROR)
        }
    }
}
