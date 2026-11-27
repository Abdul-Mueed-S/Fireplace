package com.evu.fireplace

import android.os.PowerManager
import kotlin.math.min

/**
 * Decides how hard the CPU workers may run, once per second.
 *
 * Three modes:
 *  - BOOST:   full power until the *predicted* chip stress reaches the level's target, so the
 *             phone warms up quickly instead of creeping up.
 *  - HOLD:    a gentle controller keeps the chip stress at the target. It looks ahead
 *             ([LEAD_SECONDS]) because the reading lags our heat by two minutes or more, which
 *             is what made the old controller overshoot and then swing.
 *  - BATTERY: used when the phone doesn't report chip stress (many brands don't, or rate-limit
 *             it). Power is capped and steered by the battery temperature alone.
 *
 * In every mode the battery limits (see HeatLevel.targetC / riseC) only ever hold power back,
 * smoothly: a ceiling on power shrinks as the (smoothed) battery temperature nears its limit.
 * The battery reports whole degrees, so reacting to each step directly would make power jump.
 */
class ThermalGovernor(private val level: HeatLevel) {

    enum class Mode(val label: String) {
        BOOST("boost"),
        HOLD("hold"),
        BATTERY("battery-only"),
    }

    companion object {
        private const val KI = 0.02f                    // power change per second per unit of error
        private const val MAX_STEP = 0.01f              // never move more than 1% power per second
        private const val SMOOTHING = 0.2f              // ~5 s low-pass on the chip-stress reading
        private const val LEAD_SECONDS = 90f            // how far ahead we look
        private const val SLOPE_WINDOW = 30             // seconds used to measure the trend
        private const val MAX_SLOPE = 0.003f            // clamp on the trend, per second
        private const val ASSUMED_GAIN = 0.25f          // typical chip-stress rise per 100% power
        private const val MIN_BOOST_SECONDS = 15
        private const val MAX_BOOST_SECONDS = 180
        private const val HANDOFF_MIN_DUTY = 0.30f
        private const val HANDOFF_MAX_DUTY = 0.90f
        private const val BATTERY_MODE_MAX_DUTY = 0.60f
        private const val BATTERY_MODE_START_DUTY = 0.30f
        private const val NEVER_SEEN_SECONDS = 3        // no chip-stress reading at all -> battery-only
        private const val LOST_SIGNAL_SECONDS = 15      // reading disappeared mid-run -> battery-only
        private const val REGAINED_SIGNAL_SECONDS = 10
        private const val MAX_VALID_HEADROOM = 2f
        private const val BATTERY_SMOOTHING = 0.1f      // ~10 s low-pass on the battery reading
        private const val CEILING_BAND_C = 2f           // power ceiling falls from 100% to 0% over this
        private const val BOOST_SCALE_SPAN = 0.10f      // boost eases off when starting this close to target
        private const val MIN_BOOST_FRACTION = 0.30f
    }

    var mode: Mode = Mode.BOOST
        private set

    var duty: Float = level.maxDuty
        private set

    private var seconds = 0
    private var filtered = Float.NaN
    private var firstHeadroom = Float.NaN
    private var startBatteryC = Float.NaN
    private var batteryFiltered = Float.NaN
    private var validStreak = 0
    private var lostStreak = 0
    private val history = ArrayDeque<Float>()

    /** Battery temperature this run should not exceed. */
    private fun batteryLimit(): Float = min(level.targetC, startBatteryC + level.riseC)

    /** 1.0 well below the battery limit, falling smoothly to 0.0 one degree past it. */
    private fun batteryCeiling(): Float =
        ((batteryLimit() - batteryFiltered + CEILING_BAND_C / 2f) / CEILING_BAND_C).coerceIn(0f, 1f)

    /** Full power from a cold start, easing off if the phone starts already close to the target. */
    private fun boostPower(): Float {
        if (firstHeadroom.isNaN()) return level.maxDuty
        val fraction = ((level.targetHeadroom - firstHeadroom) / BOOST_SCALE_SPAN)
            .coerceIn(MIN_BOOST_FRACTION, 1f)
        return level.maxDuty * fraction
    }

    private fun predicted(): Float {
        if (filtered.isNaN()) return Float.NaN
        if (history.size <= SLOPE_WINDOW) return filtered
        val slope = ((history.last() - history.first()) / SLOPE_WINDOW).coerceIn(-MAX_SLOPE, MAX_SLOPE)
        return filtered + slope * LEAD_SECONDS
    }

    fun update(batteryTempC: Float, thermalStatus: Int, headroom: Float): Float {
        seconds++
        if (startBatteryC.isNaN()) startBatteryC = batteryTempC
        batteryFiltered = if (batteryFiltered.isNaN()) batteryTempC
        else batteryFiltered + BATTERY_SMOOTHING * (batteryTempC - batteryFiltered)

        val valid = !headroom.isNaN() && headroom >= 0f && headroom <= MAX_VALID_HEADROOM
        if (valid) {
            validStreak++
            lostStreak = 0
            filtered = if (filtered.isNaN()) headroom else filtered + SMOOTHING * (headroom - filtered)
            if (firstHeadroom.isNaN()) firstHeadroom = filtered
            history.addLast(filtered)
            if (history.size > SLOPE_WINDOW + 1) history.removeFirst()
        } else {
            lostStreak++
            validStreak = 0
        }

        // Fall back to battery-only control when chip stress isn't available.
        val lostLimit = if (firstHeadroom.isNaN()) NEVER_SEEN_SECONDS else LOST_SIGNAL_SECONDS
        if (mode != Mode.BATTERY && lostStreak >= lostLimit) {
            mode = Mode.BATTERY
            duty = min(duty, BATTERY_MODE_START_DUTY)
        } else if (mode == Mode.BATTERY && validStreak >= REGAINED_SIGNAL_SECONDS) {
            mode = Mode.HOLD
        }

        val ceiling = batteryCeiling()

        if (mode == Mode.BOOST) {
            val pred = predicted()
            val reached = seconds >= MIN_BOOST_SECONDS && !pred.isNaN() && pred >= level.targetHeadroom
            val done = reached ||
                    seconds >= MAX_BOOST_SECONDS ||
                    thermalStatus >= PowerManager.THERMAL_STATUS_LIGHT ||
                    ceiling < 0.6f
            if (!done) {
                duty = min(boostPower(), level.maxDuty * ceiling)
                return duty
            }
            mode = Mode.HOLD
            duty = if (firstHeadroom.isNaN()) {
                HANDOFF_MIN_DUTY
            } else {
                ((level.targetHeadroom - firstHeadroom) / ASSUMED_GAIN)
                    .coerceIn(HANDOFF_MIN_DUTY, min(level.maxDuty, HANDOFF_MAX_DUTY))
            }
        }

        var delta: Float
        var upper = level.maxDuty
        if (mode == Mode.BATTERY) {
            upper = min(level.maxDuty, BATTERY_MODE_MAX_DUTY)
            val e = batteryLimit() - batteryTempC
            delta = if (e >= 0f) (0.004f * e).coerceAtMost(MAX_STEP) else (0.02f * e).coerceAtLeast(-0.02f)
            // Without chip stress, Android's own throttling state is the only chip-side warning.
            if (thermalStatus >= PowerManager.THERMAL_STATUS_LIGHT) delta = min(delta, -MAX_STEP)
        } else {
            delta = (KI * (level.targetHeadroom - predicted())).coerceIn(-MAX_STEP, MAX_STEP)
            if (filtered >= HeatLevel.HEADROOM_BACKOFF) delta -= 0.03f
        }

        if (thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) delta = -0.10f

        // Battery limits only ever hold power back, via a smooth ceiling.
        upper = min(upper, level.maxDuty * ceiling)
        duty = (duty + delta).coerceIn(0f, upper)
        return duty
    }
}