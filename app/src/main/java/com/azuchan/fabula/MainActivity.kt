package com.azuchan.fabula

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.azuchan.fabula.ui.camera.CameraScreen
import com.azuchan.fabula.ui.theme.FabulaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Camera UI draws edge-to-edge; Compose applies safeDrawingPadding to controls.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            // The viewfinder background is black, so use light system-bar icons.
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        requestHighestRefreshRate()

        setContent {
            FabulaTheme {
                CameraScreen()
            }
        }
    }

    /**
     * Asks for the display's fastest mode at the current resolution (120 Hz on the Pixel 9a), so
     * dials, zoom and animations track the finger smoothly. The system may still lower it (battery
     * saver, Smooth Display off).
     */
    private fun requestHighestRefreshRate() {
        val display = display ?: return
        val current = display.mode
        val fastest = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return
        window.attributes = window.attributes.apply {
            preferredDisplayModeId = fastest.modeId
            preferredRefreshRate = fastest.refreshRate
        }
    }
}
