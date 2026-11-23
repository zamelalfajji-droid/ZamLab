package com.example.gl

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.util.Log
import androidx.core.graphics.createBitmap
import com.example.editor.ColorGradingEngine
import com.example.editor.GeometryMath
import com.example.editor.GradingParams
import com.example.lut.CubeLut
import com.example.lut.LutSourceFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "GlOffscreenRenderer"

/**
 * Full-resolution offscreen OpenGL ES 2.0 2-pass renderer using an EGL Pbuffer + Pass-1 FBO.
 * Runs the exact same Pass-1 (Steps 1–5 tone & color) + Pass-2 (Step 6 spatial detail + finishing)
 * GPU shaders as the live preview canvas so exports match the live viewport 1:1.
 * Automatically falls back to [ColorGradingEngine.processFullResolution] if EGL/GLES is
 * unavailable or the photo exceeds the GPU's `GL_MAX_TEXTURE_SIZE`.
 */
object GlOffscreenRenderer {

    suspend fun renderFullResolution(
        source: Bitmap,
        params: GradingParams,
        lut: CubeLut?
    ): Bitmap = withContext(Dispatchers.Default) {
        if (source.isRecycled || source.width <= 0 || source.height <= 0) {
            return@withContext createBitmap(1, 1)
        }
        val (outW, outH) = GeometryMath.computeOutputDimensions(source.width, source.height, params.crop)
        val effectiveLut = if (lut?.sourceFormat == LutSourceFormat.XMP) null else lut

        val gpuBitmap = try {
            renderOnPbuffer(source, outW, outH, params, effectiveLut)
        } catch (t: Throwable) {
            Log.w(TAG, "Offscreen GL export fell back to CPU engine: ${t.message}")
            null
        }

        if (gpuBitmap != null) {
            return@withContext gpuBitmap
        }
        ColorGradingEngine.processFullResolution(source, params, effectiveLut)
    }

    private fun renderOnPbuffer(
        source: Bitmap,
        outW: Int,
        outH: Int,
        params: GradingParams,
        effectiveLut: CubeLut?
    ): Bitmap? {
        val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return null
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return null

        var context: EGLContext = EGL14.EGL_NO_CONTEXT
        var surface: EGLSurface = EGL14.EGL_NO_SURFACE
        var toneProgram = 0
        var spatialProgram = 0
        val textures = IntArray(4)
        val fbos = IntArray(1)
        var packedLut: GlGradingShaders.PackedLutTexture? = null
        var curveBmp: Bitmap? = null

        try {
            val attribList = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attribList, 0, configs, 0, 1, numConfigs, 0)) {
                return null
            }
            val config = configs[0] ?: return null

            context = EGL14.eglCreateContext(
                display,
                config,
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                0
            )
            if (context == EGL14.EGL_NO_CONTEXT) return null

            surface = EGL14.eglCreatePbufferSurface(
                display,
                config,
                intArrayOf(
                    EGL14.EGL_WIDTH, outW,
                    EGL14.EGL_HEIGHT, outH,
                    EGL14.EGL_NONE
                ),
                0
            )
            if (surface == EGL14.EGL_NO_SURFACE) return null

            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return null

            val maxTex = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTex, 0)
            val maxSize = maxTex[0]
            if (maxSize > 0 && (source.width > maxSize || source.height > maxSize || outW > maxSize || outH > maxSize)) {
                Log.w(TAG, "Image exceeds GL_MAX_TEXTURE_SIZE=$maxSize; using CPU fallback")
                return null
            }

            toneProgram = GlGradingShaders.compileToneColorProgram()
            spatialProgram = GlGradingShaders.compileSpatialFinishProgram()
            val tHandles = GlGradingShaders.ToneColorUniformHandles(toneProgram)
            val sHandles = GlGradingShaders.SpatialFinishUniformHandles(spatialProgram)
            val quadBuffer = GlGradingShaders.createQuadBuffer(readbackFlipY = true)

            GLES20.glGenTextures(4, textures, 0)
            GlGradingShaders.uploadTexture(textures[0], source)

            if (effectiveLut != null) {
                packedLut = GlGradingShaders.packLutToTiledBitmap(effectiveLut)
                GlGradingShaders.uploadTexture(textures[1], packedLut.bitmap)
            } else {
                val dummyLut = createBitmap(1, 1)
                GlGradingShaders.uploadTexture(textures[1], dummyLut)
                dummyLut.recycle()
            }
            if (!params.toneCurve.isDefault) {
                curveBmp = GlGradingShaders.packToneCurveBitmap(params.toneCurve)
                GlGradingShaders.uploadTexture(textures[2], curveBmp)
            } else {
                val dummyCurve = createBitmap(1, 1)
                GlGradingShaders.uploadTexture(textures[2], dummyCurve)
                dummyCurve.recycle()
            }

            // Allocate Pass-1 offscreen FBO texture (outW x outH)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[3])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                GLES20.GL_RGBA,
                outW,
                outH,
                0,
                GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE,
                null
            )

            GLES20.glGenFramebuffers(1, fbos, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbos[0])
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER,
                GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D,
                textures[3],
                0
            )
            if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                return null
            }

            // PASS 1: Tone & Color (Steps 1–5) -> FBO (textures[3])
            GLES20.glViewport(0, 0, outW, outH)
            GLES20.glUseProgram(toneProgram)
            GLES20.glUniform1i(tHandles.uImageTex, 0)
            GLES20.glUniform1i(tHandles.uCurveTex, 1)

            quadBuffer.position(0)
            GLES20.glEnableVertexAttribArray(tHandles.aPosition)
            GLES20.glVertexAttribPointer(tHandles.aPosition, 2, GLES20.GL_FLOAT, false, 16, quadBuffer)
            quadBuffer.position(2)
            GLES20.glEnableVertexAttribArray(tHandles.aTexCoord)
            GLES20.glVertexAttribPointer(tHandles.aTexCoord, 2, GLES20.GL_FLOAT, false, 16, quadBuffer)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[2])

            GlGradingShaders.bindToneColorUniforms(
                handles = tHandles,
                params = params,
                imageWidth = source.width,
                imageHeight = source.height,
                applyCrop = true,
                outputWidth = outW,
                outputHeight = outH
            )
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(tHandles.aPosition)
            GLES20.glDisableVertexAttribArray(tHandles.aTexCoord)

            // PASS 2: Spatial Detail (Step 6) + Finishing -> Pbuffer (default FBO 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, outW, outH)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            GLES20.glUseProgram(spatialProgram)
            GLES20.glUniform1i(sHandles.uGradedTex, 0)
            GLES20.glUniform1i(sHandles.uLutTex, 1)
            GLES20.glUniform1i(sHandles.uRawTex, 2)

            quadBuffer.position(0)
            GLES20.glEnableVertexAttribArray(sHandles.aPosition)
            GLES20.glVertexAttribPointer(sHandles.aPosition, 2, GLES20.GL_FLOAT, false, 16, quadBuffer)
            quadBuffer.position(2)
            GLES20.glEnableVertexAttribArray(sHandles.aTexCoord)
            GLES20.glVertexAttribPointer(sHandles.aTexCoord, 2, GLES20.GL_FLOAT, false, 16, quadBuffer)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[3])
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[1])
            GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])

            GlGradingShaders.bindSpatialFinishUniforms(
                handles = sHandles,
                params = params,
                lut = effectiveLut,
                packedLut = packedLut,
                fboWidth = outW,
                fboHeight = outH,
                imageWidth = source.width,
                imageHeight = source.height,
                isOriginal = false,
                splitRatio = -1f,
                applyCrop = true
            )
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(sHandles.aPosition)
            GLES20.glDisableVertexAttribArray(sHandles.aTexCoord)
            GLES20.glFinish()

            val pixelBytes = ByteBuffer.allocateDirect(outW * outH * 4).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, outW, outH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixelBytes)
            pixelBytes.rewind()

            val outBitmap = createBitmap(outW, outH)
            outBitmap.copyPixelsFromBuffer(pixelBytes)
            return outBitmap
        } finally {
            try {
                if (toneProgram != 0) GLES20.glDeleteProgram(toneProgram)
                if (spatialProgram != 0) GLES20.glDeleteProgram(spatialProgram)
                if (textures.any { it != 0 }) GLES20.glDeleteTextures(textures.size, textures, 0)
                if (fbos[0] != 0) GLES20.glDeleteFramebuffers(1, fbos, 0)
                packedLut?.bitmap?.let { if (!it.isRecycled) it.recycle() }
                curveBmp?.let { if (!it.isRecycled) it.recycle() }
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            } catch (_: Throwable) {
            }
        }
    }
}
