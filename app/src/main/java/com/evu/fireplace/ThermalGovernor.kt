package com.evu.fireplace

import android.os.PowerManager

/**
 * Decides how hard the CPU workers may run, once per second.
 *
 * The chip, not the battery, is the real limit, so the governor steers by Android's thermal
 * headroom forecast: ramp up while there is room below [HeatLevel.targetHeadroom], ease off
 * when above it. Battery temperature is only a cap, and the plain battery rule is used as a
 * fallback if the phone doesn't report headroom. Light throttling is normal when warm and no
 * longer freezes the ramp; moderate throttling (or worse) backs off hard.
 */
class ThermalGovernor(private val level: HeatLevel) {

    var duty: Float = 0.15f
        private set

    fun update(batteryTempC: Float, thermalStatus: Int, headroom: Float): Float {
        var delta = if (headroom.isNaN()) {
            val e = level.targetC - batteryTempC
            if (e > 0f) (0.02f * e).coerceAtMost(0.04f) else 0.15f * e
        } else {
            val e = level.targetHeadroom - headroom   // positive = room to heat more
            if (e > 0f) (0.4f * e).coerceAtMost(0.04f) else e
        }
        // battery is a cap: above its target, never ramp up and ease off
        if (batteryTempC > level.targetC) delta = delta.coerceAtMost(0.15f * (level.targetC - batteryTempC))
        if (!headroom.isNaN() && headroom >= HeatLevel.HEADROOM_BACKOFF) delta = -0.10f
        if (thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) delta = -0.10f
        duty = (duty + delta).coerceIn(0f, level.maxDuty)
        return duty
    }
}