package com.wjf.fadebreak.track

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import com.wjf.fadebreak.core.BreakSettings
import com.wjf.fadebreak.core.BreakState
import com.wjf.fadebreak.core.DebugLog
import com.wjf.fadebreak.data.BreakDatabase
import com.wjf.fadebreak.data.BreakRepository
import com.wjf.fadebreak.data.SettingsRepository
import com.wjf.fadebreak.service.BreakStateMachine
import com.wjf.fadebreak.service.OverlayController
import com.wjf.fadebreak.service.WakeupAlarm
import com.wjf.fadebreak.service.WakeupReason
import com.wjf.fadebreak.service.WakeupScheduler
import com.wjf.fadebreak.service.nextWakeAt
import com.wjf.fadebreak.ui.overlay.BackgroundResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ActivityAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val settingsState = MutableStateFlow(BreakSettings.defaults())
    private val settings: BreakSettings get() = settingsState.value
    private var previewMode = false
    private var previewJob: Job? = null

    private lateinit var overlay: OverlayController
    private lateinit var machine: BreakStateMachine
    private lateinit var scheduler: WakeupScheduler

    private val repository by lazy {
        BreakRepository(BreakDatabase.get(applicationContext).breakDao())
    }
    private val settingsRepository by lazy { SettingsRepository(applicationContext) }
    private val backgroundResolver by lazy {
        BackgroundResolver(applicationContext, settingsRepository)
    }
    private val foregroundChecker by lazy { ForegroundAppChecker(applicationContext) }
    private val powerManager by lazy {
        getSystemService(Context.POWER_SERVICE) as PowerManager
    }
    private val wakeAlarm by lazy { WakeupAlarm(applicationContext) }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Screen on/off toggles accumulation: re-evaluate right away.
            DebugLog.d("screen broadcast ${intent?.action}")
            scheduler.request()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        DebugLog.init(applicationContext)
        DebugLog.d("service connected (pid=${android.os.Process.myPid()})")

        overlay = OverlayController(applicationContext)
        scheduler = WakeupScheduler { reason ->
            when (reason) {
                // Doze/vendor freezer held us back, but the device stayed awake: the
                // accumulated usage is real, so only drop the gap (never reset).
                WakeupReason.FROZEN_WHILE_AWAKE -> {
                    DebugLog.w("process frozen while screen on, drop gap")
                    machine.skipGap(SystemClock.elapsedRealtime())
                }
                // The device actually slept (screen was off): same as a screen-off.
                WakeupReason.SLEPT -> {
                    DebugLog.w("device slept, reset session")
                    if (overlay.isShowing) overlay.hide()
                    machine.screenOff(SystemClock.elapsedRealtime())
                }
                WakeupReason.ON_TIME -> Unit
            }
            tickNow()
        }

        overlay.onDismissed = {
            if (previewMode) {
                previewMode = false
                previewJob?.cancel()
            } else {
                machine.dismiss(SystemClock.elapsedRealtime(), settings)
            }
            scheduler.request()
        }
        machine = BreakStateMachine(
            onTrigger = { showOverlay(settings.fadeMs) },
            onAutoDismiss = { overlay.dismiss() },
            onBreakResult = { visibleMs, taken ->
                val dismissedAt = System.currentTimeMillis()
                DebugLog.d("break result visibleMs=$visibleMs taken=$taken")
                scope.launch {
                    repository.record(
                        triggeredAt = dismissedAt - visibleMs,
                        dismissedAt = dismissedAt,
                        visibleMs = visibleMs,
                        taken = taken
                    )
                }
            }
        )

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        scope.launch {
            settingsRepository.settings.collect { updated ->
                val changed = settingsState.value != updated
                settingsState.value = updated
                if (changed) {
                    DebugLog.d(
                        "settings changed enabled=${updated.enabled} " +
                            "timeout=${updated.timeoutMs} retry=${updated.retryMs}"
                    )
                    // Decode happens lazily when a reminder fires; caching a full-screen
                    // bitmap here would keep multi-MB native memory resident forever.
                    scheduler.request()
                }
            }
        }

        scheduler.request()
    }

    // Only used to notice leaving a whitelisted app; everything else is event-driven.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            settings.whitelist.isNotEmpty()
        ) {
            scheduler.request()
        }
    }

    override fun onInterrupt() = Unit

    fun preview() {
        previewMode = true
        showOverlay(settings.fadeMs)
    }

    private fun tickNow() {
        try {
            onTick()
        } catch (t: Throwable) {
            DebugLog.e("tick failed", t)
        }
        try {
            scheduleNext()
        } catch (t: Throwable) {
            DebugLog.e("schedule failed", t)
        }
    }

    /** Schedule exactly one evaluation for the next moment the state can change. */
    private fun scheduleNext() {
        if (previewMode || !settings.enabled) {
            scheduler.cancel()
            wakeAlarm.cancel()
            return
        }
        val now = SystemClock.elapsedRealtime()
        val next = nextWakeAt(now, machine.snapshot(), settings)
        // `0` means "wait for an event"; poll on a short cadence so a missed
        // ACTION_SCREEN_ON can never leave us stuck with the screen actually on.
        // Keep it well below the interval, otherwise a lost event costs a whole
        // extra timeout period before monitoring resumes.
        scheduler.scheduleAt(next, IDLE_POLL_MS)
        // Mirror the wake at the true deadline so Doze can't hold it back.
        if (next == 0L) {
            wakeAlarm.cancel()
        } else {
            wakeAlarm.scheduleAt(next)
        }
        DebugLog.d(
            "schedule state=${machine.state} next=" +
                (if (next == 0L) "event" else "${next - now}ms")
        )
    }

    /** Called by [WakeupAlarmReceiver]: re-evaluate right away. */
    internal fun onWakeAlarm() {
        DebugLog.d("alarm fired, evaluating directly")
        // Evaluate synchronously instead of posting: if the vendor freezes us again
        // right after the alarm, a posted runnable may never run.
        if (::machine.isInitialized) tickNow()
    }

    private fun onTick() {
        val now = SystemClock.elapsedRealtime()
        // PowerManager is the source of truth for the screen state. Screen on/off
        // broadcasts can be missed while the process is backgrounded, which would
        // otherwise freeze the timer (stuck "off") or let it accrue while the screen
        // is actually off (stuck "on" -> the overlay then fires on resume).
        val screenOn = powerManager.isInteractive

        if (previewMode) {
            // A preview that outlives the screen turning off would otherwise pause monitoring.
            if (!screenOn) {
                overlay.hide()
                previewMode = false
            }
            return
        }
        if (!settings.enabled) {
            if (overlay.isShowing) overlay.hide()
            machine.screenOff(now)
            return
        }
        if (!screenOn && overlay.isShowing) {
            overlay.hide()
        }
        if (!screenOn) {
            // The foreground cache is meaningless across a screen-off gap.
            foregroundChecker.reset()
        }
        // If we cannot draw the overlay, don't enter BREAK_ACTIVE (would get stuck).
        val blocked = !overlay.canDraw() ||
            (settings.whitelist.isNotEmpty() &&
                foregroundChecker.hasPermission() &&
                foregroundChecker.foregroundPackage() in settings.whitelist)
        machine.tick(now, screenOn, blocked, settings)
        DebugLog.d(
            "tick screenOn=$screenOn blocked=$blocked state=${machine.state} " +
                "continuous=${machine.snapshot().continuousMs}"
        )
    }

    private fun showOverlay(fadeMs: Long) {
        val current = settings
        if (!powerManager.isInteractive) {
            DebugLog.w("show overlay skipped: screen off")
            return
        }
        DebugLog.d("show overlay fade=${fadeMs}ms bg=${current.bgImageEnabled}")
        if (!current.bgImageEnabled) {
            presentOverlay(current, null, fadeMs)
            return
        }
        // Decode first so the fade-in starts with the image already in place; showing
        // the solid colour first made it flash green before the picture appeared.
        scope.launch {
            val background = backgroundResolver.resolve(current)
            withContext(Dispatchers.Main) {
                val stillValid = powerManager.isInteractive &&
                    (previewMode || machine.state == BreakState.BREAK_ACTIVE)
                if (!stillValid) {
                    DebugLog.w("overlay image ready but screen/state changed, skip")
                    return@withContext
                }
                presentOverlay(current, background, fadeMs)
            }
        }
    }

    private fun presentOverlay(current: BreakSettings, background: Bitmap?, fadeMs: Long) {
        overlay.fadeOutMs = current.fadeOutMs
        overlay.show(fadeMs, current.maxOpacity)
        overlay.updateBackground(background)
        if (previewMode) {
            // A preview is a short demo: fade in, hold briefly, then fade back out.
            previewJob?.cancel()
            previewJob = scope.launch {
                delay(fadeMs + PREVIEW_HOLD_MS)
                withContext(Dispatchers.Main) {
                    if (previewMode) overlay.dismiss()
                }
            }
        }
    }

    private fun cleanup() {
        DebugLog.d("service cleanup (unbind/destroy)")
        if (::scheduler.isInitialized) scheduler.cancel()
        runCatching { wakeAlarm.cancel() }
        if (::overlay.isInitialized) overlay.hide()
        scope.cancel()
        runCatching { unregisterReceiver(screenReceiver) }
        if (instance === this) instance = null
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: ActivityAccessibilityService? = null
            private set

        /** Poll cadence while idle (waiting for a screen-on event), so a lost event
         *  is recovered quickly instead of after a whole timeout period. */
        private const val IDLE_POLL_MS = 60_000L

        /** How long a preview stays fully visible after fading in, before fading out. */
        private const val PREVIEW_HOLD_MS = 3_000L
    }
}
