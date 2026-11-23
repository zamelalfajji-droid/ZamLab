package com.zamalfajji.zamlab

import com.zamalfajji.zamlab.editor.ExportFormat
import com.zamalfajji.zamlab.editor.ExportResolution
import com.zamalfajji.zamlab.editor.ImageIoHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DngAndFormatUnitTest {

    @Test
    fun `ExportFormat exposes genuine JPG, Lossless PNG, WebP, 16-bit TIFF, and PDF with 4096px cap`() {
        assertEquals(5, ExportFormat.entries.size)
        assertEquals("image/jpeg", ExportFormat.JPG.mimeType)
        assertEquals("image/png", ExportFormat.PNG.mimeType)
        assertEquals("image/webp", ExportFormat.WEBP.mimeType)
        assertEquals("image/tiff", ExportFormat.TIFF.mimeType)
        assertEquals("application/pdf", ExportFormat.PDF.mimeType)
        assertEquals("Downloads/ZamLab", ExportFormat.PDF.defaultFolderDescription)
        assertEquals("Pictures/ZamLab", ExportFormat.JPG.defaultFolderDescription)
        assertEquals("Pictures/ZamLab", ExportFormat.PNG.defaultFolderDescription)
        assertEquals("Pictures/ZamLab", ExportFormat.WEBP.defaultFolderDescription)
        assertEquals("Downloads/ZamLab", ExportFormat.TIFF.defaultFolderDescription)
        assertFalse(ExportFormat.JPG.isDownloadsDestination)
        assertFalse(ExportFormat.PNG.isDownloadsDestination)
        assertFalse(ExportFormat.WEBP.isDownloadsDestination)
        assertTrue(ExportFormat.TIFF.isDownloadsDestination)
        assertTrue(ExportFormat.PDF.isDownloadsDestination)
        assertEquals("Saved to Gallery", ExportFormat.JPG.savedSnackbarMessage)
        assertEquals("Saved to Gallery", ExportFormat.PNG.savedSnackbarMessage)
        assertEquals("Saved to Gallery", ExportFormat.WEBP.savedSnackbarMessage)
        assertEquals("Saved to Downloads", ExportFormat.TIFF.savedSnackbarMessage)
        assertEquals("Saved to Downloads", ExportFormat.PDF.savedSnackbarMessage)
        assertEquals("tiff", ExportFormat.TIFF.extension)
        assertTrue(ExportFormat.JPG.supportsQuality)
        assertTrue(ExportFormat.WEBP.supportsQuality)
        assertFalse(ExportFormat.PNG.supportsQuality)
        assertFalse(ExportFormat.TIFF.supportsQuality)
        assertFalse(ExportFormat.PDF.supportsQuality)

        // Full resolution preserves up to 4096px
        val (clampedW, clampedH) = ImageIoHelper.computeTargetDimensions(6000, 4000, ExportResolution.FULL)
        assertEquals(4096, clampedW)
        assertEquals(2731, clampedH)

        // Already <= 4096px is preserved 1:1
        val (nativeW, nativeH) = ImageIoHelper.computeTargetDimensions(3840, 2160, ExportResolution.FULL)
        assertEquals(3840, nativeW)
        assertEquals(2160, nativeH)
    }
}
