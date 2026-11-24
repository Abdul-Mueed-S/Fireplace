package com.evu.fireplace

import android.os.PowerManager

/**
 * Decides how hard the CPU workers may run, once per second.
 *
 * An integral controller: it smooths Android's thermal-headroom reading (which is noisy by
 * about +-0.02 and lags our heat by roughly a minute), then nudges power up or down in
 * proportion to how far that smoothed value is from [HeatLevel.targetHeadroom]. The gain is
 * deliberately small and symmetric, so noise averages out instead of making power chatter
 * (the earlier version reacted ~5x harder to readings above target, which made it swing).
 * Battery temperature is only a cap, and the fallback if headroom isn't reported.
 * Moderate throttling (or worse) backs off hard.
 */
class ThermalGovernor(private val level: HeatLevel) {

    companion object {
        private const val KI = 0.025f          // power change per second per unit of headroom error
        private const val MAX_STEP = 0.01f     // never move more than 1% power per second
        private const val SMOOTHING = 0.2f     // ~5 s low-pass on the headroom reading
    }

    var duty: Float = 0.30f
        private set

    private var filtered = Float.NaN

    fun update(batteryTempC: Float, thermalStatus: Int, headroom: Float): Float {
        var delta: Float
        if (headroom.isNaN()) {
            delta = (0.02f * (level.targetC - batteryTempC)).coerceIn(-MAX_STEP, MAX_STEP)
        } else {
            filtered = if (filtered.isNaN()) headroom else filtered + SMOOTHING * (headroom - filtered)
            delta = (KI * (level.targetHeadroom - filtered)).coerceIn(-MAX_STEP, MAX_STEP)
            if (filtered >= HeatLevel.HEADROOM_BACKOFF) delta = -0.03f
        }
        // battery is a cap: above its target, never ramp up and ease off
        if (batteryTempC > level.targetC) delta = delta.coerceAtMost(0.15f * (level.targetC - batteryTempC))
        if (thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) delta = -0.10f
        duty = (duty + delta).coerceIn(0f, level.maxDuty)
        return duty
    }
}