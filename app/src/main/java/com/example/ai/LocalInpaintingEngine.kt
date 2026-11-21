package com.example.ai

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.example.editor.HealStroke
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Offline, structure-aware object removal engine with strict pixel preservation:
 *
 * 1. Crops a padded square bounding box around the masked object directly from the full-resolution
 *    photograph (never re-encoding or blurring pixels outside the brush mask).
 * 2. Runs a 3-level coarse-to-fine Directional Edge-Aware PatchMatch (comparing both RGB color and
 *    horizontal/vertical luminance gradients so cobblestones, walls, and lines stay crisp) +
 *    sharpened exemplar transfer.
 * 3. Solves a Red-Black Gauss-Seidel SOR Poisson gradient-domain seamless blend anchored to the
 *    exact surrounding unmasked pixels so shadows and lighting match smoothly.
 * 4. Composites only the synthesized masked area back onto the original full-resolution bitmap
 *    using a smooth 3-pixel alpha feather around the mask boundary, keeping 100% of surrounding
 *    pixels untouched and razor-sharp.
 */
object LocalInpaintingEngine {

    private const val CROP_WORK_MAX_DIM = 448
    private const val BOUNDARY_FEATHER_PX = 3.0f

    private class PyramidLevel(
        val width: Int,
        val height: Int,
        val r: FloatArray,
        val g: FloatArray,
        val b: FloatArray,
        val gx: FloatArray,
        val gy: FloatArray,
        val mask: BooleanArray
    )

    fun inpaint(
        sourceBitmap: Bitmap,
        strokes: List<HealStroke>
    ): Bitmap {
        val srcW = sourceBitmap.width
        val srcH = sourceBitmap.height
        val fullMask = rasterizeAlphaMask(srcW, srcH, strokes, defaultFeatherPx = BOUNDARY_FEATHER_PX)
        val effectiveOpacity = strokes.maxOfOrNull { it.opacity.coerceIn(0.10f, 1.0f) } ?: 1.0f

        // 1. Find exact full-resolution mask bounding box
        var fullMinX = srcW
        var fullMaxX = -1
        var fullMinY = srcH
        var fullMaxY = -1
        for (y in 0 until srcH) {
            val rowOff = y * srcW
            for (x in 0 until srcW) {
                if (fullMask[rowOff + x] > 0) {
                    if (x < fullMinX) fullMinX = x
                    if (x > fullMaxX) fullMaxX = x
                    if (y < fullMinY) fullMinY = y
                    if (y > fullMaxY) fullMaxY = y
                }
            }
        }
        if (fullMaxX < fullMinX || fullMaxY < fullMinY) {
            return sourceBitmap.copy(Bitmap.Config.ARGB_8888, false)
        }

        // 2. Smart padded square bounding-box crop around the masked object
        val holeW = fullMaxX - fullMinX + 1
        val holeH = fullMaxY - fullMinY + 1
        val holeSpan = max(holeW, holeH)
        val pad = max(36, (holeSpan * 0.75f).roundToInt())
        val targetSquareSide = holeSpan + pad * 2
        val halfSide = targetSquareSide / 2
        val centerX = fullMinX + holeW / 2
        val centerY = fullMinY + holeH / 2

        val sqX0 = (centerX - halfSide).coerceIn(0, max(0, srcW - 1))
        val sqY0 = (centerY - halfSide).coerceIn(0, max(0, srcH - 1))
        val sqX1 = (sqX0 + targetSquareSide - 1).coerceIn(sqX0, srcW - 1)
        val sqY1 = (sqY0 + targetSquareSide - 1).coerceIn(sqY0, srcH - 1)
        val sqW = sqX1 - sqX0 + 1
        val sqH = sqY1 - sqY0 + 1

        // Read ONLY the cropped square pixels directly from full-res sourceBitmap
        val cropOrigPixels = IntArray(sqW * sqH)
        sourceBitmap.getPixels(cropOrigPixels, 0, sqW, sqX0, sqY0, sqW, sqH)

        // Extract binary mask inside the full-res square crop (using threshold inside feather)
        val cropOrigMask = BooleanArray(sqW * sqH) { i ->
            val lx = i % sqW
            val ly = i / sqW
            fullMask[(sqY0 + ly) * srcW + (sqX0 + lx)] > 32
        }

        // Operate at native 1:1 pixel resolution whenever crop fits cleanly; otherwise scale just the crop
        val workScale = min(1f, CROP_WORK_MAX_DIM.toFloat() / max(sqW, sqH).toFloat())
        val workW = max(8, (sqW * workScale).roundToInt())
        val workH = max(8, (sqH * workScale).roundToInt())
        val workLen = workW * workH

        val workR = FloatArray(workLen)
        val workG = FloatArray(workLen)
        val workB = FloatArray(workLen)
        val workMask = BooleanArray(workLen)

        if (workW == sqW && workH == sqH) {
            for (i in 0 until workLen) {
                val px = cropOrigPixels[i]
                workR[i] = ((px ushr 16) and 0xFF).toFloat()
                workG[i] = ((px ushr 8) and 0xFF).toFloat()
                workB[i] = (px and 0xFF).toFloat()
                workMask[i] = cropOrigMask[i]
            }
        } else {
            val cropSrcBmp = createBitmap(sqW, sqH).apply {
                setPixels(cropOrigPixels, 0, sqW, 0, 0, sqW, sqH)
            }
            val scaledWorkBmp = cropSrcBmp.scale(workW, workH)
            val scaledPixels = IntArray(workLen)
            scaledWorkBmp.getPixels(scaledPixels, 0, workW, 0, 0, workW, workH)
            for (wy in 0 until workH) {
                val sy = ((wy + 0.5f) * sqH / workH).toInt().coerceIn(0, sqH - 1)
                val rowOff = wy * workW
                for (wx in 0 until workW) {
                    val sx = ((wx + 0.5f) * sqW / workW).toInt().coerceIn(0, sqW - 1)
                    val idx = rowOff + wx
                    val px = scaledPixels[idx]
                    workR[idx] = ((px ushr 16) and 0xFF).toFloat()
                    workG[idx] = ((px ushr 8) and 0xFF).toFloat()
                    workB[idx] = (px and 0xFF).toFloat()
                    workMask[idx] = cropOrigMask[sy * sqW + sx]
                }
            }
        }

        val baseLevel = PyramidLevel(
            width = workW,
            height = workH,
            r = workR,
            g = workG,
            b = workB,
            gx = FloatArray(workLen),
            gy = FloatArray(workLen),
            mask = workMask
        )
        recomputeGradients(baseLevel)

        // 3. Directional Edge-Aware Multi-Scale PatchMatch + Sharp Exemplar Synthesis
        runEdgeAwarePatchMatch(baseLevel)

        // 4. Gradient-domain Poisson seamless shadow/lighting blend anchored to untouched boundary
        runPoissonSeamlessBlend(baseLevel)

        // Map synthesized pixels cleanly back into the full-res square crop (high-res unscaled if 1:1)
        val synthesizedCropPixels = if (workW == sqW && workH == sqH) {
            IntArray(workLen) { i ->
                if (!workMask[i]) {
                    cropOrigPixels[i]
                } else {
                    val a = (cropOrigPixels[i] ushr 24) and 0xFF
                    val ri = baseLevel.r[i].roundToInt().coerceIn(0, 255)
                    val gi = baseLevel.g[i].roundToInt().coerceIn(0, 255)
                    val bi = baseLevel.b[i].roundToInt().coerceIn(0, 255)
                    (a shl 24) or (ri shl 16) or (gi shl 8) or bi
                }
            }
        } else {
            val workOutPixels = IntArray(workLen) { i ->
                val ri = baseLevel.r[i].roundToInt().coerceIn(0, 255)
                val gi = baseLevel.g[i].roundToInt().coerceIn(0, 255)
                val bi = baseLevel.b[i].roundToInt().coerceIn(0, 255)
                (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
            }
            val workOutBmp = createBitmap(workW, workH).apply {
                setPixels(workOutPixels, 0, workW, 0, 0, workW, workH)
            }
            val upscaledCropBmp = workOutBmp.scale(sqW, sqH)
            IntArray(sqW * sqH).also { arr ->
                upscaledCropBmp.getPixels(arr, 0, sqW, 0, 0, sqW, sqH)
            }
        }

        // 5. Strict Pixel Preservation Composite:
        // Copy exact full-res original pixels everywhere, blending ONLY across the 3px alpha feather
        val finalPixels = IntArray(srcW * srcH)
        sourceBitmap.getPixels(finalPixels, 0, srcW, 0, 0, srcW, srcH)

        for (ly in 0 until sqH) {
            val fy = sqY0 + ly
            val fullRow = fy * srcW
            val cropRow = ly * sqW
            for (lx in 0 until sqW) {
                val fx = sqX0 + lx
                val fullIdx = fullRow + fx
                val m = fullMask[fullIdx]
                if (m <= 0) continue // 100% untouched original pixel outside mask

                val fillPx = synthesizedCropPixels[cropRow + lx]
                val effectiveM = (m * effectiveOpacity).coerceIn(0f, 255f)
                if (effectiveM >= 254.5f) {
                    val origA = finalPixels[fullIdx] and (0xFF shl 24)
                    finalPixels[fullIdx] = origA or (fillPx and 0x00FFFFFF)
                } else {
                    val t = effectiveM / 255f
                    val inv = 1f - t
                    val orig = finalPixels[fullIdx]
                    val a = (orig ushr 24) and 0xFF
                    val r = (((orig ushr 16) and 0xFF) * inv + ((fillPx ushr 16) and 0xFF) * t).roundToInt().coerceIn(0, 255)
                    val g = (((orig ushr 8) and 0xFF) * inv + ((fillPx ushr 8) and 0xFF) * t).roundToInt().coerceIn(0, 255)
                    val b = ((orig and 0xFF) * inv + (fillPx and 0xFF) * t).roundToInt().coerceIn(0, 255)
                    finalPixels[fullIdx] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }

        return createBitmap(srcW, srcH).apply {
            setPixels(finalPixels, 0, srcW, 0, 0, srcW, srcH)
        }
    }

    private fun runEdgeAwarePatchMatch(baseLevel: PyramidLevel) {
        val pyramid = buildPyramid(baseLevel, maxLevels = 3)
        val coarsest = pyramid.last()
        initializeDirectionalStructure(coarsest)
        recomputeGradients(coarsest)

        val rng = Random(0x5A31C0DE)
        var prevNnfX: IntArray? = null
        var prevNnfY: IntArray? = null
        var prevW = 0
        var prevH = 0

        for (lvlIndex in pyramid.lastIndex downTo 0) {
            val lvl = pyramid[lvlIndex]
            val w = lvl.width
            val h = lvl.height
            val total = w * h

            if (lvlIndex < pyramid.lastIndex) {
                val coarser = pyramid[lvlIndex + 1]
                upsampleColorsToMasked(coarser, lvl)
                recomputeGradients(lvl)
            }

            val patchR = if (lvlIndex == 0 && min(w, h) >= 24) 3 else 2
            if (w <= patchR * 2 + 2 || h <= patchR * 2 + 2) continue

            val validPatch = BooleanArray(total)
            val validIndicesTmp = IntArray(total)
            var validCount = 0
            for (y in patchR until h - patchR) {
                val rowOff = y * w
                for (x in patchR until w - patchR) {
                    var allKnown = true
                    loopY@ for (dy in -patchR..patchR) {
                        val pRow = (y + dy) * w
                        for (dx in -patchR..patchR) {
                            if (lvl.mask[pRow + x + dx]) {
                                allKnown = false
                                break@loopY
                            }
                        }
                    }
                    if (allKnown) {
                        val idx = rowOff + x
                        validPatch[idx] = true
                        validIndicesTmp[validCount++] = idx
                    }
                }
            }

            if (validCount == 0) {
                prevNnfX = null
                prevNnfY = null
                continue
            }
            val validSources = validIndicesTmp.copyOf(validCount)

            val nnfX = IntArray(total)
            val nnfY = IntArray(total)
            val nnfErr = FloatArray(total)

            for (y in 0 until h) {
                val rowOff = y * w
                for (x in 0 until w) {
                    val idx = rowOff + x
                    if (!lvl.mask[idx]) {
                        nnfX[idx] = x
                        nnfY[idx] = y
                        continue
                    }
                    var initX = -1
                    var initY = -1
                    if (prevNnfX != null && prevNnfY != null && prevW > 0 && prevH > 0) {
                        val cx = (x / 2).coerceIn(0, prevW - 1)
                        val cy = (y / 2).coerceIn(0, prevH - 1)
                        val cIdx = cy * prevW + cx
                        val candX = (prevNnfX[cIdx] * 2).coerceIn(patchR, w - 1 - patchR)
                        val candY = (prevNnfY[cIdx] * 2).coerceIn(patchR, h - 1 - patchR)
                        if (validPatch[candY * w + candX]) {
                            initX = candX
                            initY = candY
                        }
                    }
                    if (initX < 0) {
                        val sIdx = validSources[rng.nextInt(validCount)]
                        initX = sIdx % w
                        initY = sIdx / w
                    }
                    nnfX[idx] = initX
                    nnfY[idx] = initY
                }
            }

            val patchSize = patchR * 2 + 1
            val gaussWeights = buildGaussianKernel(patchR)
            val emPasses = if (lvlIndex == 0) 2 else 3
            val pmPasses = if (lvlIndex == 0) 4 else 5

            repeat(emPasses) { emStep ->
                recomputeGradients(lvl)

                for (y in patchR until h - patchR) {
                    val rowOff = y * w
                    for (x in patchR until w - patchR) {
                        val idx = rowOff + x
                        if (!lvl.mask[idx]) continue
                        nnfErr[idx] = edgeAwarePatchSsd(lvl, x, y, nnfX[idx], nnfY[idx], patchR)
                    }
                }

                // Bidirectional PatchMatch propagation + random search
                for (pass in 0 until pmPasses) {
                    val forward = (pass and 1) == 0
                    val yStart = if (forward) patchR else h - 1 - patchR
                    val yEnd = if (forward) h - patchR else patchR - 1
                    val step = if (forward) 1 else -1

                    var y = yStart
                    while (y != yEnd) {
                        var x = if (forward) patchR else w - 1 - patchR
                        val xEnd = if (forward) w - patchR else patchR - 1
                        while (x != xEnd) {
                            val idx = y * w + x
                            if (lvl.mask[idx]) {
                                var bestX = nnfX[idx]
                                var bestY = nnfY[idx]
                                var bestErr = nnfErr[idx]

                                // Horizontal propagation
                                val nxH = x - step
                                if (nxH in patchR until w - patchR) {
                                    val nIdx = y * w + nxH
                                    if (lvl.mask[nIdx]) {
                                        val candX = nnfX[nIdx] + step
                                        val candY = nnfY[nIdx]
                                        if (candX in patchR until w - patchR &&
                                            candY in patchR until h - patchR &&
                                            validPatch[candY * w + candX]
                                        ) {
                                            val err = edgeAwarePatchSsd(lvl, x, y, candX, candY, patchR, bestErr)
                                            if (err < bestErr) {
                                                bestErr = err
                                                bestX = candX
                                                bestY = candY
                                            }
                                        }
                                    }
                                }

                                // Vertical propagation
                                val nyV = y - step
                                if (nyV in patchR until h - patchR) {
                                    val nIdx = nyV * w + x
                                    if (lvl.mask[nIdx]) {
                                        val candX = nnfX[nIdx]
                                        val candY = nnfY[nIdx] + step
                                        if (candX in patchR until w - patchR &&
                                            candY in patchR until h - patchR &&
                                            validPatch[candY * w + candX]
                                        ) {
                                            val err = edgeAwarePatchSsd(lvl, x, y, candX, candY, patchR, bestErr)
                                            if (err < bestErr) {
                                                bestErr = err
                                                bestX = candX
                                                bestY = candY
                                            }
                                        }
                                    }
                                }

                                // Multi-scale random search around best offset
                                var radius = max(w, h) / 2
                                while (radius >= 1) {
                                    val candX = (bestX + rng.nextInt(-radius, radius + 1)).coerceIn(patchR, w - 1 - patchR)
                                    val candY = (bestY + rng.nextInt(-radius, radius + 1)).coerceIn(patchR, h - 1 - patchR)
                                    if (validPatch[candY * w + candX]) {
                                        val err = edgeAwarePatchSsd(lvl, x, y, candX, candY, patchR, bestErr)
                                        if (err < bestErr) {
                                            bestErr = err
                                            bestX = candX
                                            bestY = candY
                                        }
                                    }
                                    radius /= 2
                                }

                                // Global exemplar jump probe
                                val globalCand = validSources[rng.nextInt(validCount)]
                                val gx = globalCand % w
                                val gy = globalCand / w
                                val gErr = edgeAwarePatchSsd(lvl, x, y, gx, gy, patchR, bestErr)
                                if (gErr < bestErr) {
                                    bestErr = gErr
                                    bestX = gx
                                    bestY = gy
                                }

                                nnfX[idx] = bestX
                                nnfY[idx] = bestY
                                nnfErr[idx] = bestErr
                            }
                            x += step
                        }
                        y += step
                    }
                }

                // Sharpened Exemplar Reconstruction:
                // Use smooth Gaussian voting in coarse passes, and sharp weighted exemplar copy
                // on the final finest pass so cobbles, brick mortar, and edges stay crisp!
                val isFinalSharpenedPass = (lvlIndex == 0 && emStep == emPasses - 1)
                if (isFinalSharpenedPass) {
                    val sharpR = lvl.r.copyOf()
                    val sharpG = lvl.g.copyOf()
                    val sharpB = lvl.b.copyOf()
                    for (y in patchR until h - patchR) {
                        val rowOff = y * w
                        for (x in patchR until w - patchR) {
                            val idx = rowOff + x
                            if (!lvl.mask[idx]) continue
                            val sx = nnfX[idx]
                            val sy = nnfY[idx]
                            if (sx !in patchR until w - patchR || sy !in patchR until h - patchR) continue
                            val sIdx = sy * w + sx
                            // Crisp center copy blended slightly with 4-connected consistency to eliminate any checkerboard
                            val avgVoteR = 0.25f * (lvl.r[sIdx - 1] + lvl.r[sIdx + 1] + lvl.r[sIdx - w] + lvl.r[sIdx + w])
                            val detailR = lvl.r[sIdx] - avgVoteR
                            val avgVoteG = 0.25f * (lvl.g[sIdx - 1] + lvl.g[sIdx + 1] + lvl.g[sIdx - w] + lvl.g[sIdx + w])
                            val detailG = lvl.g[sIdx] - avgVoteG
                            val avgVoteB = 0.25f * (lvl.b[sIdx - 1] + lvl.b[sIdx + 1] + lvl.b[sIdx - w] + lvl.b[sIdx + w])
                            val detailB = lvl.b[sIdx] - avgVoteB

                            sharpR[idx] = (0.70f * lvl.r[sIdx] + 0.30f * lvl.r[idx] + 0.35f * detailR).coerceIn(0f, 255f)
                            sharpG[idx] = (0.70f * lvl.g[sIdx] + 0.30f * lvl.g[idx] + 0.35f * detailG).coerceIn(0f, 255f)
                            sharpB[idx] = (0.70f * lvl.b[sIdx] + 0.30f * lvl.b[idx] + 0.35f * detailB).coerceIn(0f, 255f)
                        }
                    }
                    sharpR.copyInto(lvl.r)
                    sharpG.copyInto(lvl.g)
                    sharpB.copyInto(lvl.b)
                } else {
                    val accR = FloatArray(total)
                    val accG = FloatArray(total)
                    val accB = FloatArray(total)
                    val accW = FloatArray(total)

                    for (py in patchR until h - patchR) {
                        val pRow = py * w
                        for (px in patchR until w - patchR) {
                            val pIdx = pRow + px
                            val sx = if (lvl.mask[pIdx]) nnfX[pIdx] else px
                            val sy = if (lvl.mask[pIdx]) nnfY[pIdx] else py
                            if (sx !in patchR until w - patchR || sy !in patchR until h - patchR) continue

                            val matchConf = if (!lvl.mask[pIdx]) {
                                2.4f
                            } else {
                                1.0f / (1.0f + nnfErr[pIdx] * 0.0003f)
                            }

                            for (dy in -patchR..patchR) {
                                val ty = py + dy
                                val targetRow = ty * w
                                val srcPatchRow = (sy + dy) * w
                                val kRow = (dy + patchR) * patchSize
                                for (dx in -patchR..patchR) {
                                    val tx = px + dx
                                    val tIdx = targetRow + tx
                                    if (!lvl.mask[tIdx]) continue
                                    val sIdx = srcPatchRow + sx + dx
                                    val wt = gaussWeights[kRow + dx + patchR] * matchConf
                                    accR[tIdx] += lvl.r[sIdx] * wt
                                    accG[tIdx] += lvl.g[sIdx] * wt
                                    accB[tIdx] += lvl.b[sIdx] * wt
                                    accW[tIdx] += wt
                                }
                            }
                        }
                    }

                    for (i in 0 until total) {
                        if (lvl.mask[i] && accW[i] > 1e-5f) {
                            val invW = 1f / accW[i]
                            lvl.r[i] = (accR[i] * invW).coerceIn(0f, 255f)
                            lvl.g[i] = (accG[i] * invW).coerceIn(0f, 255f)
                            lvl.b[i] = (accB[i] * invW).coerceIn(0f, 255f)
                        }
                    }
                }
            }

            prevNnfX = nnfX
            prevNnfY = nnfY
            prevW = w
            prevH = h
        }
    }

    /**
     * Gradient-Domain Poisson Seamless Solver (Red-Black Gauss-Seidel SOR):
     * Preserves all internal exemplar gradients (cobblestones, brick grain, texture)
     * while seamlessly matching lighting and shadow continuity to the untouched border.
     */
    private fun runPoissonSeamlessBlend(level: PyramidLevel) {
        val w = level.width
        val h = level.height
        val total = w * h

        val divR = FloatArray(total)
        val divG = FloatArray(total)
        val divB = FloatArray(total)

        // Compute internal-only guidance divergence from the synthesized exemplar field G;
        // cross-boundary edges get 0 gradient so the fill aligns smoothly with surrounding brightness
        for (y in 1 until h - 1) {
            val rowOff = y * w
            for (x in 1 until w - 1) {
                val idx = rowOff + x
                if (!level.mask[idx]) continue
                val nLeft = idx - 1
                val nRight = idx + 1
                val nUp = idx - w
                val nDown = idx + w

                var dR = 0f
                var dG = 0f
                var dB = 0f
                if (level.mask[nLeft]) {
                    dR += level.r[idx] - level.r[nLeft]
                    dG += level.g[idx] - level.g[nLeft]
                    dB += level.b[idx] - level.b[nLeft]
                }
                if (level.mask[nRight]) {
                    dR += level.r[idx] - level.r[nRight]
                    dG += level.g[idx] - level.g[nRight]
                    dB += level.b[idx] - level.b[nRight]
                }
                if (level.mask[nUp]) {
                    dR += level.r[idx] - level.r[nUp]
                    dG += level.g[idx] - level.g[nUp]
                    dB += level.b[idx] - level.b[nUp]
                }
                if (level.mask[nDown]) {
                    dR += level.r[idx] - level.r[nDown]
                    dG += level.g[idx] - level.g[nDown]
                    dB += level.b[idx] - level.b[nDown]
                }
                divR[idx] = dR
                divG[idx] = dG
                divB[idx] = dB
            }
        }

        val solR = level.r.copyOf()
        val solG = level.g.copyOf()
        val solB = level.b.copyOf()
        val omega = 1.65f
        val oneMinusOmega = 1f - omega

        // Red-Black Gauss-Seidel SOR converges ~3x faster than standard Jacobi
        repeat(36) {
            for (parity in 0..1) {
                for (y in 1 until h - 1) {
                    val rowOff = y * w
                    var x = 1 + ((y + parity) and 1)
                    while (x < w - 1) {
                        val idx = rowOff + x
                        if (level.mask[idx]) {
                            val targetR = 0.25f * (solR[idx - 1] + solR[idx + 1] + solR[idx - w] + solR[idx + w] + divR[idx])
                            val targetG = 0.25f * (solG[idx - 1] + solG[idx + 1] + solG[idx - w] + solG[idx + w] + divG[idx])
                            val targetB = 0.25f * (solB[idx - 1] + solB[idx + 1] + solB[idx - w] + solB[idx + w] + divB[idx])

                            solR[idx] = (oneMinusOmega * solR[idx] + omega * targetR).coerceIn(0f, 255f)
                            solG[idx] = (oneMinusOmega * solG[idx] + omega * targetG).coerceIn(0f, 255f)
                            solB[idx] = (oneMinusOmega * solB[idx] + omega * targetB).coerceIn(0f, 255f)
                        }
                        x += 2
                    }
                }
            }
        }

        // Preserve crisp exemplar contrast while anchoring lighting seamlessly
        for (i in 0 until total) {
            if (level.mask[i]) {
                level.r[i] = (0.75f * solR[i] + 0.25f * level.r[i]).coerceIn(0f, 255f)
                level.g[i] = (0.75f * solG[i] + 0.25f * level.g[i]).coerceIn(0f, 255f)
                level.b[i] = (0.75f * solB[i] + 0.25f * level.b[i]).coerceIn(0f, 255f)
            }
        }
    }

    private fun buildPyramid(base: PyramidLevel, maxLevels: Int): List<PyramidLevel> {
        val list = mutableListOf(base)
        var current = base
        while (list.size < maxLevels && min(current.width, current.height) >= 24) {
            val nextW = max(12, current.width / 2)
            val nextH = max(12, current.height / 2)
            val nextLen = nextW * nextH
            val nr = FloatArray(nextLen)
            val ng = FloatArray(nextLen)
            val nb = FloatArray(nextLen)
            val nm = BooleanArray(nextLen)

            for (y in 0 until nextH) {
                val fy0 = min(current.height - 1, y * 2)
                val fy1 = min(current.height - 1, fy0 + 1)
                for (x in 0 until nextW) {
                    val fx0 = min(current.width - 1, x * 2)
                    val fx1 = min(current.width - 1, fx0 + 1)
                    val coords = intArrayOf(
                        fy0 * current.width + fx0,
                        fy0 * current.width + fx1,
                        fy1 * current.width + fx0,
                        fy1 * current.width + fx1
                    )
                    var anyMasked = false
                    var knownCount = 0
                    var sr = 0f
                    var sg = 0f
                    var sb = 0f
                    for (ci in coords) {
                        if (current.mask[ci]) {
                            anyMasked = true
                        } else {
                            sr += current.r[ci]
                            sg += current.g[ci]
                            sb += current.b[ci]
                            knownCount++
                        }
                    }
                    val di = y * nextW + x
                    if (knownCount > 0 && !anyMasked) {
                        nr[di] = sr / knownCount
                        ng[di] = sg / knownCount
                        nb[di] = sb / knownCount
                        nm[di] = false
                    } else if (knownCount > 0) {
                        nr[di] = sr / knownCount
                        ng[di] = sg / knownCount
                        nb[di] = sb / knownCount
                        nm[di] = true
                    } else {
                        nr[di] = current.r[coords[0]]
                        ng[di] = current.g[coords[0]]
                        nb[di] = current.b[coords[0]]
                        nm[di] = true
                    }
                }
            }
            val nextLvl = PyramidLevel(
                width = nextW,
                height = nextH,
                r = nr,
                g = ng,
                b = nb,
                gx = FloatArray(nextLen),
                gy = FloatArray(nextLen),
                mask = nm
            )
            recomputeGradients(nextLvl)
            current = nextLvl
            list += current
        }
        return list
    }

    /**
     * Directional isophote-guided boundary propagation + harmonic smoothing at coarsest scale
     * so wall edges, horizon lines, and shadow boundaries bridge cleanly prior to PatchMatch.
     */
    private fun initializeDirectionalStructure(level: PyramidLevel) {
        val w = level.width
        val h = level.height
        val total = w * h
        val remaining = level.mask.copyOf()
        val front = IntArray(total)
        var remainingCount = remaining.count { it }
        val dx8 = intArrayOf(-1, 0, 1, -1, 1, -1, 0, 1)
        val dy8 = intArrayOf(-1, -1, -1, 0, 0, 1, 1, 1)

        while (remainingCount > 0) {
            var frontLen = 0
            for (y in 0 until h) {
                val rowOff = y * w
                for (x in 0 until w) {
                    val idx = rowOff + x
                    if (!remaining[idx]) continue
                    var hasKnown = false
                    for (n in 0 until 8) {
                        val nx = x + dx8[n]
                        val ny = y + dy8[n]
                        if (nx !in 0 until w || ny !in 0 until h || !remaining[ny * w + nx]) {
                            hasKnown = true
                            break
                        }
                    }
                    if (hasKnown) front[frontLen++] = idx
                }
            }
            if (frontLen == 0) break

            for (f in 0 until frontLen) {
                val idx = front[f]
                val x = idx % w
                val y = idx / w

                // Estimate boundary gradient direction for structure-aligned weights
                val gx = level.gx[idx]
                val gy = level.gy[idx]
                val isoX = -gy
                val isoY = gx
                val isoNorm = sqrt(isoX * isoX + isoY * isoY)

                var wSum = 0f
                var rSum = 0f
                var gSum = 0f
                var bSum = 0f
                for (n in 0 until 8) {
                    val nx = x + dx8[n]
                    val ny = y + dy8[n]
                    if (nx in 0 until w && ny in 0 until h) {
                        val nIdx = ny * w + nx
                        if (!remaining[nIdx]) {
                            val baseWt = if (dx8[n] == 0 || dy8[n] == 0) 1.414f else 1f
                            val dirWt = if (isoNorm > 1f) {
                                val dot = abs(dx8[n] * isoX + dy8[n] * isoY) / isoNorm
                                0.5f + 1.5f * dot
                            } else {
                                1f
                            }
                            val wt = baseWt * dirWt
                            rSum += level.r[nIdx] * wt
                            gSum += level.g[nIdx] * wt
                            bSum += level.b[nIdx] * wt
                            wSum += wt
                        }
                    }
                }
                if (wSum > 0f) {
                    level.r[idx] = rSum / wSum
                    level.g[idx] = gSum / wSum
                    level.b[idx] = bSum / wSum
                }
            }
            for (f in 0 until frontLen) remaining[front[f]] = false
            recomputeGradients(level)
            remainingCount -= frontLen
        }

        // Mild harmonic smoothing so lighting transitions softly before PatchMatch locks texture
        val nextR = level.r.copyOf()
        val nextG = level.g.copyOf()
        val nextB = level.b.copyOf()
        repeat(10) {
            for (y in 1 until h - 1) {
                val rowOff = y * w
                for (x in 1 until w - 1) {
                    val idx = rowOff + x
                    if (!level.mask[idx]) continue
                    nextR[idx] = 0.25f * (level.r[idx - 1] + level.r[idx + 1] + level.r[idx - w] + level.r[idx + w])
                    nextG[idx] = 0.25f * (level.g[idx - 1] + level.g[idx + 1] + level.g[idx - w] + level.g[idx + w])
                    nextB[idx] = 0.25f * (level.b[idx - 1] + level.b[idx + 1] + level.b[idx - w] + level.b[idx + w])
                }
            }
            for (i in 0 until total) {
                if (level.mask[i]) {
                    level.r[i] = nextR[i]
                    level.g[i] = nextG[i]
                    level.b[i] = nextB[i]
                }
            }
        }
    }

    private fun upsampleColorsToMasked(coarser: PyramidLevel, finer: PyramidLevel) {
        val cw = coarser.width
        val ch = coarser.height
        val fw = finer.width
        val fh = finer.height
        for (y in 0 until fh) {
            val cyF = (y * ch.toFloat() / fh.toFloat()).coerceIn(0f, (ch - 1).toFloat())
            val y0 = cyF.toInt()
            val y1 = min(ch - 1, y0 + 1)
            val ty = cyF - y0
            val rowOff = y * fw
            for (x in 0 until fw) {
                val idx = rowOff + x
                if (!finer.mask[idx]) continue
                val cxF = (x * cw.toFloat() / fw.toFloat()).coerceIn(0f, (cw - 1).toFloat())
                val x0 = cxF.toInt()
                val x1 = min(cw - 1, x0 + 1)
                val tx = cxF - x0

                val i00 = y0 * cw + x0
                val i10 = y0 * cw + x1
                val i01 = y1 * cw + x0
                val i11 = y1 * cw + x1
                val w00 = (1f - tx) * (1f - ty)
                val w10 = tx * (1f - ty)
                val w01 = (1f - tx) * ty
                val w11 = tx * ty

                finer.r[idx] = coarser.r[i00] * w00 + coarser.r[i10] * w10 + coarser.r[i01] * w01 + coarser.r[i11] * w11
                finer.g[idx] = coarser.g[i00] * w00 + coarser.g[i10] * w10 + coarser.g[i01] * w01 + coarser.g[i11] * w11
                finer.b[idx] = coarser.b[i00] * w00 + coarser.b[i10] * w10 + coarser.b[i01] * w01 + coarser.b[i11] * w11
            }
        }
    }

    private fun recomputeGradients(level: PyramidLevel) {
        val w = level.width
        val h = level.height
        for (y in 1 until h - 1) {
            val rowOff = y * w
            for (x in 1 until w - 1) {
                val idx = rowOff + x
                val lumaL = 0.299f * level.r[idx - 1] + 0.587f * level.g[idx - 1] + 0.114f * level.b[idx - 1]
                val lumaR = 0.299f * level.r[idx + 1] + 0.587f * level.g[idx + 1] + 0.114f * level.b[idx + 1]
                val lumaU = 0.299f * level.r[idx - w] + 0.587f * level.g[idx - w] + 0.114f * level.b[idx - w]
                val lumaD = 0.299f * level.r[idx + w] + 0.587f * level.g[idx + w] + 0.114f * level.b[idx + w]
                level.gx[idx] = 0.5f * (lumaR - lumaL)
                level.gy[idx] = 0.5f * (lumaD - lumaU)
            }
        }
    }

    private fun edgeAwarePatchSsd(
        level: PyramidLevel,
        tx: Int,
        ty: Int,
        sx: Int,
        sy: Int,
        patchR: Int,
        earlyOutLimit: Float = Float.POSITIVE_INFINITY
    ): Float {
        val w = level.width
        var ssd = 0f
        for (dy in -patchR..patchR) {
            val tRow = (ty + dy) * w
            val sRow = (sy + dy) * w
            for (dx in -patchR..patchR) {
                val tIdx = tRow + tx + dx
                val sIdx = sRow + sx + dx
                val dr = level.r[tIdx] - level.r[sIdx]
                val dg = level.g[tIdx] - level.g[sIdx]
                val db = level.b[tIdx] - level.b[sIdx]
                val dgx = level.gx[tIdx] - level.gx[sIdx]
                val dgy = level.gy[tIdx] - level.gy[sIdx]
                val anchorWt = if (!level.mask[tIdx]) 2.2f else 1.0f
                ssd += (dr * dr + dg * dg + db * db + 1.5f * (dgx * dgx + dgy * dgy)) * anchorWt
            }
            if (ssd >= earlyOutLimit) return ssd
        }
        return ssd
    }

    private fun buildGaussianKernel(patchR: Int): FloatArray {
        val size = patchR * 2 + 1
        val sigma = max(1f, patchR * 0.7f)
        val denom = 2f * sigma * sigma
        return FloatArray(size * size) { i ->
            val dx = (i % size) - patchR
            val dy = (i / size) - patchR
            exp(-(dx * dx + dy * dy).toFloat() / denom)
        }
    }

    /**
     * Platform-independent anti-aliased capsule rasterizer producing a crisp mask with an
     * exact smooth 3-pixel alpha feather around the brush boundary (so surrounding pixels keep
     * strict alpha = 0 and stay 100% untouched).
     */
    fun rasterizeAlphaMask(
        width: Int,
        height: Int,
        strokes: List<HealStroke>,
        defaultFeatherPx: Float = BOUNDARY_FEATHER_PX
    ): IntArray {
        val alpha = IntArray(width * height)
        if (width <= 0 || height <= 0 || strokes.isEmpty()) return alpha

        val minSide = minOf(width, height).toFloat()

        for (stroke in strokes) {
            if (stroke.points.isEmpty()) continue
            val radiusPx = (stroke.radiusNorm * minSide).coerceAtLeast(3f)
            val featherFrac = stroke.feather.coerceIn(0f, 1f)
            val aaHalf = (defaultFeatherPx * 0.5f).coerceIn(0.5f, max(1f, radiusPx * 0.45f))
            val outerR = radiusPx + aaHalf
            val innerR = if (featherFrac <= 0.02f) {
                max(0f, radiusPx - aaHalf)
            } else {
                max(0f, (radiusPx - aaHalf) * (1f - featherFrac * 0.85f))
            }
            val outerRSq = outerR * outerR
            val featherSpan = (outerR - innerR).coerceAtLeast(1f)

            var sMinX = Float.POSITIVE_INFINITY
            var sMaxX = Float.NEGATIVE_INFINITY
            var sMinY = Float.POSITIVE_INFINITY
            var sMaxY = Float.NEGATIVE_INFINITY
            for ((nx, ny) in stroke.points) {
                val px = nx * width
                val py = ny * height
                if (px < sMinX) sMinX = px
                if (px > sMaxX) sMaxX = px
                if (py < sMinY) sMinY = py
                if (py > sMaxY) sMaxY = py
            }

            val bx0 = max(0, floor(sMinX - outerR - 1f).toInt())
            val bx1 = min(width - 1, ceil(sMaxX + outerR + 1f).toInt())
            val by0 = max(0, floor(sMinY - outerR - 1f).toInt())
            val by1 = min(height - 1, ceil(sMaxY + outerR + 1f).toInt())
            if (bx0 > bx1 || by0 > by1) continue

            val pts = stroke.points
            for (py in by0..by1) {
                val cy = py + 0.5f
                val rowOff = py * width
                for (px in bx0..bx1) {
                    val cx = px + 0.5f
                    var minDistSq = Float.POSITIVE_INFINITY
                    if (pts.size == 1) {
                        val dx = cx - pts[0].first * width
                        val dy = cy - pts[0].second * height
                        minDistSq = dx * dx + dy * dy
                    } else {
                        for (i in 0 until pts.size - 1) {
                            val x0 = pts[i].first * width
                            val y0 = pts[i].second * height
                            val x1 = pts[i + 1].first * width
                            val y1 = pts[i + 1].second * height
                            val segDx = x1 - x0
                            val segDy = y1 - y0
                            val lenSq = segDx * segDx + segDy * segDy
                            val t = if (lenSq <= 1e-6f) {
                                0f
                            } else {
                                (((cx - x0) * segDx + (cy - y0) * segDy) / lenSq).coerceIn(0f, 1f)
                            }
                            val projX = x0 + t * segDx
                            val projY = y0 + t * segDy
                            val dSq = (cx - projX) * (cx - projX) + (cy - projY) * (cy - projY)
                            if (dSq < minDistSq) minDistSq = dSq
                        }
                    }

                    if (minDistSq < outerRSq) {
                        val dist = sqrt(minDistSq)
                        val linearCov = ((outerR - dist) / featherSpan).coerceIn(0f, 1f)
                        val coverage = linearCov * linearCov * (3f - 2f * linearCov)
                        val idx = rowOff + px
                        val covInt = (coverage * 255f).roundToInt().coerceIn(0, 255)
                        if (covInt > alpha[idx]) {
                            alpha[idx] = covInt
                        }
                    }
                }
            }
        }
        return alpha
    }
}
