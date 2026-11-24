package com.zamalfajji.zamlab

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import com.zamalfajji.zamlab.editor.CameraCaptureHelper
import com.zamalfajji.zamlab.editor.CameraPermissionUiState
import com.zamalfajji.zamlab.editor.EditorViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CameraCaptureRobolectricTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        clearFileProviderStaticCache()
    }

    @After
    fun tearDown() {
        clearFileProviderStaticCache()
        Dispatchers.resetMain()
    }

    /**
     * Robolectric allocates a fresh temporary `cacheDir` per test method in the same sandbox,
     * whereas AndroidX `FileProvider.sCache` caches `PathStrategy` roots statically by authority.
     */
    private fun clearFileProviderStaticCache() {
        runCatching {
            val field = FileProvider::class.java.getDeclaredField("sCache")
            field.isAccessible = true
            (field.get(null) as? MutableMap<*, *>)?.clear()
        }
    }

    @Test
    fun `manifest declares camera permission, optional camera feature, FileProvider, and zero broad storage permissions`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val pm = context.packageManager
        val pkgInfo = pm.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS or
                PackageManager.GET_PROVIDERS or
                PackageManager.GET_CONFIGURATIONS
        )

        val requestedPermissions = pkgInfo.requestedPermissions?.toSet().orEmpty()
        assertTrue(
            "AndroidManifest must declare android.permission.CAMERA",
            requestedPermissions.contains(Manifest.permission.CAMERA)
        )
        assertFalse(
            "Google Play Policy: must NOT declare READ_EXTERNAL_STORAGE",
            requestedPermissions.contains(Manifest.permission.READ_EXTERNAL_STORAGE)
        )
        assertFalse(
            "Google Play Policy: must NOT declare READ_MEDIA_IMAGES",
            requestedPermissions.contains("android.permission.READ_MEDIA_IMAGES")
        )

        val expectedAuthority = CameraCaptureHelper.fileProviderAuthority(context)
        val fileProvider = pkgInfo.providers?.firstOrNull {
            it.name == "androidx.core.content.FileProvider"
        }
        assertNotNull("AndroidManifest must declare androidx.core.content.FileProvider", fileProvider)
        assertEquals(expectedAuthority, fileProvider?.authority)
        assertFalse("FileProvider must not be exported", fileProvider?.exported ?: true)
        assertTrue("FileProvider must grant URI permissions", fileProvider?.grantUriPermissions ?: false)
    }

    @Test
    fun `createCaptureOutput resolves content FileProvider URI inside camera_captures cache dir`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val output = CameraCaptureHelper.createCaptureOutput(context)

        assertEquals("content", output.uri.scheme)
        assertEquals(CameraCaptureHelper.fileProviderAuthority(context), output.uri.authority)
        assertEquals(CameraCaptureHelper.CAPTURE_DIR_NAME, output.file.parentFile?.name)
        assertTrue(output.file.name.startsWith("Z-Effect_capture_"))
        assertTrue(output.file.name.endsWith(".jpg"))
    }

    @Test
    fun `cleanupStaleCaptures removes stale files while preserving active file and quiet delete removes cancelled file`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val dir = CameraCaptureHelper.captureDirectory(context)

        val staleA = File(dir, "Z-Effect_capture_stale_a.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val staleB = File(dir, "Z-Effect_capture_stale_b.jpg").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val active = File(dir, "Z-Effect_capture_active.jpg").apply { writeBytes(byteArrayOf(7, 8, 9)) }

        val deletedCount = CameraCaptureHelper.cleanupStaleCaptures(
            context = context,
            keepFilePath = active.absolutePath
        )

        assertTrue("Should delete at least the two stale capture files", deletedCount >= 2)
        assertFalse(staleA.exists())
        assertFalse(staleB.exists())
        assertTrue("Active capture file must be preserved", active.exists())

        CameraCaptureHelper.deleteCaptureFileQuietly(active)
        assertFalse("Cancelled/deleted file must be removed", active.exists())
    }

    @Test
    fun `permission denial transitions and app settings intent target package settings`() {
        assertEquals(
            CameraPermissionUiState.RATIONALE_NEEDED,
            CameraCaptureHelper.resolveCameraPermissionDenial(
                shouldShowRationale = true,
                hasRequestedBefore = true
            )
        )
        assertEquals(
            CameraPermissionUiState.PERMANENTLY_DENIED,
            CameraCaptureHelper.resolveCameraPermissionDenial(
                shouldShowRationale = false,
                hasRequestedBefore = true
            )
        )

        val context = ApplicationProvider.getApplicationContext<Application>()
        val settingsIntent = CameraCaptureHelper.buildAppSettingsIntent(context)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, settingsIntent.action)
        assertEquals("package:${context.packageName}", settingsIntent.dataString)
        assertTrue(
            "Non-activity context should add FLAG_ACTIVITY_NEW_TASK",
            (settingsIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0
        )
    }

    @Test
    fun `onCameraPhotoCaptured loads FileProvider JPEG with EXIF rotation and cleans stale captures`() = runTest(testDispatcher) {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val providerInfo = context.packageManager.resolveContentProvider(
            CameraCaptureHelper.fileProviderAuthority(context),
            PackageManager.GET_META_DATA
        )
        assertNotNull("FileProvider ProviderInfo should resolve from manifest", providerInfo)
        Robolectric.buildContentProvider(FileProvider::class.java).create(providerInfo)

        val dir = CameraCaptureHelper.captureDirectory(context)
        val stale = File(dir, "Z-Effect_capture_old.jpg").apply {
            writeBytes(byteArrayOf(1, 2, 3))
            setLastModified(System.currentTimeMillis() - CameraCaptureHelper.DEFAULT_STALE_MAX_AGE_MS - 60_000L)
        }

        val output = CameraCaptureHelper.createCaptureOutput(context)
        val bitmap = Bitmap.createBitmap(48, 24, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(220, 140, 60))
        }
        FileOutputStream(output.file).use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
        }
        bitmap.recycle()

        // Tag captured photo as 90-degree EXIF rotated (landscape 48x24 -> portrait 24x48)
        ExifInterface(output.file.absolutePath).apply {
            setAttribute(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_ROTATE_90.toString()
            )
            saveAttributes()
        }

        val viewModel = EditorViewModel(context)
        viewModel.onCameraPhotoCaptured(output.uri, output.file)

        // Wait briefly for IO coroutines to complete
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            advanceUntilIdle()
            val state = viewModel.uiState.value
            if (state.fullWidth == 24 && state.fullHeight == 48 && !stale.exists()) {
                break
            }
            Thread.sleep(25L)
        }
        advanceUntilIdle()

        val finalState = viewModel.uiState.value
        assertEquals(
            "EXIF 90-degree rotation should swap width to 24 (status=${finalState.statusBannerMessage})",
            24,
            finalState.fullWidth
        )
        assertEquals("EXIF 90-degree rotation should swap height to 48", 48, finalState.fullHeight)
        assertNotNull("Loaded capture must populate preview bitmap", finalState.previewIdleBitmap)
        assertFalse("Stale capture file must be cleaned up", stale.exists())
    }
}
