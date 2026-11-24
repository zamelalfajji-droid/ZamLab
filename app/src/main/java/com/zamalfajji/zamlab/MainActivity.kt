package com.zamalfajji.zamlab

import android.content.ContentResolver
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import com.zamalfajji.zamlab.editor.EditorViewModel
import com.zamalfajji.zamlab.editor.StudioTab
import com.zamalfajji.zamlab.ui.EditorScreen
import com.zamalfajji.zamlab.ui.theme.ZamLabTheme

class MainActivity : ComponentActivity() {

    private val editorViewModel: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )

        requestHighRefreshRateDisplayMode()

        // Only consume launch intent on fresh creation so configuration changes never overwrite active edits
        if (savedInstanceState == null) {
            handleIncomingIntent(intent)
        }

        setContent {
            ZamLabTheme {
                EditorScreen(viewModel = editorViewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_O && event?.repeatCount == 0) {
            val st = editorViewModel.uiState.value
            if (st.activeTab == StudioTab.MASK || st.gradingParams.masks.isNotEmpty()) {
                editorViewModel.toggleShowMaskOverlay()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun handleIncomingIntent(incoming: Intent?) {
        val uri = extractIncomingImageUri(incoming) ?: return
        val flags = incoming?.flags ?: 0
        if (uri.scheme == ContentResolver.SCHEME_CONTENT &&
            (flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0 &&
            (flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0
        ) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
        editorViewModel.openIncomingPhotoFromIntent(uri)
    }

    private fun extractIncomingImageUri(incoming: Intent?): Uri? {
        if (incoming == null) return null
        if ((incoming.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return null
        val candidate: Uri? = when (incoming.action) {
            Intent.ACTION_SEND -> {
                IntentCompat.getParcelableExtra(incoming, Intent.EXTRA_STREAM, Uri::class.java)
                    ?: incoming.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            }
            Intent.ACTION_EDIT, Intent.ACTION_VIEW -> {
                incoming.data
                    ?: incoming.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            }
            else -> null
        }

        val uri = candidate ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != ContentResolver.SCHEME_CONTENT && scheme != ContentResolver.SCHEME_FILE) {
            return null
        }

        val resolvedType = incoming.type ?: runCatching { contentResolver.getType(uri) }.getOrNull()
        val isImageMime = resolvedType == null ||
            resolvedType.startsWith("image/") ||
            resolvedType == "*/*"
        if (!isImageMime && !com.zamalfajji.zamlab.editor.ImageIoHelper.isSupportedImageUri(this, uri)) {
            return null
        }

        return uri
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
