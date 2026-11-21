package com.evu.fireplace

/**
 * Heat presets.
 *
 * [targetHeadroom] is the main goal: how close to Android's severe-throttling point the
 * chip may run (0..1, see Sensors.Reading.headroom). The governor holds the chip near it.
 * [targetC] is only a cap on battery temperature (it lags the chip, so it rarely binds).
 * [maxDuty] caps how hard the CPU workers may run (0..1) and [coreFraction] is the share
 * of CPU cores that get a worker thread.
 */
enum class HeatLevel(
    val label: String,
    val targetHeadroom: Float,
    val targetC: Float,
    val maxDuty: Float,
    val coreFraction: Float,
) {
    LOW("Low", 0.60f, 37f, 0.50f, 0.50f),
    MEDIUM("Medium", 0.72f, 40f, 0.75f, 0.75f),
    HIGH("High", 0.82f, 43f, 1.00f, 1.00f);

    companion object {
        /** Battery temperature at which the fire is put out, no matter what. */
        const val HARD_CEILING_C = 45f

        /** The fire goes out at or below this battery percentage. */
        const val MIN_BATTERY_PERCENT = 20

        /** Back off hard when the thermal headroom forecast reaches this (1.0 = severe throttling). */
        const val HEADROOM_BACKOFF = 0.85f
    }
}