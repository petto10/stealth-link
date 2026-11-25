package dev.stealthcompanion

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import dev.stealthcompanion.ble.BleRuntime

class StealthApplication : Application() {
    internal lateinit var companion: CompanionController
        private set

    override fun onCreate() {
        super.onCreate()
        BleRuntime.init(this)
        companion = CompanionController(this)
        ContextCompat.registerReceiver(this, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_OFF) companion.screenTurnedOff()
            }
        }, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
    }
}
