package com.zamalfajji.zamlab

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.core.graphics.createBitmap
import androidx.test.core.app.ApplicationProvider
import com.zamalfajji.zamlab.editor.EditorViewModel
import com.zamalfajji.zamlab.editor.ExportFormat
import com.zamalfajji.zamlab.editor.ExportOptions
import com.zamalfajji.zamlab.editor.ExportResolution
import com.zamalfajji.zamlab.editor.ExportStatus
import com.zamalfajji.zamlab.editor.StudioTab
import com.zamalfajji.zamlab.ui.EditorScreen
import com.zamalfajji.zamlab.ui.theme.ZamLabTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `ZamLab branding and all 8 horizontally scrollable bottom navigation tabs render cleanly on narrow phone width without clipping`() {
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

        // Right edge fade indicator signals horizontal scrollability across all 8 tabs
        composeTestRule.onNodeWithTag("dock_scroll_fade_end").assertIsDisplayed()

        val tabExpectations = listOf(
            "tab_adjust" to StudioTab.ADJUST,
            "tab_studio" to StudioTab.STUDIO,
            "tab_upscale" to StudioTab.UPSCALE,
            "tab_denoise" to StudioTab.DENOISE,
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
    @Config(qualifiers = "w412dp-h915dp")
    fun `clean 4-zone vertical layout prevents collisions between PhotoViewport, Active Tool & Mask Brush bars, and 8-tab dock`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = EditorViewModel(app)
        awaitInitialLoad(vm)

        composeTestRule.setContent {
            ZamLabTheme {
                EditorScreen(viewModel = vm)
            }
        }
        composeTestRule.waitForIdle()

        // 1. Default state: both side drawers collapsed off-screen, edge handles visible, active tool dock sits strictly above 8-tab bar
        assertFalse(vm.uiState.value.isLayerDrawerExpanded)
        assertFalse(vm.uiState.value.isToolDrawerExpanded)
        assertFalse(vm.uiState.value.showHistogram)
        composeTestRule.onNodeWithTag("left_layers_edge_handle").assertIsDisplayed()
        composeTestRule.onNodeWithTag("right_tools_edge_handle").assertIsDisplayed()
        composeTestRule.onNodeWithTag("oled_layer_drawer").assertDoesNotExist()
        composeTestRule.onNodeWithTag("right_tool_drawer").assertDoesNotExist()
        composeTestRule.onNodeWithTag("active_tool_dock_zone").assertIsDisplayed()
        composeTestRule.onNodeWithTag("floating_capsule_nav").assertIsDisplayed()

        // Duplicate Heal & Masks shortcuts remain absent from AdjustPanel inside the dedicated tool dock
        composeTestRule.onNodeWithTag("adjust_open_heal_tool").assertDoesNotExist()
        composeTestRule.onNodeWithTag("adjust_open_mask_tool").assertDoesNotExist()

        // Strict vertical non-overlap verification: PhotoViewport -> Active Tool Dock Zone -> Bottom 8-Tab Bar
        val rootHeight = composeTestRule.onRoot().fetchSemanticsNode().boundsInRoot.height
        val canvasBounds = composeTestRule.onNodeWithTag("photo_canvas").fetchSemanticsNode().boundsInRoot
        val toolDockBounds = composeTestRule.onNodeWithTag("active_tool_dock_zone").fetchSemanticsNode().boundsInRoot
        val navDockBounds = composeTestRule.onNodeWithTag("floating_capsule_nav").fetchSemanticsNode().boundsInRoot

        assertTrue(
            "PhotoViewport must sit strictly above Active Tool Dock Zone (canvas.bottom=${canvasBounds.bottom}, dock.top=${toolDockBounds.top})",
            canvasBounds.bottom <= toolDockBounds.top + 2f
        )
        assertTrue(
            "Active Tool Dock Zone must sit strictly above 8-tab navigation dock (dock.bottom=${toolDockBounds.bottom}, nav.top=${navDockBounds.top})",
            toolDockBounds.bottom <= navDockBounds.top + 2f
        )
        val canvasRatio = canvasBounds.height / rootHeight.coerceAtLeast(1f)
        assertTrue(
            "Photo canvas should retain spacious unobstructed height >= 55% (was ${(canvasRatio * 100f).toInt()}%)",
            canvasRatio >= 0.55f
        )

        // 2. Tapping the left edge handle ("LAYERS ▸") slides the translucent Layers drawer in from the left;
        // tapping outside on the canvas slides it back off-screen to the left
        composeTestRule.onNodeWithTag("left_layers_edge_handle").performClick()
        testDispatcher.scheduler.advanceUntilIdle()
        composeTestRule.waitForIdle()
        assertTrue(vm.uiState.value.isLayerDrawerExpanded)
        composeTestRule.onNodeWithTag("oled_layer_drawer").assertIsDisplayed()
        composeTestRule.onNodeWithTag("layer_drawer_outside_scrim").assertIsDisplayed()

        composeTestRule.onNodeWithTag("layer_drawer_outside_scrim").performClick()
        testDispatcher.scheduler.advanceUntilIdle()
        composeTestRule.waitForIdle()
        assertFalse(vm.uiState.value.isLayerDrawerExpanded)
        composeTestRule.onNodeWithTag("oled_layer_drawer").assertDoesNotExist()
        composeTestRule.onNodeWithTag("left_layers_edge_handle").assertIsDisplayed()

        // 3. Tapping the right edge handle ("TOOLS ◂") slides the translucent Inspector drawer in from the right;
        // tapping outside slides it back off-screen to the right
        composeTestRule.onNodeWithTag("right_tools_edge_handle").performClick()
        testDispatcher.scheduler.advanceUntilIdle()
        composeTestRule.waitForIdle()
        assertTrue(vm.uiState.value.isToolDrawerExpanded)
        composeTestRule.onNodeWithTag("right_tool_drawer").assertIsDisplayed()
        composeTestRule.onNodeWithTag("tool_drawer_outside_scrim").assertIsDisplayed()

        composeTestRule.onNodeWithTag("tool_drawer_outside_scrim").performClick()
        testDispatcher.scheduler.advanceUntilIdle()
        composeTestRule.waitForIdle()
        assertFalse(vm.uiState.value.isToolDrawerExpanded)
        composeTestRule.onNodeWithTag("right_tool_drawer").assertDoesNotExist()

        // 4. Mask Brush bar docks cleanly ABOVE the 8-tab navigation dock (zero collision, 100% touchable)
        vm.toggleLayerMaskBrushMode()
        testDispatcher.scheduler.advanceUntilIdle()
        composeTestRule.waitForIdle()
        assertTrue(vm.uiState.value.isLayerMaskBrushMode)
        composeTestRule.onNodeWithTag("layer_mask_brush_bar").assertIsDisplayed()

        val maskBarBounds = composeTestRule.onNodeWithTag("layer_mask_brush_bar").fetchSemanticsNode().boundsInRoot
        val navBoundsDuringMask = composeTestRule.onNodeWithTag("floating_capsule_nav").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Mask Brush bar must sit strictly above 8-tab navigation dock (maskBar.bottom=${maskBarBounds.bottom}, nav.top=${navBoundsDuringMask.top})",
            maskBarBounds.bottom <= navBoundsDuringMask.top + 2f
        )
        composeTestRule.onNodeWithTag("mask_brush_done_button").assertIsDisplayed().performClick()
        testDispatcher.scheduler.advanceUntilIdle()
        composeTestRule.waitForIdle()
        assertFalse(vm.uiState.value.isLayerMaskBrushMode)

        // 5. Top bar compact Histogram icon toggles Histogram cleanly on/off
        composeTestRule.onNodeWithTag("top_histogram_button").assertIsDisplayed().performClick()
        testDispatcher.scheduler.advanceUntilIdle()
        composeTestRule.waitForIdle()
        assertTrue(vm.uiState.value.showHistogram)

        composeTestRule.onNodeWithTag("top_histogram_button").performClick()
        testDispatcher.scheduler.advanceUntilIdle()
        composeTestRule.waitForIdle()
        assertFalse(vm.uiState.value.showHistogram)

        // 6. Tapping HEAL and MASKS in the 8-tab bottom capsule dock opens their panels cleanly in the dedicated bottom tool zone
        composeTestRule.onNodeWithTag("tab_heal").performScrollTo().performClick()
        testDispatcher.scheduler.advanceUntilIdle()
        composeTestRule.waitForIdle()
        assertEquals(StudioTab.HEAL, vm.uiState.value.activeTab)
        composeTestRule.onNodeWithTag("active_tool_dock_zone").assertIsDisplayed()
        composeTestRule.onNodeWithTag("left_layers_edge_handle").assertIsDisplayed()

        composeTestRule.onNodeWithTag("tab_mask").performScrollTo().performClick()
        testDispatcher.scheduler.advanceUntilIdle()
        composeTestRule.waitForIdle()
        assertEquals(StudioTab.MASK, vm.uiState.value.activeTab)
        composeTestRule.onNodeWithTag("active_tool_dock_zone").assertIsDisplayed()
        composeTestRule.onNodeWithTag("left_layers_edge_handle").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w840dp-h720dp")
    fun `ZamLab all 8 bottom navigation tabs render active translucent tool panels cleanly above dock on wide tablet layout`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = EditorViewModel(app)
        awaitInitialLoad(vm)

        composeTestRule.setContent {
            ZamLabTheme {
                EditorScreen(viewModel = vm)
            }
        }
        composeTestRule.waitForIdle()

        listOf("tab_adjust", "tab_studio", "tab_upscale", "tab_denoise", "tab_luts", "tab_crop", "tab_heal", "tab_mask").forEach { tag ->
            composeTestRule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
        }

        composeTestRule.onNodeWithTag("tab_upscale").performScrollTo().performClick()
        testDispatcher.scheduler.advanceUntilIdle()
        composeTestRule.waitForIdle()
        assertEquals(StudioTab.UPSCALE, vm.uiState.value.activeTab)
        composeTestRule.onNodeWithTag("active_tool_dock_zone").assertIsDisplayed()
        composeTestRule.onNodeWithTag("upscale_2x_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("upscale_4x_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("upscale_apply_button").assertIsDisplayed()

        composeTestRule.onNodeWithTag("tab_denoise").performScrollTo().performClick()
        testDispatcher.scheduler.advanceUntilIdle()
        composeTestRule.waitForIdle()
        assertEquals(StudioTab.DENOISE, vm.uiState.value.activeTab)
        composeTestRule.onNodeWithTag("denoise_preset_off").assertIsDisplayed()
        composeTestRule.onNodeWithTag("denoise_preset_natural").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("denoise_compare_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("denoise_slider_luminance").assertIsDisplayed()
        composeTestRule.onNodeWithTag("denoise_slider_color").performScrollTo().assertIsDisplayed()

        composeTestRule.onNodeWithTag("tab_crop").performScrollTo().performClick()
        testDispatcher.scheduler.advanceUntilIdle()
        composeTestRule.waitForIdle()
        assertEquals(StudioTab.CROP, vm.uiState.value.activeTab)
        composeTestRule.onNodeWithTag("active_tool_dock_zone").assertIsDisplayed()
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
