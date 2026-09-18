package org.sih.itantra

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import org.sih.itantra.domain.repository.CommunicationRepository
import org.sih.itantra.presentation.navigation.AppNavigation
import org.sih.itantra.presentation.ui.theme.ITANTRATheme

class MainActivity : ComponentActivity() {

    private var repository: CommunicationRepository? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Permissions handled
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Force hardware acceleration and highest display refresh rate (120 Hz)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )
        enable120HzRefreshRate()

        requestRequiredPermissions()

        val app = application as ItantraApplication
        val repo = app.communicationRepository
        repository = repo

        // Update repository with active screen refresh rate
        detectAndSyncDisplayRate()

        setContent {
            ITANTRATheme {
                AppNavigation(repository = repo)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        enable120HzRefreshRate()
        detectAndSyncDisplayRate()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enable120HzRefreshRate()
            detectAndSyncDisplayRate()
        }
    }

    /**
     * Unlocks and forces 120 Hz display refresh rate on supported devices.
     * Checks all available display modes and sets preferredDisplayModeId and preferredRefreshRate.
     */
    private fun enable120HzRefreshRate() {
        try {
            val win = window ?: return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val display: Display? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        this.display
                    } catch (_: Throwable) {
                        (getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.getDisplay(Display.DEFAULT_DISPLAY)
                    }
                } else {
                    @Suppress("DEPRECATION")
                    windowManager.defaultDisplay
                }

                if (display != null) {
                    val supportedModes = display.supportedModes ?: emptyArray()
                    val currentMode = display.mode

                    // Filter modes: prioritize current resolution to avoid unwanted resolution scale changes
                    val candidateModes = supportedModes.filter {
                        currentMode == null || (it.physicalWidth == currentMode.physicalWidth && it.physicalHeight == currentMode.physicalHeight)
                    }.ifEmpty { supportedModes.toList() }

                    // Seek 120 Hz mode (118 Hz to 122 Hz), or highest available above 60 Hz
                    val highRateMode = candidateModes.filter { it.refreshRate in 118.0f..122.0f }.maxByOrNull { it.refreshRate }
                        ?: candidateModes.filter { it.refreshRate > 60.0f }.maxByOrNull { it.refreshRate }
                        ?: candidateModes.maxByOrNull { it.refreshRate }

                    val layoutParams = win.attributes
                    if (highRateMode != null) {
                        layoutParams.preferredDisplayModeId = highRateMode.modeId
                        layoutParams.preferredRefreshRate = highRateMode.refreshRate
                    } else {
                        layoutParams.preferredRefreshRate = 120.0f
                    }
                    win.attributes = layoutParams
                } else {
                    val layoutParams = win.attributes
                    layoutParams.preferredRefreshRate = 120.0f
                    win.attributes = layoutParams
                }
            } else {
                val layoutParams = win.attributes
                layoutParams.preferredRefreshRate = 120.0f
                win.attributes = layoutParams
            }
        } catch (e: Throwable) {
            android.util.Log.w("MainActivity", "High refresh rate request: ${e.message}")
        }
    }

    private fun detectAndSyncDisplayRate() {
        try {
            val display: Display? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    this.display
                } catch (_: Throwable) {
                    (getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.getDisplay(Display.DEFAULT_DISPLAY)
                }
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay
            }

            val hz = display?.refreshRate?.toInt() ?: 120
            repository?.setScreenRefreshRate(if (hz >= 118) 120 else hz)
        } catch (_: Throwable) {}
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.VIBRATE,
            Manifest.permission.CAMERA
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
}
