package com.evu.fireplace

/**
 * Heat presets.
 *
 * The chip, not the battery, is the real limit, so each level is defined by an absolute
 * [targetHeadroom]: how close to Android's severe-throttling point (1.0) the chip may run
 * (see Reading.headroom). Android starts *light* throttling at about 0.84-0.85, which is
 * harmless; the fire only goes out at moderate throttling.
 *
 * Battery limits work on every phone, even ones that don't report chip stress:
 *  - [targetC]: absolute battery temperature the level aims to stay under
 *  - [riseC]:   how much the battery may warm above where it started
 * [maxDuty] caps how hard the CPU workers may run (0..1) and [coreFraction] is the share
 * of CPU cores that get a worker thread.
 */
enum class HeatLevel(
    val label: String,
    val tagline: String,
    val targetHeadroom: Float,
    val targetC: Float,
    val riseC: Float,
    val maxDuty: Float,
    val coreFraction: Float,
) {
    LOW("Low", "Gentle warmth", 0.74f, 37f, 4f, 0.50f, 0.50f),
    MEDIUM("Medium", "Comfortable heat", 0.80f, 40f, 6f, 0.75f, 0.75f),
    HIGH("High", "Maximum heat", 0.85f, 43f, 9f, 1.00f, 1.00f);

    companion object {
        /** Battery temperature at which the fire is put out, no matter what. */
        const val HARD_CEILING_C = 45f

        /** The fire is put out if the battery warms this much above its starting temperature. */
        const val MAX_RISE_C = 12f

        /** Refuse to light the fire if the battery is already this warm. */
        const val START_MAX_BATTERY_C = 41f

        /**
         * A battery reading below this is treated as a broken sensor (some brands report the
         * wrong scale), so the fire won't run: without a trustworthy battery reading, the
         * battery safety limits can't work.
         */
        const val MIN_PLAUSIBLE_BATTERY_C = 5f

        /** The fire goes out at or below this battery percentage. */
        const val MIN_BATTERY_PERCENT = 20

        /** Back off firmly when the smoothed chip stress reaches this (1.0 = severe throttling). */
        const val HEADROOM_BACKOFF = 0.90f

        /** Processor sensor sanity stop: hot in absolute terms AND well above its starting value. */
        const val CPU_STOP_C = 90f
        const val CPU_STOP_RISE_C = 25f

        /** Longest a fire may burn. */
        const val MAX_MINUTES = 180
    }
}