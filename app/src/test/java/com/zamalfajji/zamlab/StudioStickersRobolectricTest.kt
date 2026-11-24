package com.zamalfajji.zamlab

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import com.zamalfajji.zamlab.ai.RawSubjectSegmentation
import com.zamalfajji.zamlab.editor.EditorViewModel
import com.zamalfajji.zamlab.editor.LayerBlendMode
import com.zamalfajji.zamlab.editor.LayerCompositor
import com.zamalfajji.zamlab.editor.StickerCategory
import com.zamalfajji.zamlab.editor.StickerHttpFetcher
import com.zamalfajji.zamlab.editor.StickerLibrary
import com.zamalfajji.zamlab.editor.StickerSearchRepository
import com.zamalfajji.zamlab.editor.StudioTab
import java.io.File
import java.io.IOException
import com.zamalfajji.zamlab.ui.EditorScreen
import com.zamalfajji.zamlab.ui.theme.ZamLabTheme
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
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
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StudioStickersRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val testDispatcher = StandardTestDispatcher()
    private val trackedViewModels = mutableListOf<EditorViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val app = ApplicationProvider.getApplicationContext<Application>()
        StickerSearchRepository.resetCachesForTesting()
        File(app.cacheDir, "stickers").deleteRecursively()
        StickerSearchRepository.httpFetcher = StickerHttpFetcher { url, _ ->
            throw IOException("Hermetic offline test: $url")
        }
    }

    @After
    fun tearDown() {
        trackedViewModels.forEach { it.viewModelScope.cancel() }
        trackedViewModels.clear()
        testDispatcher.scheduler.advanceUntilIdle()
        StickerSearchRepository.resetCachesForTesting()
        Dispatchers.resetMain()
    }

    private fun awaitCondition(timeoutMs: Long = 10_000L, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            testDispatcher.scheduler.advanceUntilIdle()
            if (predicate()) return
            Thread.sleep(20)
        }
        throw AssertionError("Timed out waiting for condition")
    }

    private fun awaitInitialLoad(vm: EditorViewModel) {
        trackedViewModels.add(vm)
        awaitCondition {
            vm.uiState.value.fullWidth > 0 && vm.uiState.value.layers.isNotEmpty()
        }
    }

    @Test
    fun `StickerLibrary provides 150+ offline vector stickers across 10 categories with crisp transparency`() {
        val stickers = StickerLibrary.allBuiltInStickers
        assertTrue("Expected >= 150 built-in stickers, got ${stickers.size}", stickers.size >= 150)
        assertEquals(154, stickers.size)

        val builtInCategories = listOf(
            StickerCategory.SHAPES,
            StickerCategory.ARROWS,
            StickerCategory.LIGHT_FLARES,
            StickerCategory.BADGES,
            StickerCategory.FRAMES,
            StickerCategory.SYMBOLS,
            StickerCategory.NEON_GLOW,
            StickerCategory.CYBER_HUD,
            StickerCategory.SOCIAL_CREATOR,
            StickerCategory.VINTAGE_STAMPS
        )
        assertEquals(10, builtInCategories.size)
        builtInCategories.forEach { category ->
            val catStickers = StickerLibrary.stickersForCategory(category)
            assertTrue(
                "Category $category should contain >= 15 stickers, got ${catStickers.size}",
                catStickers.size >= 15
            )
            catStickers.forEach { sticker ->
                val bmp = StickerLibrary.renderBuiltInSticker(sticker, sizePx = 80)
                assertEquals(80, bmp.width)
                assertEquals(80, bmp.height)
                var opaqueCount = 0
                var transparentCount = 0
                val pixels = IntArray(bmp.width * bmp.height)
                bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                for (px in pixels) {
                    val a = (px ushr 24) and 0xFF
                    if (a >= 90) opaqueCount++
                    if (a <= 15) transparentCount++
                }
                assertTrue("Sticker ${sticker.id} must have visible opaque pixels", opaqueCount > 15)
                assertTrue("Sticker ${sticker.id} must preserve transparent background", transparentCount > 15)
                if (category == StickerCategory.LIGHT_FLARES) {
                    assertEquals(LayerBlendMode.SCREEN, sticker.defaultBlendMode)
                }
                bmp.recycle()
            }
        }
    }

    private fun createSolidTransparentPngBytes(
        width: Int = 64,
        height: Int = 64,
        color: Int = Color.argb(255, 56, 189, 248)
    ): ByteArray {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.TRANSPARENT)
        val marginX = width / 4
        val marginY = height / 4
        for (y in marginY until height - marginY) {
            for (x in marginX until width - marginX) {
                bmp.setPixel(x, y, color)
            }
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }

    @Test
    fun `StickerLibrary imports ZIP sticker packs, skips __MACOSX, downscales oversized PNGs, caps at 100, and guides RAR users`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        StickerLibrary.loadSavedCutouts(app).forEach {
            StickerLibrary.deleteSavedCutout(app, it.id)
        }

        val smallPng = createSolidTransparentPngBytes(64, 64)
        val oversizedPng = createSolidTransparentPngBytes(1400, 1000)

        // 1. Build an in-memory ZIP with valid PNGs, oversized PNG, __MACOSX hidden entry, and a non-image .txt entry
        val zipOut = ByteArrayOutputStream()
        ZipOutputStream(zipOut).use { zos ->
            zos.putNextEntry(ZipEntry("pack/neon_star.png"))
            zos.write(smallPng)
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("pack/oversized_banner.png"))
            zos.write(oversizedPng)
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("__MACOSX/pack/._neon_star.png"))
            zos.write(smallPng)
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("pack/readme.txt"))
            zos.write("ignored".toByteArray())
            zos.closeEntry()
        }

        val outcome = StickerLibrary.importZipStream(
            context = app,
            inputStream = ByteArrayInputStream(zipOut.toByteArray())
        )
        assertEquals(2, outcome.importedStickers.size)
        assertFalse(outcome.hitStickerCap)
        assertTrue(outcome.importedStickers.any { it.name == "Neon star" })
        assertTrue(outcome.importedStickers.any { it.name == "Oversized banner" })

        // Verify oversized entry was downscaled to <= 1024px
        val oversizedSaved = outcome.importedStickers.first { it.name == "Oversized banner" }
        val decodedOversized = StickerLibrary.decodeCutoutBitmap(oversizedSaved.file, maxDimension = 2048)
        assertNotNull(decodedOversized)
        assertTrue(
            "Expected downscaled width <= 1024, got ${decodedOversized!!.width}",
            decodedOversized.width <= StickerLibrary.MAX_IMPORT_DIMENSION_PX
        )
        assertTrue(
            "Expected downscaled height <= 1024, got ${decodedOversized.height}",
            decodedOversized.height <= StickerLibrary.MAX_IMPORT_DIMENSION_PX
        )
        decodedOversized.recycle()

        // 2. Verify max-sticker cap enforcement (using small test cap of 3 on a 5-entry ZIP)
        val cappedZip = ByteArrayOutputStream()
        ZipOutputStream(cappedZip).use { zos ->
            for (i in 1..5) {
                zos.putNextEntry(ZipEntry("sticker_$i.png"))
                zos.write(smallPng)
                zos.closeEntry()
            }
        }
        val cappedOutcome = StickerLibrary.importZipStream(
            context = app,
            inputStream = ByteArrayInputStream(cappedZip.toByteArray()),
            maxStickers = 3
        )
        assertEquals(3, cappedOutcome.importedStickers.size)
        assertTrue(cappedOutcome.hitStickerCap)

        // 3. Verify RAR archive detection returns the exact user-friendly guidance message
        val rarFile = File(app.cacheDir, "cool_pack.rar")
        FileOutputStream(rarFile).use { fos ->
            fos.write(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00)) // Rar! magic bytes
        }
        assertTrue(StickerLibrary.hasRarMagicHeader(byteArrayOf(0x52, 0x61, 0x72, 0x21)))
        val rarResult = StickerLibrary.importStickerPackFromUris(app, listOf(Uri.fromFile(rarFile)))
        assertTrue(rarResult.importedStickers.isEmpty())
        assertTrue(rarResult.isError)
        assertEquals(StickerLibrary.RAR_UNSUPPORTED_MESSAGE, rarResult.message)
        rarFile.delete()

        // 4. Verify importing a real .zip file URI via importStickerPackFromUris reports imported count
        val zipFile = File(app.cacheDir, "test_stickers.zip")
        FileOutputStream(zipFile).use { it.write(zipOut.toByteArray()) }
        val zipResult = StickerLibrary.importStickerPackFromUris(app, listOf(Uri.fromFile(zipFile)))
        assertEquals(2, zipResult.importedStickers.size)
        assertFalse(zipResult.isError)
        assertEquals("Imported 2 stickers into My Cutouts", zipResult.message)
        zipFile.delete()

        // Cleanup saved cutouts
        StickerLibrary.loadSavedCutouts(app).forEach {
            StickerLibrary.deleteSavedCutout(app, it.id)
        }
    }

    @Test
    fun `StickerLibrary saves trims loads and deletes My Cutouts in local app storage`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        // Clean any leftover test files
        StickerLibrary.loadSavedCutouts(app).forEach {
            StickerLibrary.deleteSavedCutout(app, it.id)
        }

        // 120x120 bitmap with a 40x40 opaque subject centered inside transparent margins
        val cutout = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
        cutout.eraseColor(Color.TRANSPARENT)
        for (y in 40 until 80) {
            for (x in 40 until 80) {
                cutout.setPixel(x, y, Color.argb(255, 245, 166, 35))
            }
        }

        val saved = StickerLibrary.saveCutoutBitmap(app, cutout, "Golden Badge")
        assertTrue(saved.file.exists())
        assertEquals("Golden Badge", saved.name)

        val loaded = StickerLibrary.loadSavedCutouts(app)
        assertTrue(loaded.any { it.id == saved.id && it.name == "Golden Badge" })

        val decoded = StickerLibrary.decodeCutoutBitmap(saved.file)
        assertNotNull(decoded)
        // Trimmed to 40 + 2*8 padding = 56x56 (< original 120x120)
        assertTrue("Expected trimmed width < 100, got ${decoded!!.width}", decoded.width < 100)
        assertTrue("Expected trimmed height < 100, got ${decoded.height}", decoded.height < 100)
        decoded.recycle()
        cutout.recycle()

        val deleted = StickerLibrary.deleteSavedCutout(app, saved.id)
        assertTrue(deleted)
        assertFalse(saved.file.exists())
        assertTrue(StickerLibrary.loadSavedCutouts(app).none { it.id == saved.id })
    }

    @Test
    fun `adding built-in vector stickers creates moveable scalable rotatable layers with Undo and composite export`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = EditorViewModel(app)
        awaitInitialLoad(vm)
        val initialCount = vm.uiState.value.layers.size

        val flare = StickerLibrary.stickersForCategory(StickerCategory.LIGHT_FLARES).first()
        vm.addBuiltInStickerLayer(flare)
        awaitCondition { vm.uiState.value.layers.size == initialCount + 1 }

        val added = vm.uiState.value.activeLayer!!
        assertEquals(flare.name, added.name)
        assertEquals(LayerBlendMode.SCREEN, added.blendMode)
        assertFalse(added.isBackground)
        assertTrue(vm.uiState.value.isActiveLayerTransformable)

        // Move, scale, and rotate the sticker layer cleanly
        val startScale = added.scale
        vm.transformActiveLayer(
            deltaOffsetX = 0.14f,
            deltaOffsetY = -0.09f,
            scaleFactor = 0.80f,
            deltaRotationDegrees = 22f
        )
        val transformed = vm.uiState.value.activeLayer!!
        assertEquals(0.14f, transformed.offsetX, 0.001f)
        assertEquals(-0.09f, transformed.offsetY, 0.001f)
        assertEquals(startScale * 0.80f, transformed.scale, 0.002f)
        assertEquals(22f, transformed.rotation, 0.001f)

        // Composite full stack with no exceptions
        val base = vm.uiState.value.layers.first().bitmap
        val composited = runBlocking {
            LayerCompositor.compositeForExport(
                context = app,
                layers = vm.uiState.value.layers,
                outWidth = base.width,
                outHeight = base.height,
                preRenderedBackground = base,
                preferGpuOffscreen = false
            )
        }
        assertEquals(base.width, composited.width)
        assertEquals(base.height, composited.height)
        composited.recycle()

        // Undo removes the sticker cleanly
        assertTrue(vm.uiState.value.canUndo)
        vm.undo()
        awaitCondition { vm.uiState.value.layers.size == initialCount }
    }

    @Test
    fun `Sticker from Photo runs SmartCutProcessor saves to My Cutouts and drops cutout layer onto canvas`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        runBlocking {
            StickerLibrary.loadSavedCutouts(app).forEach {
                StickerLibrary.deleteSavedCutout(app, it.id)
            }
        }
        val vm = EditorViewModel(app)
        awaitInitialLoad(vm)
        val initialLayerCount = vm.uiState.value.layers.size

        // Synthetic photo: solid blue background with bright warm orange centered subject
        val photo = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
        for (y in 0 until 120) {
            for (x in 0 until 120) {
                val inside = x in 35..85 && y in 30..90
                photo.setPixel(x, y, if (inside) Color.rgb(245, 166, 35) else Color.rgb(20, 35, 70))
            }
        }

        vm.createStickerFromBitmap(
            sourceBitmap = photo,
            name = "Portrait Cut",
            customMaskProvider = { bmp ->
                val conf = FloatArray(bmp.width * bmp.height) { idx ->
                    val x = idx % bmp.width
                    val y = idx / bmp.width
                    if (x in 35..85 && y in 30..90) 1f else 0f
                }
                RawSubjectSegmentation(
                    confidenceMask = conf,
                    foregroundBitmap = null,
                    width = bmp.width,
                    height = bmp.height
                )
            }
        )

        awaitCondition {
            !vm.uiState.value.isCreatingCutoutSticker &&
                vm.uiState.value.layers.size == initialLayerCount + 1 &&
                vm.uiState.value.savedCutouts.isNotEmpty()
        }

        val state = vm.uiState.value
        val savedCutout = state.savedCutouts.first()
        assertEquals("Portrait Cut", savedCutout.name)
        assertTrue(savedCutout.file.exists())

        val droppedLayer = state.activeLayer!!
        assertEquals("Portrait Cut", droppedLayer.name)
        assertFalse(droppedLayer.isBackground)

        // Dropping the saved cutout again from "My Cutouts" creates another independent layer
        vm.addSavedCutoutStickerLayer(savedCutout)
        awaitCondition { vm.uiState.value.layers.size == initialLayerCount + 2 }

        // Deleting from "My Cutouts" removes it from local storage and UI state
        vm.deleteSavedCutoutSticker(savedCutout.id)
        awaitCondition { vm.uiState.value.savedCutouts.none { it.id == savedCutout.id } }
        photo.recycle()
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp")
    fun `STUDIO tab displays Stickers panel with My Cutouts and Adjust tab retains Tone Curve and HSL controls`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = EditorViewModel(app)
        awaitInitialLoad(vm)

        composeTestRule.setContent {
            ZamLabTheme {
                EditorScreen(viewModel = vm)
            }
        }
        composeTestRule.waitForIdle()

        // 1. ADJUST tab renders directly in the dedicated Active Tool Dock Zone above the 8-tab bar
        assertEquals(StudioTab.ADJUST, vm.uiState.value.activeTab)
        composeTestRule.onNodeWithTag("active_tool_dock_zone").assertIsDisplayed()
        composeTestRule.onNodeWithTag("adjust_tool_strip")
            .performScrollToNode(hasTestTag("subpanel_curve"))
        composeTestRule.onNodeWithTag("subpanel_curve").assertIsDisplayed()
        composeTestRule.onNodeWithTag("adjust_tool_strip")
            .performScrollToNode(hasTestTag("tool_hsl_mixer"))
        composeTestRule.onNodeWithTag("tool_hsl_mixer").assertIsDisplayed()

        // 2. Tap STUDIO tab in bottom dock and verify Stickers UI + categories + "+ Sticker from Photo" & "+ Import ZIP / Pack"
        composeTestRule.onNodeWithTag("tab_studio").performClick()
        testDispatcher.scheduler.advanceUntilIdle()
        composeTestRule.waitForIdle()
        assertEquals(StudioTab.STUDIO, vm.uiState.value.activeTab)

        composeTestRule.onNodeWithTag("studio_stickers_panel").assertIsDisplayed()
        composeTestRule.onNodeWithTag("create_sticker_from_photo_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("import_sticker_pack_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("sticker_category_trending").assertIsDisplayed()
        composeTestRule.onNodeWithTag("trending_local_fallback_grid").assertIsDisplayed()
        composeTestRule.onNodeWithTag("trending_offline_banner").assertDoesNotExist()
        composeTestRule.onNodeWithTag("sticker_category_my_cutouts").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("sticker_category_shapes").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("sticker_category_light_flares").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("sticker_category_frames").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("sticker_category_neon_glow").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("sticker_category_cyber_hud").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("sticker_category_social_creator").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("sticker_category_vintage_stamps").performScrollTo().assertIsDisplayed()

        // 3. Switching to Shapes and tapping a built-in vector sticker drops a new layer onto the canvas
        composeTestRule.onNodeWithTag("sticker_category_shapes").performScrollTo().performClick()
        testDispatcher.scheduler.advanceUntilIdle()
        composeTestRule.waitForIdle()
        val layersBefore = vm.uiState.value.layers.size
        composeTestRule.onNodeWithTag("sticker_item_shape_gold_ring").performClick()
        awaitCondition { vm.uiState.value.layers.size == layersBefore + 1 }
        assertEquals("Gold Ring", vm.uiState.value.activeLayer?.name)

        // 4. Opening the right-edge Inspector drawer and tapping the Photo Info HUD icon displays the open-source sticker & emoji attribution footer
        composeTestRule.onNodeWithTag("right_tools_edge_handle").performClick()
        testDispatcher.scheduler.advanceUntilIdle()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("photo_info_toggle_button").performClick()
        testDispatcher.scheduler.advanceUntilIdle()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("photo_info_hud_pill").assertIsDisplayed()
        composeTestRule.onNodeWithTag("photo_info_hud_credits")
            .assertIsDisplayed()
            .assertTextContains("OpenMoji (CC BY-SA 4.0)", substring = true)
            .assertTextContains("Google Noto Emoji (Apache 2.0)", substring = true)
            .assertTextContains("Twemoji (CC BY 4.0)", substring = true)
            .assertTextContains("Wikimedia Commons", substring = true)
    }
}
