package org.sih.itantra.battery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.sih.itantra.domain.model.BatteryState

/**
 * Battery-aware optimization engine.
 * Protects field communicators from dying during prolonged search and rescue operations.
 */
class BatteryAdaptiveManager(private val context: Context) {

    private val _batteryLevel = MutableStateFlow(80)
    val batteryLevel: StateFlow<Int> = _batteryLevel.asStateFlow()

    private val _batteryState = MutableStateFlow(BatteryState.NORMAL)
    val batteryState: StateFlow<BatteryState> = _batteryState.asStateFlow()

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) {
                    val pct = ((level.toFloat() / scale.toFloat()) * 100).toInt()
                    updateLevel(pct)
                }
            }
        }
    }

    init {
        val initialLevel = readCurrentBatteryLevel()
        updateLevel(initialLevel)

        try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val sticky = context.registerReceiver(batteryReceiver, filter)
            sticky?.let {
                val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) {
                    val pct = ((level.toFloat() / scale.toFloat()) * 100).toInt()
                    updateLevel(pct)
                }
            }
        } catch (ignored: Exception) {}
    }

    fun readCurrentBatteryLevel(): Int {
        try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val capacity = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            if (capacity in 0..100) {
                return capacity
            }
        } catch (_: Exception) {}

        try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val sticky = context.registerReceiver(null, filter)
            if (sticky != null) {
                val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) {
                    return ((level.toFloat() / scale.toFloat()) * 100).toInt().coerceIn(0, 100)
                }
            }
        } catch (_: Exception) {}

        return 80 // Sensible default only if hardware APIs are completely blocked
    }

    fun updateLevel(pct: Int) {
        val clamped = pct.coerceIn(0, 100)
        _batteryLevel.value = clamped
        _batteryState.value = BatteryState.fromLevel(clamped)
    }

    /**
     * Determines whether UI animations (like high-frequency waveforms or radar sweeps) should be throttled.
     */
    val shouldThrottleAnimations: Boolean
        get() = _batteryState.value == BatteryState.LOW_POWER || _batteryState.value == BatteryState.CRITICAL

    /**
     * In low or critical power, forces pure semantic compression to minimize radio transmit time.
     */
    val enforceSemanticOnly: Boolean
        get() = _batteryState.value == BatteryState.LOW_POWER || _batteryState.value == BatteryState.CRITICAL
}
