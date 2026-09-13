package com.wjf.fadebreak.service

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.wjf.fadebreak.core.BreakSettings
import com.wjf.fadebreak.core.BreakState
import com.wjf.fadebreak.core.DebugLog

/** Immutable view of [BreakStateMachine] used to decide the next wake-up. */
data class BreakSnapshot(
    val state: BreakState,
    val continuousMs: Long,
    val showAt: Long,
    val retryUntil: Long,
    val autoDismissRequested: Boolean
)

/**
 * Scheduling policy: when must the state machine be evaluated again?
 * Returns an absolute `elapsedRealtime`, or `0` to wait for an external event.
 */
fun nextWakeAt(now: Long, snapshot: BreakSnapshot, settings: BreakSettings): Long =
    when (snapshot.state) {
        // Screen off / not started: wait for the screen-on event.
        BreakState.IDLE -> 0L
        // Trigger the moment continuous usage reaches the interval.
        BreakState.MONITORING -> {
            val remaining = (settings.timeoutMs - snapshot.continuousMs).coerceAtLeast(0L)
            now + remaining + WAKE_MARGIN_MS
        }
        // Eligible but blocked: re-check periodically (foreground changes also notify us).
        BreakState.ELIGIBLE -> now + BLOCKED_POLL_MS
        // Wait for the fade-out, which is driven by the overlay/dismiss callback.
        BreakState.BREAK_ACTIVE ->
            if (snapshot.autoDismissRequested) 0L
            else snapshot.showAt + settings.minBreakMs + WAKE_MARGIN_MS
        BreakState.COOLDOWN -> snapshot.retryUntil + WAKE_MARGIN_MS
    }

private const val WAKE_MARGIN_MS = 500L
private const val BLOCKED_POLL_MS = 30_000L

/** Why a scheduled wake-up fired, or [ON_TIME] for an immediate/event-driven evaluation. */
enum class WakeupReason { ON_TIME, FROZEN_WHILE_AWAKE, SLEPT }

/**
 * Single-shot wake-up scheduler. It never polls: at most one evaluation is queued and
 * events can request an immediate (coalesced) one.
 *
 * Two very different things can delay a wake-up, and they must not be conflated:
 *
 *  - **Device sleep** (screen off): [SystemClock.elapsedRealtime] advances but
 *    [SystemClock.uptimeMillis] pauses. The session must reset, exactly like a real
 *    screen-off.
 *  - **Process frozen** (Doze/vendor freezer, screen on): both clocks advance but the
 *    callback fires late. This is *not* a screen-off; the accumulated usage must survive
 *    and only the gap is dropped.
 *
 * The old code used elapsedRealtime for both and treated every late callback as a
 * screen-off, so any Doze/freeze wiped the continuous-usage timer and the overlay could
 * never fire.
 */
class WakeupScheduler(
    private val onWake: (WakeupReason) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var scheduledAtUptime = 0L
    private var scheduledAtElapsed = 0L

    private val runnable = object : Runnable {
        override fun run() {
            val uptimeNow = SystemClock.uptimeMillis()
            val elapsedNow = SystemClock.elapsedRealtime()
            val hadSchedule = scheduledAtUptime != 0L
            val uptimeGap = if (hadSchedule) uptimeNow - scheduledAtUptime else 0L
            val elapsedGap = if (hadSchedule) elapsedNow - scheduledAtElapsed else 0L
            scheduledAtUptime = 0L
            scheduledAtElapsed = 0L
            val reason = classify(hadSchedule, uptimeGap, elapsedGap)
            if (reason != WakeupReason.ON_TIME) {
                DebugLog.w(
                    "wake late=$reason uptimeGap=${uptimeGap}ms elapsedGap=${elapsedGap}ms"
                )
            }
            onWake(reason)
        }
    }

    private fun classify(hadSchedule: Boolean, uptimeGap: Long, elapsedGap: Long): WakeupReason =
        when {
            !hadSchedule -> WakeupReason.ON_TIME
            elapsedGap - uptimeGap > SLEEP_TOLERANCE_MS -> WakeupReason.SLEPT
            uptimeGap > SUSPENSION_TOLERANCE_MS -> WakeupReason.FROZEN_WHILE_AWAKE
            else -> WakeupReason.ON_TIME
        }

    /** Queue an immediate evaluation, coalescing multiple requests. */
    fun request() {
        handler.removeCallbacks(runnable)
        scheduledAtUptime = 0L
        scheduledAtElapsed = 0L
        handler.post(runnable)
    }

    /**
     * Queue the single next evaluation. [next] is an absolute `elapsedRealtime`; when it is
     * `0` the machine is waiting for events, so poll again after [idlePollMs]. A wake-up is
     * never slept through for longer than [MAX_SLEEP_MS] as a safety net.
     */
    fun scheduleAt(next: Long, idlePollMs: Long) {
        handler.removeCallbacks(runnable)
        val now = SystemClock.elapsedRealtime()
        val delay = if (next == 0L) {
            idlePollMs.coerceIn(MIN_DELAY_MS, MAX_SLEEP_MS)
        } else {
            (next - now).coerceIn(MIN_DELAY_MS, MAX_SLEEP_MS)
        }
        scheduledAtUptime = SystemClock.uptimeMillis() + delay
        scheduledAtElapsed = now + delay
        handler.postDelayed(runnable, delay)
    }

    fun cancel() {
        handler.removeCallbacks(runnable)
        scheduledAtUptime = 0L
        scheduledAtElapsed = 0L
    }

    private companion object {
        /** Callback later than requested (awake) => process was frozen; don't reset. */
        const val SUSPENSION_TOLERANCE_MS = 120_000L
        /** Clock divergence above this => the device actually slept; reset the session. */
        const val SLEEP_TOLERANCE_MS = 5_000L
        const val MIN_DELAY_MS = 50L
        const val MAX_SLEEP_MS = 15 * 60_000L
    }
}
