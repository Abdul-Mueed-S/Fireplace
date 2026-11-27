package com.evu.fireplace

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import java.io.File

/**
 * One snapshot of everything we can learn about the phone's temperature and power state.
 *
 * [batteryC] works on every phone. [cpuC] / [gpuC] / [skinC] come from /sys/class/thermal and
 * are null when the brand blocks apps from reading them. [headroom] is Android's own forecast
 * of how close the chip is to severe throttling (0 = cool, 1 = severe), NaN if the phone
 * doesn't support it or is rate-limiting it.
 */
data class Reading(
    val batteryC: Float?,
    val cpuC: Float?,
    val gpuC: Float?,
    val skinC: Float?,
    val headroom: Float,
    val thermalStatus: Int,
    val percent: Int,
    val plugged: Boolean,
    val powerSave: Boolean,
)

object Sensors {

    private const val HEADROOM_STALE_MS = 20_000L        // keep using the last good value this long
    private const val HEADROOM_RETRY_AFTER_NAN_MS = 6_000L

    private enum class Kind { CPU, GPU, SKIN }
    private class Zone(val kind: Kind, val temp: File)

    private var zones: List<Zone>? = null
    private var lastHeadroom = Float.NaN
    private var lastHeadroomOkAt = 0L
    private var lastAttemptAt = 0L
    private var retryNotBefore = 0L

    /**
     * @param headroomEveryMs how often the (possibly rate-limited) chip-stress call may be made.
     * Some phones return NaN when it is called too often (roughly more than 10 times a minute),
     * so the idle screen passes a long interval and the running service passes 1 second. After
     * a NaN we wait a few seconds before asking again and keep using the last good value.
     */
    @Synchronized
    fun read(context: Context, headroomEveryMs: Long = 1000L): Reading {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val raw = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        // Android reports tenths of a degree. Zero, negative or absurd values mean "no sensor".
        val batteryC = if (raw == Int.MIN_VALUE || raw <= 0 || raw > 900) null else raw / 10f
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else 100
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val status = if (Build.VERSION.SDK_INT >= 29) {
            try { pm.currentThermalStatus } catch (e: Exception) { 0 }
        } else 0

        val now = SystemClock.elapsedRealtime()
        if (Build.VERSION.SDK_INT >= 30 && now - lastAttemptAt >= headroomEveryMs && now >= retryNotBefore) {
            lastAttemptAt = now
            val v = try { pm.getThermalHeadroom(10) } catch (e: Exception) { Float.NaN }
            if (v.isNaN()) {
                retryNotBefore = now + HEADROOM_RETRY_AFTER_NAN_MS
            } else {
                lastHeadroom = v
                lastHeadroomOkAt = now
            }
        }
        val headroom = if (now - lastHeadroomOkAt <= HEADROOM_STALE_MS) lastHeadroom else Float.NaN

        var cpu: Float? = null
        var gpu: Float? = null
        var skin: Float? = null
        for (zone in zoneList()) {
            val t = readTemp(zone.temp) ?: continue
            when (zone.kind) {
                Kind.CPU -> cpu = maxOf(cpu ?: t, t)
                Kind.GPU -> gpu = maxOf(gpu ?: t, t)
                Kind.SKIN -> skin = maxOf(skin ?: t, t)
            }
        }
        return Reading(batteryC, cpu, gpu, skin, headroom, status, percent, plugged, pm.isPowerSaveMode)
    }

    /** Finds the readable thermal zones once; brands name them differently. */
    private fun zoneList(): List<Zone> {
        zones?.let { return it }
        val found = mutableListOf<Zone>()
        try {
            File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }?.forEach { dir ->
                val type = File(dir, "type").readTextOrNull()?.lowercase() ?: return@forEach
                val kind = when {
                    "gpu" in type -> Kind.GPU
                    "cpu" in type || "soc" in type -> Kind.CPU
                    "skin" in type || "case" in type || "quiet" in type || "xo-therm" in type || "back" in type -> Kind.SKIN
                    else -> return@forEach
                }
                val temp = File(dir, "temp")
                if (readTemp(temp) != null) found += Zone(kind, temp)
            }
        } catch (e: Exception) {
            // blocked by the system: leave the list empty
        }
        zones = found
        return found
    }

    private fun readTemp(file: File): Float? {
        var t = file.readTextOrNull()?.toFloatOrNull() ?: return null
        if (t > 200f) t /= 1000f // millidegrees -> degrees
        return if (t in 1f..120f) t else null
    }

    private fun File.readTextOrNull(): String? =
        try { readText().trim() } catch (e: Exception) { null }
}