package com.zamalfajji.zamlab.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Build
import com.zamalfajji.zamlab.gl.GlOffscreenRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Renders per-layer graded previews (including bokeh blur, alpha mask, and edge feathering),
 * live OLED drawer thumbnails, interactive mask brush strokes, and full-resolution
 * multi-layer exports with blend modes using [LayerTransformMath] so preview and export match 1:1.
 */
object LayerCompositor {

    const val OVERLAY_PREVIEW_MAX_DIM: Int = 1024
    const val LAYER_THUMBNAIL_MAX_DIM: Int = 112

    /**
     * Renders a layer's live graded preview bitmap with per-layer [ColorGradingState],
     * [ImageLayer.bokehBlurRadius], [ImageLayer.maskBitmap], and [ImageLayer.edgeFeather].
     */
    suspend fun renderLayerPreview(
        layer: ImageLayer,
        maxDimension: Int = OVERLAY_PREVIEW_MAX_DIM
    ): Bitmap = withContext(Dispatchers.Default) {
        val src = layer.bitmap
        val working = downscaleIfNeeded(src, maxDimension)
        val params = layer.colorGradingState.gradingParams
        val lut = layer.colorGradingState.activeLut

        var current = if (params.isDefault && lut == null) {
            working.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            // For overlay collage layers, keep crop neutral so canvas framing belongs to the base canvas
            val overlayParams = if (layer.isBackground) {
                params
            } else {
                params.copy(crop = CropGeometryParams())
            }
            ColorGradingEngine.processFullResolution(
                source = working,
                params = overlayParams,
                lut = lut
            )
        }
        if (working !== src && !working.isRecycled) {
            working.recycle()
        }

        // Apply smooth bokeh blur if requested
        if (layer.bokehBlurRadius > 0.05f) {
            val blurred = applyFastBokehBlur(current, layer.bokehBlurRadius)
            if (blurred !== current && !current.isRecycled) current.recycle()
            current = blurred
        }

        // Apply alpha mask + edge feathering
        val mask = layer.maskBitmap?.takeIf { !it.isRecycled }
        if (mask != null) {
            val masked = applyMaskBitmap(current, mask)
            if (masked !== current && !current.isRecycled) current.recycle()
            current = masked
        }

        if (layer.edgeFeather > 0.05f) {
            val feathered = applyAlphaEdgeFeather(
                source = current,
                featherRadiusPx = layer.edgeFeather,
                hasAlphaMask = mask != null
            )
            if (feathered !== current && !current.isRecycled) current.recycle()
            current = feathered
        }

        current
    }

    /**
     * Fast synchronous helper used during interactive Mask Brush drags: applies [maskBitmap]
     * (and optional [edgeFeather]) onto an already-graded/blurred base bitmap so brush strokes
     * respond at interactive frame rates.
     */
    fun applyMaskAndFeatherSync(
        baseBitmap: Bitmap,
        maskBitmap: Bitmap?,
        edgeFeather: Float
    ): Bitmap {
        var current = if (maskBitmap != null && !maskBitmap.isRecycled) {
            applyMaskBitmap(baseBitmap, maskBitmap)
        } else {
            baseBitmap.copy(Bitmap.Config.ARGB_8888, false)
        }
        if (edgeFeather > 0.05f) {
            val feathered = applyAlphaEdgeFeather(
                source = current,
                featherRadiusPx = edgeFeather,
                hasAlphaMask = maskBitmap != null
            )
            if (feathered !== current && !current.isRecycled) current.recycle()
            current = feathered
        }
        return current
    }

    /**
     * Renders a small (~112px) graded thumbnail for display inside the OLED Layer Drawer card.
     */
    suspend fun renderLayerThumbnail(
        source: Bitmap,
        gradingState: ColorGradingState,
        isBackground: Boolean,
        maskBitmap: Bitmap? = null,
        bokehBlurRadius: Float = 0f,
        edgeFeather: Float = 0f
    ): Bitmap = withContext(Dispatchers.Default) {
        val thumbSrc = downscaleIfNeeded(source, LAYER_THUMBNAIL_MAX_DIM)
        val params = gradingState.gradingParams
        val lut = gradingState.activeLut
        var current = if (params.isDefault && lut == null) {
            thumbSrc.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            val effectiveParams = if (isBackground) params else params.copy(crop = CropGeometryParams())
            ColorGradingEngine.processFullResolution(
                source = thumbSrc,
                params = effectiveParams,
                lut = lut
            )
        }
        if (thumbSrc !== source && !thumbSrc.isRecycled) {
            thumbSrc.recycle()
        }

        if (bokehBlurRadius > 0.05f) {
            // Scale blur radius proportionally for the small thumbnail
            val scaledBlur = (bokehBlurRadius * 0.22f).coerceIn(1f, 6f)
            val blurred = applyFastBokehBlur(current, scaledBlur)
            if (blurred !== current && !current.isRecycled) current.recycle()
            current = blurred
        }

        val mask = maskBitmap?.takeIf { !it.isRecycled }
        if (mask != null) {
            val masked = applyMaskBitmap(current, mask)
            if (masked !== current && !current.isRecycled) current.recycle()
            current = masked
        }

        if (edgeFeather > 0.05f) {
            val scaledFeather = (edgeFeather * 0.25f).coerceIn(1f, 5f)
            val feathered = applyAlphaEdgeFeather(
                source = current,
                featherRadiusPx = scaledFeather,
                hasAlphaMask = mask != null
            )
            if (feathered !== current && !current.isRecycled) current.recycle()
            current = feathered
        }

        current
    }

    /**
     * Creates a full-opacity (`0xFFFFFFFF`) mutable `ARGB_8888` alpha mask matching `(width, height)`
     * when the user enters Mask Brush mode on a layer that does not yet have an alpha mask.
     */
    fun createOpaqueMaskBitmap(width: Int, height: Int): Bitmap {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
    }

    /**
     * Paints a soft anti-aliased dab or continuous stroke segment from `(fromX, fromY)` to
     * `(toX, toY)` directly onto [mutableMaskBitmap] in layer-mask pixel coordinates.
     *
     * - [LayerMaskBrushMode.ERASE] punches out alpha (`DST_OUT`).
     * - [LayerMaskBrushMode.RESTORE] restores solid opaque alpha (`SRC_OVER` white).
     */
    fun paintMaskStrokeSegment(
        mutableMaskBitmap: Bitmap,
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        radiusPx: Float,
        softness: Float,
        mode: LayerMaskBrushMode
    ) {
        if (mutableMaskBitmap.isRecycled || !mutableMaskBitmap.isMutable) return
        val r = radiusPx.coerceAtLeast(1.5f)
        val soft = softness.coerceIn(0f, 1f)
        val canvas = Canvas(mutableMaskBitmap)

        val dx = toX - fromX
        val dy = toY - fromY
        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        val step = max(1.5f, r * 0.25f)
        val steps = max(1, ceil(dist / step).toInt())

        val innerStop = (1f - soft * 0.92f).coerceIn(0.05f, 0.98f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            xfermode = when (mode) {
                LayerMaskBrushMode.ERASE -> PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                LayerMaskBrushMode.RESTORE -> null
            }
        }

        for (i in 0..steps) {
            val t = if (steps == 0) 1f else i.toFloat() / steps.toFloat()
            val cx = fromX + dx * t
            val cy = fromY + dy * t
            paint.shader = RadialGradient(
                cx,
                cy,
                r,
                intArrayOf(Color.WHITE, Color.WHITE, Color.TRANSPARENT),
                floatArrayOf(0f, innerStop, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, r, paint)
        }
    }

    /**
     * Composites all visible [layers] in exact bottom-to-top z-order onto a full-resolution
     * export bitmap of dimensions `(outWidth, outHeight)` applying each layer's [ColorGradingState],
     * [ImageLayer.bokehBlurRadius], [ImageLayer.maskBitmap], [ImageLayer.edgeFeather],
     * [ImageLayer.blendMode], [ImageLayer.opacity], and transform geometry.
     */
    suspend fun compositeForExport(
        context: Context,
        layers: List<ImageLayer>,
        outWidth: Int,
        outHeight: Int,
        preRenderedBackground: Bitmap?,
        preferGpuOffscreen: Boolean = true
    ): Bitmap = withContext(Dispatchers.Default) {
        val safeW = outWidth.coerceAtLeast(1)
        val safeH = outHeight.coerceAtLeast(1)
        val output = Bitmap.createBitmap(safeW, safeH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val resolutionScale = (max(safeW, safeH).toFloat() / OVERLAY_PREVIEW_MAX_DIM.toFloat())
            .coerceIn(0.5f, 3.0f)

        for (layer in layers) {
            if (!layer.isVisible || layer.opacity <= 0.001f) continue

            var tempLayerBitmap: Bitmap? = null
            var tempDecodedSource: Bitmap? = null
            try {
                var layerBitmap: Bitmap = if (layer.isBackground && preRenderedBackground != null && !preRenderedBackground.isRecycled) {
                    preRenderedBackground
                } else {
                    val exportMaxDim = max(safeW, safeH).coerceAtMost(4096)
                    val rawLayerSource = decodeLayerExportSource(context, layer, exportMaxDim)
                    if (rawLayerSource !== layer.bitmap) {
                        tempDecodedSource = rawLayerSource
                    }
                    val grading = layer.colorGradingState
                    val effectiveParams = if (layer.isBackground) {
                        grading.gradingParams
                    } else {
                        grading.gradingParams.copy(crop = CropGeometryParams())
                    }
                    val graded = if (effectiveParams.isDefault && grading.activeLut == null) {
                        rawLayerSource
                    } else if (preferGpuOffscreen) {
                        GlOffscreenRenderer.renderFullResolution(
                            source = rawLayerSource,
                            params = effectiveParams,
                            lut = grading.activeLut
                        )
                    } else {
                        ColorGradingEngine.processFullResolution(
                            source = rawLayerSource,
                            params = effectiveParams,
                            lut = grading.activeLut
                        )
                    }
                    if (graded !== rawLayerSource) {
                        tempLayerBitmap = graded
                    }
                    graded
                }

                // Apply per-layer Bokeh Blur proportionally to export resolution
                if (layer.bokehBlurRadius > 0.05f) {
                    val exportBlur = (layer.bokehBlurRadius * resolutionScale).coerceIn(1f, 60f)
                    val blurred = applyFastBokehBlur(layerBitmap, exportBlur)
                    if (tempLayerBitmap != null && tempLayerBitmap !== blurred &&
                        tempLayerBitmap !== preRenderedBackground && !tempLayerBitmap.isRecycled
                    ) {
                        tempLayerBitmap.recycle()
                    }
                    tempLayerBitmap = blurred
                    layerBitmap = blurred
                }

                // Apply per-layer Alpha Mask (Smart Cut or manual Mask Brush)
                val mask = layer.maskBitmap?.takeIf { !it.isRecycled }
                if (mask != null) {
                    val masked = applyMaskBitmap(layerBitmap, mask)
                    if (tempLayerBitmap != null && tempLayerBitmap !== masked &&
                        tempLayerBitmap !== preRenderedBackground && !tempLayerBitmap.isRecycled
                    ) {
                        tempLayerBitmap.recycle()
                    }
                    tempLayerBitmap = masked
                    layerBitmap = masked
                }

                // Apply per-layer Alpha Edge Feathering
                if (layer.edgeFeather > 0.05f) {
                    val exportFeather = (layer.edgeFeather * resolutionScale).coerceIn(1f, 45f)
                    val feathered = applyAlphaEdgeFeather(
                        source = layerBitmap,
                        featherRadiusPx = exportFeather,
                        hasAlphaMask = mask != null
                    )
                    if (tempLayerBitmap != null && tempLayerBitmap !== feathered &&
                        tempLayerBitmap !== preRenderedBackground && !tempLayerBitmap.isRecycled
                    ) {
                        tempLayerBitmap.recycle()
                    }
                    tempLayerBitmap = feathered
                    layerBitmap = feathered
                }

                val alphaInt = (layer.opacity.coerceIn(0f, 1f) * 255f).roundToInt()
                val isDirectFullCanvas = layer.isBackground &&
                    layer.offsetX == 0f &&
                    layer.offsetY == 0f &&
                    layer.scale == 1f &&
                    layer.rotation == 0f &&
                    layerBitmap.width == safeW &&
                    layerBitmap.height == safeH

                // On API < 29, Soft Light has no PorterDuff equivalent, so composite via exact W3C pixel blend
                if (layer.blendMode == LayerBlendMode.SOFT_LIGHT && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    val transformed = Bitmap.createBitmap(safeW, safeH, Bitmap.Config.ARGB_8888)
                    val tCanvas = Canvas(transformed)
                    val tPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                        alpha = alphaInt
                    }
                    if (isDirectFullCanvas) {
                        tCanvas.drawBitmap(layerBitmap, 0f, 0f, tPaint)
                    } else {
                        val matrix = LayerTransformMath.buildCanvasMatrix(
                            canvasW = safeW.toFloat(),
                            canvasH = safeH.toFloat(),
                            layerW = layerBitmap.width.toFloat(),
                            layerH = layerBitmap.height.toFloat(),
                            offsetX = layer.offsetX,
                            offsetY = layer.offsetY,
                            scale = layer.scale,
                            rotationDegrees = layer.rotation
                        )
                        tCanvas.drawBitmap(layerBitmap, matrix, tPaint)
                    }
                    blendBitmapOntoDestination(output, transformed, LayerBlendMode.SOFT_LIGHT)
                    transformed.recycle()
                } else {
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                        alpha = alphaInt
                        applyBlendModeToPaint(this, layer.blendMode)
                    }
                    if (isDirectFullCanvas) {
                        canvas.drawBitmap(layerBitmap, 0f, 0f, paint)
                    } else {
                        val matrix = LayerTransformMath.buildCanvasMatrix(
                            canvasW = safeW.toFloat(),
                            canvasH = safeH.toFloat(),
                            layerW = layerBitmap.width.toFloat(),
                            layerH = layerBitmap.height.toFloat(),
                            offsetX = layer.offsetX,
                            offsetY = layer.offsetY,
                            scale = layer.scale,
                            rotationDegrees = layer.rotation
                        )
                        canvas.drawBitmap(layerBitmap, matrix, paint)
                    }
                }
            } finally {
                if (tempLayerBitmap != null && tempLayerBitmap !== preRenderedBackground &&
                    tempLayerBitmap !== layer.bitmap && !tempLayerBitmap.isRecycled
                ) {
                    tempLayerBitmap.recycle()
                }
                if (tempDecodedSource != null && tempDecodedSource !== layer.bitmap && !tempDecodedSource.isRecycled) {
                    tempDecodedSource.recycle()
                }
            }
        }
        output
    }

    /**
     * Fast O(W*H) 3-pass separable box blur approximating a smooth Gaussian / optical bokeh
     * disk across `radiusPx` (`0..60`). Handles premultiplied RGB/alpha cleanly so transparent
     * subject cutouts never develop dark fringes.
     */
    fun applyFastBokehBlur(source: Bitmap, radiusPx: Float): Bitmap {
        val r = radiusPx.roundToInt().coerceIn(0, 60)
        val w = source.width.coerceAtLeast(1)
        val h = source.height.coerceAtLeast(1)
        if (r <= 0 || w < 2 || h < 2) {
            return source.copy(Bitmap.Config.ARGB_8888, false)
        }

        val total = w * h
        val srcPixels = IntArray(total)
        source.getPixels(srcPixels, 0, w, 0, 0, w, h)

        val curA = FloatArray(total)
        val curR = FloatArray(total)
        val curG = FloatArray(total)
        val curB = FloatArray(total)
        for (i in 0 until total) {
            val c = srcPixels[i]
            val a = ((c ushr 24) and 0xFF) / 255f
            curA[i] = a
            curR[i] = ((c ushr 16) and 0xFF) * a
            curG[i] = ((c ushr 8) and 0xFF) * a
            curB[i] = (c and 0xFF) * a
        }

        val tmpA = FloatArray(total)
        val tmpR = FloatArray(total)
        val tmpG = FloatArray(total)
        val tmpB = FloatArray(total)

        // 3 separable box passes converge smoothly to a Gaussian bokeh PSF
        val passRadii = computeThreeBoxPassRadii(r)
        for (passR in passRadii) {
            if (passR <= 0) continue
            boxBlurHorizontal(curA, tmpA, w, h, passR)
            boxBlurHorizontal(curR, tmpR, w, h, passR)
            boxBlurHorizontal(curG, tmpG, w, h, passR)
            boxBlurHorizontal(curB, tmpB, w, h, passR)

            boxBlurVertical(tmpA, curA, w, h, passR)
            boxBlurVertical(tmpR, curR, w, h, passR)
            boxBlurVertical(tmpG, curG, w, h, passR)
            boxBlurVertical(tmpB, curB, w, h, passR)
        }

        val outPixels = IntArray(total)
        for (i in 0 until total) {
            val a = curA[i].coerceIn(0f, 1f)
            val aInt = (a * 255f).roundToInt().coerceIn(0, 255)
            if (aInt == 0 || a < 1e-4f) {
                outPixels[i] = 0
            } else {
                val invA = 1f / a
                val rInt = (curR[i] * invA).roundToInt().coerceIn(0, 255)
                val gInt = (curG[i] * invA).roundToInt().coerceIn(0, 255)
                val bInt = (curB[i] * invA).roundToInt().coerceIn(0, 255)
                outPixels[i] = (aInt shl 24) or (rInt shl 16) or (gInt shl 8) or bInt
            }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(outPixels, 0, w, 0, 0, w, h)
        }
    }

    /**
     * Softens the alpha edges of [source] by [featherRadiusPx] (`0..45`) while leaving RGB
     * untouched so Smart Cut cutouts and rectangular collage overlays blend seamlessly.
     */
    fun applyAlphaEdgeFeather(
        source: Bitmap,
        featherRadiusPx: Float,
        hasAlphaMask: Boolean = true
    ): Bitmap {
        val r = featherRadiusPx.roundToInt().coerceIn(0, 45)
        val w = source.width.coerceAtLeast(1)
        val h = source.height.coerceAtLeast(1)
        if (r <= 0 || w < 2 || h < 2) {
            return source.copy(Bitmap.Config.ARGB_8888, false)
        }

        val total = w * h
        val pixels = IntArray(total)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        val alpha = FloatArray(total)
        var hasTransparentPixels = false
        for (i in 0 until total) {
            val a = ((pixels[i] ushr 24) and 0xFF) / 255f
            alpha[i] = a
            if (a < 0.99f) hasTransparentPixels = true
        }

        // For opaque rectangular collage photos without a cutout mask, inset border alpha to 0
        // so edge feather creates a smooth soft border fade around the photo frame.
        if (!hasAlphaMask && !hasTransparentPixels) {
            val border = min(r, min(w, h) / 4).coerceAtLeast(1)
            for (y in 0 until h) {
                val rowOff = y * w
                val dy = min(y, h - 1 - y)
                for (x in 0 until w) {
                    val dx = min(x, w - 1 - x)
                    val edgeDist = min(dx, dy)
                    if (edgeDist < border) {
                        val t = edgeDist.toFloat() / border.toFloat()
                        alpha[rowOff + x] *= t * t * (3f - 2f * t)
                    }
                }
            }
        }

        val tmp = FloatArray(total)
        val passRadii = computeThreeBoxPassRadii(r)
        for (passR in passRadii) {
            if (passR <= 0) continue
            boxBlurHorizontal(alpha, tmp, w, h, passR)
            boxBlurVertical(tmp, alpha, w, h, passR)
        }

        // Preserve original transparent exterior cleanly (prevent feather expanding outside hard mask halo)
        for (i in 0 until total) {
            val origA = ((pixels[i] ushr 24) and 0xFF) / 255f
            val featheredA = if (hasTransparentPixels) {
                min(origA, alpha[i])
            } else {
                alpha[i]
            }
            val aInt = (featheredA.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
            pixels[i] = (aInt shl 24) or (pixels[i] and 0x00FFFFFF)
        }

        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }

    private fun computeThreeBoxPassRadii(radius: Int): IntArray {
        val sigma = max(1f, radius * 0.55f)
        val idealW = sqrt((12f * sigma * sigma / 3f) + 1f)
        var wl = idealW.toInt()
        if (wl % 2 == 0) wl--
        wl = wl.coerceAtLeast(1)
        val wu = wl + 2
        val mIdeal = (12f * sigma * sigma - 3f * wl * wl - 12f * wl - 9f) / (-4f * wl - 4f)
        val m = mIdeal.roundToInt().coerceIn(0, 3)
        return IntArray(3) { i ->
            val width = if (i < m) wl else wu
            ((width - 1) / 2).coerceAtLeast(if (i == 0) 1 else 0)
        }
    }

    private fun boxBlurHorizontal(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int) {
        val invWindow = 1f / (2 * r + 1).toFloat()
        for (y in 0 until h) {
            val rowOff = y * w
            var sum = src[rowOff] * (r + 1)
            for (k in 1..r) {
                sum += src[rowOff + min(k, w - 1)]
            }
            for (x in 0 until w) {
                dst[rowOff + x] = sum * invWindow
                val addIdx = rowOff + min(x + r + 1, w - 1)
                val subIdx = rowOff + max(x - r, 0)
                sum += src[addIdx] - src[subIdx]
            }
        }
    }

    private fun boxBlurVertical(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int) {
        val invWindow = 1f / (2 * r + 1).toFloat()
        for (x in 0 until w) {
            var sum = src[x] * (r + 1)
            for (k in 1..r) {
                sum += src[min(k, h - 1) * w + x]
            }
            for (y in 0 until h) {
                dst[y * w + x] = sum * invWindow
                val addRow = min(y + r + 1, h - 1) * w + x
                val subRow = max(y - r, 0) * w + x
                sum += src[addRow] - src[subRow]
            }
        }
    }

    private fun applyBlendModeToPaint(paint: Paint, mode: LayerBlendMode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            paint.blendMode = when (mode) {
                LayerBlendMode.NORMAL -> BlendMode.SRC_OVER
                LayerBlendMode.MULTIPLY -> BlendMode.MULTIPLY
                LayerBlendMode.SCREEN -> BlendMode.SCREEN
                LayerBlendMode.OVERLAY -> BlendMode.OVERLAY
                LayerBlendMode.SOFT_LIGHT -> BlendMode.SOFT_LIGHT
            }
        } else {
            paint.xfermode = when (mode) {
                LayerBlendMode.NORMAL -> null
                LayerBlendMode.MULTIPLY -> PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
                LayerBlendMode.SCREEN -> PorterDuffXfermode(PorterDuff.Mode.SCREEN)
                LayerBlendMode.OVERLAY -> PorterDuffXfermode(PorterDuff.Mode.OVERLAY)
                LayerBlendMode.SOFT_LIGHT -> PorterDuffXfermode(PorterDuff.Mode.OVERLAY)
            }
        }
    }

    private fun blendBitmapOntoDestination(dst: Bitmap, src: Bitmap, mode: LayerBlendMode) {
        val w = dst.width
        val h = dst.height
        val total = w * h
        val dPx = IntArray(total)
        val sPx = IntArray(total)
        dst.getPixels(dPx, 0, w, 0, 0, w, h)
        src.getPixels(sPx, 0, w, 0, 0, w, h)
        for (i in 0 until total) {
            val s = sPx[i]
            val sa = ((s ushr 24) and 0xFF) / 255f
            if (sa <= 0f) continue
            val d = dPx[i]
            val da = ((d ushr 24) and 0xFF) / 255f
            val sr = ((s ushr 16) and 0xFF) / 255f
            val sg = ((s ushr 8) and 0xFF) / 255f
            val sb = (s and 0xFF) / 255f
            val dr = ((d ushr 16) and 0xFF) / 255f
            val dg = ((d ushr 8) and 0xFF) / 255f
            val db = (d and 0xFF) / 255f

            val br = blendChannel(dr, sr, mode)
            val bg = blendChannel(dg, sg, mode)
            val bb = blendChannel(db, sb, mode)

            val outA = sa + da * (1f - sa)
            if (outA <= 1e-5f) {
                dPx[i] = 0
            } else {
                val outR = ((1f - da) * sa * sr + (1f - sa) * da * dr + sa * da * br) / outA
                val outG = ((1f - da) * sa * sg + (1f - sa) * da * dg + sa * da * bg) / outA
                val outB = ((1f - da) * sa * sb + (1f - sa) * da * db + sa * da * bb) / outA
                dPx[i] = ((outA * 255f).roundToInt().coerceIn(0, 255) shl 24) or
                    ((outR * 255f).roundToInt().coerceIn(0, 255) shl 16) or
                    ((outG * 255f).roundToInt().coerceIn(0, 255) shl 8) or
                    (outB * 255f).roundToInt().coerceIn(0, 255)
            }
        }
        dst.setPixels(dPx, 0, w, 0, 0, w, h)
    }

    private fun blendChannel(cb: Float, cs: Float, mode: LayerBlendMode): Float {
        return when (mode) {
            LayerBlendMode.NORMAL -> cs
            LayerBlendMode.MULTIPLY -> cb * cs
            LayerBlendMode.SCREEN -> 1f - (1f - cb) * (1f - cs)
            LayerBlendMode.OVERLAY -> if (cb < 0.5f) 2f * cb * cs else 1f - 2f * (1f - cb) * (1f - cs)
            LayerBlendMode.SOFT_LIGHT -> {
                if (cs <= 0.5f) {
                    cb - (1f - 2f * cs) * cb * (1f - cb)
                } else {
                    val d = if (cb <= 0.25f) ((16f * cb - 12f) * cb + 4f) * cb else sqrt(cb)
                    cb + (2f * cs - 1f) * (d - cb)
                }
            }
        }.coerceIn(0f, 1f)
    }

    private suspend fun decodeLayerExportSource(
        context: Context,
        layer: ImageLayer,
        maxDim: Int
    ): Bitmap {
        val ref = layer.sourceRef ?: return layer.bitmap
        val decoded = runCatching {
            ImageIoHelper.loadNativeForExport(
                context = context,
                sourceRef = ref,
                targetMaxSourceDim = maxDim
            )
        }.getOrNull()
        return decoded?.takeIf { !it.isRecycled } ?: layer.bitmap
    }

    fun applyMaskBitmap(source: Bitmap, mask: Bitmap): Bitmap {
        val w = source.width.coerceAtLeast(1)
        val h = source.height.coerceAtLeast(1)
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawBitmap(source, 0f, 0f, null)

        val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        val scaledMask = if (mask.width != w || mask.height != h) {
            Bitmap.createScaledBitmap(mask, w, h, true)
        } else {
            mask
        }
        canvas.drawBitmap(scaledMask, 0f, 0f, maskPaint)
        if (scaledMask !== mask && !scaledMask.isRecycled) {
            scaledMask.recycle()
        }
        return result
    }

    private fun downscaleIfNeeded(src: Bitmap, maxDim: Int): Bitmap {
        val longest = max(src.width, src.height)
        if (longest <= maxDim || longest <= 0) return src
        val ratio = maxDim.toFloat() / longest.toFloat()
        val dstW = (src.width * ratio).roundToInt().coerceAtLeast(1)
        val dstH = (src.height * ratio).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, dstW, dstH, true)
    }
}
