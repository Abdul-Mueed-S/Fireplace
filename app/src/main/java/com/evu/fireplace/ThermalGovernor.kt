package com.evu.fireplace

import android.os.PowerManager

/**
 * Decides how hard the CPU workers may run, once per second.
 *
 * Rule of thumb: warm up slowly, cool down fast. Battery temperature lags behind the
 * chip temperature, so we also watch Android's own thermal status and thermal-headroom
 * forecast and back off early when either says the phone is getting stressed.
 */
class ThermalGovernor(private val level: HeatLevel) {

    var duty: Float = 0.15f
        private set

    fun update(batteryTempC: Float, thermalStatus: Int, headroom: Float): Float {
        val error = level.targetC - batteryTempC
        var delta = when {
            error > 0f -> (0.02f * error).coerceAtMost(0.04f)  // below target: ramp up gently
            else -> 0.15f * error                              // above target: ease off quickly
        }
        if (thermalStatus >= PowerManager.THERMAL_STATUS_LIGHT) delta = -0.10f
        if (!headroom.isNaN() && headroom >= HeatLevel.HEADROOM_BACKOFF) delta = -0.10f
        duty = (duty + delta).coerceIn(0f, level.maxDuty)
        return duty
    }
}
