package com.evu.fireplace

import android.Manifest
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.view.View
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

    /** The history list the chart is currently showing, to skip redundant redraws. */
    private var chartShown: List<Sample>? = null

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
        // Update the hint and timer preview as soon as a choice changes.
        b.levelGroup.addOnButtonCheckedListener { _, _, _ -> render(HeatState.ui.value) }
        b.timerGroup.addOnButtonCheckedListener { _, _, _ -> render(HeatState.ui.value) }

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

    // ---------------------------------------------------------------- drawing the screen

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)

    /** Normal below [warn], amber up to [hot], red above. */
    private fun heatColor(value: Float?, warn: Float, hot: Float): Int = when {
        value == null -> color(R.color.text_secondary)
        value >= hot -> color(R.color.danger)
        value >= warn -> color(R.color.amber)
        else -> color(R.color.text_primary)
    }

    private fun render(s: HeatUiState) {
        val r = if (s.running) s.reading else (idleReading ?: s.reading)
        val level = if (s.running) s.level else selectedLevel()

        // --- status pill, detail line and banner
        val finishedNicely = s.stopReason == "Timer finished."
        val pillText: String
        val pillColor: Int
        when {
            s.running && s.mode == "boost" -> { pillText = "Heating up fast"; pillColor = R.color.orange }
            s.running && s.mode == "hold" -> { pillText = "Holding heat"; pillColor = R.color.ok }
            s.running -> { pillText = "Battery-only mode"; pillColor = R.color.info }
            s.stopReason != null && finishedNicely -> { pillText = "Finished"; pillColor = R.color.ok }
            s.stopReason != null -> { pillText = "Put out"; pillColor = R.color.danger }
            else -> { pillText = "Ready"; pillColor = R.color.text_secondary }
        }
        b.statusPillText.text = pillText
        b.statusPill.setCardBackgroundColor(color(pillColor))

        b.statusDetail.text = when {
            s.running && s.mode == "boost" -> "Full power until the chip is warm, then it holds steady."
            s.running && s.mode == "hold" -> "Keeping the chip at its target heat."
            s.running -> "This phone doesn't report chip stress, so power follows the battery temperature."
            else -> "Ready - pick a heat level and light the fire."
        }

        val showBanner = !s.running && s.stopReason != null
        b.bannerCard.visibility = if (showBanner) View.VISIBLE else View.GONE
        if (showBanner) {
            b.bannerText.text = s.stopReason
            b.bannerCard.setCardBackgroundColor(color(if (finishedNicely) R.color.banner_ok else R.color.banner_warn))
        }

        // --- hero: fire, battery temperature, time left, power
        b.fireView.intensity = if (s.running) 0.25f + 0.75f * (s.duty / s.level.maxDuty).coerceIn(0f, 1f) else 0f
        b.tempValue.text = r?.batteryC?.let { "%.1f°C".format(it) } ?: "--°C"
        b.tempValue.setTextColor(heatColor(r?.batteryC, 38f, 41f))

        if (s.running) {
            b.timeLeftText.text = "%d:%02d".format(s.remainingSec / 60, s.remainingSec % 60)
            b.timeLeftText.setTextColor(color(R.color.text_primary))
        } else {
            b.timeLeftText.text = "%d:00".format(selectedMinutes())
            b.timeLeftText.setTextColor(color(R.color.text_secondary))
        }
        val power = if (s.running) (s.duty * 100).toInt() else 0
        b.powerBar.setProgressCompat(power, true)
        b.powerText.text = if (s.running) "Power $power%" else "Power off"

        // --- sensor tiles
        val cpu = r?.cpuC
        val gpu = r?.gpuC
        b.cpuValue.text = cpu?.let { "%.0f°C".format(it) } ?: "n/a"
        b.cpuValue.setTextColor(heatColor(cpu, 60f, 75f))
        b.gpuValue.text = gpu?.let { "%.0f°C".format(it) } ?: "n/a"
        b.gpuValue.setTextColor(heatColor(gpu, 55f, 70f))

        val stress = r?.headroom?.takeIf { !it.isNaN() }
        b.stressValue.text = stress?.let { "%d%%".format((it * 100).toInt()) } ?: "n/a"
        b.stressValue.setTextColor(heatColor(stress, 0.80f, 0.88f))

        val throttle = r?.thermalStatus
        b.throttleValue.text = throttle?.let { thermalName(it) } ?: "--"
        b.throttleValue.setTextColor(
            when {
                throttle == null || throttle == 0 -> color(R.color.text_primary)
                throttle == 1 -> color(R.color.amber)
                else -> color(R.color.danger)
            }
        )

        // --- chart
        if (s.history !== chartShown) {
            chartShown = s.history
            b.chartView.submit(s.history, level.targetHeadroom)
        }

        // --- controls
        b.levelHint.text = "${level.tagline} · keeps the battery under ${level.targetC.toInt()}°C"
        if (s.running) {
            b.toggleButton.text = "Put out the fire"
            b.toggleButton.backgroundTintList = ColorStateList.valueOf(color(R.color.danger))
            b.toggleButton.setTextColor(color(R.color.text_primary))
        } else {
            b.toggleButton.text = "Light the fire"
            b.toggleButton.backgroundTintList = ColorStateList.valueOf(color(R.color.orange))
            b.toggleButton.setTextColor(color(R.color.bg))
        }
        for (i in 0 until b.levelGroup.childCount) b.levelGroup.getChildAt(i).isEnabled = !s.running
        for (i in 0 until b.timerGroup.childCount) b.timerGroup.getChildAt(i).isEnabled = !s.running
    }

    private fun thermalName(status: Int): String = when (status) {
        0 -> "None"
        1 -> "Light"
        2 -> "Moderate"
        3 -> "Severe"
        4 -> "Critical"
        5 -> "Emergency"
        else -> "Shutdown"
    }
}