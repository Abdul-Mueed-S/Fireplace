package com.evu.fireplace

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes one CSV file per fire session (one row per second) into the app's private
 * `files/logs/` folder, so you can study how the phone behaves. Share it from the app.
 */
object LogWriter {

    private const val HEADER =
        "elapsed_s,time,level,battery_c,cpu_c,gpu_c,skin_c,headroom,thermal,duty_pct,battery_pct,event"

    private var file: File? = null
    private var startMs = 0L

    private fun logsDir(context: Context): File =
        File(context.filesDir, "logs").apply { mkdirs() }

    fun start(context: Context, level: HeatLevel, minutes: Int) {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val f = File(logsDir(context), "fireplace_${stamp}_${level.label.lowercase(Locale.US)}.csv")
        try {
            f.writeText(HEADER + "\n")
        } catch (e: Exception) {
            return
        }
        file = f
        startMs = SystemClock.elapsedRealtime()
        row(level, null, 0f, -1, "start ${minutes}min")
    }

    fun row(level: HeatLevel, r: Reading?, duty: Float, batteryPercent: Int, event: String = "") {
        val f = file ?: return
        val elapsed = (SystemClock.elapsedRealtime() - startMs) / 1000
        val clock = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        fun num(v: Float?) = if (v == null || v.isNaN()) "" else String.format(Locale.US, "%.2f", v)
        val line = listOf(
            elapsed.toString(),
            clock,
            level.label,
            num(r?.batteryC),
            num(r?.cpuC),
            num(r?.gpuC),
            num(r?.skinC),
            num(r?.headroom),
            r?.thermalStatus?.toString() ?: "",
            (duty * 100).toInt().toString(),
            if (batteryPercent >= 0) batteryPercent.toString() else "",
            event.replace(",", ";"),
        ).joinToString(",")
        try {
            f.appendText(line + "\n")
        } catch (e: Exception) {
            // disk full or removed: skip this row
        }
    }

    fun end() {
        file = null
    }

    /** The most recent log file, or null if there is none. */
    fun latest(context: Context): File? =
        logsDir(context).listFiles { f -> f.extension == "csv" }?.maxByOrNull { it.lastModified() }

    /** Deletes all logs except the one being written right now. Returns how many were deleted. */
    fun clear(context: Context): Int {
        var n = 0
        logsDir(context).listFiles { f -> f.extension == "csv" }?.forEach {
            if (it != file && it.delete()) n++
        }
        return n
    }
}