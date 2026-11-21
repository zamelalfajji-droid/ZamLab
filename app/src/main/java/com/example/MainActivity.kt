package com.example

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.ui.EditorScreen
import com.example.ui.theme.ZamLabTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )

        requestHighRefreshRateDisplayMode()

        setContent {
            ZamLabTheme {
                EditorScreen()
            }
        }
    }

    /**
     * Requests the highest refresh-rate display mode matching the device's current physical
     * resolution (e.g., 120Hz on flagship LTPO panels) so real-time grading, pinch-to-zoom,
     * and healing brush strokes stay silky smooth.
     */
    private fun requestHighRefreshRateDisplayMode() {
        runCatching {
            val activeDisplay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay
            } ?: return

            val currentMode = activeDisplay.mode
            val bestMode = activeDisplay.supportedModes
                .filter {
                    it.physicalWidth == currentMode.physicalWidth &&
                        it.physicalHeight == currentMode.physicalHeight
                }
                .maxByOrNull { it.refreshRate }
                ?: return

            if (bestMode.modeId != currentMode.modeId && bestMode.refreshRate > currentMode.refreshRate) {
                window.attributes = window.attributes.apply {
                    preferredDisplayModeId = bestMode.modeId
                }
            }
        }
    }
}
