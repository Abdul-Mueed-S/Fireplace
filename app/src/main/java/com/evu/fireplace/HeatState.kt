package com.evu.fireplace

import kotlinx.coroutines.flow.MutableStateFlow

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
)

/** Tiny in-process bridge between [HeatService] and the UI. */
object HeatState {
    val ui = MutableStateFlow(HeatUiState())
}