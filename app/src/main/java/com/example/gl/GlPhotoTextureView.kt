package com.example.gl

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.view.TextureView
import androidx.core.graphics.createBitmap
import com.example.editor.GeometryMath
import com.example.editor.GradingParams
import com.example.editor.ToneCurveData
import com.example.lut.CubeLut
import com.example.lut.LutSourceFormat
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "GlPhotoTextureView"

/**
 * Immutable snapshot of render inputs consumed by the OpenGL thread.
 * Equality compares bitmap identity (`===`) and value-equality on grading state so identical
 * frames are skipped in RENDERMODE_WHEN_DIRTY fashion with zero redundant GPU work.
 */
class GlRenderState(
    val imageBitmap: Bitmap?,
    val params: GradingParams,
    val lut: CubeLut?,
    val isOriginal: Boolean,
    val splitRatio: Float,
    val applyCrop: Boolean
) {
    fun matches(other: GlRenderState?): Boolean {
        if (other == null) return false
        return imageBitmap === other.imageBitmap &&
            params == other.params &&
            lut?.id == other.lut?.id &&
            isOriginal == other.isOriginal &&
            splitRatio == other.splitRatio &&
            applyCrop == other.applyCrop
    }
}

/**
 * Lock-free dirty-frame coalescer implementing `RENDERMODE_WHEN_DIRTY` semantics:
 * - Multiple rapid 120Hz slider updates arriving while a frame is in flight overwrite the
 *   pending state and schedule at most ONE render pass, eliminating frame queue buildup.
 * - Identical states are skipped with zero GPU wakeups when the editor is idle.
 */
class DirtyRenderGate {
    private val latestSubmitted = AtomicReference<GlRenderState?>(null)
    private val isDirty = AtomicBoolean(false)
    private val frameScheduled = AtomicBoolean(false)

    /**
     * Submits [state]. Returns `true` ONLY when a new render runnable should be posted to the
     * GL thread (state actually changed and no render pass is already queued).
     */
    fun submit(state: GlRenderState): Boolean {
        val prev = latestSubmitted.get()
        if (state.matches(prev)) {
            return false
        }
        latestSubmitted.set(state)
        isDirty.set(true)
        return frameScheduled.compareAndSet(false, true)
    }

    /**
     * Marks the surface dirty unconditionally (e.g., on surface creation or viewport resize).
     * Returns `true` when a render pass should be scheduled.
     */
    fun markSurfaceDirty(): Boolean {
        isDirty.set(true)
        return frameScheduled.compareAndSet(false, true)
    }

    /**
     * Consumes the pending dirty state on the GL thread. Returns `null` if no dirty frame
     * is pending.
     */
    fun consumeForRender(): GlRenderState? {
        frameScheduled.set(false)
        if (!isDirty.compareAndSet(true, false)) {
            return null
        }
        return latestSubmitted.get()
    }

    /**
     * Called right after swapping buffers: if a newer slider tick arrived while the GPU was
     * busy rendering, claims the next frame slot so the latest state is drawn immediately.
     */
    fun shouldRescheduleAfterDraw(): Boolean {
        return isDirty.get() && frameScheduled.compareAndSet(false, true)
    }

    fun currentState(): GlRenderState? = latestSubmitted.get()
}

/**
 * Hardware-accelerated OpenGL ES 2.0 TextureView controller optimized for 120Hz zero-lag editing:
 * - Single GPU-resident proxy texture (<=2048px) — zero texture re-uploads during slider scrubbing.
 * - Reuses textures, cached uniform/attribute locations (`ShaderUniformHandles`), a reusable
 *   256x1 tone-curve texture updated via `glTexSubImage2D`, and an offscreen FBO slot.
 * - True on-demand rendering (`RENDERMODE_WHEN_DIRTY`) via lock-free coalescing (`DirtyRenderGate`)
 *   and a single pre-allocated render runnable with zero per-frame heap allocations.
 */
class GlPhotoRenderController(
    private val textureView: TextureView
) : TextureView.SurfaceTextureListener {

    private var handlerThread: HandlerThread? = null
    private var glHandler: Handler? = null

    private var eglDisplay: EGLDisplay? = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext? = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface? = EGL14.EGL_NO_SURFACE
    private var eglConfig: EGLConfig? = null

    private var program = 0
    private var uniformHandles: GlGradingShaders.ShaderUniformHandles? = null
    private var imageTexId = 0
    private var lutTexId = 0
    private var curveTexId = 0
    private var reusableFboId = 0
    private var reusableFboTexId = 0
    private var quadBuffer: FloatBuffer? = null

    private var viewportW = 0
    private var viewportH = 0
    private var surfaceEpoch = 0
    private var lastRenderedEpoch = -1

    private var lastUploadedImage: Bitmap? = null
    private var lastUploadedImageW = 0
    private var lastUploadedImageH = 0
    private var lastLutId: String? = null
    private var lastToneCurve: ToneCurveData? = null
    private var currentPackedLut: GlGradingShaders.PackedLutTexture? = null
    private var lastRenderedState: GlRenderState? = null

    // Reusable 256x1 tone-curve upload buffer (zero Bitmap/IntArray allocation during curve drags)
    private var reusableCurveBitmap: Bitmap? = null
    private val reusableCurvePixels = IntArray(256)
    private var curveTextureAllocated = false

    private val dirtyGate = DirtyRenderGate()

    // Single pre-allocated runnable posted to GL thread (zero lambda allocation per slider tick)
    private val renderRunnable = Runnable { drainDirtyFrameOnGlThread() }

    @Volatile
    private var isSurfaceReady = false

    init {
        textureView.isOpaque = true
        textureView.surfaceTextureListener = this
    }

    fun updateState(
        imageBitmap: Bitmap?,
        params: GradingParams,
        lut: CubeLut?,
        isOriginal: Boolean,
        splitRatio: Float = -1f,
        applyCrop: Boolean = true
    ) {
        val nextState = GlRenderState(
            imageBitmap = imageBitmap,
            params = params,
            lut = lut,
            isOriginal = isOriginal,
            splitRatio = splitRatio,
            applyCrop = applyCrop
        )
        if (dirtyGate.submit(nextState)) {
            glHandler?.post(renderRunnable)
        }
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        val thread = HandlerThread("GlPhotoRenderThread", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
        handlerThread = thread
        val handler = Handler(thread.looper)
        glHandler = handler
        handler.post {
            initEgl(surface, width, height)
            surfaceEpoch++
            if (dirtyGate.markSurfaceDirty()) {
                drainDirtyFrameOnGlThread()
            } else {
                drainDirtyFrameOnGlThread()
            }
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        glHandler?.post {
            viewportW = width
            viewportH = height
            GLES20.glViewport(0, 0, width, height)
            surfaceEpoch++
            if (dirtyGate.markSurfaceDirty()) {
                drainDirtyFrameOnGlThread()
            } else {
                drainDirtyFrameOnGlThread()
            }
        }
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        release()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    fun release() {
        val handler = glHandler
        val thread = handlerThread
        glHandler = null
        handlerThread = null
        isSurfaceReady = false
        if (handler != null && thread != null) {
            handler.removeCallbacks(renderRunnable)
            handler.post {
                releaseGl()
                thread.quitSafely()
            }
        }
    }

    private fun initEgl(surface: SurfaceTexture, width: Int, height: Int) {
        try {
            viewportW = width
            viewportH = height

            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

            val attribList = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0)
            val config = configs[0] ?: throw IllegalStateException("No EGLConfig found")
            eglConfig = config

            val ctxAttribs = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
            )
            eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            eglSurface = EGL14.eglCreateWindowSurface(
                eglDisplay,
                config,
                surface,
                intArrayOf(EGL14.EGL_NONE),
                0
            )

            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
            // Keep swap interval at 1 for tear-free vsync on 120Hz panels
            EGL14.eglSwapInterval(eglDisplay, 1)

            program = GlGradingShaders.compileProgram()
            uniformHandles = GlGradingShaders.ShaderUniformHandles(program)
            quadBuffer = GlGradingShaders.createQuadBuffer(readbackFlipY = false)

            val textures = IntArray(4)
            GLES20.glGenTextures(4, textures, 0)
            imageTexId = textures[0]
            lutTexId = textures[1]
            curveTexId = textures[2]
            reusableFboTexId = textures[3]

            val fbos = IntArray(1)
            GLES20.glGenFramebuffers(1, fbos, 0)
            reusableFboId = fbos[0]

            // Pre-allocate reusable 256x1 tone-curve bitmap & GPU storage once
            val curveBmp = createBitmap(256, 1)
            reusableCurveBitmap = curveBmp
            GlGradingShaders.packToneCurveInto(ToneCurveData(), reusableCurvePixels, curveBmp)
            GlGradingShaders.uploadTexture(curveTexId, curveBmp)
            curveTextureAllocated = true

            // Bind constant sampler units once for the lifetime of the GL program
            val handles = uniformHandles
            if (handles != null) {
                GLES20.glUseProgram(program)
                GLES20.glUniform1i(handles.uImageTex, 0)
                GLES20.glUniform1i(handles.uLutTex, 1)
                GLES20.glUniform1i(handles.uCurveTex, 2)
            }

            GLES20.glViewport(0, 0, viewportW, viewportH)
            isSurfaceReady = true
        } catch (e: Exception) {
            Log.e(TAG, "EGL initialization failed", e)
            isSurfaceReady = false
        }
    }

    private fun drainDirtyFrameOnGlThread() {
        val state = dirtyGate.consumeForRender() ?: return
        if (!isSurfaceReady || eglDisplay == null || eglDisplay == EGL14.EGL_NO_DISPLAY ||
            eglSurface == null || eglSurface == EGL14.EGL_NO_SURFACE
        ) return
        val img = state.imageBitmap ?: return
        if (img.isRecycled) return
        val handles = uniformHandles ?: return
        val buf = quadBuffer ?: return

        // Skip redundant GPU draw if neither state nor surface epoch changed
        if (surfaceEpoch == lastRenderedEpoch && state.matches(lastRenderedState)) {
            return
        }

        // 1. Proxy image texture — uploaded once per bitmap, or updated in-place via texSubImage2D
        if (img !== lastUploadedImage) {
            if (lastUploadedImageW == img.width && lastUploadedImageH == img.height && lastUploadedImageW > 0) {
                GlGradingShaders.uploadTextureSubImage(imageTexId, img)
            } else {
                GlGradingShaders.uploadTexture(imageTexId, img)
                lastUploadedImageW = img.width
                lastUploadedImageH = img.height
            }
            lastUploadedImage = img
        }

        // 2. 3D/1D .cube LUT texture — uploaded only when selected LUT ID changes
        val effectiveLut = if (state.lut?.sourceFormat == LutSourceFormat.XMP) null else state.lut
        if (effectiveLut?.id != lastLutId) {
            lastLutId = effectiveLut?.id
            currentPackedLut?.bitmap?.recycle()
            currentPackedLut = effectiveLut?.let {
                val packed = GlGradingShaders.packLutToTiledBitmap(it)
                GlGradingShaders.uploadTexture(lutTexId, packed.bitmap)
                packed
            }
        }

        // 3. Tone Curve 256x1 LUT — zero-allocation in-place texSubImage2D update
        val curve = state.params.toneCurve
        if (curve != lastToneCurve) {
            lastToneCurve = curve
            if (!curve.isDefault) {
                val curveBmp = reusableCurveBitmap
                if (curveBmp != null && !curveBmp.isRecycled) {
                    GlGradingShaders.packToneCurveInto(curve, reusableCurvePixels, curveBmp)
                    if (curveTextureAllocated) {
                        GlGradingShaders.uploadTextureSubImage(curveTexId, curveBmp)
                    } else {
                        GlGradingShaders.uploadTexture(curveTexId, curveBmp)
                        curveTextureAllocated = true
                    }
                }
            }
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glClearColor(0.05f, 0.06f, 0.075f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)

        buf.position(0)
        GLES20.glEnableVertexAttribArray(handles.aPosition)
        GLES20.glVertexAttribPointer(handles.aPosition, 2, GLES20.GL_FLOAT, false, 16, buf)

        buf.position(2)
        GLES20.glEnableVertexAttribArray(handles.aTexCoord)
        GLES20.glVertexAttribPointer(handles.aTexCoord, 2, GLES20.GL_FLOAT, false, 16, buf)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imageTexId)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTexId)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, curveTexId)

        val (outW, outH) = if (state.applyCrop) {
            GeometryMath.computeOutputDimensions(img.width, img.height, state.params.crop)
        } else {
            GeometryMath.orientedDimensions(img.width, img.height, state.params.crop)
        }

        GlGradingShaders.bindUniforms(
            handles = handles,
            params = state.params,
            lut = effectiveLut,
            packedLut = currentPackedLut,
            imageWidth = img.width,
            imageHeight = img.height,
            isOriginal = state.isOriginal,
            splitRatio = state.splitRatio,
            applyCrop = state.applyCrop,
            outputWidth = outW,
            outputHeight = outH
        )

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(handles.aPosition)
        GLES20.glDisableVertexAttribArray(handles.aTexCoord)

        EGL14.eglSwapBuffers(eglDisplay, eglSurface)

        lastRenderedState = state
        lastRenderedEpoch = surfaceEpoch

        // If a newer slider tick arrived mid-render, immediately drain the latest dirty frame
        if (dirtyGate.shouldRescheduleAfterDraw()) {
            glHandler?.post(renderRunnable)
        }
    }

    private fun releaseGl() {
        runCatching {
            currentPackedLut?.bitmap?.recycle()
            currentPackedLut = null
            reusableCurveBitmap?.let { if (!it.isRecycled) it.recycle() }
            reusableCurveBitmap = null
            curveTextureAllocated = false

            if (reusableFboId != 0) {
                GLES20.glDeleteFramebuffers(1, intArrayOf(reusableFboId), 0)
            }
            if (imageTexId != 0 || lutTexId != 0 || curveTexId != 0 || reusableFboTexId != 0) {
                GLES20.glDeleteTextures(4, intArrayOf(imageTexId, lutTexId, curveTexId, reusableFboTexId), 0)
            }
            if (program != 0) {
                GLES20.glDeleteProgram(program)
            }
            val disp = eglDisplay
            if (disp != null && disp != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    disp,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT
                )
                val surf = eglSurface
                if (surf != null && surf != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(disp, surf)
                }
                val ctx = eglContext
                if (ctx != null && ctx != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(disp, ctx)
                }
                EGL14.eglTerminate(disp)
            }
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
        program = 0
        uniformHandles = null
        imageTexId = 0
        lutTexId = 0
        curveTexId = 0
        reusableFboId = 0
        reusableFboTexId = 0
        lastUploadedImage = null
        lastUploadedImageW = 0
        lastUploadedImageH = 0
        lastLutId = null
        lastToneCurve = null
        lastRenderedState = null
        lastRenderedEpoch = -1
    }
}
