package com.zamalfajji.zamlab.gl

import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import com.zamalfajji.zamlab.editor.LocalMask
import com.zamalfajji.zamlab.editor.MaskEngine
import com.zamalfajji.zamlab.editor.MaskSubMask
import com.zamalfajji.zamlab.editor.MaskType
import com.zamalfajji.zamlab.gl.shaders.GuidedFilterShaders
import com.zamalfajji.zamlab.gl.shaders.MaskLocalAdjustShader
import com.zamalfajji.zamlab.gl.shaders.MaskShaderCommon
import com.zamalfajji.zamlab.gl.shaders.MaskSubMaskShaders
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.max

/**
 * Multi-pass OpenGL ES 3.0 / WebGL2-compatible offscreen masking pipeline.
 *
 * Orchestrates modular fragment shaders per enabled [LocalMask]:
 *  1. Resolution-independent Vector Brush segment stamping (normalized [0, 1] UV)
 *  2. Boolean sub-mask compositing (ADD, SUBTRACT, INTERSECT) for Radial, Linear, Brush,
 *     Luminance Range (Rec. 709), and Color Range (CIELAB) + layer Invert
 *  3. 4-pass separable GPU Guided Image Filter driven by `edgeSnap` (0..100) using the
 *     underlying RGB photo as the guide image
 *  4. All 8 selective local adjustments (Exposure, Contrast, Highlights, Shadows,
 *     Temperature, Tint, Saturation, Sharpness & Clarity)
 *  5. Captures the active mask's final refined alpha texture for real-time 50% Rubylith overlay
 */
class GlMaskPipeline(private val isEs3: Boolean) {

    data class PipelineResult(
        val gradedPhotoTexId: Int,
        val overlayMaskTexId: Int,
        val hasOverlayMask: Boolean
    )

    private var subMaskProgram = 0
    private var brushStampProgram = 0
    private var invertProgram = 0
    private var guidedMomentsHProgram = 0
    private var guidedCoeffsVProgram = 0
    private var guidedAbHProgram = 0
    private var guidedApplyVProgram = 0
    private var localAdjustProgram = 0

    // Uniform locations — SubMask Composite
    private var subBaseTexLoc = -1
    private var subGuideTexLoc = -1
    private var subBrushTexLoc = -1
    private var subMetaLoc = -1
    private var subParamALoc = -1
    private var subParamBLoc = -1
    private var subPosAttr = -1
    private var subUvAttr = -1

    // Uniform locations — Vector Brush Stamp
    private var brushPrevTexLoc = -1
    private var brushParamsLoc = -1
    private var brushSegCountLoc = -1
    private var brushSegmentsLoc = -1
    private var brushPosAttr = -1
    private var brushUvAttr = -1

    // Uniform locations — Invert / Copy
    private var invMaskTexLoc = -1
    private var invFlagLoc = -1
    private var invPosAttr = -1
    private var invUvAttr = -1

    // Uniform locations — Guided Filter (4 passes)
    private var gmGuideTexLoc = -1
    private var gmMaskTexLoc = -1
    private var gmParamsLoc = -1
    private var gmPosAttr = -1
    private var gmUvAttr = -1

    private var gcMomentsTexLoc = -1
    private var gcParamsLoc = -1
    private var gcEncodeLoc = -1
    private var gcPosAttr = -1
    private var gcUvAttr = -1

    private var gabCoeffsTexLoc = -1
    private var gabParamsLoc = -1
    private var gabPosAttr = -1
    private var gabUvAttr = -1

    private var gapAbTexLoc = -1
    private var gapGuideTexLoc = -1
    private var gapParamsLoc = -1
    private var gapEncodeLoc = -1
    private var gapPosAttr = -1
    private var gapUvAttr = -1

    // Uniform locations — Local Adjustments
    private var adjPhotoTexLoc = -1
    private var adjMaskTexLoc = -1
    private var adjALoc = -1
    private var adjBLoc = -1
    private var adjCLoc = -1
    private var adjPosAttr = -1
    private var adjUvAttr = -1

    // Offscreen FBOs & textures
    // 0=maskA, 1=maskB, 2=brushA, 3=brushB, 4=guidedH, 5=guidedV, 6=gradedA, 7=gradedB, 8=overlayMask
    private val fbos = IntArray(9)
    private val texs = IntArray(9)
    private var allocW = 0
    private var allocH = 0
    private var useFloatGuidedFbo = false
    private var initialized = false

    private val segScratch = FloatArray(MaskSubMaskShaders.MAX_BRUSH_SEGMENTS_PER_BATCH * 4)
    private val labScratch = FloatArray(3)

    fun initialize(): Boolean {
        return try {
            val vs = MaskShaderCommon.adaptForGlContext(
                MaskShaderCommon.FULLSCREEN_VERTEX_SHADER_ES3,
                isVertex = true,
                isEs3 = isEs3
            )

            subMaskProgram = compileProgram(
                vs,
                MaskShaderCommon.adaptForGlContext(MaskSubMaskShaders.SUBMASK_COMPOSITE_FRAGMENT_SHADER_ES3, false, isEs3)
            )
            brushStampProgram = compileProgram(
                vs,
                MaskShaderCommon.adaptForGlContext(MaskSubMaskShaders.BRUSH_STROKE_STAMP_FRAGMENT_SHADER_ES3, false, isEs3)
            )
            invertProgram = compileProgram(
                vs,
                MaskShaderCommon.adaptForGlContext(MaskSubMaskShaders.MASK_INVERT_FRAGMENT_SHADER_ES3, false, isEs3)
            )
            guidedMomentsHProgram = compileProgram(
                vs,
                MaskShaderCommon.adaptForGlContext(GuidedFilterShaders.GUIDED_MOMENTS_H_FRAGMENT_SHADER_ES3, false, isEs3)
            )
            guidedCoeffsVProgram = compileProgram(
                vs,
                MaskShaderCommon.adaptForGlContext(GuidedFilterShaders.GUIDED_COEFFS_V_FRAGMENT_SHADER_ES3, false, isEs3)
            )
            guidedAbHProgram = compileProgram(
                vs,
                MaskShaderCommon.adaptForGlContext(GuidedFilterShaders.GUIDED_AB_H_FRAGMENT_SHADER_ES3, false, isEs3)
            )
            guidedApplyVProgram = compileProgram(
                vs,
                MaskShaderCommon.adaptForGlContext(GuidedFilterShaders.GUIDED_APPLY_V_FRAGMENT_SHADER_ES3, false, isEs3)
            )
            localAdjustProgram = compileProgram(
                vs,
                MaskShaderCommon.adaptForGlContext(MaskLocalAdjustShader.MASK_LOCAL_ADJUST_FRAGMENT_SHADER_ES3, false, isEs3)
            )

            cacheUniformLocations()
            GLES20.glGenFramebuffers(fbos.size, fbos, 0)
            GLES20.glGenTextures(texs.size, texs, 0)
            initialized = true
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize GlMaskPipeline", t)
            release()
            false
        }
    }

    private fun cacheUniformLocations() {
        subPosAttr = GLES20.glGetAttribLocation(subMaskProgram, "aPosition")
        subUvAttr = GLES20.glGetAttribLocation(subMaskProgram, "aTexCoord")
        subBaseTexLoc = GLES20.glGetUniformLocation(subMaskProgram, "uBaseMaskTex")
        subGuideTexLoc = GLES20.glGetUniformLocation(subMaskProgram, "uGuidePhotoTex")
        subBrushTexLoc = GLES20.glGetUniformLocation(subMaskProgram, "uBrushMaskTex")
        subMetaLoc = GLES20.glGetUniformLocation(subMaskProgram, "uSubMeta")
        subParamALoc = GLES20.glGetUniformLocation(subMaskProgram, "uParamA")
        subParamBLoc = GLES20.glGetUniformLocation(subMaskProgram, "uParamB")
        GLES20.glUseProgram(subMaskProgram)
        GLES20.glUniform1i(subBaseTexLoc, 0)
        GLES20.glUniform1i(subGuideTexLoc, 1)
        GLES20.glUniform1i(subBrushTexLoc, 2)

        brushPosAttr = GLES20.glGetAttribLocation(brushStampProgram, "aPosition")
        brushUvAttr = GLES20.glGetAttribLocation(brushStampProgram, "aTexCoord")
        brushPrevTexLoc = GLES20.glGetUniformLocation(brushStampProgram, "uPrevBrushTex")
        brushParamsLoc = GLES20.glGetUniformLocation(brushStampProgram, "uBrushParams")
        brushSegCountLoc = GLES20.glGetUniformLocation(brushStampProgram, "uSegmentCount")
        brushSegmentsLoc = GLES20.glGetUniformLocation(brushStampProgram, "uSegments")
        GLES20.glUseProgram(brushStampProgram)
        GLES20.glUniform1i(brushPrevTexLoc, 0)

        invPosAttr = GLES20.glGetAttribLocation(invertProgram, "aPosition")
        invUvAttr = GLES20.glGetAttribLocation(invertProgram, "aTexCoord")
        invMaskTexLoc = GLES20.glGetUniformLocation(invertProgram, "uMaskTex")
        invFlagLoc = GLES20.glGetUniformLocation(invertProgram, "uInvert")
        GLES20.glUseProgram(invertProgram)
        GLES20.glUniform1i(invMaskTexLoc, 0)

        gmPosAttr = GLES20.glGetAttribLocation(guidedMomentsHProgram, "aPosition")
        gmUvAttr = GLES20.glGetAttribLocation(guidedMomentsHProgram, "aTexCoord")
        gmGuideTexLoc = GLES20.glGetUniformLocation(guidedMomentsHProgram, "uGuidePhotoTex")
        gmMaskTexLoc = GLES20.glGetUniformLocation(guidedMomentsHProgram, "uMaskTex")
        gmParamsLoc = GLES20.glGetUniformLocation(guidedMomentsHProgram, "uFilterParams")
        GLES20.glUseProgram(guidedMomentsHProgram)
        GLES20.glUniform1i(gmGuideTexLoc, 0)
        GLES20.glUniform1i(gmMaskTexLoc, 1)

        gcPosAttr = GLES20.glGetAttribLocation(guidedCoeffsVProgram, "aPosition")
        gcUvAttr = GLES20.glGetAttribLocation(guidedCoeffsVProgram, "aTexCoord")
        gcMomentsTexLoc = GLES20.glGetUniformLocation(guidedCoeffsVProgram, "uMomentsHTex")
        gcParamsLoc = GLES20.glGetUniformLocation(guidedCoeffsVProgram, "uFilterParams")
        gcEncodeLoc = GLES20.glGetUniformLocation(guidedCoeffsVProgram, "uEncodeRgba8")
        GLES20.glUseProgram(guidedCoeffsVProgram)
        GLES20.glUniform1i(gcMomentsTexLoc, 0)

        gabPosAttr = GLES20.glGetAttribLocation(guidedAbHProgram, "aPosition")
        gabUvAttr = GLES20.glGetAttribLocation(guidedAbHProgram, "aTexCoord")
        gabCoeffsTexLoc = GLES20.glGetUniformLocation(guidedAbHProgram, "uCoeffsTex")
        gabParamsLoc = GLES20.glGetUniformLocation(guidedAbHProgram, "uFilterParams")
        GLES20.glUseProgram(guidedAbHProgram)
        GLES20.glUniform1i(gabCoeffsTexLoc, 0)

        gapPosAttr = GLES20.glGetAttribLocation(guidedApplyVProgram, "aPosition")
        gapUvAttr = GLES20.glGetAttribLocation(guidedApplyVProgram, "aTexCoord")
        gapAbTexLoc = GLES20.glGetUniformLocation(guidedApplyVProgram, "uAbHTex")
        gapGuideTexLoc = GLES20.glGetUniformLocation(guidedApplyVProgram, "uGuidePhotoTex")
        gapParamsLoc = GLES20.glGetUniformLocation(guidedApplyVProgram, "uFilterParams")
        gapEncodeLoc = GLES20.glGetUniformLocation(guidedApplyVProgram, "uEncodeRgba8")
        GLES20.glUseProgram(guidedApplyVProgram)
        GLES20.glUniform1i(gapAbTexLoc, 0)
        GLES20.glUniform1i(gapGuideTexLoc, 1)

        adjPosAttr = GLES20.glGetAttribLocation(localAdjustProgram, "aPosition")
        adjUvAttr = GLES20.glGetAttribLocation(localAdjustProgram, "aTexCoord")
        adjPhotoTexLoc = GLES20.glGetUniformLocation(localAdjustProgram, "uPhotoTex")
        adjMaskTexLoc = GLES20.glGetUniformLocation(localAdjustProgram, "uMaskTex")
        adjALoc = GLES20.glGetUniformLocation(localAdjustProgram, "uAdjA")
        adjBLoc = GLES20.glGetUniformLocation(localAdjustProgram, "uAdjB")
        adjCLoc = GLES20.glGetUniformLocation(localAdjustProgram, "uAdjC")
        GLES20.glUseProgram(localAdjustProgram)
        GLES20.glUniform1i(adjPhotoTexLoc, 0)
        GLES20.glUniform1i(adjMaskTexLoc, 1)
    }

    private fun ensureFbos(width: Int, height: Int): Boolean {
        val w = max(1, width)
        val h = max(1, height)
        if (w == allocW && h == allocH && allocW > 0) return true

        // Allocate standard RGBA8 FBOs for mask ping-pong, brush scratch, graded ping-pong, overlay
        val standardIndices = intArrayOf(IDX_MASK_A, IDX_MASK_B, IDX_BRUSH_A, IDX_BRUSH_B, IDX_GRADED_A, IDX_GRADED_B, IDX_OVERLAY_MASK)
        for (idx in standardIndices) {
            if (!allocateFbo(idx, w, h, tryFloat = false)) return false
        }

        // Try RGBA16F for Guided Filter moments/coeffs on ES 3.0; gracefully fall back to encoded RGBA8
        val floatOk = isEs3 &&
            allocateFbo(IDX_GUIDED_H, w, h, tryFloat = true) &&
            allocateFbo(IDX_GUIDED_V, w, h, tryFloat = true)
        if (floatOk) {
            useFloatGuidedFbo = true
        } else {
            useFloatGuidedFbo = false
            if (!allocateFbo(IDX_GUIDED_H, w, h, tryFloat = false)) return false
            if (!allocateFbo(IDX_GUIDED_V, w, h, tryFloat = false)) return false
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        allocW = w
        allocH = h
        return true
    }

    private fun allocateFbo(idx: Int, w: Int, h: Int, tryFloat: Boolean): Boolean {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texs[idx])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        if (tryFloat) {
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                GLES30.GL_RGBA16F,
                w,
                h,
                0,
                GLES20.GL_RGBA,
                GLES30.GL_HALF_FLOAT,
                null
            )
        } else {
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
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbos[idx])
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            texs[idx],
            0
        )
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        return status == GLES20.GL_FRAMEBUFFER_COMPLETE
    }

    /**
     * Runs the full multi-pass GPU masking pipeline over [masks].
     * Returns the resulting graded texture ID (either [inputPhotoTexId] if no mask adjustments
     * were active, or the final ping-pong graded FBO texture) plus the refined active overlay mask
     * texture ID when [captureOverlay] is true.
     */
    fun processMasks(
        inputPhotoTexId: Int,
        masks: List<LocalMask>,
        activeMaskId: String?,
        captureOverlay: Boolean,
        fboWidth: Int,
        fboHeight: Int,
        quadBuffer: FloatBuffer
    ): PipelineResult {
        if (!initialized || masks.isEmpty()) {
            return PipelineResult(inputPhotoTexId, 0, false)
        }

        val relevantMasks = masks.filter { m ->
            m.enabled && (m.hasAdjustments || (captureOverlay && m.id == activeMaskId))
        }
        if (relevantMasks.isEmpty()) {
            return PipelineResult(inputPhotoTexId, 0, false)
        }
        if (!ensureFbos(fboWidth, fboHeight)) {
            return PipelineResult(inputPhotoTexId, 0, false)
        }

        GLES20.glViewport(0, 0, fboWidth, fboHeight)

        var currentPhotoTex = inputPhotoTexId
        var nextGradedIdx = IDX_GRADED_A
        var hasOverlay = false
        val aspect = fboWidth.toFloat() / max(1, fboHeight).toFloat()

        for (mask in relevantMasks) {
            val maskTexId = renderSingleMaskAlpha(
                mask = mask,
                guidePhotoTexId = inputPhotoTexId,
                width = fboWidth,
                height = fboHeight,
                aspect = aspect,
                quadBuffer = quadBuffer
            )

            if (captureOverlay && mask.id == activeMaskId) {
                copyMaskToFbo(maskTexId, fbos[IDX_OVERLAY_MASK], invert = false, quadBuffer = quadBuffer)
                hasOverlay = true
            }

            if (mask.hasAdjustments) {
                val targetFbo = fbos[nextGradedIdx]
                val targetTex = texs[nextGradedIdx]
                applyLocalAdjustPass(
                    photoTexId = currentPhotoTex,
                    maskTexId = maskTexId,
                    targetFbo = targetFbo,
                    mask = mask,
                    width = fboWidth,
                    height = fboHeight,
                    quadBuffer = quadBuffer
                )
                currentPhotoTex = targetTex
                nextGradedIdx = if (nextGradedIdx == IDX_GRADED_A) IDX_GRADED_B else IDX_GRADED_A
            }
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        return PipelineResult(
            gradedPhotoTexId = currentPhotoTex,
            overlayMaskTexId = if (hasOverlay) texs[IDX_OVERLAY_MASK] else 0,
            hasOverlayMask = hasOverlay
        )
    }

    private fun renderSingleMaskAlpha(
        mask: LocalMask,
        guidePhotoTexId: Int,
        width: Int,
        height: Int,
        aspect: Float,
        quadBuffer: FloatBuffer
    ): Int {
        // Clear initial mask ping FBO to 0
        clearFboBlack(fbos[IDX_MASK_A])
        var readIdx = IDX_MASK_A
        var writeIdx = IDX_MASK_B

        val subs = mask.resolvedSubMasks().filter { it.enabled }
        var initializedLayer = false

        for (sub in subs) {
            val brushTexId = if (sub.type == MaskType.BRUSH) {
                renderVectorBrushSubMask(sub, aspect, quadBuffer)
            } else {
                texs[IDX_BRUSH_A]
            }

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbos[writeIdx])
            GLES20.glUseProgram(subMaskProgram)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texs[readIdx])
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, guidePhotoTexId)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, brushTexId)

            GLES20.glUniform4f(
                subMetaLoc,
                sub.type.shaderType.toFloat(),
                sub.op.shaderOp.toFloat(),
                if (initializedLayer) 1f else 0f,
                aspect
            )

            when (sub.type) {
                MaskType.RADIAL -> {
                    GLES20.glUniform4f(subParamALoc, sub.centerX, sub.centerY, sub.radiusX, sub.radiusY)
                    val rotRad = (sub.rotationDeg * (PI / 180.0)).toFloat()
                    GLES20.glUniform4f(subParamBLoc, rotRad, sub.feather, 0f, 0f)
                }
                MaskType.LINEAR -> {
                    GLES20.glUniform4f(subParamALoc, sub.startX, sub.startY, sub.endX, sub.endY)
                    GLES20.glUniform4f(subParamBLoc, sub.feather, 0f, 0f, 0f)
                }
                MaskType.BRUSH -> {
                    GLES20.glUniform4f(subParamALoc, 0f, 0f, 0f, 0f)
                    GLES20.glUniform4f(subParamBLoc, 0f, 0f, 0f, 0f)
                }
                MaskType.LUMINANCE_RANGE -> {
                    GLES20.glUniform4f(subParamALoc, sub.lumMin, sub.lumMax, sub.lumFalloff, 0f)
                    GLES20.glUniform4f(subParamBLoc, 0f, 0f, 0f, 0f)
                }
                MaskType.COLOR_RANGE -> {
                    MaskEngine.srgbToLab(sub.targetR, sub.targetG, sub.targetB, labScratch)
                    GLES20.glUniform4f(
                        subParamALoc,
                        labScratch[0],
                        labScratch[1],
                        labScratch[2],
                        if (sub.colorSampled) 1f else 0f
                    )
                    GLES20.glUniform4f(subParamBLoc, sub.colorTolerance, sub.colorSmoothness, 0f, 0f)
                }
            }

            drawQuad(subPosAttr, subUvAttr, quadBuffer)
            initializedLayer = true
            val tmp = readIdx
            readIdx = writeIdx
            writeIdx = tmp
        }

        // Apply layer Invert if enabled
        if (mask.inverted) {
            copyMaskToFbo(texs[readIdx], fbos[writeIdx], invert = true, quadBuffer = quadBuffer)
            val tmp = readIdx
            readIdx = writeIdx
            writeIdx = tmp
        }

        // Multi-pass GPU Guided Image Filter (Edge-Aware Feathering) when edgeSnap > 0
        if (mask.edgeSnap > 0.5f) {
            val (radius, eps) = MaskEngine.guidedFilterParams(mask.edgeSnap, width, height)
            if (radius >= 1) {
                val texelW = 1f / max(1, width).toFloat()
                val texelH = 1f / max(1, height).toFloat()
                val encodeFlag = if (useFloatGuidedFbo) 0f else 1f

                // Pass 1: Horizontal moments (I, p, I*I, I*p) -> guidedH
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbos[IDX_GUIDED_H])
                GLES20.glUseProgram(guidedMomentsHProgram)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, guidePhotoTexId)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texs[readIdx])
                GLES20.glUniform4f(gmParamsLoc, texelW, texelH, radius.toFloat(), eps)
                drawQuad(gmPosAttr, gmUvAttr, quadBuffer)

                // Pass 2: Vertical moments + linear coeffs (a, b) -> guidedV
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbos[IDX_GUIDED_V])
                GLES20.glUseProgram(guidedCoeffsVProgram)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texs[IDX_GUIDED_H])
                GLES20.glUniform4f(gcParamsLoc, texelW, texelH, radius.toFloat(), eps)
                GLES20.glUniform1f(gcEncodeLoc, encodeFlag)
                drawQuad(gcPosAttr, gcUvAttr, quadBuffer)

                // Pass 3: Horizontal mean(a, b) -> guidedH
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbos[IDX_GUIDED_H])
                GLES20.glUseProgram(guidedAbHProgram)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texs[IDX_GUIDED_V])
                GLES20.glUniform4f(gabParamsLoc, texelW, texelH, radius.toFloat(), eps)
                drawQuad(gabPosAttr, gabUvAttr, quadBuffer)

                // Pass 4: Vertical mean(a, b) * I + meanB -> writeIdx mask FBO
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbos[writeIdx])
                GLES20.glUseProgram(guidedApplyVProgram)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texs[IDX_GUIDED_H])
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, guidePhotoTexId)
                GLES20.glUniform4f(gapParamsLoc, texelW, texelH, radius.toFloat(), eps)
                GLES20.glUniform1f(gapEncodeLoc, encodeFlag)
                drawQuad(gapPosAttr, gapUvAttr, quadBuffer)

                readIdx = writeIdx
            }
        }

        return texs[readIdx]
    }

    private fun renderVectorBrushSubMask(
        sub: MaskSubMask,
        aspect: Float,
        quadBuffer: FloatBuffer
    ): Int {
        clearFboBlack(fbos[IDX_BRUSH_A])
        if (sub.strokes.isEmpty()) return texs[IDX_BRUSH_A]

        var readIdx = IDX_BRUSH_A
        var writeIdx = IDX_BRUSH_B
        val maxBatch = MaskSubMaskShaders.MAX_BRUSH_SEGMENTS_PER_BATCH

        GLES20.glUseProgram(brushStampProgram)
        for (stroke in sub.strokes) {
            val pts = stroke.points
            if (pts.isEmpty()) continue
            val segCountTotal = if (pts.size == 1) 1 else pts.size - 1
            var offset = 0
            while (offset < segCountTotal) {
                val batchCount = minOf(maxBatch, segCountTotal - offset)
                for (i in 0 until batchCount) {
                    val p0 = pts[offset + i]
                    val p1 = if (pts.size == 1) p0 else pts[offset + i + 1]
                    val base = i * 4
                    segScratch[base] = p0.first
                    segScratch[base + 1] = p0.second
                    segScratch[base + 2] = p1.first
                    segScratch[base + 3] = p1.second
                }
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbos[writeIdx])
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texs[readIdx])
                GLES20.glUniform4f(brushParamsLoc, stroke.radius, stroke.hardness, stroke.flow, aspect)
                GLES20.glUniform1i(brushSegCountLoc, batchCount)
                GLES20.glUniform4fv(brushSegmentsLoc, batchCount, segScratch, 0)
                drawQuad(brushPosAttr, brushUvAttr, quadBuffer)

                val tmp = readIdx
                readIdx = writeIdx
                writeIdx = tmp
                offset += batchCount
            }
        }
        return texs[readIdx]
    }

    private fun copyMaskToFbo(srcTexId: Int, dstFbo: Int, invert: Boolean, quadBuffer: FloatBuffer) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, dstFbo)
        GLES20.glUseProgram(invertProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTexId)
        GLES20.glUniform1f(invFlagLoc, if (invert) 1f else 0f)
        drawQuad(invPosAttr, invUvAttr, quadBuffer)
    }

    private fun applyLocalAdjustPass(
        photoTexId: Int,
        maskTexId: Int,
        targetFbo: Int,
        mask: LocalMask,
        width: Int,
        height: Int,
        quadBuffer: FloatBuffer
    ) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, targetFbo)
        GLES20.glUseProgram(localAdjustProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, photoTexId)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTexId)

        GLES20.glUniform4f(
            adjALoc,
            mask.exposure,
            mask.contrast / 100f,
            mask.highlights / 100f,
            mask.shadows / 100f
        )
        GLES20.glUniform4f(
            adjBLoc,
            mask.effectiveTemperature / 100f,
            mask.tint / 100f,
            mask.saturation / 100f,
            mask.sharpness / 100f
        )
        GLES20.glUniform4f(
            adjCLoc,
            mask.clarity / 100f,
            1f / max(1, width).toFloat(),
            1f / max(1, height).toFloat(),
            0f
        )
        drawQuad(adjPosAttr, adjUvAttr, quadBuffer)
    }

    private fun clearFboBlack(fboId: Int) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
    }

    private fun drawQuad(posAttr: Int, uvAttr: Int, quadBuffer: FloatBuffer) {
        quadBuffer.position(0)
        GLES20.glEnableVertexAttribArray(posAttr)
        GLES20.glVertexAttribPointer(posAttr, 2, GLES20.GL_FLOAT, false, 16, quadBuffer)
        quadBuffer.position(2)
        GLES20.glEnableVertexAttribArray(uvAttr)
        GLES20.glVertexAttribPointer(uvAttr, 2, GLES20.GL_FLOAT, false, 16, quadBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(posAttr)
        GLES20.glDisableVertexAttribArray(uvAttr)
    }

    private fun compileProgram(vsSource: String, fsSource: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vsSource)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fsSource)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw IllegalStateException("Mask GLSL link failed: $log")
        }
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw IllegalStateException("Mask GLSL compile failed (type=$type): $log")
        }
        return shader
    }

    fun release() {
        val progs = intArrayOf(
            subMaskProgram, brushStampProgram, invertProgram,
            guidedMomentsHProgram, guidedCoeffsVProgram, guidedAbHProgram,
            guidedApplyVProgram, localAdjustProgram
        )
        for (p in progs) {
            if (p != 0) GLES20.glDeleteProgram(p)
        }
        subMaskProgram = 0
        brushStampProgram = 0
        invertProgram = 0
        guidedMomentsHProgram = 0
        guidedCoeffsVProgram = 0
        guidedAbHProgram = 0
        guidedApplyVProgram = 0
        localAdjustProgram = 0

        if (fbos.any { it != 0 }) {
            GLES20.glDeleteFramebuffers(fbos.size, fbos, 0)
            fbos.fill(0)
        }
        if (texs.any { it != 0 }) {
            GLES20.glDeleteTextures(texs.size, texs, 0)
            texs.fill(0)
        }
        allocW = 0
        allocH = 0
        initialized = false
    }

    companion object {
        private const val TAG = "GlMaskPipeline"
        private const val IDX_MASK_A = 0
        private const val IDX_MASK_B = 1
        private const val IDX_BRUSH_A = 2
        private const val IDX_BRUSH_B = 3
        private const val IDX_GUIDED_H = 4
        private const val IDX_GUIDED_V = 5
        private const val IDX_GRADED_A = 6
        private const val IDX_GRADED_B = 7
        private const val IDX_OVERLAY_MASK = 8
    }
}
