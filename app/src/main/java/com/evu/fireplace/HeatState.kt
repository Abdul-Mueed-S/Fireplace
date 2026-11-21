package com.evu.fireplace

import kotlinx.coroutines.flow.MutableStateFlow

data class HeatUiState(
    val running: Boolean = false,
    val level: HeatLevel = HeatLevel.MEDIUM,
    val batteryTempC: Float? = null,
    val thermalStatus: Int = 0,
    val duty: Float = 0f,
    val remainingSec: Int = 0,
    /** Why the fire last went out (null if it never ran or the user stopped it). */
    val stopReason: String? = null,
)

/** Tiny in-process bridge between [HeatService] and the UI. */
object HeatState {
    val ui = MutableStateFlow(HeatUiState())
}
