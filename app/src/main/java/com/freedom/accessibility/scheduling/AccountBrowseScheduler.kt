package com.freedom.accessibility.scheduling

import android.os.Handler

/**
 * Owns the single delayed callback that advances the automatic-browse state machine.
 *
 * Contract preserved from FreedomAccessibilityService:
 * 1. Eligibility is checked before scheduling.
 * 2. An already queued step is never replaced by a later request.
 * 3. The pending flag is cleared immediately before invoking the state-machine step.
 * 4. Cancellation removes this exact callback and clears the pending flag.
 *
 * Manual-touch recovery, overlay watchdog, countdown and gesture-calibration callbacks
 * intentionally remain separate because they have independent lifecycles and deadlines.
 */
internal class AccountBrowseScheduler(
    private val handler: Handler,
    private val canSchedule: () -> Boolean,
    private val onStep: () -> Unit
) {
    private var pending = false

    private val stepRunnable = Runnable {
        pending = false
        onStep()
    }

    fun schedule(delayMs: Long) {
        if (!canSchedule() || pending) return
        pending = true
        handler.postDelayed(stepRunnable, delayMs.coerceAtLeast(0L))
    }

    fun cancel() {
        handler.removeCallbacks(stepRunnable)
        pending = false
    }
}
