package com.wjf.fadebreak.track

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** AlarmManager safety net: asks the live service to re-evaluate, if it is connected. */
class WakeupAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        ActivityAccessibilityService.instance?.onWakeAlarm()
    }
}
