package com.zamalfajji.zamlab.lut

import androidx.compose.runtime.Immutable
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import kotlin.math.floor

enum class LutType {
    LUT_3D,
    LUT_1D
}

enum class LutSourceFormat {
    CUBE,
    XMP
}

@Immutable
data class CubeLut(
    val id: String,
    val title: String,
    val type: LutType,
    val size: Int,
    val domainMin: FloatArray = floatArrayOf(0f, 0f, 0f),
    val domainMax: FloatArray = floatArrayOf(1f, 1f, 1f),
    val table: FloatArray, // Packed RGB triplets: size^3 * 3 for 3D, size * 3 for 1D
    val sourceFormat: LutSourceFormat = LutSourceFormat.CUBE,
    val category: String = "",
    val xmpSettings: XmpDevelopSettings? = null
) {
    /**
     * Evaluates the LUT for normalized [0..1] RGB input using trilinear (3D) or linear (1D) interpolation.
     * Writes result into [outRgb] (length >= 3) to avoid heap allocations per pixel.
     */
    fun sample(rIn: Float, gIn: Float, bIn: Float, outRgb: FloatArray) {
        if (outRgb.size < 3) return
        val safeR = if (rIn.isFinite()) rIn else 0f
        val safeG = if (gIn.isFinite()) gIn else 0f
        val safeB = if (bIn.isFinite()) bIn else 0f
        if (size < 2) {
            outRgb[0] = safeR.coerceIn(0f, 1f)
            outRgb[1] = safeG.coerceIn(0f, 1f)
            outRgb[2] = safeB.coerceIn(0f, 1f)
            return
        }
        val dMin0 = domainMin.getOrNull(0)?.takeIf { it.isFinite() } ?: 0f
        val dMin1 = domainMin.getOrNull(1)?.takeIf { it.isFinite() } ?: 0f
        val dMin2 = domainMin.getOrNull(2)?.takeIf { it.isFinite() } ?: 0f
        val dMax0 = domainMax.getOrNull(0)?.takeIf { it.isFinite() } ?: 1f
        val dMax1 = domainMax.getOrNull(1)?.takeIf { it.isFinite() } ?: 1f
        val dMax2 = domainMax.getOrNull(2)?.takeIf { it.isFinite() } ?: 1f

        val nr = ((safeR - dMin0) / (dMax0 - dMin0).coerceAtLeast(1e-5f)).coerceIn(0f, 1f)
        val ng = ((safeG - dMin1) / (dMax1 - dMin1).coerceAtLeast(1e-5f)).coerceIn(0f, 1f)
        val nb = ((safeB - dMin2) / (dMax2 - dMin2).coerceAtLeast(1e-5f)).coerceIn(0f, 1f)

        if (type == LutType.LUT_1D) {
            if (table.size < size * 3) {
                outRgb[0] = nr
                outRgb[1] = ng
                outRgb[2] = nb
                return
            }
            val maxIdx = (size - 1).toFloat()
            outRgb[0] = sample1DChannel(nr * maxIdx, 0)
            outRgb[1] = sample1DChannel(ng * maxIdx, 1)
            outRgb[2] = sample1DChannel(nb * maxIdx, 2)
            return
        }

        if (table.size < size * size * size * 3) {
            outRgb[0] = nr
            outRgb[1] = ng
            outRgb[2] = nb
            return
        }

        val maxIdx = (size - 1).toFloat()
        val fr = nr * maxIdx
        val fg = ng * maxIdx
        val fb = nb * maxIdx

        val r0 = floor(fr).toInt().coerceIn(0, size - 1)
        val g0 = floor(fg).toInt().coerceIn(0, size - 1)
        val b0 = floor(fb).toInt().coerceIn(0, size - 1)
        val r1 = (r0 + 1).coerceAtMost(size - 1)
        val g1 = (g0 + 1).coerceAtMost(size - 1)
        val b1 = (b0 + 1).coerceAtMost(size - 1)

        val dr = fr - r0
        val dg = fg - g0
        val db = fb - b0

        val s = size
        val s2 = s * s

        val i000 = (r0 + g0 * s + b0 * s2) * 3
        val i100 = (r1 + g0 * s + b0 * s2) * 3
        val i010 = (r0 + g1 * s + b0 * s2) * 3
        val i110 = (r1 + g1 * s + b0 * s2) * 3
        val i001 = (r0 + g0 * s + b1 * s2) * 3
        val i101 = (r1 + g0 * s + b1 * s2) * 3
        val i011 = (r0 + g1 * s + b1 * s2) * 3
        val i111 = (r1 + g1 * s + b1 * s2) * 3

        val t = table
        for (c in 0..2) {
            val c00 = t[i000 + c] + (t[i100 + c] - t[i000 + c]) * dr
            val c10 = t[i010 + c] + (t[i110 + c] - t[i010 + c]) * dr
            val c01 = t[i001 + c] + (t[i101 + c] - t[i001 + c]) * dr
            val c11 = t[i011 + c] + (t[i111 + c] - t[i011 + c]) * dr

            val c0 = c00 + (c10 - c00) * dg
            val c1 = c01 + (c11 - c01) * dg

            outRgb[c] = (c0 + (c1 - c0) * db).coerceIn(0f, 1f)
        }
    }

    private fun sample1DChannel(pos: Float, channelOffset: Int): Float {
        val idx0 = floor(pos).toInt().coerceIn(0, size - 1)
        val idx1 = (idx0 + 1).coerceAtMost(size - 1)
        val frac = pos - idx0
        val v0 = table[idx0 * 3 + channelOffset]
        val v1 = table[idx1 * 3 + channelOffset]
        return (v0 + (v1 - v0) * frac).coerceIn(0f, 1f)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CubeLut) return false
        return id == other.id && title == other.title && type == other.type && size == other.size
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + size
        return result
    }
}

object CubeLutParser {

    fun parse(
        inputStream: InputStream,
        id: String,
        fallbackTitle: String,
        category: String = "LUTs"
    ): CubeLut {
        var title = fallbackTitle
        var lutType: LutType? = null
        var lutSize = 0
        val domainMin = floatArrayOf(0f, 0f, 0f)
        val domainMax = floatArrayOf(1f, 1f, 1f)
        val values = ArrayList<Float>(33 * 33 * 33 * 3)

        val reader = BufferedReader(InputStreamReader(inputStream))
        val whitespaceRegex = Regex("\\s+")

        reader.useLines { lines ->
            for (rawLine in lines) {
                val commentIdx = rawLine.indexOf('#')
                val clean = (if (commentIdx >= 0) rawLine.substring(0, commentIdx) else rawLine).trim()
                if (clean.isEmpty()) continue

                if (clean.startsWith("TITLE", ignoreCase = true)) {
                    val parsedTitle = clean.substring(5).trim().trim('"').trim()
                    if (parsedTitle.isNotEmpty()) title = parsedTitle
                    continue
                }
                if (clean.startsWith("LUT_3D_SIZE", ignoreCase = true)) {
                    lutType = LutType.LUT_3D
                    lutSize = clean.substring(11).trim().toIntOrNull()
                        ?: throw IllegalArgumentException("Invalid LUT_3D_SIZE value.")
                    continue
                }
                if (clean.startsWith("LUT_1D_SIZE", ignoreCase = true)) {
                    lutType = LutType.LUT_1D
                    lutSize = clean.substring(11).trim().toIntOrNull()
                        ?: throw IllegalArgumentException("Invalid LUT_1D_SIZE value.")
                    continue
                }
                if (clean.startsWith("DOMAIN_MIN", ignoreCase = true)) {
                    val parts = clean.split(whitespaceRegex)
                    if (parts.size >= 4) {
                        domainMin[0] = parts[1].toFloatOrNull()?.takeIf { it.isFinite() } ?: 0f
                        domainMin[1] = parts[2].toFloatOrNull()?.takeIf { it.isFinite() } ?: 0f
                        domainMin[2] = parts[3].toFloatOrNull()?.takeIf { it.isFinite() } ?: 0f
                    }
                    continue
                }
                if (clean.startsWith("DOMAIN_MAX", ignoreCase = true)) {
                    val parts = clean.split(whitespaceRegex)
                    if (parts.size >= 4) {
                        domainMax[0] = parts[1].toFloatOrNull()?.takeIf { it.isFinite() } ?: 1f
                        domainMax[1] = parts[2].toFloatOrNull()?.takeIf { it.isFinite() } ?: 1f
                        domainMax[2] = parts[3].toFloatOrNull()?.takeIf { it.isFinite() } ?: 1f
                    }
                    continue
                }
                if (clean.startsWith("LUT_3D_INPUT_RANGE", ignoreCase = true) ||
                    clean.startsWith("LUT_1D_INPUT_RANGE", ignoreCase = true)
                ) {
                    continue
                }

                // Data triplet line: R G B
                val parts = clean.split(whitespaceRegex)
                if (parts.size >= 3) {
                    val r = parts[0].toFloatOrNull()?.takeIf { it.isFinite() }
                    val g = parts[1].toFloatOrNull()?.takeIf { it.isFinite() }
                    val b = parts[2].toFloatOrNull()?.takeIf { it.isFinite() }
                    if (r != null && g != null && b != null) {
                        values.add(r)
                        values.add(g)
                        values.add(b)
                    }
                }
            }
        }

        val resolvedType = lutType
            ?: throw IllegalArgumentException("Missing LUT_3D_SIZE or LUT_1D_SIZE header in .cube file.")
        val maxLutSize = if (resolvedType == LutType.LUT_3D) 65 else 256
        if (lutSize < 2 || lutSize > maxLutSize) {
            throw IllegalArgumentException("Unsupported LUT size: $lutSize (max $maxLutSize).")
        }

        val expectedTriplets = when (resolvedType) {
            LutType.LUT_3D -> lutSize * lutSize * lutSize
            LutType.LUT_1D -> lutSize
        }
        val expectedFloats = expectedTriplets * 3
        if (values.size < expectedFloats) {
            throw IllegalArgumentException(
                "Incomplete .cube LUT data: expected $expectedTriplets RGB entries, found ${values.size / 3}."
            )
        }

        val table = FloatArray(expectedFloats) { idx -> values[idx] }
        return CubeLut(
            id = id,
            title = title,
            type = resolvedType,
            size = lutSize,
            domainMin = domainMin,
            domainMax = domainMax,
            table = table,
            category = category
        )
    }
}
