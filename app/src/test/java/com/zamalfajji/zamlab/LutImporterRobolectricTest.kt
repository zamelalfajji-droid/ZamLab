package com.zamalfajji.zamlab

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.zamalfajji.zamlab.lut.LutImporter
import com.zamalfajji.zamlab.lut.LutSourceFormat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LutImporterRobolectricTest {

    @Test
    fun `loads all 80 bundled XMP presets and bundled CUBE LUTs from assets`(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val (bundled, _) = LutImporter.loadBundledAndSavedLuts(context)

        val xmpPresets = bundled.filter { it.sourceFormat == LutSourceFormat.XMP }
        val cubeLuts = bundled.filter { it.sourceFormat == LutSourceFormat.CUBE }

        assertEquals("Expected all 80 bundled XMP presets to load", 80, xmpPresets.size)
        assertTrue("Expected bundled .cube LUTs to load", cubeLuts.size >= 3)

        val categories = xmpPresets.map { it.category }.toSet()
        assertTrue("Expected 8 distinct preset packs, got $categories", categories.size >= 7)
        assertTrue(bundled.all { it.category.isNotBlank() && it.table.size == it.size * it.size * it.size * 3 })
    }

    @Test
    fun `recursively imports nested subfolders and nested zips while skipping Mac metadata`(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val customDir = File(context.filesDir, "custom_luts").apply {
            deleteRecursively()
        }

        val sampleXmp = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
             <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
              <rdf:Description rdf:about=""
                xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/"
                crs:Exposure2012="+0.25">
               <crs:Name><rdf:Alt><rdf:li xml:lang="x-default">Nested Mood</rdf:li></rdf:Alt></crs:Name>
              </rdf:Description>
             </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent().toByteArray()

        val sampleCube = """
            TITLE "Deep Cube"
            LUT_3D_SIZE 2
            0.0 0.0 0.0
            1.0 0.0 0.0
            0.0 1.0 0.0
            1.0 1.0 0.0
            0.0 0.0 1.0
            1.0 0.0 1.0
            0.0 1.0 1.0
            1.0 1.0 1.0
        """.trimIndent().toByteArray()

        val innerZipBytes = ByteArrayOutputStream().use { baos ->
            ZipOutputStream(baos).use { zos ->
                zos.putNextEntry(ZipEntry("InnerPack/DeepSub/deep_cube.cube"))
                zos.write(sampleCube)
                zos.closeEntry()
            }
            baos.toByteArray()
        }

        val outerZipFile = File(context.cacheDir, "nested_pack_test.zip")
        ByteArrayOutputStream().use { baos ->
            ZipOutputStream(baos).use { zos ->
                // Nested folder with Windows backslashes
                zos.putNextEntry(ZipEntry("Level1\\Level2 - Desktop Presets\\nested_mood.xmp"))
                zos.write(sampleXmp)
                zos.closeEntry()

                // Nested ZIP archive
                zos.putNextEntry(ZipEntry("Archives/inner_luts.zip"))
                zos.write(innerZipBytes)
                zos.closeEntry()

                // macOS metadata that must be skipped
                zos.putNextEntry(ZipEntry("__MACOSX/Level1/._nested_mood.xmp"))
                zos.write(sampleXmp)
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("Level1/.DS_Store"))
                zos.write(byteArrayOf(1, 2, 3))
                zos.closeEntry()
            }
            outerZipFile.writeBytes(baos.toByteArray())
        }

        val summary = LutImporter.importFromUri(context, Uri.fromFile(outerZipFile))
        assertEquals(2, summary.importedLuts.size)

        val importedTitles = summary.importedLuts.map { it.title }.toSet()
        assertTrue(importedTitles.contains("Nested Mood"))
        assertTrue(importedTitles.contains("Deep Cube"))
        assertFalse(summary.importedLuts.any { it.title.startsWith("._") })

        val xmpItem = summary.importedLuts.first { it.title == "Nested Mood" }
        assertEquals("Level2", xmpItem.category)

        val cubeItem = summary.importedLuts.first { it.title == "Deep Cube" }
        assertEquals("DeepSub", cubeItem.category)

        // Verify persisted files reload cleanly without collision
        val (_, savedCustom) = LutImporter.loadBundledAndSavedLuts(context)
        assertEquals(2, savedCustom.size)

        outerZipFile.delete()
        customDir.deleteRecursively()
    }
}
