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
import android.os.Looper
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
    val applyCrop: Boolean,
    val showHighlightClipping: Boolean = false,
    val showShadowClipping: Boolean = false
) {
    fun matches(other: GlRenderState?): Boolean {
        if (other == null) return false
        return imageBitmap === other.imageBitmap &&
            params == other.params &&
            lut?.id == other.lut?.id &&
            isOriginal == other.isOriginal &&
            splitRatio == other.splitRatio &&
            applyCrop == other.applyCrop &&
            showHighlightClipping == other.showHighlightClipping &&
            showShadowClipping == other.showShadowClipping
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
     * Releases the schedule latch without clearing [isDirty] when the GL handler is not yet
     * attached or could not accept the runnable, preventing pre-surface deadlocks.
     */
    fun releaseScheduledLatch() {
        frameScheduled.set(false)
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
 * Hardware-accelerated 2-pass OpenGL ES 2.0 TextureView controller optimized for 120Hz zero-lag editing:
 * - Pass 1 renders Steps 1–5 (sRGB->Linear, Exposure, Linear WB, Smooth Tone Curves/Shoulder/Contrast,
 *   Linear->sRGB, Curves, HSL, Vibrance/Saturation, Split/Wheels, Local Masks) into an offscreen FBO.
 * - Pass 2 renders Step 6 (real multi-pixel spatial Dehaze, Texture, Clarity, Sharpen, NR) and
 *   finishing (Vignette, Grain, 3D LUT, Halation/Bloom, Anamorphic Streak, Before/After Compare)
 *   sampling the actual Pass-1 graded FBO texture.
 * - Never deadlocks if [updateState] arrives before `onSurfaceTextureAvailable` or if the
 *   `TextureView` surface is already available upon attach.
 * - Notifies [onRenderStatusChanged] so the viewport can seamlessly activate a live CPU fallback
 *   if EGL/GLES initialization fails on an unsupported device.
 */
class GlPhotoRenderController(
    private val textureView: TextureView,
    private val onRenderStatusChanged: (Boolean) -> Unit = {}
) : TextureView.SurfaceTextureListener {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var handlerThread: HandlerThread? = null
    @Volatile
    private var glHandler: Handler? = null

    private var eglDisplay: EGLDisplay? = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext? = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface? = EGL14.EGL_NO_SURFACE
    private var eglConfig: EGLConfig? = null

    private var toneColorProgram = 0
    private var spatialFinishProgram = 0
    private var toneHandles: GlGradingShaders.ToneColorUniformHandles? = null
    private var spatialHandles: GlGradingShaders.SpatialFinishUniformHandles? = null

    private var imageTexId = 0
    private var lutTexId = 0
    private var curveTexId = 0
    private var pass1FboId = 0
    private var pass1TexId = 0
    private var fboWidth = 0
    private var fboHeight = 0

    private var fboQuadBuffer: FloatBuffer? = null
    private var screenQuadBuffer: FloatBuffer? = null

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

    // Reusable 256x2 opaque tone-curve upload buffer (zero allocation during curve drags)
    private var reusableCurveBitmap: Bitmap? = null
    private val reusableCurvePixels = IntArray(512)
    private var curveTextureAllocated = false

    private val dirtyGate = DirtyRenderGate()
    private val renderRunnable = Runnable { drainDirtyFrameOnGlThread() }

    @Volatile
    private var isSurfaceReady = false
    @Volatile
    private var released = false

    init {
        textureView.isOpaque = true
        textureView.surfaceTextureListener = this
        // Handle the case where TextureView already has an active SurfaceTexture on attach
        if (textureView.isAvailable) {
            textureView.surfaceTexture?.let { existingSurface ->
                onSurfaceTextureAvailable(
                    existingSurface,
                    textureView.width.coerceAtLeast(1),
                    textureView.height.coerceAtLeast(1)
                )
            }
        }
    }

    fun updateState(
        imageBitmap: Bitmap?,
        params: GradingParams,
        lut: CubeLut?,
        isOriginal: Boolean,
        splitRatio: Float = -1f,
        applyCrop: Boolean = true,
        showHighlightClipping: Boolean = false,
        showShadowClipping: Boolean = false
    ) {
        if (released) return
        val nextState = GlRenderState(
            imageBitmap = imageBitmap,
            params = params,
            lut = lut,
            isOriginal = isOriginal,
            splitRatio = splitRatio,
            applyCrop = applyCrop,
            showHighlightClipping = showHighlightClipping,
            showShadowClipping = showShadowClipping
        )
        if (dirtyGate.submit(nextState)) {
            val handler = glHandler
            if (handler == null || !handler.post(renderRunnable)) {
                // Release latch while keeping isDirty=true so onSurfaceTextureAvailable or
                // the next update immediately schedules the pending frame.
                dirtyGate.releaseScheduledLatch()
            }
        }
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (released) return
        if (handlerThread != null) return
        val thread = HandlerThread("GlPhotoRenderThread", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
        handlerThread = thread
        val handler = Handler(thread.looper)
        glHandler = handler
        handler.post {
            val ok = initEgl(surface, width, height)
            notifyRenderStatus(ok)
            if (ok) {
                surfaceEpoch++
                dirtyGate.markSurfaceDirty()
                drainDirtyFrameOnGlThread()
            }
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        glHandler?.post {
            viewportW = width.coerceAtLeast(1)
            viewportH = height.coerceAtLeast(1)
            if (isSurfaceReady) {
                ensurePass1Fbo(viewportW, viewportH)
                GLES20.glViewport(0, 0, viewportW, viewportH)
                surfaceEpoch++
                dirtyGate.markSurfaceDirty()
                drainDirtyFrameOnGlThread()
            }
        }
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        // Tear down the EGL surface & thread without permanently locking `released = true`,
        // so returning from Photo Picker / Share Sheet / app resume cleanly recreates EGL.
        teardownSurface()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    private fun teardownSurface() {
        val handler = glHandler
        val thread = handlerThread
        glHandler = null
        handlerThread = null
        isSurfaceReady = false
        dirtyGate.releaseScheduledLatch()
        if (handler != null && thread != null) {
            handler.removeCallbacks(renderRunnable)
            handler.post {
                releaseGl()
                thread.quitSafely()
            }
        }
    }

    fun release() {
        released = true
        teardownSurface()
    }

    private fun notifyRenderStatus(ready: Boolean) {
        mainHandler.post {
            if (!released) {
                onRenderStatusChanged(ready)
            }
        }
    }

    private fun initEgl(surface: SurfaceTexture, width: Int, height: Int): Boolean {
        return try {
            viewportW = width.coerceAtLeast(1)
            viewportH = height.coerceAtLeast(1)

            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == null || display == EGL14.EGL_NO_DISPLAY) {
                throw IllegalStateException("eglGetDisplay returned EGL_NO_DISPLAY")
            }
            eglDisplay = display
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                throw IllegalStateException("eglInitialize failed")
            }

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
            if (!EGL14.eglChooseConfig(display, attribList, 0, configs, 0, 1, numConfigs, 0)) {
                throw IllegalStateException("eglChooseConfig failed")
            }
            val config = configs[0] ?: throw IllegalStateException("No EGLConfig found")
            eglConfig = config

            val ctxAttribs = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
            )
            val context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            if (context == null || context == EGL14.EGL_NO_CONTEXT) {
                throw IllegalStateException("eglCreateContext failed")
            }
            eglContext = context

            val windowSurface = EGL14.eglCreateWindowSurface(
                display,
                config,
                surface,
                intArrayOf(EGL14.EGL_NONE),
                0
            )
            if (windowSurface == null || windowSurface == EGL14.EGL_NO_SURFACE) {
                throw IllegalStateException("eglCreateWindowSurface failed")
            }
            eglSurface = windowSurface

            if (!EGL14.eglMakeCurrent(display, windowSurface, windowSurface, context)) {
                throw IllegalStateException("eglMakeCurrent failed")
            }
            EGL14.eglSwapInterval(display, 0)

            toneColorProgram = GlGradingShaders.compileToneColorProgram()
            spatialFinishProgram = GlGradingShaders.compileSpatialFinishProgram()
            val tHandles = GlGradingShaders.ToneColorUniformHandles(toneColorProgram)
            val sHandles = GlGradingShaders.SpatialFinishUniformHandles(spatialFinishProgram)
            toneHandles = tHandles
            spatialHandles = sHandles

            fboQuadBuffer = GlGradingShaders.createQuadBuffer(readbackFlipY = true)
            screenQuadBuffer = GlGradingShaders.createQuadBuffer(readbackFlipY = false)

            val textures = IntArray(4)
            GLES20.glGenTextures(4, textures, 0)
            imageTexId = textures[0]
            lutTexId = textures[1]
            curveTexId = textures[2]
            pass1TexId = textures[3]

            val fbos = IntArray(1)
            GLES20.glGenFramebuffers(1, fbos, 0)
            pass1FboId = fbos[0]

            if (!ensurePass1Fbo(viewportW, viewportH)) {
                throw IllegalStateException("Pass-1 FBO incomplete")
            }

            // Pre-allocate reusable 256x2 opaque tone-curve bitmap & GPU storage once
            val curveBmp = createBitmap(256, 2)
            reusableCurveBitmap = curveBmp
            GlGradingShaders.packToneCurveInto(ToneCurveData(), reusableCurvePixels, curveBmp)
            GlGradingShaders.uploadTexture(curveTexId, curveBmp)
            curveTextureAllocated = true

            // Initialize LUT sampler with a valid 1x1 texture so every GLES20 sampler unit is texture-complete
            val dummyLut = createBitmap(1, 1)
            GlGradingShaders.uploadTexture(lutTexId, dummyLut)
            dummyLut.recycle()

            // Bind constant sampler units once per program
            GLES20.glUseProgram(toneColorProgram)
            GLES20.glUniform1i(tHandles.uImageTex, 0)
            GLES20.glUniform1i(tHandles.uCurveTex, 1)

            GLES20.glUseProgram(spatialFinishProgram)
            GLES20.glUniform1i(sHandles.uGradedTex, 0)
            GLES20.glUniform1i(sHandles.uLutTex, 1)
            GLES20.glUniform1i(sHandles.uRawTex, 2)

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, viewportW, viewportH)
            isSurfaceReady = true
            true
        } catch (t: Throwable) {
            Log.e(TAG, "EGL / 2-pass GLSL initialization failed — activating CPU fallback", t)
            isSurfaceReady = false
            releaseGl()
            false
        }
    }

    private fun ensurePass1Fbo(targetW: Int, targetH: Int): Boolean {
        val w = targetW.coerceAtLeast(1)
        val h = targetH.coerceAtLeast(1)
        if (w == fboWidth && h == fboHeight && pass1FboId != 0 && pass1TexId != 0) {
            return true
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, pass1TexId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_RGBA,
            w,
            h,
            0,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            null
        )

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, pass1FboId)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            pass1TexId,
            0
        )
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.e(TAG, "Pass-1 FBO status incomplete: 0x${Integer.toHexString(status)}")
            return false
        }
        fboWidth = w
        fboHeight = h
        return true
    }

    private fun drainDirtyFrameOnGlThread() {
        val state = dirtyGate.consumeForRender() ?: return
        if (!isSurfaceReady ||
            eglDisplay == null || eglDisplay == EGL14.EGL_NO_DISPLAY ||
            eglContext == null || eglContext == EGL14.EGL_NO_CONTEXT ||
            eglSurface == null || eglSurface == EGL14.EGL_NO_SURFACE
        ) return
        val img = state.imageBitmap ?: return
        if (img.isRecycled || img.width <= 0 || img.height <= 0) return
        val tHandles = toneHandles ?: return
        val sHandles = spatialHandles ?: return
        val fboQuad = fboQuadBuffer ?: return
        val screenQuad = screenQuadBuffer ?: return

        if (surfaceEpoch == lastRenderedEpoch && state.matches(lastRenderedState)) {
            return
        }

        try {
            // 1. Proxy source image texture — uploaded once per bitmap, zero per-drag uploads
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

            // 2. 3D/1D .cube LUT texture — uploaded only when selected LUT changes
            val effectiveLut = if (state.lut?.sourceFormat == LutSourceFormat.XMP) null else state.lut
            if (effectiveLut?.id != lastLutId) {
                val prevPacked = currentPackedLut
                val nextPacked = effectiveLut?.let {
                    val packed = GlGradingShaders.packLutToTiledBitmap(it)
                    GlGradingShaders.uploadTexture(lutTexId, packed.bitmap)
                    packed
                }
                currentPackedLut = nextPacked
                lastLutId = effectiveLut?.id
                prevPacked?.bitmap?.let { oldBmp ->
                    if (oldBmp !== nextPacked?.bitmap && !oldBmp.isRecycled) {
                        oldBmp.recycle()
                    }
                }
            }

            // 3. Tone Curve 256x2 opaque LUT — in-place sub-image update
            val curve = state.params.toneCurve
            if (curve != lastToneCurve) {
                lastToneCurve = curve
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

            if (!ensurePass1Fbo(viewportW, viewportH)) {
                isSurfaceReady = false
                notifyRenderStatus(false)
                return
            }

            val (outW, outH) = if (state.applyCrop) {
                GeometryMath.computeOutputDimensions(img.width, img.height, state.params.crop)
            } else {
                GeometryMath.orientedDimensions(img.width, img.height, state.params.crop)
            }

            // =========================================================================
            // PASS 1: Tone & Color Grading (Steps 1–5) -> Offscreen FBO (pass1TexId)
            // =========================================================================
            if (!state.isOriginal) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, pass1FboId)
                GLES20.glViewport(0, 0, fboWidth, fboHeight)
                GLES20.glUseProgram(toneColorProgram)

                fboQuad.position(0)
                GLES20.glEnableVertexAttribArray(tHandles.aPosition)
                GLES20.glVertexAttribPointer(tHandles.aPosition, 2, GLES20.GL_FLOAT, false, 16, fboQuad)
                fboQuad.position(2)
                GLES20.glEnableVertexAttribArray(tHandles.aTexCoord)
                GLES20.glVertexAttribPointer(tHandles.aTexCoord, 2, GLES20.GL_FLOAT, false, 16, fboQuad)

                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imageTexId)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, curveTexId)

                GlGradingShaders.bindToneColorUniforms(
                    handles = tHandles,
                    params = state.params,
                    imageWidth = img.width,
                    imageHeight = img.height,
                    applyCrop = state.applyCrop,
                    outputWidth = outW,
                    outputHeight = outH
                )

                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GLES20.glDisableVertexAttribArray(tHandles.aPosition)
                GLES20.glDisableVertexAttribArray(tHandles.aTexCoord)
            }

            // =========================================================================
            // PASS 2: Spatial Detail (Step 6) + Finishing + Compare -> Screen Surface
            // =========================================================================
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, viewportW, viewportH)
            GLES20.glClearColor(0.07f, 0.075f, 0.086f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            GLES20.glUseProgram(spatialFinishProgram)

            screenQuad.position(0)
            GLES20.glEnableVertexAttribArray(sHandles.aPosition)
            GLES20.glVertexAttribPointer(sHandles.aPosition, 2, GLES20.GL_FLOAT, false, 16, screenQuad)
            screenQuad.position(2)
            GLES20.glEnableVertexAttribArray(sHandles.aTexCoord)
            GLES20.glVertexAttribPointer(sHandles.aTexCoord, 2, GLES20.GL_FLOAT, false, 16, screenQuad)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, pass1TexId)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTexId)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imageTexId)

            GlGradingShaders.bindSpatialFinishUniforms(
                handles = sHandles,
                params = state.params,
                lut = effectiveLut,
                packedLut = currentPackedLut,
                fboWidth = fboWidth,
                fboHeight = fboHeight,
                imageWidth = img.width,
                imageHeight = img.height,
                isOriginal = state.isOriginal,
                splitRatio = state.splitRatio,
                applyCrop = state.applyCrop,
                showHighlightClipping = state.showHighlightClipping,
                showShadowClipping = state.showShadowClipping
            )

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(sHandles.aPosition)
            GLES20.glDisableVertexAttribArray(sHandles.aTexCoord)

            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
            textureView.postInvalidateOnAnimation()

            lastRenderedState = state
            lastRenderedEpoch = surfaceEpoch

            // If a newer slider tick arrived mid-render, immediately drain the latest dirty frame
            if (dirtyGate.shouldRescheduleAfterDraw()) {
                val handler = glHandler
                if (handler == null || !handler.post(renderRunnable)) {
                    dirtyGate.releaseScheduledLatch()
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "GL frame draw error", t)
            if (dirtyGate.shouldRescheduleAfterDraw()) {
                val handler = glHandler
                if (handler == null || !handler.post(renderRunnable)) {
                    dirtyGate.releaseScheduledLatch()
                }
            }
        }
    }

    private fun releaseGl() {
        try {
            if (toneColorProgram != 0) {
                GLES20.glDeleteProgram(toneColorProgram)
                toneColorProgram = 0
            }
            if (spatialFinishProgram != 0) {
                GLES20.glDeleteProgram(spatialFinishProgram)
                spatialFinishProgram = 0
            }
            toneHandles = null
            spatialHandles = null
            val texs = intArrayOf(imageTexId, lutTexId, curveTexId, pass1TexId)
            GLES20.glDeleteTextures(texs.size, texs, 0)
            imageTexId = 0
            lutTexId = 0
            curveTexId = 0
            pass1TexId = 0
            if (pass1FboId != 0) {
                GLES20.glDeleteFramebuffers(1, intArrayOf(pass1FboId), 0)
                pass1FboId = 0
            }
            fboWidth = 0
            fboHeight = 0
            currentPackedLut?.bitmap?.let { if (!it.isRecycled) it.recycle() }
            currentPackedLut = null
            reusableCurveBitmap?.let { if (!it.isRecycled) it.recycle() }
            reusableCurveBitmap = null
            curveTextureAllocated = false
            lastUploadedImage = null
            lastUploadedImageW = 0
            lastUploadedImageH = 0
            lastLutId = null
            lastToneCurve = null
            lastRenderedState = null

            val display = eglDisplay
            if (display != null && display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    display,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT
                )
                eglSurface?.let {
                    if (it != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, it)
                }
                eglContext?.let {
                    if (it != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, it)
                }
                EGL14.eglTerminate(display)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Error releasing GL resources", t)
        } finally {
            eglSurface = EGL14.EGL_NO_SURFACE
            eglContext = EGL14.EGL_NO_CONTEXT
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }
    }
}
