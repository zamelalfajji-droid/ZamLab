package com.example.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AiCreateModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun clampInferenceSteps_enforcesValidRange10To25() {
        assertEquals(10, SdModelManager.clampInferenceSteps(1))
        assertEquals(10, SdModelManager.clampInferenceSteps(10))
        assertEquals(18, SdModelManager.clampInferenceSteps(18))
        assertEquals(25, SdModelManager.clampInferenceSteps(25))
        assertEquals(25, SdModelManager.clampInferenceSteps(50))
    }

    @Test
    fun computeCenterCropRect_handles1x1_4x5_and_16x9() {
        val sq = SdModelManager.computeCenterCropRect(512, 512, AiAspectRatio.SQUARE)
        assertEquals(CropPixelRect(0, 0, 512, 512), sq)

        val portrait = SdModelManager.computeCenterCropRect(512, 512, AiAspectRatio.PORTRAIT_4_5)
        assertEquals(410, portrait.width)
        assertEquals(512, portrait.height)
        assertEquals(51, portrait.left)
        assertEquals(0, portrait.top)

        val landscape = SdModelManager.computeCenterCropRect(512, 512, AiAspectRatio.LANDSCAPE_16_9)
        assertEquals(512, landscape.width)
        assertEquals(288, landscape.height)
        assertEquals(0, landscape.left)
        assertEquals(112, landscape.top)
    }

    @Test
    fun normalizeModelDownloadUrl_convertsHuggingFaceAndGithubBlobUrls() {
        assertEquals(
            "https://huggingface.co/na5h13/stable-diffusion-v1-5-mediapipe/resolve/main/sd15-mediapipe.zip",
            SdModelManager.normalizeModelDownloadUrl(
                "https://huggingface.co/na5h13/stable-diffusion-v1-5-mediapipe/blob/main/sd15-mediapipe.zip"
            )
        )
        assertEquals(
            "https://huggingface.co/owner/repo/resolve/main/model.zip?download=true",
            SdModelManager.normalizeModelDownloadUrl(
                "  https://huggingface.co/owner/repo/blob/main/model.zip?download=true  "
            )
        )
        assertEquals(
            "https://github.com/owner/repo/raw/main/sd.zip",
            SdModelManager.normalizeModelDownloadUrl(
                "https://github.com/owner/repo/blob/main/sd.zip"
            )
        )
        val releaseUrl = "https://github.com/owner/repo/releases/download/v1.0/model.zip"
        assertEquals(releaseUrl, SdModelManager.normalizeModelDownloadUrl(releaseUrl))
    }

    @Test(expected = IllegalArgumentException::class)
    fun normalizeModelDownloadUrl_rejectsBlankOrUnsupportedScheme() {
        SdModelManager.normalizeModelDownloadUrl("ftp://huggingface.co/user/repo/model.zip")
    }

    @Test
    fun multiModelCatalog_persistsSwitchesAndDeletesFoldersCleanly() {
        val aiModelsRoot = tempFolder.newFolder("ai_models")

        val modelAFolder = File(aiModelsRoot, "sd15_mediapipe").apply { mkdirs() }
        File(modelAFolder, "unet.tflite").writeBytes(ByteArray(1200) { 1 })

        val modelBFolder = File(aiModelsRoot, "sd15_portrait").apply { mkdirs() }
        File(modelBFolder, "unet.bin").writeBytes(ByteArray(2400) { 2 })

        val initialCatalog = AiModelCatalogState(
            models = listOf(
                InstalledAiModel(
                    id = "sd15_mediapipe",
                    displayName = "Stable Diffusion v1.5",
                    sourceUrl = SdModelManager.MODEL_ZIP_URL,
                    sizeBytes = 1200L
                ),
                InstalledAiModel(
                    id = "sd15_portrait",
                    displayName = "SD 1.5 Portrait",
                    sourceUrl = "local://portrait.zip",
                    sizeBytes = 2400L
                )
            ),
            activeModelId = "sd15_mediapipe"
        )

        SdModelManager.saveCatalogToDir(aiModelsRoot, initialCatalog)

        val reloaded = SdModelManager.loadCatalogFromDir(aiModelsRoot, minModelBytes = 500L)
        assertEquals(2, reloaded.models.size)
        assertEquals("sd15_mediapipe", reloaded.activeModel?.id)
        assertEquals("SD 1.5 Portrait", reloaded.models[1].displayName)
        assertEquals("local://portrait.zip", reloaded.models[1].sourceUrl)
        assertEquals(2400L, reloaded.models[1].sizeBytes)

        val switched = SdModelManager.setActiveModelInDir(aiModelsRoot, "sd15_portrait", minModelBytes = 500L)
        assertEquals("sd15_portrait", switched.activeModel?.id)

        val afterDelete = SdModelManager.deleteModelFromDir(aiModelsRoot, "sd15_portrait", minModelBytes = 500L)
        assertFalse(modelBFolder.exists())
        assertEquals(1, afterDelete.models.size)
        assertEquals("sd15_mediapipe", afterDelete.activeModel?.id)

        val afterDeleteLast = SdModelManager.deleteModelFromDir(aiModelsRoot, "sd15_mediapipe", minModelBytes = 500L)
        assertFalse(modelAFolder.exists())
        assertTrue(afterDeleteLast.models.isEmpty())
        assertNull(afterDeleteLast.activeModel)
    }

    @Test
    fun unzipSafelyAndUnwrap_flattensNestedFoldersAndFindsModelRoot() {
        val zipFile = tempFolder.newFile("nested_sd.zip")
        val destDir = tempFolder.newFolder("ai_model_nested")

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            zos.putNextEntry(ZipEntry("__MACOSX/._junk"))
            zos.write(byteArrayOf(1, 2, 3))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("sd15-mediapipe/bins/text_encoder.tflite"))
            zos.write(ByteArray(256) { 7 })
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("sd15-mediapipe/bins/diffusion_model.bin"))
            zos.write(ByteArray(512) { 9 })
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("sd15-mediapipe/bins/vocab.txt"))
            zos.write("hello\nworld\n".toByteArray())
            zos.closeEntry()
        }

        SdModelManager.unzipSafely(zipFile, destDir)
        SdModelManager.unwrapSingleChildDirectories(destDir)

        assertTrue(File(destDir, "text_encoder.tflite").exists())
        assertTrue(File(destDir, "diffusion_model.bin").exists())
        assertTrue(File(destDir, "vocab.txt").exists())
        assertFalse(File(destDir, "__MACOSX").exists())

        val resolved = SdModelManager.resolveModelDirectory(destDir, minModelBytes = 500L)
        assertNotNull(resolved)
        assertEquals(destDir.canonicalPath, resolved!!.canonicalPath)
    }

    @Test(expected = SecurityException::class)
    fun unzipSafely_rejectsZipSlipTraversal() {
        val zipFile = tempFolder.newFile("evil.zip")
        val destDir = tempFolder.newFolder("ai_model_safe")

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            zos.putNextEntry(ZipEntry("../../outside.tflite"))
            zos.write(byteArrayOf(1, 2, 3))
            zos.closeEntry()
        }

        SdModelManager.unzipSafely(zipFile, destDir)
    }

    @Test
    fun resolveModelDirectory_returnsNullWhenEmptyOrIncomplete() {
        val emptyDir = tempFolder.newFolder("empty_ai_model")
        assertNull(SdModelManager.resolveModelDirectory(emptyDir))

        File(emptyDir, "tiny.tflite").writeBytes(ByteArray(128))
        assertNull(SdModelManager.resolveModelDirectory(emptyDir, minModelBytes = 10_000L))
    }
}
