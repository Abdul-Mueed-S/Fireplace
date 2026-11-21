package com.evu.fireplace

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import java.io.File

/**
 * One snapshot of everything we can learn about the phone's temperature.
 *
 * [batteryC] always works. [cpuC] / [gpuC] / [skinC] come from /sys/class/thermal and are
 * null when Android blocks apps from reading those sensors (common on newer phones).
 * [headroom] is Android's own forecast of how close the chip is to severe throttling
 * (0 = cool, 1 = severe throttling), NaN if unsupported.
 */
data class Reading(
    val batteryC: Float?,
    val cpuC: Float?,
    val gpuC: Float?,
    val skinC: Float?,
    val headroom: Float,
    val thermalStatus: Int,
)

object Sensors {

    fun read(context: Context): Reading {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val raw = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val batteryC = if (raw == Int.MIN_VALUE) null else raw / 10f

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val status = if (Build.VERSION.SDK_INT >= 29) pm.currentThermalStatus else 0
        val headroom = if (Build.VERSION.SDK_INT >= 30) pm.getThermalHeadroom(10) else Float.NaN

        var cpu: Float? = null
        var gpu: Float? = null
        var skin: Float? = null
        try {
            File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }?.forEach { zone ->
                val type = File(zone, "type").readTextOrNull()?.lowercase() ?: return@forEach
                var t = File(zone, "temp").readTextOrNull()?.toFloatOrNull() ?: return@forEach
                if (t > 200f) t /= 1000f // millidegrees -> degrees
                if (t < 0f || t > 120f) return@forEach
                when {
                    "gpu" in type -> gpu = maxOf(gpu ?: t, t)
                    "cpu" in type -> cpu = maxOf(cpu ?: t, t)
                    "skin" in type || "case" in type || "quiet" in type || "xo-therm" in type ->
                        skin = maxOf(skin ?: t, t)
                }
            }
        } catch (e: Exception) {
            // blocked by SELinux or missing: leave the values null
        }
        return Reading(batteryC, cpu, gpu, skin, headroom, status)
    }

    private fun File.readTextOrNull(): String? =
        try { readText().trim() } catch (e: Exception) { null }
}