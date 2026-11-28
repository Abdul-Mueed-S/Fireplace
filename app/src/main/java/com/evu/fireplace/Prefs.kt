package com.evu.fireplace

import android.content.Context

/** The few settings the app remembers. Also used by the tile and shortcuts. */
object Prefs {
    private const val FILE = "fireplace"

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun level(c: Context): HeatLevel =
        HeatLevel.values().getOrElse(sp(c).getInt("level", HeatLevel.MEDIUM.ordinal)) { HeatLevel.MEDIUM }

    fun setLevel(c: Context, level: HeatLevel) {
        sp(c).edit().putInt("level", level.ordinal).apply()
    }

    fun minutes(c: Context): Int = sp(c).getInt("minutes", 30).coerceIn(1, HeatLevel.MAX_MINUTES)

    fun setMinutes(c: Context, minutes: Int) {
        sp(c).edit().putInt("minutes", minutes).apply()
    }

    fun keepScreenOn(c: Context): Boolean = sp(c).getBoolean("keep_screen_on", false)

    fun setKeepScreenOn(c: Context, on: Boolean) {
        sp(c).edit().putBoolean("keep_screen_on", on).apply()
    }
}