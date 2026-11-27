package com.evu.fireplace

import android.Manifest
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.evu.fireplace.databinding.ActivityMainBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    /** Latest sensor snapshot taken by the activity while the fire is off. */
    private var idleReading: Reading? = null

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // The service runs either way, but without permission the notification is hidden.
            if (!granted) {
                Toast.makeText(this, "Notifications are off - the Put out button won't show.", Toast.LENGTH_LONG).show()
            }
            startFire()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.toggleButton.setOnClickListener {
            if (HeatState.ui.value.running) stopFire() else requestStart()
        }
        b.btnShareLog.setOnClickListener { shareLog() }
        b.btnClearLogs.setOnClickListener {
            val n = LogWriter.clear(this)
            val msg = if (n == 0) "No old logs to clear." else "Cleared $n log file(s)."
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { HeatState.ui.collect { render(it) } }
                // While the fire is off the service isn't reporting, so read the sensors ourselves.
                // Some phones rate-limit the chip-stress call, so ask for it only every few seconds.
                launch {
                    while (true) {
                        if (!HeatState.ui.value.running) {
                            idleReading = Sensors.read(this@MainActivity, 8000L)
                            render(HeatState.ui.value)
                        }
                        delay(2000)
                    }
                }
            }
        }
    }

    private fun selectedLevel(): HeatLevel = when (b.levelGroup.checkedButtonId) {
        R.id.btnLow -> HeatLevel.LOW
        R.id.btnHigh -> HeatLevel.HIGH
        else -> HeatLevel.MEDIUM
    }

    private fun selectedMinutes(): Int = when (b.timerGroup.checkedButtonId) {
        R.id.btn60 -> 60
        R.id.btn90 -> 90
        else -> 30
    }

    private fun requestStart() {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if ((battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0) {
            Toast.makeText(this, "Unplug the charger first.", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startFire()
        }
    }

    private fun startFire() {
        val intent = Intent(this, HeatService::class.java)
            .setAction(HeatService.ACTION_START)
            .putExtra(HeatService.EXTRA_LEVEL, selectedLevel().ordinal)
            .putExtra(HeatService.EXTRA_MINUTES, selectedMinutes())
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopFire() {
        startService(Intent(this, HeatService::class.java).setAction(HeatService.ACTION_STOP))
    }

    private fun shareLog() {
        val file = LogWriter.latest(this)
        if (file == null) {
            Toast.makeText(this, "No log yet - light the fire first.", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Share log"))
    }

    private fun render(s: HeatUiState) {
        val r = if (s.running) s.reading else (idleReading ?: s.reading)

        b.fireView.intensity = if (s.running) 0.25f + 0.75f * (s.duty / s.level.maxDuty).coerceIn(0f, 1f) else 0f
        b.tempValue.text = r?.batteryC?.let { "%.1f°C".format(it) } ?: "--°C"

        b.statusText.text = when {
            s.running -> {
                val left = "%d:%02d".format(s.remainingSec / 60, s.remainingSec % 60)
                "${s.level.label} · ${modeText(s.mode)} · $left left · power ${(s.duty * 100).toInt()}%"
            }
            s.stopReason != null -> s.stopReason
            else -> "Battery temperature"
        }
        b.sensorText.text = sensorSummary(r)

        b.toggleButton.text = if (s.running) "Put out the fire" else "Light the fire"
        for (i in 0 until b.levelGroup.childCount) b.levelGroup.getChildAt(i).isEnabled = !s.running
        for (i in 0 until b.timerGroup.childCount) b.timerGroup.getChildAt(i).isEnabled = !s.running
    }

    private fun modeText(mode: String): String = when (mode) {
        "boost" -> "heating up fast"
        "hold" -> "holding heat"
        "battery-only" -> "battery-only mode"
        else -> mode
    }

    private fun sensorSummary(r: Reading?): String {
        if (r == null) return ""
        val lines = mutableListOf<String>()
        val chips = listOfNotNull(
            r.cpuC?.let { "CPU %.1f°C".format(it) },
            r.gpuC?.let { "GPU %.1f°C".format(it) },
            r.skinC?.let { "Skin %.1f°C".format(it) },
        )
        if (chips.isNotEmpty()) {
            lines += chips.joinToString("  ·  ")
        } else {
            lines += "CPU/GPU sensors are blocked by Android on this phone"
        }
        if (!r.headroom.isNaN()) {
            lines += "Chip stress %d%%  ·  throttling: %s".format((r.headroom * 100).toInt(), thermalName(r.thermalStatus))
        } else {
            lines += "Chip stress not reported by this phone  ·  throttling: %s".format(thermalName(r.thermalStatus))
        }
        return lines.joinToString("\n")
    }

    private fun thermalName(status: Int): String = when (status) {
        0 -> "none"
        1 -> "light"
        2 -> "moderate"
        3 -> "severe"
        4 -> "critical"
        5 -> "emergency"
        else -> "shutdown"
    }
}