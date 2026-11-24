package com.zamalfajji.zamlab.editor

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

enum class CameraPermissionUiState {
    IDLE,
    RATIONALE_NEEDED,
    PERMANENTLY_DENIED
}

data class CameraCaptureOutput(
    val file: File,
    val uri: Uri
)

/**
 * Manages temporary high-resolution camera capture files, `FileProvider` URIs,
 * stale capture cleanup, and runtime camera permission denial state transitions.
 */
object CameraCaptureHelper {
    const val CAPTURE_DIR_NAME: String = "camera_captures"
    const val FILE_PROVIDER_AUTHORITY_SUFFIX: String = ".fileprovider"
    const val DEFAULT_STALE_MAX_AGE_MS: Long = 15L * 60L * 1000L
    private const val CAPTURE_FILE_PREFIX: String = "Z-Effect_capture_"
    private const val CAPTURE_FILE_SUFFIX: String = ".jpg"

    private val captureSeq = AtomicInteger(0)

    fun fileProviderAuthority(context: Context): String =
        "${context.packageName}$FILE_PROVIDER_AUTHORITY_SUFFIX"

    fun captureDirectory(context: Context): File =
        File(context.cacheDir, CAPTURE_DIR_NAME).apply {
            if (!exists()) {
                mkdirs()
            }
        }

    fun createCaptureOutput(context: Context): CameraCaptureOutput {
        val dir = captureDirectory(context)
        val seq = captureSeq.incrementAndGet()
        val file = File(dir, "$CAPTURE_FILE_PREFIX${System.currentTimeMillis()}_$seq$CAPTURE_FILE_SUFFIX")
        val uri = FileProvider.getUriForFile(context, fileProviderAuthority(context), file)
        return CameraCaptureOutput(file = file, uri = uri)
    }

    fun deleteCaptureFileQuietly(file: File?) {
        if (file == null) return
        runCatching {
            if (file.exists()) {
                file.delete()
            }
        }
    }

    /**
     * Deletes temporary camera capture files in `cacheDir/camera_captures/`.
     *
     * @param keepFilePath optional absolute path of an active capture file to preserve.
     * @param maxAgeMs when > 0, only deletes files older than [maxAgeMs]; when <= 0, deletes all
     * non-kept files (used on fresh editor session startup).
     * @return number of deleted files.
     */
    fun cleanupStaleCaptures(
        context: Context,
        keepFilePath: String? = null,
        maxAgeMs: Long = 0L,
        nowMs: Long = System.currentTimeMillis()
    ): Int {
        val dir = File(context.cacheDir, CAPTURE_DIR_NAME)
        if (!dir.exists() || !dir.isDirectory) return 0
        val children = dir.listFiles() ?: return 0
        var deleted = 0
        for (child in children) {
            if (!child.isFile) continue
            if (keepFilePath != null && child.absolutePath == keepFilePath) continue
            val isStale = maxAgeMs <= 0L || (nowMs - child.lastModified() >= maxAgeMs)
            if (isStale && runCatching { child.delete() }.getOrDefault(false)) {
                deleted++
            }
        }
        return deleted
    }

    /**
     * Resolves the UI state to display after a `CAMERA` permission request is denied.
     */
    fun resolveCameraPermissionDenial(
        shouldShowRationale: Boolean,
        hasRequestedBefore: Boolean
    ): CameraPermissionUiState {
        return when {
            shouldShowRationale -> CameraPermissionUiState.RATIONALE_NEEDED
            hasRequestedBefore -> CameraPermissionUiState.PERMANENTLY_DENIED
            else -> CameraPermissionUiState.PERMANENTLY_DENIED
        }
    }

    fun buildAppSettingsIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        ).apply {
            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    fun findActivity(context: Context): Activity? {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }
}
