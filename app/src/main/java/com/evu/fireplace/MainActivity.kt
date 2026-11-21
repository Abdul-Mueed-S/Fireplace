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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.evu.fireplace.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

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

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                HeatState.ui.collect { render(it) }
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

    private fun render(s: HeatUiState) {
        b.fireView.intensity = if (s.running) 0.25f + 0.75f * (s.duty / s.level.maxDuty).coerceIn(0f, 1f) else 0f
        b.tempValue.text = s.batteryTempC?.let { "%.1f°C".format(it) } ?: "--°C"

        b.statusText.text = when {
            s.running -> "${s.level.label} · ${s.remainingSec / 60}:%02d left · power ${(s.duty * 100).toInt()}%%".format(s.remainingSec % 60)
            s.stopReason != null -> s.stopReason
            else -> "Battery temperature"
        }

        b.toggleButton.text = if (s.running) "Put out the fire" else "Light the fire"
        for (i in 0 until b.levelGroup.childCount) b.levelGroup.getChildAt(i).isEnabled = !s.running
        for (i in 0 until b.timerGroup.childCount) b.timerGroup.getChildAt(i).isEnabled = !s.running
    }
}
