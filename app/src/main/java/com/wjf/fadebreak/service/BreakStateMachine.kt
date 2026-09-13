package com.wjf.fadebreak.service

import com.wjf.fadebreak.core.BreakSettings
import com.wjf.fadebreak.core.BreakState
import com.wjf.fadebreak.core.DebugLog

class BreakStateMachine(
    private val onTrigger: () -> Unit,
    private val onAutoDismiss: () -> Unit,
    private val onBreakResult: (visibleMs: Long, taken: Boolean) -> Unit
) {

    var state: BreakState = BreakState.IDLE
        private set

    private var continuousMs = 0L
    private var lastTick = 0L
    private var showAt = 0L
    private var retryUntil = 0L
    private var autoDismissRequested = false
    private var interruptionCount = 0

    fun tick(
        now: Long,
        screenOn: Boolean,
        blocked: Boolean,
        settings: BreakSettings
    ) {
        if (!screenOn) {
            screenOff(now)
            return
        }

        val delta = if (lastTick == 0L) 0L else now - lastTick
        lastTick = now

        when (state) {
            BreakState.IDLE, BreakState.MONITORING, BreakState.ELIGIBLE ->
                evaluate(now, delta, blocked, settings)
            BreakState.BREAK_ACTIVE -> {
                // Auto fade-out once the eye-care duration has elapsed.
                if (!autoDismissRequested && now - showAt >= settings.minBreakMs) {
                    autoDismissRequested = true
                    onAutoDismiss()
                }
            }
            BreakState.COOLDOWN -> if (now >= retryUntil) {
                transition(BreakState.MONITORING)
                // Evaluate right away so an overdue session triggers on this tick
                // instead of waiting for the next one.
                evaluate(now, 0L, blocked, settings)
            }
        }
    }

    private fun evaluate(now: Long, delta: Long, blocked: Boolean, settings: BreakSettings) {
        if (state == BreakState.IDLE) transition(BreakState.MONITORING)
        // Continuous usage = time the screen stays on.
        continuousMs += delta
        val eligible = continuousMs >= settings.timeoutMs

        when {
            eligible && !blocked -> {
                showAt = now
                autoDismissRequested = false
                onTrigger()
                transition(BreakState.BREAK_ACTIVE)
            }
            eligible -> transition(BreakState.ELIGIBLE)
            // The interval grew while eligible: fall back to plain monitoring.
            state == BreakState.ELIGIBLE -> transition(BreakState.MONITORING)
        }
    }

    fun dismiss(now: Long, settings: BreakSettings) {
        if (state != BreakState.BREAK_ACTIVE) return
        val visible = now - showAt
        val taken = visible >= settings.minBreakMs
        onBreakResult(visible, taken)
        autoDismissRequested = false
        if (taken) {
            // A proper eye rest resets the continuous-usage clock.
            continuousMs = 0L
            interruptionCount = 0
        } else {
            interruptionCount++
            // Two dismissals in a row mean the user is genuinely busy right now:
            // start a fresh timing cycle instead of nagging them again.
            if (interruptionCount >= MAX_CONSECUTIVE_INTERRUPTIONS) {
                DebugLog.d("interrupted $interruptionCount times, restarting timer")
                continuousMs = 0L
                interruptionCount = 0
            }
        }
        transition(BreakState.COOLDOWN)
        retryUntil = now + settings.retryMs
    }

    fun screenOff(now: Long) {
        if (state == BreakState.BREAK_ACTIVE) {
            onBreakResult(now - showAt, false)
        }
        continuousMs = 0L
        lastTick = 0L
        autoDismissRequested = false
        interruptionCount = 0
        transition(BreakState.IDLE)
    }

    /**
     * The process was frozen/dozed while the screen stayed on: we cannot know whether the
     * gap was real usage, so don't credit it — but crucially, do NOT reset the accumulated
     * continuous usage. Only a genuine screen-off should clear the session.
     */
    fun skipGap(now: Long) {
        if (state == BreakState.MONITORING || state == BreakState.ELIGIBLE) {
            lastTick = now
        }
    }

    /** Read-only view for the scheduler; keeps timing policy out of the state machine. */
    fun snapshot(): BreakSnapshot = BreakSnapshot(
        state = state,
        continuousMs = continuousMs,
        showAt = showAt,
        retryUntil = retryUntil,
        autoDismissRequested = autoDismissRequested
    )

    private fun transition(next: BreakState) {
        if (state == next) return
        DebugLog.d("state ${state} -> $next")
        state = next
    }

    private companion object {
        /** Early dismissals in a row before the timer restarts. */
        const val MAX_CONSECUTIVE_INTERRUPTIONS = 2
    }
}
