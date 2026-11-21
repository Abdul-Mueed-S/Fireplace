package com.evu.fireplace

/**
 * Heat presets. The target is a *battery* temperature (the one number Android reliably
 * exposes); the chip runs hotter than the battery.
 * [maxDuty] caps how hard the CPU workers may run (0..1) and [coreFraction] is the share
 * of CPU cores that get a worker thread.
 */
enum class HeatLevel(
    val label: String,
    val targetC: Float,
    val maxDuty: Float,
    val coreFraction: Float,
) {
    LOW("Low", 37f, 0.50f, 0.50f),
    MEDIUM("Medium", 40f, 0.75f, 0.75f),
    HIGH("High", 43f, 1.00f, 1.00f);

    companion object {
        /** Battery temperature at which the fire is put out, no matter what. */
        const val HARD_CEILING_C = 45f

        /** The fire goes out at or below this battery percentage. */
        const val MIN_BATTERY_PERCENT = 20

        /** Back off when the thermal headroom forecast reaches this (1.0 = severe throttling). */
        const val HEADROOM_BACKOFF = 0.85f
    }
}