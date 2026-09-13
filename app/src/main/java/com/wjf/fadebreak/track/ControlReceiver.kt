package com.wjf.fadebreak.track

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Cross-process hook so the settings UI (running in `:ui`) can ask the accessibility
 * service (running in the default process) to flash a preview overlay.
 */
class ControlReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PREVIEW) return
        ActivityAccessibilityService.instance?.preview()
    }

    companion object {
        const val ACTION_PREVIEW = "com.wjf.fadebreak.action.PREVIEW"
    }
}
