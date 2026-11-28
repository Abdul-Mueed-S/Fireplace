package com.evu.fireplace

import kotlinx.coroutines.flow.MutableStateFlow

/** One second of the running fire, kept for the live chart. */
data class Sample(
    val batteryC: Float,
    val cpuC: Float?,
    val gpuC: Float?,
    /** Chip stress 0..1, NaN when the phone doesn't report it. */
    val headroom: Float,
    /** Power 0..1. */
    val duty: Float,
)

data class HeatUiState(
    val running: Boolean = false,
    val level: HeatLevel = HeatLevel.MEDIUM,
    val reading: Reading? = null,
    val duty: Float = 0f,
    val remainingSec: Int = 0,
    /** "boost", "hold" or "battery-only" while running. */
    val mode: String = "",
    /** Why the fire last went out or refused to start (null if it never ran or the user stopped it). */
    val stopReason: String? = null,
    /** The last few minutes, one sample per second. Kept after the fire goes out. */
    val history: List<Sample> = emptyList(),
)

/** Tiny in-process bridge between [HeatService] and the UI. */
object HeatState {
    const val HISTORY_SIZE = 300
    val ui = MutableStateFlow(HeatUiState())
}