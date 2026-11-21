package com.evu.fireplace

/**
 * Heat presets. The target is a *battery* temperature (the one number Android reliably
 * exposes); the chip runs hotter than the battery, so these are deliberately conservative.
 * [maxDuty] caps how hard the CPU workers may run (0..1) at this level.
 */
enum class HeatLevel(val label: String, val targetC: Float, val maxDuty: Float) {
    LOW("Low", 36f, 0.45f),
    MEDIUM("Medium", 38.5f, 0.70f),
    HIGH("High", 41f, 0.90f);

    companion object {
        /** Battery temperature at which the fire is put out, no matter what. */
        const val HARD_CEILING_C = 43f

        /** The fire goes out at or below this battery percentage. */
        const val MIN_BATTERY_PERCENT = 20

        /** Back off when the thermal headroom forecast reaches this (1.0 = severe throttling). */
        const val HEADROOM_BACKOFF = 0.80f
    }
}
