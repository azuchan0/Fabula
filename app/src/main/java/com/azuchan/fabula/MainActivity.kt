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

        setContent {
            FabulaTheme {
                CameraScreen()
            }
        }
    }
}
