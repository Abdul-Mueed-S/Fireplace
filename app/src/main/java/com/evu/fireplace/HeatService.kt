package com.evu.fireplace

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Foreground service that burns CPU in short bursts (duty cycle) to make heat, under a
 * once-per-second safety check.
 *
 * It refuses to start if the phone is charging, low on battery, already warm, or its battery
 * sensor looks wrong. While running, the fire is put out on: battery too hot, battery warmed
 * too much since the start, processor sensor way up, Android thermal throttling (moderate or
 * worse), charger plugged in, low battery, an unreadable battery temperature, an error in
 * the safety check itself, or the timer ending.
 */
class HeatService : Service() {

    companion object {
        const val ACTION_START = "com.evu.fireplace.START"
        const val ACTION_STOP = "com.evu.fireplace.STOP"
        const val EXTRA_LEVEL = "level"
        const val EXTRA_MINUTES = "minutes"

        private const val CHANNEL_RUNNING = "fireplace"
        private const val CHANNEL_ALERT = "fireplace_alert"
        private const val NOTIF_ID = 1
        private const val ALERT_ID = 2
        private const val PERIOD_MS = 100L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val workers = mutableListOf<Thread>()
    private var wakeLock: PowerManager.WakeLock? = null
    private var governor: ThermalGovernor? = null
    private var level = HeatLevel.MEDIUM
    private var endAtMs = 0L
    private var startBatteryC = Float.NaN
    private var startCpuC: Float? = null
    private var lastReading: Reading? = null
    private var lastMode = ""
    private val history = ArrayDeque<Sample>()

    @Volatile private var running = false
    @Volatile private var dutyPercent = 0
    @Volatile private var sink = 0.0 // keeps the JIT from deleting the busy loop

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                onTick()
            } catch (e: Exception) {
                // If the safety check itself breaks, never keep heating blind.
                stopFire("Safety check failed (" + e.javaClass.simpleName + ") - fire put out.")
            }
            if (running) handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        // Anything started with startForegroundService() must call startForeground() promptly,
        // even if we then refuse to run, or Android kills the app.
        if (!running && action == ACTION_START) {
            createChannels()
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification("Starting…"), foregroundType())
        }

        when (action) {
            ACTION_START -> if (!running && intent != null) {
                val lvl = HeatLevel.values()
                    .getOrElse(intent.getIntExtra(EXTRA_LEVEL, HeatLevel.MEDIUM.ordinal)) { HeatLevel.MEDIUM }
                start(lvl, intent.getIntExtra(EXTRA_MINUTES, 30))
            }
            ACTION_STOP -> if (running) stopFire(null) else stopSelf()
            else -> if (!running) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        halt()
        super.onDestroy()
    }

    private fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0

    // ---------------------------------------------------------------- start / stop

    private fun start(newLevel: HeatLevel, minutes: Int) {
        level = newLevel
        val mins = minutes.coerceIn(1, HeatLevel.MAX_MINUTES)

        // Refuse to start in unsafe conditions.
        val r = Sensors.read(this, 0L)
        val bt = r.batteryC
        val refusal = when {
            bt == null -> "Couldn't read the battery temperature."
            bt < HeatLevel.MIN_PLAUSIBLE_BATTERY_C ->
                "Battery sensor reads %.1f°C - too cold, or this phone reports it oddly. Not lighting the fire.".format(bt)
            r.plugged -> "Unplug the charger first."
            r.percent <= HeatLevel.MIN_BATTERY_PERCENT -> "Battery is at ${r.percent}% - charge it first."
            bt >= HeatLevel.START_MAX_BATTERY_C -> "Phone is already warm (%.0f°C) - let it cool a little first.".format(bt)
            else -> null
        }
        if (refusal != null || bt == null) {
            val why = refusal ?: "Couldn't read the battery temperature."
            HeatState.ui.value = HeatUiState(stopReason = why, level = level)
            postAlert(why)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        endAtMs = SystemClock.elapsedRealtime() + mins * 60_000L
        startBatteryC = bt
        startCpuC = r.cpuC
        lastReading = r
        lastMode = ""
        governor = ThermalGovernor(level)
        LogWriter.start(this, level, mins)

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fireplace:heat").apply {
            setReferenceCounted(false)
            acquire((HeatLevel.MAX_MINUTES + 5) * 60_000L) // released in halt(); this is only a backstop
        }

        running = true
        dutyPercent = 0
        startWorkers()
        HeatState.ui.value = HeatUiState(
            running = true, level = level, reading = r, remainingSec = mins * 60, mode = "boost",
        )
        handler.post(ticker) // first safety check runs immediately
    }

    private fun stopFire(reason: String?) {
        LogWriter.row(level, lastReading, 0f, lastMode, "stop: " + (reason ?: "put out by user"))
        LogWriter.end()
        HeatState.ui.value = HeatState.ui.value.copy(
            running = false, duty = 0f, remainingSec = 0, mode = "", stopReason = reason,
        )
        halt()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (reason != null) postAlert(reason)
        stopSelf()
    }

    private fun halt() {
        running = false
        dutyPercent = 0
        handler.removeCallbacks(ticker)
        workers.forEach { it.interrupt() }
        workers.clear()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    // ---------------------------------------------------------------- heat workers

    private fun startWorkers() {
        val count = (Runtime.getRuntime().availableProcessors() * level.coreFraction).toInt().coerceAtLeast(1)
        repeat(count) { i ->
            val t = Thread({
                var x = 1.0 + i
                try {
                    while (running) {
                        val busyMs = dutyPercent * PERIOD_MS / 100
                        if (busyMs > 0) {
                            val deadline = System.nanoTime() + busyMs * 1_000_000L
                            while (System.nanoTime() < deadline) {
                                repeat(2000) { x = sin(x) * cos(x) + sqrt(abs(x)) + 1.0001 }
                            }
                        }
                        sink = x
                        val rest = PERIOD_MS - busyMs
                        if (rest > 0) Thread.sleep(rest)
                    }
                } catch (e: InterruptedException) {
                    // service is stopping
                }
            }, "fire-worker-$i")
            t.start()
            history.clear()
            workers += t
        }
    }

    // ---------------------------------------------------------------- safety loop

    private fun onTick() {
        val r = Sensors.read(this, 1000L)
        lastReading = r
        val bt = r.batteryC
        if (bt == null || bt < HeatLevel.MIN_PLAUSIBLE_BATTERY_C) {
            stopFire("Couldn't read a sensible battery temperature - fire put out.")
            return
        }
        val cpu = r.cpuC
        val cpuBase = startCpuC
        val remainingSec = ((endAtMs - SystemClock.elapsedRealtime()) / 1000).toInt().coerceAtLeast(0)

        val reason = when {
            bt >= HeatLevel.HARD_CEILING_C -> "Battery reached %.1f°C - fire put out.".format(bt)
            bt - startBatteryC >= HeatLevel.MAX_RISE_C -> "Battery warmed by %.0f°C - fire put out.".format(bt - startBatteryC)
            cpu != null && cpu >= HeatLevel.CPU_STOP_C && (cpuBase == null || cpu - cpuBase >= HeatLevel.CPU_STOP_RISE_C) ->
                "Processor reached %.0f°C - fire put out.".format(cpu)
            r.thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE -> "Android reported thermal throttling - fire put out."
            r.plugged -> "Charger connected - fire put out."
            r.percent <= HeatLevel.MIN_BATTERY_PERCENT -> "Battery is at ${r.percent}% - fire put out."
            remainingSec <= 0 -> "Timer finished."
            else -> null
        }
        if (reason != null) {
            stopFire(reason)
            return
        }

        val gov = governor ?: return
        val duty = gov.update(bt, r.thermalStatus, r.headroom)
        dutyPercent = (duty * 100).toInt()
        lastMode = gov.mode.label
        LogWriter.row(level, r, duty, lastMode)

        history.addLast(Sample(bt, r.cpuC, r.gpuC, r.headroom, duty))
        if (history.size > HeatState.HISTORY_SIZE) history.removeFirst()

        HeatState.ui.value = HeatUiState(
            running = true, level = level, reading = r, duty = duty,
            remainingSec = remainingSec, mode = lastMode, history = history.toList(),
        )
        getSystemService(NotificationManager::class.java).notify(
            NOTIF_ID,
            buildNotification("%s · %.1f°C · %d min left".format(level.label, bt, (remainingSec + 59) / 60)),
        )
    }

    // ---------------------------------------------------------------- notifications

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RUNNING, "Fireplace running", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, "Fireplace went out", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun buildNotification(text: String): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, HeatService::class.java).setAction(ACTION_STOP), flags
        )
        return NotificationCompat.Builder(this, CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_flame)
            .setContentTitle("Fireplace is on")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, "Put out", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    /** Tells the person why the fire went out (or never started), even if the app isn't open. */
    private fun postAlert(text: String) {
        createChannels()
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val n = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_flame)
            .setContentTitle("Fireplace went out")
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(ALERT_ID, n)
    }
}