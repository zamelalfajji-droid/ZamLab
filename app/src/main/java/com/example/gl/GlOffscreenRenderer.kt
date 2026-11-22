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
import kotlin.math.max

private const val TAG = "GlOffscreenRenderer"

/**
 * Renders the full-resolution photograph exclusively on export using an offscreen
 * OpenGL ES 2.0 EGL Pbuffer surface, with automatic CPU fallback if the image exceeds
 * the device's GL_MAX_TEXTURE_SIZE.
 */
object GlOffscreenRenderer {

    suspend fun renderFullResolution(
        source: Bitmap,
        params: GradingParams,
        lut: CubeLut?
    ): Bitmap = withContext(Dispatchers.Default) {
        val effectiveLut = if (lut != null && lut.sourceFormat != LutSourceFormat.XMP) lut else null
        val applyLut = effectiveLut != null && params.lutIntensity > 0.001f
        if (params.isDefault && !applyLut) {
            return@withContext source.copy(Bitmap.Config.ARGB_8888, false)
        }

        val glBitmap = runCatching {
            renderViaEglPbuffer(source, params, lut)
        }.onFailure { e ->
            Log.w(TAG, "Offscreen GL export fallback to CPU engine: ${e.message}")
        }.getOrNull()

        glBitmap ?: ColorGradingEngine.processFullResolution(source, params, lut)
    }

    private fun renderViaEglPbuffer(
        source: Bitmap,
        params: GradingParams,
        lut: CubeLut?
    ): Bitmap {
        if (source.isRecycled) {
            throw IllegalStateException("Source bitmap is recycled")
        }
        val srcW = source.width
        val srcH = source.height
        if (srcW <= 0 || srcH <= 0) {
            throw IllegalArgumentException("Invalid source dimensions ${srcW}x$srcH")
        }
        val (w, h) = GeometryMath.computeOutputDimensions(srcW, srcH, params.crop)
        if (w <= 0 || h <= 0) {
            throw IllegalArgumentException("Invalid output dimensions ${w}x$h")
        }
        var display: EGLDisplay? = EGL14.EGL_NO_DISPLAY
        var context: EGLContext? = EGL14.EGL_NO_CONTEXT
        var surface: EGLSurface? = EGL14.EGL_NO_SURFACE
        var program = 0
        val textures = IntArray(3)
        var packedLut: GlGradingShaders.PackedLutTexture? = null
        var curveBmp: Bitmap? = null
        val effectiveLut = if (lut != null && lut.sourceFormat != LutSourceFormat.XMP) lut else null

        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == null || display == EGL14.EGL_NO_DISPLAY) {
                throw IllegalStateException("eglGetDisplay returned EGL_NO_DISPLAY")
            }
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                throw IllegalStateException("eglInitialize failed")
            }

            val configAttribs = intArrayOf(
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0)) {
                throw IllegalStateException("eglChooseConfig failed")
            }
            val config = configs[0] ?: throw IllegalStateException("No Pbuffer EGLConfig")

            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            if (context == null || context == EGL14.EGL_NO_CONTEXT) {
                throw IllegalStateException("eglCreateContext failed")
            }

            val pbufferAttribs = intArrayOf(
                EGL14.EGL_WIDTH, w,
                EGL14.EGL_HEIGHT, h,
                EGL14.EGL_NONE
            )
            surface = EGL14.eglCreatePbufferSurface(display, config, pbufferAttribs, 0)
            if (surface == null || surface == EGL14.EGL_NO_SURFACE) {
                throw IllegalStateException("eglCreatePbufferSurface failed")
            }

            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
                throw IllegalStateException("eglMakeCurrent failed")
            }

            val maxTexSize = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTexSize, 0)
            if (maxTexSize[0] <= 0 || max(max(srcW, srcH), max(w, h)) > maxTexSize[0]) {
                throw IllegalStateException("Bitmap ${srcW}x${srcH} -> ${w}x$h exceeds GL_MAX_TEXTURE_SIZE=${maxTexSize[0]}")
            }

            program = GlGradingShaders.compileProgram()
            val handles = GlGradingShaders.ShaderUniformHandles(program)
            val quadBuffer = GlGradingShaders.createQuadBuffer(readbackFlipY = true)

            GLES20.glGenTextures(3, textures, 0)
            GlGradingShaders.uploadTexture(textures[0], source)

            if (effectiveLut != null) {
                packedLut = GlGradingShaders.packLutToTiledBitmap(effectiveLut)
                GlGradingShaders.uploadTexture(textures[1], packedLut.bitmap)
            }
            if (!params.toneCurve.isDefault) {
                curveBmp = GlGradingShaders.packToneCurveBitmap(params.toneCurve)
                GlGradingShaders.uploadTexture(textures[2], curveBmp)
            }

            GLES20.glViewport(0, 0, w, h)
            GLES20.glUseProgram(program)

            quadBuffer.position(0)
            GLES20.glEnableVertexAttribArray(handles.aPosition)
            GLES20.glVertexAttribPointer(handles.aPosition, 2, GLES20.GL_FLOAT, false, 16, quadBuffer)

            quadBuffer.position(2)
            GLES20.glEnableVertexAttribArray(handles.aTexCoord)
            GLES20.glVertexAttribPointer(handles.aTexCoord, 2, GLES20.GL_FLOAT, false, 16, quadBuffer)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
            GLES20.glUniform1i(handles.uImageTex, 0)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[1])
            GLES20.glUniform1i(handles.uLutTex, 1)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[2])
            GLES20.glUniform1i(handles.uCurveTex, 2)

            GlGradingShaders.bindUniforms(
                handles = handles,
                params = params,
                lut = effectiveLut,
                packedLut = packedLut,
                imageWidth = srcW,
                imageHeight = srcH,
                isOriginal = false,
                applyCrop = true,
                outputWidth = w,
                outputHeight = h
            )

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(handles.aPosition)
            GLES20.glDisableVertexAttribArray(handles.aTexCoord)
            GLES20.glFinish()

            val byteCount = w.toLong() * h.toLong() * 4L
            if (byteCount <= 0L || byteCount > Int.MAX_VALUE) {
                throw IllegalStateException("Output readback buffer too large for ${w}x$h")
            }
            val pixelBuffer = ByteBuffer.allocateDirect(byteCount.toInt()).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixelBuffer)
            pixelBuffer.rewind()

            val out = createBitmap(w, h)
            out.copyPixelsFromBuffer(pixelBuffer)
            return out
        } finally {
            runCatching {
                packedLut?.bitmap?.recycle()
                curveBmp?.recycle()
                if (textures[0] != 0 || textures[1] != 0 || textures[2] != 0) {
                    GLES20.glDeleteTextures(3, textures, 0)
                }
                if (program != 0) GLES20.glDeleteProgram(program)
                if (display != null && display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(
                        display,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT
                    )
                    if (surface != null && surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                    if (context != null && context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                    EGL14.eglTerminate(display)
                }
            }
        }
    }
}
