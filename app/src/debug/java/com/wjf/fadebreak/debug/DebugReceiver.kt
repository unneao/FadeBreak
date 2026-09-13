package com.wjf.fadebreak.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.wjf.fadebreak.core.AppConstants
import com.wjf.fadebreak.data.SettingsRepository
import com.wjf.fadebreak.track.ActivityAccessibilityService
import kotlinx.coroutines.runBlocking

/** Debug-only hook to tweak settings during development. */
class DebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DEBUG_SET) return

        val repository = SettingsRepository(context.applicationContext)
        val current = runBlocking { repository.current() }
        val updated = current.copy(
            timeoutMs = intent.getLongExtra("timeoutSec", current.timeoutMs / 1000) * 1000,
            fadeMs = intent.getLongExtra("fadeSec", current.fadeMs / 1000) * 1000,
            fadeOutMs = intent.getLongExtra("fadeOutSec", current.fadeOutMs / 1000) * 1000,
            maxOpacity = intent.getFloatExtra("maxOpacity", current.maxOpacity),
            minBreakMs = intent.getLongExtra("minBreakSec", current.minBreakMs / 1000) * 1000,
            retryMs = intent.getLongExtra("retrySec", current.retryMs / 1000) * 1000,
            enabled = intent.getIntExtra("enabled", if (current.enabled) 1 else 0) == 1,
            whitelist = intent.getStringExtra("whitelist")
                ?.split(',')
                ?.filter { it.isNotBlank() }
                ?.toSet()
                ?: current.whitelist
        )
        runBlocking { repository.set(updated) }
        Log.i(AppConstants.TAG, "debug settings=$updated")

        if (intent.getStringExtra("service") == "preview") {
            ActivityAccessibilityService.instance?.preview()
        }
    }

    companion object {
        const val ACTION_DEBUG_SET = "com.wjf.fadebreak.DEBUG_SET"
    }
}
