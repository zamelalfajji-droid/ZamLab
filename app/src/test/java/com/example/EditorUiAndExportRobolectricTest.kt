package com.example

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.graphics.createBitmap
import androidx.test.core.app.ApplicationProvider
import com.example.editor.EditorViewModel
import com.example.editor.ExportFormat
import com.example.editor.ExportOptions
import com.example.editor.ExportResolution
import com.example.editor.ExportStatus
import com.example.editor.StudioTab
import com.example.ui.EditorScreen
import com.example.ui.theme.ZamLabTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EditorUiAndExportRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun awaitInitialLoad(vm: EditorViewModel) {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            testDispatcher.scheduler.advanceUntilIdle()
            val state = vm.uiState.value
            if (state.fullWidth > 0 && state.lutFolders.isNotEmpty()) {
                return
            }
            Thread.sleep(20)
        }
        throw AssertionError("Timed out waiting for initial editor load")
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun `ZamLab branding and all 6 bottom navigation tabs render cleanly on narrow phone width without clipping`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = EditorViewModel(app)
        awaitInitialLoad(vm)

        composeTestRule.setContent {
            ZamLabTheme {
                EditorScreen(viewModel = vm)
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("zamlab_brand_emblem").assertIsDisplayed()
        composeTestRule.onNodeWithTag("open_photo_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("top_share_button").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("export_button").performScrollTo().assertIsDisplayed()

        val tabExpectations = listOf(
            "tab_adjust" to StudioTab.ADJUST,
            "tab_all" to StudioTab.ALL,
            "tab_luts" to StudioTab.LUTS,
            "tab_crop" to StudioTab.CROP,
            "tab_heal" to StudioTab.HEAL,
            "tab_mask" to StudioTab.MASK
        )
        tabExpectations.forEach { (tag, expectedTab) ->
            val node = composeTestRule.onNodeWithTag(tag)
            node.performScrollTo().assertIsDisplayed()
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue(
                "Tab $tag should have touch-compliant height >= 48px (was ${bounds.height})",
                bounds.height >= 48f
            )
            assertTrue(
                "Tab $tag should have touch-compliant width >= 48px (was ${bounds.width})",
                bounds.width >= 48f
            )
            node.performClick()
            composeTestRule.waitForIdle()
            assertEquals(expectedTab, vm.uiState.value.activeTab)
        }
    }

    @Test
    @Config(qualifiers = "w840dp-h720dp")
    fun `ZamLab 6 bottom navigation tabs fit cleanly inside 360dp side panel on wide tablet layout`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = EditorViewModel(app)
        awaitInitialLoad(vm)

        composeTestRule.setContent {
            ZamLabTheme {
                EditorScreen(viewModel = vm)
            }
        }
        composeTestRule.waitForIdle()

        listOf("tab_adjust", "tab_all", "tab_luts", "tab_crop", "tab_heal", "tab_mask").forEach { tag ->
            composeTestRule.onNodeWithTag(tag).assertIsDisplayed()
        }
        composeTestRule.onNodeWithTag("tab_crop").performClick()
        composeTestRule.waitForIdle()
        assertEquals(StudioTab.CROP, vm.uiState.value.activeTab)
    }

    @Test
    fun `load photo, edit, undo, redo, and export image end to end with zero exceptions`() = runTest(testDispatcher) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = EditorViewModel(app)
        awaitInitialLoad(vm)

        val inputFile = File(app.cacheDir, "zamlab_e2e_input.png")
        val exportFile = File(app.cacheDir, "zamlab_e2e_output.jpg")
        try {
            val srcBmp = createBitmap(160, 120).apply {
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        setPixel(x, y, Color.rgb(60 + x, 90 + (y / 2), 140))
                    }
                }
            }
            inputFile.outputStream().use { srcBmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            srcBmp.recycle()

            val startToken = vm.uiState.value.viewportResetToken
            vm.openPhotoFromUri(Uri.fromFile(inputFile))
            val loadDeadline = System.currentTimeMillis() + 5_000L
            while (System.currentTimeMillis() < loadDeadline && vm.uiState.value.viewportResetToken <= startToken) {
                testDispatcher.scheduler.advanceUntilIdle()
                Thread.sleep(20)
            }
            assertEquals(160, vm.uiState.value.fullWidth)
            assertEquals(120, vm.uiState.value.fullHeight)

            // Apply grading edits, undo, then redo
            vm.setSliderDragging(true)
            vm.updateGradingParams { it.copy(exposure = 0.5f, contrast = 20f, vibrance = 25f) }
            vm.setSliderDragging(false)
            assertTrue(vm.uiState.value.canUndo)

            vm.undo()
            assertEquals(0f, vm.uiState.value.gradingParams.exposure, 0.001f)
            vm.redo()
            assertEquals(0.5f, vm.uiState.value.gradingParams.exposure, 0.001f)

            // Export to document URI cleanly
            vm.exportToDocumentUri(
                Uri.fromFile(exportFile),
                ExportOptions(format = ExportFormat.JPG, quality = 92, resolution = ExportResolution.FULL)
            )
            val exportDeadline = System.currentTimeMillis() + 8_000L
            while (System.currentTimeMillis() < exportDeadline &&
                vm.uiState.value.exportStatus !is ExportStatus.Success
            ) {
                testDispatcher.scheduler.advanceUntilIdle()
                if (vm.uiState.value.exportStatus is ExportStatus.Error) {
                    throw AssertionError("Export failed: ${(vm.uiState.value.exportStatus as ExportStatus.Error).message}")
                }
                Thread.sleep(20)
            }
            assertTrue("Export should succeed", vm.uiState.value.exportStatus is ExportStatus.Success)
            assertTrue("Exported file should exist and be non-empty", exportFile.exists() && exportFile.length() > 0L)

            val decoded = BitmapFactory.decodeFile(exportFile.absolutePath)
            assertNotNull("Exported JPG should decode cleanly", decoded)
            assertEquals(160, decoded.width)
            assertEquals(120, decoded.height)
            decoded.recycle()
        } finally {
            inputFile.delete()
            exportFile.delete()
        }
    }
}
