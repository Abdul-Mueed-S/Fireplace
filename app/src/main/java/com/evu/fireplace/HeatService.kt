package com.evu.fireplace

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
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
 * once-per-second safety check. It stops itself on: hard temperature ceiling, system
 * thermal throttling, charger plugged in, low battery, unreadable temperature, or timer end.
 */
class HeatService : Service() {

    companion object {
        const val ACTION_START = "com.evu.fireplace.START"
        const val ACTION_STOP = "com.evu.fireplace.STOP"
        const val EXTRA_LEVEL = "level"
        const val EXTRA_MINUTES = "minutes"

        private const val CHANNEL_ID = "fireplace"
        private const val NOTIF_ID = 1
        private const val PERIOD_MS = 100L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val workers = mutableListOf<Thread>()
    private var wakeLock: PowerManager.WakeLock? = null
    private var governor: ThermalGovernor? = null
    private var level = HeatLevel.MEDIUM
    private var endAtMs = 0L

    @Volatile private var running = false
    @Volatile private var dutyPercent = 0
    @Volatile private var sink = 0.0 // keeps the JIT from deleting the busy loop

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            onTick()
            if (running) handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (!running) start(intent)
            ACTION_STOP -> stopFire(reason = null)
            else -> if (!running) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        halt()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- start / stop

    private fun start(intent: Intent) {
        level = HeatLevel.values().getOrElse(intent.getIntExtra(EXTRA_LEVEL, 1)) { HeatLevel.MEDIUM }
        val minutes = intent.getIntExtra(EXTRA_MINUTES, 30).coerceIn(1, 180)
        endAtMs = SystemClock.elapsedRealtime() + minutes * 60_000L
        governor = ThermalGovernor(level)

        createChannel()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification("Starting…"), type)

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fireplace:heat").apply {
            acquire(minutes * 60_000L + 60_000L) // auto-release even if we crash
        }

        running = true
        dutyPercent = 0
        startWorkers()
        HeatState.ui.value = HeatUiState(running = true, level = level, remainingSec = minutes * 60)
        handler.post(ticker) // first safety check runs immediately
    }

    private fun stopFire(reason: String?) {
        HeatState.ui.value = HeatState.ui.value.copy(
            running = false, duty = 0f, remainingSec = 0, stopReason = reason
        )
        halt()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
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
        val count = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
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
            workers += t
        }
    }

    // ---------------------------------------------------------------- safety loop

    private fun onTick() {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val rawTemp = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val tempC = if (rawTemp == Int.MIN_VALUE) null else rawTemp / 10f
        val levelRaw = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percent = if (levelRaw >= 0 && scale > 0) levelRaw * 100 / scale else 100
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val thermal = if (Build.VERSION.SDK_INT >= 29) pm.currentThermalStatus else 0
        val headroom = if (Build.VERSION.SDK_INT >= 30) pm.getThermalHeadroom(10) else Float.NaN

        val remainingSec = ((endAtMs - SystemClock.elapsedRealtime()) / 1000).toInt().coerceAtLeast(0)

        val reason = when {
            tempC == null -> "Couldn't read the battery temperature."
            tempC >= HeatLevel.HARD_CEILING_C -> "Battery reached %.1f°C - fire put out.".format(tempC)
            thermal >= PowerManager.THERMAL_STATUS_MODERATE -> "Android reported thermal throttling - fire put out."
            plugged -> "Charger connected - fire put out."
            percent <= HeatLevel.MIN_BATTERY_PERCENT -> "Battery is at $percent% - fire put out."
            remainingSec <= 0 -> "Timer finished."
            else -> null
        }
        if (reason != null) {
            stopFire(reason)
            return
        }

        val duty = governor!!.update(tempC!!, thermal, headroom)
        dutyPercent = (duty * 100).toInt()

        HeatState.ui.value = HeatUiState(
            running = true, level = level, batteryTempC = tempC, thermalStatus = thermal,
            duty = duty, remainingSec = remainingSec,
        )
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification("%s · %.1f°C · %d min left".format(level.label, tempC, (remainingSec + 59) / 60)))
    }

    // ---------------------------------------------------------------- notification

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Fireplace", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, HeatService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_flame)
            .setContentTitle("Fireplace is on")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, "Put out", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }
}
