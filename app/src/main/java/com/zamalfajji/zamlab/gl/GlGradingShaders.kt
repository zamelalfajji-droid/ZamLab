package com.zamalfajji.zamlab.gl

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import androidx.core.graphics.createBitmap
import com.zamalfajji.zamlab.editor.CameraProfiles
import com.zamalfajji.zamlab.editor.GeometryMath
import com.zamalfajji.zamlab.editor.GradingParams
import com.zamalfajji.zamlab.editor.HslBand
import com.zamalfajji.zamlab.editor.MaskType
import com.zamalfajji.zamlab.editor.ToneCurveData
import com.zamalfajji.zamlab.lut.CubeLut
import com.zamalfajji.zamlab.lut.LutType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Two-pass OpenGL ES 2.0 real-time photo grading shader pipeline.
 *
 * Processing architecture (deterministic, physically accurate 6-step order):
 * - **Pass 1 (`TONE_COLOR_FRAGMENT_SHADER` -> Offscreen FBO `uGradedTex`)**:
 *   - Geometry / crop / perspective / lens distortion / chromatic aberration / lens blur
 *   - Camera Profile tone & matrix shaping
 *   - Step 1: Exact IEC 61966-2-1 sRGB -> Linear light conversion
 *   - Step 2: Photographic Exposure in linear light: `color *= pow(2.0, exposureEV)`
 *   - Step 3: White Balance (Temp & Tint) using luminance-balanced linear channel multipliers
 *   - Step 4: Smooth tonal curves for Highlights, Shadows, Whites, Blacks, Parametric Tones,
 *             C1-continuous soft highlight shoulder roll-off, and smooth S-curve Contrast with
 *             soft toe & shoulder protection
 *   - Step 5: Exact Linear -> sRGB display conversion, Tone Curves (`uCurveTex`), RGB Calibration,
 *             8-band HSL Mixer, Point Color, Grayscale OR skin-tone & saturation-protected Vibrance
 *             + Saturation, Split Toning, Color Grading Wheels, and up to 4 Selective Local Masks
 *
 * - **Pass 2 (`SPATIAL_FINISH_FRAGMENT_SHADER` reading Pass-1 FBO `uGradedTex` -> Screen/Pbuffer)**:
 *   - Step 6: Real multi-pixel neighborhood spatial filtering on the ACTUAL graded pixels:
 *     - Edge-aware Luminance & Color Noise Reduction
 *     - Dehaze: 9-tap local dark-channel atmospheric veil estimation + radiance & saturation recovery
 *     - Texture: Small-radius 9-tap Gaussian blur difference (`c - blurSmall`) with edge-halo guard
 *     - Clarity: Wider-radius 13-tap Gaussian blur difference (`c - blurLarge`) weighted by a smooth
 *       midtone mask protecting pure blacks and pure whites
 *     - Sharpen: 9-tap Gaussian unsharp mask + 5-tap Laplacian with flat-area noise threshold protection
 *   - Finishing passes: Vignette, Film Grain, 3D/1D `.cube` LUT, Film Halation & Bloom,
 *     Anamorphic Streak, and pixel-exact Before/After original & split-compare reading `uRawTex`.
 */
object GlGradingShaders {

    /**
     * Computes luminance-balanced linear-space White Balance channel multipliers for
     * `tempNorm` (-1..+1) and `tintNorm` (-1..+1). Shared 1:1 with [com.zamalfajji.zamlab.editor.ColorGradingEngine].
     */
    fun computeLinearWhiteBalanceMultipliers(tempNorm: Float, tintNorm: Float, out: FloatArray) {
        val t = tempNorm.coerceIn(-1f, 1f)
        val g = tintNorm.coerceIn(-1f, 1f)
        if (abs(t) <= 0.0005f && abs(g) <= 0.0005f) {
            out[0] = 1f
            out[1] = 1f
            out[2] = 1f
            return
        }
        val rawR = exp(t * 0.42f + g * 0.14f)
        val rawG = exp(t * 0.04f - g * 0.28f)
        val rawB = exp(-t * 0.46f + g * 0.14f)
        val lumaNorm = (0.2126f * rawR + 0.7152f * rawG + 0.0722f * rawB).coerceAtLeast(1e-4f)
        out[0] = rawR / lumaNorm
        out[1] = rawG / lumaNorm
        out[2] = rawB / lumaNorm
    }

    /**
     * Computes the C1-continuous soft highlight shoulder knee in linear light space.
     * Returns `1.0` when there is no positive linear overdrive (guaranteeing bit-exact unedited
     * `[0, 1]` passthrough) and smoothly lowers toward `0.78` under heavy exposure/highlight/white
     * gain so bright skies preserve smooth gradations instead of hard clipping.
     */
    fun computeSoftShoulderKnee(
        exposureEV: Float,
        wbMaxMul: Float,
        highlightsNorm: Float,
        whitesNorm: Float
    ): Float {
        val maxLinearGain = 2f.pow(exposureEV.coerceAtLeast(0f)) *
            wbMaxMul.coerceAtLeast(1f) *
            2f.pow(highlightsNorm.coerceAtLeast(0f) * 0.85f + whitesNorm.coerceAtLeast(0f) * 0.70f)
        val overdrive = (maxLinearGain - 1f).coerceAtLeast(0f)
        if (overdrive <= 0.001f) return 1f
        val strength = (overdrive / (overdrive + 1.25f)).coerceIn(0f, 1f)
        return 1f - 0.22f * strength
    }

    private const val VERTEX_SHADER = """
        attribute vec2 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            vTexCoord = aTexCoord;
            gl_Position = vec4(aPosition, 0.0, 1.0);
        }
    """

    /**
     * Pass 1 Fragment Shader: Geometry, Optics, Camera Profile, and Steps 1–5 (Tone & Color).
     * Compact packed-vec4 uniforms (~47 vec4 total, zero dynamic array indexing) for rock-solid
     * compatibility across all OpenGL ES 2.0 drivers and software emulators.
     */
    private const val TONE_COLOR_FRAGMENT_SHADER = """
        #ifdef GL_FRAGMENT_PRECISION_HIGH
        precision highp float;
        #else
        precision mediump float;
        #endif

        varying vec2 vTexCoord;

        uniform sampler2D uImageTex;
        uniform sampler2D uCurveTex;

        uniform mat3 uOrientMatrix;
        uniform vec4 uCropRect;        // (left, top, right, bottom)
        uniform vec4 uOptics;          // (distortion, caNorm, lensBlur, hasProfile)
        uniform vec4 uImageInfo;       // (invW, invH, outAspect, hasCurve)

        uniform vec4 uProfTone;        // (contrast*st, shadowLift*st, hiCompress*st, midPunch*st)
        uniform vec4 uProfGain;        // (1+rGain*st, 1+gGain*st, 1+bGain*st, 0)
        uniform vec4 uProfColor;       // (satDelta*st, vibDelta*st, monoMix, 0)
        uniform vec4 uProfMono;        // (monoW.rgb, 0)
        uniform vec4 uProfShCast;      // (shBias.rgb * st, 0)
        uniform vec4 uProfHiCast;      // (hiBias.rgb * st, 0)

        uniform vec4 uToneA;           // (exposureEV, tempNorm, tintNorm, contrastNorm)
        uniform vec4 uToneB;           // (highlightsNorm, shadowsNorm, whitesNorm, blacksNorm)
        uniform vec4 uWbMult;          // (wbR, wbG, wbB, shoulderKnee)
        uniform vec4 uParamTone;       // (paramHi, paramLights, paramDarks, paramSh)
        uniform vec4 uColorA;          // (vibranceNorm, saturationNorm, isGrayscale, 0)

        uniform vec4 uCalibA;          // (redHue, redSat, greenHue, greenSat)
        uniform vec4 uCalibB;          // (blueHue, blueSat, splitBalance, hasCal)
        uniform vec4 uSplitSh;         // (shTint.rgb, shAmt)
        uniform vec4 uSplitHi;         // (hiTint.rgb, hiAmt)

        uniform vec4 uWheelSh;         // (tint.rgb, lum)
        uniform vec4 uWheelMid;        // (tint.rgb, lum)
        uniform vec4 uWheelHi;         // (tint.rgb, lum)
        uniform vec4 uWheelGl;         // (tint.rgb, lum)
        uniform vec4 uWheelMix;        // (blending, balance, hasWheels, hasHsl)

        uniform vec4 uPointA;          // (sourceHueDeg, sourceSat, sourceLuma, rangeNorm)
        uniform vec4 uPointB;          // (hueShift, satShift, lumShift, hasPoint)

        uniform vec4 uHsl0;            // Red (hue, sat, lum, 0)
        uniform vec4 uHsl1;            // Orange
        uniform vec4 uHsl2;            // Yellow
        uniform vec4 uHsl3;            // Green
        uniform vec4 uHsl4;            // Aqua
        uniform vec4 uHsl5;            // Blue
        uniform vec4 uHsl6;            // Purple
        uniform vec4 uHsl7;            // Magenta

        uniform vec4 uMaskMeta;        // (maskCount, 0, 0, 0)
        uniform vec4 uMask0A;          // (type, inverted, feather, exposureEV)
        uniform vec4 uMask0B;          // (p0, p1, p2, p3)
        uniform vec4 uMask0C;          // (contrast, warmth, saturation, sharpness)

        uniform vec4 uMask1A;
        uniform vec4 uMask1B;
        uniform vec4 uMask1C;

        uniform vec4 uMask2A;
        uniform vec4 uMask2B;
        uniform vec4 uMask2C;

        uniform vec4 uMask3A;
        uniform vec4 uMask3B;
        uniform vec4 uMask3C;

        vec3 srgbToLinear(vec3 c) {
            vec3 cc = clamp(c, 0.0, 1.0);
            vec3 lo = cc / 12.92;
            vec3 hi = pow(max((cc + 0.055) / 1.055, vec3(1e-6)), vec3(2.4));
            return mix(lo, hi, step(vec3(0.04045), cc));
        }

        vec3 linearToSrgb(vec3 c) {
            vec3 cc = max(c, vec3(0.0));
            vec3 lo = cc * 12.92;
            vec3 hi = 1.055 * pow(max(cc, vec3(1e-6)), vec3(1.0 / 2.4)) - 0.055;
            return clamp(mix(lo, hi, step(vec3(0.0031308), cc)), 0.0, 1.0);
        }

        float softShoulderChan(float x, float knee) {
            if (x <= 0.0) return 0.0;
            if (knee >= 0.999 || x <= knee) return min(x, 1.0);
            float excess = x - knee;
            float span = max(1.0 - knee, 0.01);
            return knee + span * (excess / (excess + span));
        }

        vec3 applySoftShoulder(vec3 lin, float knee) {
            return vec3(
                softShoulderChan(lin.r, knee),
                softShoulderChan(lin.g, knee),
                softShoulderChan(lin.b, knee)
            );
        }

        vec3 applySmoothContrast(vec3 c, float contrastNorm) {
            if (abs(contrastNorm) <= 0.0005) return c;
            vec3 cc = clamp(c, 0.0, 1.0);
            if (contrastNorm > 0.0) {
                vec3 s1 = cc * cc * (3.0 - 2.0 * cc);
                vec3 s2 = s1 * s1 * (3.0 - 2.0 * s1);
                vec3 target = mix(s1, s2, contrastNorm * 0.72);
                return clamp(mix(cc, target, min(1.0, contrastNorm * 1.22)), 0.0, 1.0);
            } else {
                float t = -contrastNorm;
                return clamp(vec3(0.5) + (cc - 0.5) * (1.0 - t * 0.68), 0.0, 1.0);
            }
        }

        float bandWeight(float v, float center, float halfW) {
            float d = abs(v - center);
            if (d >= halfW) return 0.0;
            float t = 1.0 - d / halfW;
            return t * t * (3.0 - 2.0 * t);
        }

        vec3 rgbToHsl(vec3 c) {
            float maxC = max(c.r, max(c.g, c.b));
            float minC = min(c.r, min(c.g, c.b));
            float d = maxC - minC;
            float l = 0.5 * (maxC + minC);
            if (d < 0.00001) return vec3(0.0, 0.0, l);
            float s = (l > 0.5) ? (d / max(2.0 - maxC - minC, 0.0001)) : (d / max(maxC + minC, 0.0001));
            float h = 0.0;
            if (maxC == c.r) {
                h = (c.g - c.b) / d + (c.g < c.b ? 6.0 : 0.0);
            } else if (maxC == c.g) {
                h = (c.b - c.r) / d + 2.0;
            } else {
                h = (c.r - c.g) / d + 4.0;
            }
            h = mod(h * 60.0 + 360.0, 360.0);
            return vec3(h, clamp(s, 0.0, 1.0), clamp(l, 0.0, 1.0));
        }

        float hue2rgb(float p, float q, float tIn) {
            float t = tIn;
            if (t < 0.0) t += 1.0;
            if (t > 1.0) t -= 1.0;
            if (t < 1.0 / 6.0) return p + (q - p) * 6.0 * t;
            if (t < 0.5) return q;
            if (t < 2.0 / 3.0) return p + (q - p) * (2.0 / 3.0 - t) * 6.0;
            return p;
        }

        vec3 hslToRgb(vec3 hsl) {
            float h = hsl.x;
            float s = hsl.y;
            float l = hsl.z;
            if (s <= 0.00001) return vec3(l);
            float q = (l < 0.5) ? (l * (1.0 + s)) : (l + s - l * s);
            float p = 2.0 * l - q;
            float hk = h / 360.0;
            return clamp(vec3(
                hue2rgb(p, q, hk + 1.0 / 3.0),
                hue2rgb(p, q, hk),
                hue2rgb(p, q, hk - 1.0 / 3.0)
            ), 0.0, 1.0);
        }

        vec2 mapToRawUv(vec2 uv) {
            vec2 orientedUv = vec2(
                mix(uCropRect.x, uCropRect.z, uv.x),
                mix(uCropRect.y, uCropRect.w, uv.y)
            );
            vec3 mapped = uOrientMatrix * vec3(orientedUv, 1.0);
            vec2 rawUv = mapped.xy / max(abs(mapped.z), 0.0001);
            if (abs(uOptics.x) > 0.001) {
                vec2 centered = rawUv - vec2(0.5);
                float r2 = dot(centered, centered);
                float k = uOptics.x * 0.45;
                float edgeScale = 1.0 + max(0.0, k) * 0.35;
                rawUv = vec2(0.5) + centered * (1.0 + k * r2) / edgeScale;
            }
            return rawUv;
        }

        vec3 sampleSourcePixel(vec2 rawUv) {
            if (rawUv.x < 0.0 || rawUv.x > 1.0 || rawUv.y < 0.0 || rawUv.y > 1.0) {
                return vec3(0.04, 0.045, 0.055);
            }
            vec3 c = texture2D(uImageTex, rawUv).rgb;
            // Radial Chromatic Aberration (R/B channel radial UV shift from center)
            if (abs(uOptics.y) > 0.0005) {
                vec2 dir = rawUv - vec2(0.5);
                float r2 = dot(dir, dir);
                vec2 shift = dir * (uOptics.y * 0.022 * (0.45 + 1.65 * r2));
                vec2 uvR = clamp(rawUv + shift, vec2(0.0), vec2(1.0));
                vec2 uvB = clamp(rawUv - shift, vec2(0.0), vec2(1.0));
                c.r = texture2D(uImageTex, uvR).r;
                c.b = texture2D(uImageTex, uvB).b;
            }
            // Optional Lens Blur (smooth 8-tap radial bokeh)
            if (uOptics.z > 0.005) {
                vec2 d = vTexCoord - vec2(0.5);
                d.x *= uImageInfo.z;
                float dist = length(d);
                float blurMask = smoothstep(0.18, 0.72, dist);
                float radiusPx = uOptics.z * 9.0 * blurMask;
                if (radiusPx > 0.35) {
                    vec2 stepUv = uImageInfo.xy * radiusPx;
                    vec3 acc = c * 0.20;
                    acc += texture2D(uImageTex, clamp(rawUv + vec2(stepUv.x, 0.0), 0.0, 1.0)).rgb * 0.10;
                    acc += texture2D(uImageTex, clamp(rawUv - vec2(stepUv.x, 0.0), 0.0, 1.0)).rgb * 0.10;
                    acc += texture2D(uImageTex, clamp(rawUv + vec2(0.0, stepUv.y), 0.0, 1.0)).rgb * 0.10;
                    acc += texture2D(uImageTex, clamp(rawUv - vec2(0.0, stepUv.y), 0.0, 1.0)).rgb * 0.10;
                    acc += texture2D(uImageTex, clamp(rawUv + vec2(stepUv.x, stepUv.y) * 0.707, 0.0, 1.0)).rgb * 0.10;
                    acc += texture2D(uImageTex, clamp(rawUv + vec2(-stepUv.x, stepUv.y) * 0.707, 0.0, 1.0)).rgb * 0.10;
                    acc += texture2D(uImageTex, clamp(rawUv + vec2(stepUv.x, -stepUv.y) * 0.707, 0.0, 1.0)).rgb * 0.10;
                    acc += texture2D(uImageTex, clamp(rawUv + vec2(-stepUv.x, -stepUv.y) * 0.707, 0.0, 1.0)).rgb * 0.10;
                    c = acc;
                }
            }
            return c;
        }

        vec3 applyCameraProfile(vec3 c) {
            if (uOptics.w < 0.5) return c;
            vec3 p = clamp(c * uProfGain.rgb, 0.0, 1.0);
            float monoT = clamp(uProfColor.z, 0.0, 1.0);
            if (monoT > 0.001) {
                float bw = clamp(dot(p, uProfMono.rgb), 0.0, 1.0);
                p = mix(p, vec3(bw), monoT);
            }
            if (monoT < 0.999) {
                if (abs(uProfColor.y) > 0.001) {
                    float maxC = max(p.r, max(p.g, p.b));
                    float minC = min(p.r, min(p.g, p.b));
                    float vibMult = max(0.0, 1.0 + uProfColor.y * (1.0 - (maxC - minC)) * 0.85);
                    float lum = dot(p, vec3(0.2126, 0.7152, 0.0722));
                    p = clamp(mix(vec3(lum), p, vibMult), 0.0, 1.0);
                }
                if (abs(uProfColor.x) > 0.001) {
                    float satMult = max(0.0, 1.0 + uProfColor.x);
                    float lum = dot(p, vec3(0.2126, 0.7152, 0.0722));
                    p = clamp(mix(vec3(lum), p, satMult), 0.0, 1.0);
                }
            }
            float pLuma = dot(p, vec3(0.2126, 0.7152, 0.0722));
            float midMask = 4.0 * pLuma * (1.0 - pLuma);
            float cGain = 1.0 + uProfTone.x + uProfTone.w * midMask;
            p = clamp((p - 0.5) * cGain + 0.5, 0.0, 1.0);
            if (abs(uProfTone.y) > 0.0005) {
                float lum = dot(p, vec3(0.2126, 0.7152, 0.0722));
                float toe = clamp(1.0 - lum, 0.0, 1.0);
                p = clamp(p + vec3(uProfTone.y * toe * toe), 0.0, 1.0);
            }
            if (abs(uProfTone.z) > 0.0005) {
                float lum = clamp(dot(p, vec3(0.2126, 0.7152, 0.0722)), 0.0, 1.0);
                p = clamp(p - vec3(uProfTone.z * pow(max(lum, 0.0001), 1.8) * 0.35), 0.0, 1.0);
            }
            float l2 = clamp(dot(p, vec3(0.2126, 0.7152, 0.0722)), 0.0, 1.0);
            float shW = pow(max(1.0 - l2, 0.0001), 1.6);
            float hiW = pow(max(l2, 0.0001), 1.6);
            p = clamp(p + uProfShCast.rgb * shW + uProfHiCast.rgb * hiW, 0.0, 1.0);
            return p;
        }

        void accumHslBand(
            float hueDeg, float center, float span, vec4 adj,
            inout float dHue, inout float dSat, inout float dLum, inout float totalW
        ) {
            float dist = abs(hueDeg - center);
            if (dist > 180.0) dist = 360.0 - dist;
            if (dist < span) {
                float w = 1.0 - dist / span;
                totalW += w;
                dHue += adj.x * w;
                dSat += adj.y * w;
                dLum += adj.z * w;
            }
        }

        vec3 applyHslMixer(vec3 c) {
            vec3 hsl = rgbToHsl(c);
            if (hsl.y <= 0.002) return c;
            float dHue = 0.0;
            float dSat = 0.0;
            float dLum = 0.0;
            float totalW = 0.0;
            accumHslBand(hsl.x, 0.0,   35.0, uHsl0, dHue, dSat, dLum, totalW);
            accumHslBand(hsl.x, 30.0,  35.0, uHsl1, dHue, dSat, dLum, totalW);
            accumHslBand(hsl.x, 60.0,  35.0, uHsl2, dHue, dSat, dLum, totalW);
            accumHslBand(hsl.x, 120.0, 55.0, uHsl3, dHue, dSat, dLum, totalW);
            accumHslBand(hsl.x, 180.0, 45.0, uHsl4, dHue, dSat, dLum, totalW);
            accumHslBand(hsl.x, 230.0, 45.0, uHsl5, dHue, dSat, dLum, totalW);
            accumHslBand(hsl.x, 275.0, 40.0, uHsl6, dHue, dSat, dLum, totalW);
            accumHslBand(hsl.x, 325.0, 40.0, uHsl7, dHue, dSat, dLum, totalW);
            if (totalW > 0.0001) {
                dHue /= totalW;
                dSat /= totalW;
                dLum /= totalW;
            }
            float newH = mod(hsl.x + (dHue / 100.0) * 32.0 + 360.0, 360.0);
            float newS = clamp(hsl.y * (1.0 + dSat / 100.0), 0.0, 1.0);
            float newL = clamp(hsl.z + (dLum / 100.0) * 0.22 * hsl.y, 0.0, 1.0);
            return hslToRgb(vec3(newH, newS, newL));
        }

        vec3 applySingleMask(vec3 col, vec2 uv, vec4 mA, vec4 mB, vec4 mC) {
            float feather = clamp(mA.z, 0.02, 1.0);
            float w = 0.0;
            if (mA.x < 0.5) {
                // Radial ellipse mask
                vec2 d = (uv - mB.xy) / max(mB.zw, vec2(0.02));
                float r = length(d);
                float inner = max(0.0, 1.0 - feather);
                float outer = 1.0 + feather * 0.15;
                w = 1.0 - smoothstep(inner, max(inner + 0.02, outer), r);
            } else {
                // Linear gradient mask
                vec2 dir = mB.zw - mB.xy;
                float len2 = max(dot(dir, dir), 0.0004);
                float t = dot(uv - mB.xy, dir) / len2;
                float halfF = max(0.04, feather * 0.5);
                w = 1.0 - smoothstep(0.5 - halfF, 0.5 + halfF, t);
            }
            if (mA.y > 0.5) w = 1.0 - w;
            if (w <= 0.002) return col;

            // Local mask edits in linear + display space
            vec3 lin = srgbToLinear(col);
            if (abs(mA.w) > 0.001) {
                lin *= exp2(mA.w * w);
            }
            if (abs(mC.y) > 0.001) {
                float lt = mC.y * w;
                vec3 mWb = vec3(exp(lt * 0.42), exp(lt * 0.04), exp(-lt * 0.46));
                float mLuma = max(dot(mWb, vec3(0.2126, 0.7152, 0.0722)), 0.0001);
                lin *= (mWb / mLuma);
            }
            vec3 mc = linearToSrgb(applySoftShoulder(lin, 0.88));
            if (abs(mC.x) > 0.001) {
                mc = applySmoothContrast(mc, mC.x * w);
            }
            if (abs(mC.z) > 0.001) {
                float lum = dot(mc, vec3(0.2126, 0.7152, 0.0722));
                mc = clamp(mix(vec3(lum), mc, max(0.0, 1.0 + mC.z * w)), 0.0, 1.0);
            }
            return clamp(mc, 0.0, 1.0);
        }

        void main() {
            vec2 rawUv = mapToRawUv(vTexCoord);
            vec3 c = sampleSourcePixel(rawUv);

            // Optional Camera Profile base transform
            c = applyCameraProfile(c);

            // =========================================================================
            // STEP 1: Convert sRGB input pixels to linear light space
            // =========================================================================
            vec3 lin = srgbToLinear(c);

            // =========================================================================
            // STEP 2: Photographic Exposure in linear light: color *= pow(2.0, EV)
            // =========================================================================
            if (abs(uToneA.x) > 0.0005) {
                lin *= exp2(uToneA.x);
            }

            // =========================================================================
            // STEP 3: White Balance (Temp & Tint) in linear light space
            // =========================================================================
            if (abs(uToneA.y) > 0.0005 || abs(uToneA.z) > 0.0005) {
                lin *= uWbMult.rgb;
            }

            // =========================================================================
            // STEP 4: Highlights, Shadows, Whites, Blacks, Soft Shoulder & Contrast
            // =========================================================================
            float hiNorm = uToneB.x;
            float shNorm = uToneB.y;
            float whNorm = uToneB.z;
            float blNorm = uToneB.w;
            if (abs(hiNorm) > 0.0005 || abs(shNorm) > 0.0005 || abs(whNorm) > 0.0005 || abs(blNorm) > 0.0005) {
                float linLum = max(dot(lin, vec3(0.2126, 0.7152, 0.0722)), 0.00001);
                float lumNorm = (linLum / (linLum + 0.35)) * 1.35;

                // Highlights: upper-tone smooth recovery/lift preserving highlight headroom
                if (abs(hiNorm) > 0.0005) {
                    float wHi = smoothstep(0.18, 0.95, lumNorm);
                    float hiStops = (hiNorm < 0.0) ? (hiNorm * 1.35) : (hiNorm * 0.90);
                    lin *= exp2(hiStops * wHi);
                }

                // Shadows: lower-tone smooth lift/deepening without crushing blacks
                if (abs(shNorm) > 0.0005) {
                    float wSh = 1.0 - smoothstep(0.02, 0.45, lumNorm);
                    float shStops = (shNorm > 0.0) ? (shNorm * 1.35) : (shNorm * 1.05);
                    lin *= exp2(shStops * wSh);
                }

                // Whites: smooth upper-range white-point gain
                if (abs(whNorm) > 0.0005) {
                    float wWh = smoothstep(0.08, 1.0, lumNorm);
                    lin *= exp2(whNorm * 0.75 * wWh);
                }

                // Blacks: soft toe lift (positive) or smooth multiplicative deepening (negative)
                if (abs(blNorm) > 0.0005) {
                    float wToe = 1.0 - smoothstep(0.0, 0.50, lumNorm);
                    if (blNorm > 0.0) {
                        lin += vec3(blNorm * 0.032 * wToe) * clamp(1.0 - lin, 0.0, 1.0);
                    } else {
                        lin *= exp2(blNorm * 1.15 * wToe);
                    }
                }
            }

            // C1-continuous soft highlight shoulder roll-off before display conversion
            lin = applySoftShoulder(max(lin, vec3(0.0)), uWbMult.w);

            // =========================================================================
            // STEP 5: Convert back to sRGB display space + Smooth Contrast + Color
            // =========================================================================
            c = linearToSrgb(lin);

            // Smooth S-curve Contrast with soft toe & shoulder roll-off
            if (abs(uToneA.w) > 0.0005) {
                c = applySmoothContrast(c, uToneA.w);
            }

            // Parametric Tones (4-band smooth tonal shaping)
            if (abs(uParamTone.x) > 0.001 || abs(uParamTone.y) > 0.001 ||
                abs(uParamTone.z) > 0.001 || abs(uParamTone.w) > 0.001) {
                float pLum = dot(c, vec3(0.2126, 0.7152, 0.0722));
                float pDelta =
                    uParamTone.w * 0.22 * bandWeight(pLum, 0.12, 0.24) +
                    uParamTone.z * 0.20 * bandWeight(pLum, 0.36, 0.26) +
                    uParamTone.y * 0.20 * bandWeight(pLum, 0.64, 0.26) +
                    uParamTone.x * 0.22 * bandWeight(pLum, 0.88, 0.24);
                c = clamp(c + vec3(pDelta), 0.0, 1.0);
            }

            // Tone Curves (256x2 packed texture: row 0 = R,G,B; row 1 = Master)
            if (uImageInfo.w > 0.5) {
                float mR = texture2D(uCurveTex, vec2(c.r, 0.75)).r;
                float mG = texture2D(uCurveTex, vec2(c.g, 0.75)).r;
                float mB = texture2D(uCurveTex, vec2(c.b, 0.75)).r;
                c.r = texture2D(uCurveTex, vec2(mR, 0.25)).r;
                c.g = texture2D(uCurveTex, vec2(mG, 0.25)).g;
                c.b = texture2D(uCurveTex, vec2(mB, 0.25)).b;
            }

            // Camera Calibration (Row-normalized RGB Primary Hue & Saturation)
            if (uCalibB.w > 0.5) {
                float rShift = uCalibA.x * 0.18;
                float gShift = uCalibA.z * 0.18;
                float bShift = uCalibB.x * 0.18;

                float r00 = 1.0 - abs(rShift) * 0.25;
                float r01 = max(0.0, -gShift);
                float r02 = max(0.0, bShift);
                float nr = (c.r * r00 + c.g * r01 + c.b * r02) / max(r00 + r01 + r02, 0.001);

                float g10 = max(0.0, rShift);
                float g11 = 1.0 - abs(gShift) * 0.25;
                float g12 = max(0.0, -bShift);
                float ng = (c.r * g10 + c.g * g11 + c.b * g12) / max(g10 + g11 + g12, 0.001);

                float b20 = max(0.0, -rShift);
                float b21 = max(0.0, gShift);
                float b22 = 1.0 - abs(bShift) * 0.25;
                float nb = (c.r * b20 + c.g * b21 + c.b * b22) / max(b20 + b21 + b22, 0.001);

                float cLum = dot(vec3(nr, ng, nb), vec3(0.2126, 0.7152, 0.0722));
                c = clamp(vec3(
                    cLum + (nr - cLum) * (1.0 + uCalibA.y * 0.5),
                    cLum + (ng - cLum) * (1.0 + uCalibA.w * 0.5),
                    cLum + (nb - cLum) * (1.0 + uCalibB.y * 0.5)
                ), 0.0, 1.0);
            }

            // 8-band HSL Mixer
            if (uWheelMix.w > 0.5) {
                c = applyHslMixer(c);
            }

            // Point Color targeted swatch shift
            if (uPointB.w > 0.5) {
                vec3 hsl = rgbToHsl(c);
                float dH = abs(hsl.x - uPointA.x);
                if (dH > 180.0) dH = 360.0 - dH;
                float hTol = 18.0 + uPointA.w * 42.0;
                float sTol = 0.22 + uPointA.w * 0.45;
                float lTol = 0.22 + uPointA.w * 0.45;
                float wH = 1.0 - smoothstep(0.0, hTol, dH);
                float wS = 1.0 - smoothstep(0.0, sTol, abs(hsl.y - uPointA.y));
                float wL = 1.0 - smoothstep(0.0, lTol, abs(hsl.z - uPointA.z));
                float pw = wH * wS * wL * smoothstep(0.02, 0.12, hsl.y);
                if (pw > 0.002) {
                    float nh = mod(hsl.x + uPointB.x * 45.0 * pw + 360.0, 360.0);
                    float ns = clamp(hsl.y * (1.0 + uPointB.y * pw), 0.0, 1.0);
                    float nl = clamp(hsl.z + uPointB.z * 0.28 * pw, 0.0, 1.0);
                    c = hslToRgb(vec3(nh, ns, nl));
                }
            }

            // Grayscale OR Vibrance (protecting saturated colors + skin tones) & Saturation
            if (uColorA.z > 0.5) {
                float bw = dot(c, vec3(0.299, 0.587, 0.114));
                c = vec3(bw);
            } else {
                // Vibrance: smart saturation protecting already-saturated colors AND skin tones
                if (abs(uColorA.x) > 0.0005) {
                    float lum = dot(c, vec3(0.2126, 0.7152, 0.0722));
                    float maxC = max(c.r, max(c.g, c.b));
                    float minC = min(c.r, min(c.g, c.b));
                    float sat = (maxC - minC) / max(maxC, 0.001);
                    float lowSatWeight = pow(max(1.0 - clamp(sat, 0.0, 1.0), 0.0), 1.35);

                    // Skin-tone protection mask (warm orange/peach hue range ~12..46 deg where R > G > B)
                    float rgDiff = max(0.0, c.r - c.g);
                    float gbDiff = max(0.0, c.g - c.b);
                    float skinOrder = smoothstep(0.0, 0.08, rgDiff) * smoothstep(0.0, 0.06, gbDiff);
                    float hueFrac = (c.g - c.b) / max(c.r - c.b, 0.001);
                    float skinHue = smoothstep(0.10, 0.28, hueFrac) * (1.0 - smoothstep(0.62, 0.85, hueFrac));
                    float skinMask = skinOrder * skinHue * smoothstep(0.12, 0.28, lum) * (1.0 - smoothstep(0.82, 0.96, lum));
                    float skinProtect = (uColorA.x > 0.0) ? (1.0 - 0.72 * skinMask) : (1.0 - 0.30 * skinMask);

                    float vibGain = 1.0 + uColorA.x * 0.95 * lowSatWeight * skinProtect;
                    c = clamp(mix(vec3(lum), c, max(0.0, vibGain)), 0.0, 1.0);
                }

                // Saturation: uniform chroma scaling
                if (abs(uColorA.y) > 0.0005) {
                    float lum = dot(c, vec3(0.2126, 0.7152, 0.0722));
                    c = clamp(mix(vec3(lum), c, max(0.0, 1.0 + uColorA.y)), 0.0, 1.0);
                }
            }

            // Split Toning
            if (uSplitSh.w > 0.001 || uSplitHi.w > 0.001) {
                float lum = dot(c, vec3(0.2126, 0.7152, 0.0722));
                float pivot = clamp(0.5 - uCalibB.z * 0.25, 0.15, 0.85);
                float shW = (1.0 - smoothstep(0.0, pivot, lum)) * uSplitSh.w;
                float hiW = smoothstep(pivot, 1.0, lum) * uSplitHi.w;
                c = clamp(c + uSplitSh.rgb * shW * 0.35 + uSplitHi.rgb * hiW * 0.35, 0.0, 1.0);
            }

            // 4-Way Color Grading Wheels
            if (uWheelMix.z > 0.5) {
                float lum = dot(c, vec3(0.2126, 0.7152, 0.0722));
                float pivot = clamp(0.5 - uWheelMix.y * 0.28, 0.15, 0.85);
                float soft = 0.12 + uWheelMix.x * 0.38;
                float wSh = 1.0 - smoothstep(max(0.0, pivot - soft), min(1.0, pivot + soft * 0.4), lum);
                float wHi = smoothstep(max(0.0, pivot - soft * 0.4), min(1.0, pivot + soft), lum);
                float wMid = clamp(1.0 - wSh - wHi + 0.35 * (1.0 - abs(lum - pivot) * 2.0), 0.0, 1.0);
                vec3 wheelTint = uWheelSh.rgb * wSh + uWheelMid.rgb * wMid + uWheelHi.rgb * wHi + uWheelGl.rgb;
                float wheelLum = uWheelSh.w * wSh + uWheelMid.w * wMid + uWheelHi.w * wHi + uWheelGl.w;
                c = clamp(c + wheelTint * 0.32 + vec3(wheelLum * 0.22), 0.0, 1.0);
            }

            // Selective Local Masks (up to 4 unrolled cleanly)
            if (uMaskMeta.x > 0.5) c = applySingleMask(c, vTexCoord, uMask0A, uMask0B, uMask0C);
            if (uMaskMeta.x > 1.5) c = applySingleMask(c, vTexCoord, uMask1A, uMask1B, uMask1C);
            if (uMaskMeta.x > 2.5) c = applySingleMask(c, vTexCoord, uMask2A, uMask2B, uMask2C);
            if (uMaskMeta.x > 3.5) c = applySingleMask(c, vTexCoord, uMask3A, uMask3B, uMask3C);

            gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
        }
    """

    /**
     * Pass 2 Fragment Shader: Step 6 Real Spatial Detail Filtering on Pass-1 Graded Texture
     * (`uGradedTex`) + Finishing (Vignette, Film Grain, 3D/1D LUT, Halation/Bloom, Anamorphic
     * Streak, and pixel-exact Before/After original & split comparison reading `uRawTex`).
     */
    private const val SPATIAL_FINISH_FRAGMENT_SHADER = """
        #ifdef GL_FRAGMENT_PRECISION_HIGH
        precision highp float;
        #else
        precision mediump float;
        #endif

        varying vec2 vTexCoord;

        uniform sampler2D uGradedTex;   // Unit 0: Pass-1 tone & color graded FBO texture
        uniform sampler2D uLutTex;      // Unit 1: 3D/1D .cube LUT tiled atlas
        uniform sampler2D uRawTex;      // Unit 2: Original unmodified source texture (for compare)

        uniform vec4 uTexelInfo;        // (invW, invH, outW, outH)
        uniform vec4 uDetailA;          // (textureNorm, clarityNorm, dehazeNorm, sharpeningNorm)
        uniform vec4 uDetailB;          // (sharpenRadius, sharpenDetail, sharpenMasking, lumaNrNorm)
        uniform vec4 uDetailC;          // (colorNrNorm, vignetteNorm, grainNorm, grainSizeNorm)
        uniform vec4 uEffectsA;         // (grainRoughNorm, halationNorm, bloomNorm, anamorphicNorm)

        uniform vec4 uLutParams;        // (lutType, lutSize, lutIntensity, lutTilesPerRow)
        uniform vec3 uLutDomainMin;
        uniform vec3 uLutDomainMax;

        uniform vec4 uCompare;          // (isOriginal, splitRatio, applyCrop, clippingMask: bit0=hi, bit1=sh)
        uniform mat3 uOrientMatrix;
        uniform vec4 uCropRect;

        vec3 sampleRawOriginal(vec2 uv) {
            vec2 orientedUv = vec2(
                mix(uCropRect.x, uCropRect.z, uv.x),
                mix(uCropRect.y, uCropRect.w, uv.y)
            );
            vec3 mapped = uOrientMatrix * vec3(orientedUv, 1.0);
            vec2 rawUv = mapped.xy / max(abs(mapped.z), 0.0001);
            if (rawUv.x < 0.0 || rawUv.x > 1.0 || rawUv.y < 0.0 || rawUv.y > 1.0) {
                return vec3(0.04, 0.045, 0.055);
            }
            return texture2D(uRawTex, rawUv).rgb;
        }

        // 9-tap separable-weighted Gaussian blur at given pixel radius on the graded FBO texture
        vec3 sampleGaussian9(vec2 uv, vec2 stepUv) {
            vec3 c0 = texture2D(uGradedTex, uv).rgb * 4.0;
            vec3 ax =
                texture2D(uGradedTex, clamp(uv + vec2( stepUv.x, 0.0), 0.0, 1.0)).rgb +
                texture2D(uGradedTex, clamp(uv + vec2(-stepUv.x, 0.0), 0.0, 1.0)).rgb +
                texture2D(uGradedTex, clamp(uv + vec2(0.0,  stepUv.y), 0.0, 1.0)).rgb +
                texture2D(uGradedTex, clamp(uv + vec2(0.0, -stepUv.y), 0.0, 1.0)).rgb;
            vec3 dg =
                texture2D(uGradedTex, clamp(uv + vec2( stepUv.x,  stepUv.y), 0.0, 1.0)).rgb +
                texture2D(uGradedTex, clamp(uv + vec2(-stepUv.x,  stepUv.y), 0.0, 1.0)).rgb +
                texture2D(uGradedTex, clamp(uv + vec2( stepUv.x, -stepUv.y), 0.0, 1.0)).rgb +
                texture2D(uGradedTex, clamp(uv + vec2(-stepUv.x, -stepUv.y), 0.0, 1.0)).rgb;
            return (c0 + ax * 2.0 + dg) * (1.0 / 16.0);
        }

        float grainHash(vec2 p) {
            vec3 p3 = fract(vec3(p.xyx) * 0.1031);
            p3 += dot(p3, p3.yzx + 33.33);
            return fract((p3.x + p3.y) * p3.z);
        }

        float smoothGrainNoise(vec2 p) {
            vec2 i = floor(p);
            vec2 f = fract(p);
            vec2 u = f * f * (3.0 - 2.0 * f);
            return mix(
                mix(grainHash(i), grainHash(i + vec2(1.0, 0.0)), u.x),
                mix(grainHash(i + vec2(0.0, 1.0)), grainHash(i + vec2(1.0, 1.0)), u.x),
                u.y
            );
        }

        vec3 sampleLutSlice(float rNorm, float gNorm, float bSlice, float size, float tilesPerRow) {
            float tileCol = mod(bSlice, tilesPerRow);
            float tileRow = floor(bSlice / tilesPerRow);
            float atlasW = tilesPerRow * size;
            float atlasH = ceil(size / tilesPerRow) * size;
            float u = (tileCol * size + 0.5 + rNorm * (size - 1.0)) / atlasW;
            float v = (tileRow * size + 0.5 + gNorm * (size - 1.0)) / atlasH;
            return texture2D(uLutTex, vec2(u, v)).rgb;
        }

        vec3 applyCubeLut(vec3 c) {
            float lutType = uLutParams.x;
            float size = uLutParams.y;
            float intensity = uLutParams.z;
            float tilesPerRow = uLutParams.w;
            if (lutType < 0.5 || size < 2.0 || intensity <= 0.001) return c;
            vec3 denom = max(uLutDomainMax - uLutDomainMin, vec3(0.0001));
            vec3 n = clamp((c - uLutDomainMin) / denom, 0.0, 1.0);
            vec3 mapped;
            if (lutType < 1.5) {
                float uR = (0.5 + n.r * (size - 1.0)) / size;
                float uG = (0.5 + n.g * (size - 1.0)) / size;
                float uB = (0.5 + n.b * (size - 1.0)) / size;
                mapped = vec3(
                    texture2D(uLutTex, vec2(uR, 0.5)).r,
                    texture2D(uLutTex, vec2(uG, 0.5)).g,
                    texture2D(uLutTex, vec2(uB, 0.5)).b
                );
            } else {
                float bIdx = n.b * (size - 1.0);
                float b0 = floor(bIdx);
                float b1 = min(size - 1.0, b0 + 1.0);
                float fb = bIdx - b0;
                vec3 s0 = sampleLutSlice(n.r, n.g, b0, size, tilesPerRow);
                vec3 s1 = sampleLutSlice(n.r, n.g, b1, size, tilesPerRow);
                mapped = mix(s0, s1, fb);
            }
            return mix(c, mapped, clamp(intensity, 0.0, 1.0));
        }

        void main() {
            // Instant pixel-exact Before/After comparison reading unmodified uRawTex
            if (uCompare.x > 0.5) {
                gl_FragColor = vec4(sampleRawOriginal(vTexCoord), 1.0);
                return;
            }
            if (uCompare.y >= 0.0 && vTexCoord.x < uCompare.y) {
                gl_FragColor = vec4(sampleRawOriginal(vTexCoord), 1.0);
                return;
            }

            vec2 texel = uTexelInfo.xy;
            vec3 c = texture2D(uGradedTex, vTexCoord).rgb;

            // =========================================================================
            // STEP 6: Real Multi-Pixel Spatial Detail Passes on the Graded Image
            // =========================================================================

            // 6a. Edge-preserving Luminance & Color Noise Reduction (distinguishes isolated speckles from coherent edges)
            if (uDetailB.w > 0.005 || uDetailC.x > 0.005) {
                vec3 nL = texture2D(uGradedTex, clamp(vTexCoord - vec2(texel.x, 0.0), 0.0, 1.0)).rgb;
                vec3 nR = texture2D(uGradedTex, clamp(vTexCoord + vec2(texel.x, 0.0), 0.0, 1.0)).rgb;
                vec3 nU = texture2D(uGradedTex, clamp(vTexCoord - vec2(0.0, texel.y), 0.0, 1.0)).rgb;
                vec3 nD = texture2D(uGradedTex, clamp(vTexCoord + vec2(0.0, texel.y), 0.0, 1.0)).rgb;
                vec3 nearAvg = 0.25 * (nL + nR + nU + nD);
                vec3 wideSmooth = sampleGaussian9(vTexCoord, texel * 1.65);
                vec3 targetSmooth = mix(nearAvg, wideSmooth, 0.45);

                if (uDetailB.w > 0.005) {
                    float cLum = dot(c, vec3(0.2126, 0.7152, 0.0722));
                    float sLum = dot(targetSmooth, vec3(0.2126, 0.7152, 0.0722));
                    float dMin = min(
                        min(abs(cLum - dot(nL, vec3(0.2126, 0.7152, 0.0722))), abs(cLum - dot(nR, vec3(0.2126, 0.7152, 0.0722)))),
                        min(abs(cLum - dot(nU, vec3(0.2126, 0.7152, 0.0722))), abs(cLum - dot(nD, vec3(0.2126, 0.7152, 0.0722))))
                    );
                    float coherentEdge = max(abs(cLum - sLum) - dMin * 0.85, 0.0);
                    float edgeKeep = clamp(1.0 - coherentEdge * 6.5, 0.08, 1.0);
                    float newLum = mix(cLum, sLum, uDetailB.w * 0.86 * edgeKeep);
                    c = clamp(c + vec3(newLum - cLum), 0.0, 1.0);
                }
                if (uDetailC.x > 0.005) {
                    float cLum = dot(c, vec3(0.2126, 0.7152, 0.0722));
                    float sLum = dot(targetSmooth, vec3(0.2126, 0.7152, 0.0722));
                    vec3 cCh = c - vec3(cLum);
                    vec3 sCh = targetSmooth - vec3(sLum);
                    float dChMin = min(
                        min(length(cCh - (nL - vec3(dot(nL, vec3(0.2126, 0.7152, 0.0722))))),
                            length(cCh - (nR - vec3(dot(nR, vec3(0.2126, 0.7152, 0.0722)))))),
                        min(length(cCh - (nU - vec3(dot(nU, vec3(0.2126, 0.7152, 0.0722))))),
                            length(cCh - (nD - vec3(dot(nD, vec3(0.2126, 0.7152, 0.0722))))))
                    );
                    float chromaEdge = max(length(cCh - sCh) - dChMin * 0.85, 0.0);
                    float chromaKeep = clamp(1.0 - chromaEdge * 3.0, 0.14, 1.0);
                    c = clamp(vec3(cLum) + mix(cCh, sCh, uDetailC.x * 0.94 * chromaKeep), 0.0, 1.0);
                }
            }

            // 6b. Dehaze: Local dark-channel atmospheric veil estimation + radiance & saturation recovery
            if (abs(uDetailA.z) > 0.0005) {
                vec2 dhStep = texel * 4.0;
                float minDc = min(c.r, min(c.g, c.b));
                float avgDc = minDc;

                vec3 s1 = texture2D(uGradedTex, clamp(vTexCoord + vec2( dhStep.x, 0.0), 0.0, 1.0)).rgb;
                vec3 s2 = texture2D(uGradedTex, clamp(vTexCoord + vec2(-dhStep.x, 0.0), 0.0, 1.0)).rgb;
                vec3 s3 = texture2D(uGradedTex, clamp(vTexCoord + vec2(0.0,  dhStep.y), 0.0, 1.0)).rgb;
                vec3 s4 = texture2D(uGradedTex, clamp(vTexCoord + vec2(0.0, -dhStep.y), 0.0, 1.0)).rgb;
                vec3 s5 = texture2D(uGradedTex, clamp(vTexCoord + vec2( dhStep.x,  dhStep.y), 0.0, 1.0)).rgb;
                vec3 s6 = texture2D(uGradedTex, clamp(vTexCoord + vec2(-dhStep.x,  dhStep.y), 0.0, 1.0)).rgb;
                vec3 s7 = texture2D(uGradedTex, clamp(vTexCoord + vec2( dhStep.x, -dhStep.y), 0.0, 1.0)).rgb;
                vec3 s8 = texture2D(uGradedTex, clamp(vTexCoord + vec2(-dhStep.x, -dhStep.y), 0.0, 1.0)).rgb;

                float d1 = min(s1.r, min(s1.g, s1.b));
                float d2 = min(s2.r, min(s2.g, s2.b));
                float d3 = min(s3.r, min(s3.g, s3.b));
                float d4 = min(s4.r, min(s4.g, s4.b));
                float d5 = min(s5.r, min(s5.g, s5.b));
                float d6 = min(s6.r, min(s6.g, s6.b));
                float d7 = min(s7.r, min(s7.g, s7.b));
                float d8 = min(s8.r, min(s8.g, s8.b));

                minDc = min(minDc, min(min(d1, d2), min(d3, d4)));
                minDc = min(minDc, min(min(d5, d6), min(d7, d8)));
                avgDc = (avgDc + d1 + d2 + d3 + d4 + d5 + d6 + d7 + d8) * (1.0 / 9.0);

                // Smooth local atmospheric veil estimate (blends min dark channel with local mean to avoid halos)
                float veil = clamp(mix(minDc, avgDc, 0.35), 0.0, 0.92);

                if (uDetailA.z > 0.0) {
                    float dAmt = uDetailA.z;
                    float omega = dAmt * 0.78;
                    float tMin = mix(1.0, 0.28, dAmt);
                    float transmission = max(1.0 - omega * veil, tMin);
                    vec3 atmos = vec3(0.95);
                    vec3 recovered = (c - atmos * (1.0 - transmission) * 0.85) / transmission;
                    recovered = clamp(recovered, 0.0, 1.0);
                    // Proportional contrast & saturation recovery
                    float rLum = dot(recovered, vec3(0.2126, 0.7152, 0.0722));
                    float satRecovery = 1.0 + dAmt * 0.32 * (0.35 + 0.65 * veil);
                    c = clamp(mix(vec3(rLum), recovered, satRecovery), 0.0, 1.0);
                } else {
                    float hAmt = -uDetailA.z;
                    float lum = dot(c, vec3(0.2126, 0.7152, 0.0722));
                    vec3 hazeColor = vec3(0.86, 0.89, 0.93);
                    float veilMix = hAmt * 0.48 * (1.0 - 0.32 * lum);
                    c = mix(c, hazeColor, veilMix);
                    float hLum = dot(c, vec3(0.2126, 0.7152, 0.0722));
                    c = clamp(mix(vec3(hLum), c, 1.0 - hAmt * 0.24), 0.0, 1.0);
                }
            }

            // 6c. Texture: Small-radius Gaussian blur difference (fine detail without major edge halos)
            if (abs(uDetailA.x) > 0.0005) {
                vec3 blurSmall = sampleGaussian9(vTexCoord, texel * 1.25);
                vec3 highFreq = c - blurSmall;
                float hfEnergy = dot(highFreq, highFreq);
                float haloGuard = 1.0 / (1.0 + 22.0 * hfEnergy);
                c = clamp(c + highFreq * (uDetailA.x * 1.45) * haloGuard, 0.0, 1.0);
            }

            // 6d. Clarity: Larger-radius midtone local contrast enhancement protecting pure blacks & whites
            if (abs(uDetailA.y) > 0.0005) {
                vec3 blurMid = sampleGaussian9(vTexCoord, texel * 3.0);
                vec3 blurWide = sampleGaussian9(vTexCoord, texel * 6.0);
                vec3 blurLarge = mix(blurMid, blurWide, 0.55);
                vec3 midFreq = c - blurLarge;
                float lum = dot(c, vec3(0.2126, 0.7152, 0.0722));
                float midtoneMask = smoothstep(0.05, 0.28, lum) * (1.0 - smoothstep(0.72, 0.95, lum));
                c = clamp(c + midFreq * (uDetailA.y * 0.90) * midtoneMask, 0.0, 1.0);
            }

            // 6e. Sharpen: Unsharp mask + Laplacian high-pass with flat-area noise threshold protection
            if (uDetailA.w > 0.0005) {
                float rPx = max(0.75, uDetailB.x);
                vec3 blurSharp = sampleGaussian9(vTexCoord, texel * rPx);
                vec3 usm = c - blurSharp;

                // 5-tap micro-edge Laplacian at 1.0px
                vec3 n1 = texture2D(uGradedTex, clamp(vTexCoord + vec2( texel.x, 0.0), 0.0, 1.0)).rgb;
                vec3 n2 = texture2D(uGradedTex, clamp(vTexCoord + vec2(-texel.x, 0.0), 0.0, 1.0)).rgb;
                vec3 n3 = texture2D(uGradedTex, clamp(vTexCoord + vec2(0.0,  texel.y), 0.0, 1.0)).rgb;
                vec3 n4 = texture2D(uGradedTex, clamp(vTexCoord + vec2(0.0, -texel.y), 0.0, 1.0)).rgb;
                vec3 lap = c - 0.25 * (n1 + n2 + n3 + n4);

                float detailMix = 0.25 + 0.45 * clamp(uDetailB.y, 0.0, 1.0);
                vec3 highPass = mix(usm, lap, detailMix);

                // Flat-area noise threshold protection (blocks 1-LSB flat noise and scales smoothly with masking)
                float edgeMag = abs(dot(usm, vec3(0.2126, 0.7152, 0.0722))) +
                                0.5 * abs(dot(lap, vec3(0.2126, 0.7152, 0.0722)));
                float threshold = 0.008 + uDetailB.z * 0.14;
                float edgeWeight = smoothstep(threshold * 0.5, threshold * 1.5, edgeMag);

                c = clamp(c + highPass * (uDetailA.w * 2.15) * edgeWeight, 0.0, 1.0);
            }

            // =========================================================================
            // FINISHING PASSES (Vignette, Film Grain, 3D LUT, Halation/Bloom, Streak)
            // =========================================================================

            // Vignette
            if (abs(uDetailC.y) > 0.001) {
                vec2 d = (vTexCoord - vec2(0.5)) * 2.0;
                float r2 = clamp(dot(d, d) * 0.5, 0.0, 1.5);
                if (uDetailC.y < 0.0) {
                    float factor = clamp(1.0 + uDetailC.y * 0.65 * r2, 0.12, 1.0);
                    c *= factor;
                } else {
                    float lift = clamp(uDetailC.y * 0.45 * r2, 0.0, 0.65);
                    c = mix(c, vec3(1.0), lift);
                }
            }

            // Film Grain
            if (uDetailC.z > 0.005) {
                float cellSize = mix(1.0, 5.2, clamp(uDetailC.w, 0.0, 1.0));
                vec2 pxCoord = vTexCoord * uTexelInfo.zw / cellSize;
                float coarse = smoothGrainNoise(pxCoord) - 0.5;
                float fine = grainHash(floor(pxCoord * 1.7 + vec2(17.3, 41.9))) - 0.5;
                float grain = mix(coarse, fine, clamp(uEffectsA.x, 0.0, 1.0));
                float lum = dot(c, vec3(0.2126, 0.7152, 0.0722));
                float midWeight = 0.55 + 0.45 * (1.0 - abs(lum - 0.5) * 1.6);
                c = clamp(c + vec3(grain * uDetailC.z * 0.24 * midWeight), 0.0, 1.0);
            }

            // 3D / 1D .cube LUT
            c = applyCubeLut(c);

            // Film Halation & Bloom (multi-tap highlight halo sampled on graded FBO)
            if (uEffectsA.y > 0.001 || uEffectsA.z > 0.001) {
                vec3 halAccum = vec3(0.0);
                vec3 bloomAccum = vec3(0.0);
                float totalW = 0.0;
                float maxR = max(uEffectsA.y, uEffectsA.z);
                vec2 step1 = texel * (3.5 + 8.5 * maxR);
                vec2 step2 = step1 * 2.15;

                // Ring 1 (8 taps)
                vec2 o0 = vec2( 1.0,  0.0);
                vec2 o1 = vec2(-1.0,  0.0);
                vec2 o2 = vec2( 0.0,  1.0);
                vec2 o3 = vec2( 0.0, -1.0);
                vec2 o4 = vec2( 0.707,  0.707);
                vec2 o5 = vec2(-0.707,  0.707);
                vec2 o6 = vec2( 0.707, -0.707);
                vec2 o7 = vec2(-0.707, -0.707);

                for (int rIdx = 0; rIdx < 2; rIdx++) {
                    vec2 rad = (rIdx == 0) ? step1 : step2;
                    float wt = (rIdx == 0) ? 1.0 : 0.62;
                    vec3 t0 = texture2D(uGradedTex, clamp(vTexCoord + o0 * rad, 0.0, 1.0)).rgb;
                    vec3 t1 = texture2D(uGradedTex, clamp(vTexCoord + o1 * rad, 0.0, 1.0)).rgb;
                    vec3 t2 = texture2D(uGradedTex, clamp(vTexCoord + o2 * rad, 0.0, 1.0)).rgb;
                    vec3 t3 = texture2D(uGradedTex, clamp(vTexCoord + o3 * rad, 0.0, 1.0)).rgb;
                    vec3 t4 = texture2D(uGradedTex, clamp(vTexCoord + o4 * rad, 0.0, 1.0)).rgb;
                    vec3 t5 = texture2D(uGradedTex, clamp(vTexCoord + o5 * rad, 0.0, 1.0)).rgb;
                    vec3 t6 = texture2D(uGradedTex, clamp(vTexCoord + o6 * rad, 0.0, 1.0)).rgb;
                    vec3 t7 = texture2D(uGradedTex, clamp(vTexCoord + o7 * rad, 0.0, 1.0)).rgb;

                    vec3 avg = (t0 + t1 + t2 + t3 + t4 + t5 + t6 + t7) * 0.125;
                    float l0 = dot(t0, vec3(0.2126, 0.7152, 0.0722));
                    float l1 = dot(t1, vec3(0.2126, 0.7152, 0.0722));
                    float l2 = dot(t2, vec3(0.2126, 0.7152, 0.0722));
                    float l3 = dot(t3, vec3(0.2126, 0.7152, 0.0722));
                    float l4 = dot(t4, vec3(0.2126, 0.7152, 0.0722));
                    float l5 = dot(t5, vec3(0.2126, 0.7152, 0.0722));
                    float l6 = dot(t6, vec3(0.2126, 0.7152, 0.0722));
                    float l7 = dot(t7, vec3(0.2126, 0.7152, 0.0722));

                    float hiSum =
                        smoothstep(0.68, 0.96, l0) + smoothstep(0.68, 0.96, l1) +
                        smoothstep(0.68, 0.96, l2) + smoothstep(0.68, 0.96, l3) +
                        smoothstep(0.68, 0.96, l4) + smoothstep(0.68, 0.96, l5) +
                        smoothstep(0.68, 0.96, l6) + smoothstep(0.68, 0.96, l7);
                    float hiAvg = hiSum * 0.125;
                    halAccum += vec3(1.0, 0.24, 0.05) * (hiAvg * wt);
                    bloomAccum += avg * (hiAvg * wt);
                    totalW += wt;
                }
                if (totalW > 0.001) {
                    halAccum /= totalW;
                    bloomAccum /= totalW;
                    vec3 addGlow = halAccum * (uEffectsA.y * 0.95) + bloomAccum * (uEffectsA.z * 0.85);
                    c = clamp(1.0 - (1.0 - c) * (1.0 - clamp(addGlow, 0.0, 1.0)), 0.0, 1.0);
                }
            }

            // Anamorphic Streak: horizontal 1D Gaussian cyan/blue highlight streak
            if (uEffectsA.w > 0.001) {
                float streakAccum = 0.0;
                float weightSum = 0.0;
                float span = 0.045 + 0.125 * uEffectsA.w;
                for (int i = -6; i <= 6; i++) {
                    float fi = float(i) / 6.0;
                    float w = exp(-fi * fi * 2.4);
                    vec2 sUv = clamp(vec2(vTexCoord.x + fi * span, vTexCoord.y), 0.0, 1.0);
                    vec3 sc = texture2D(uGradedTex, sUv).rgb;
                    float sLum = dot(sc, vec3(0.2126, 0.7152, 0.0722));
                    float sPeak = max(sLum, max(sc.r, max(sc.g, sc.b)) * 0.92);
                    float hi = smoothstep(0.68, 0.96, sPeak);
                    streakAccum += (hi * hi) * w;
                    weightSum += w;
                }
                float streak = (streakAccum / max(weightSum, 0.001)) * (uEffectsA.w * 1.45);
                c = clamp(c + vec3(0.24, 0.74, 1.0) * streak, 0.0, 1.0);
            }

            // Preview-only Highlight (Red) & Shadow (Blue) Clipping Warning Overlay
            // Never active during full-resolution export (uCompare.w == 0.0).
            if (uCompare.w > 0.5) {
                float clipFlags = floor(uCompare.w + 0.5);
                bool showHiClip = mod(clipFlags, 2.0) > 0.5;
                bool showShClip = clipFlags >= 1.5;
                float minCh = min(c.r, min(c.g, c.b));
                float maxCh = max(c.r, max(c.g, c.b));
                if (showHiClip && minCh >= 0.988) {
                    c = vec3(1.0, 0.14, 0.14);
                } else if (showShClip && maxCh <= 0.012) {
                    c = vec3(0.12, 0.48, 1.0);
                }
            }

            gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
        }
    """

    class ToneColorUniformHandles(program: Int) {
        val aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        val aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")

        val uImageTex = GLES20.glGetUniformLocation(program, "uImageTex")
        val uCurveTex = GLES20.glGetUniformLocation(program, "uCurveTex")

        val uOrientMatrix = GLES20.glGetUniformLocation(program, "uOrientMatrix")
        val uCropRect = GLES20.glGetUniformLocation(program, "uCropRect")
        val uOptics = GLES20.glGetUniformLocation(program, "uOptics")
        val uImageInfo = GLES20.glGetUniformLocation(program, "uImageInfo")

        val uProfTone = GLES20.glGetUniformLocation(program, "uProfTone")
        val uProfGain = GLES20.glGetUniformLocation(program, "uProfGain")
        val uProfColor = GLES20.glGetUniformLocation(program, "uProfColor")
        val uProfMono = GLES20.glGetUniformLocation(program, "uProfMono")
        val uProfShCast = GLES20.glGetUniformLocation(program, "uProfShCast")
        val uProfHiCast = GLES20.glGetUniformLocation(program, "uProfHiCast")

        val uToneA = GLES20.glGetUniformLocation(program, "uToneA")
        val uToneB = GLES20.glGetUniformLocation(program, "uToneB")
        val uWbMult = GLES20.glGetUniformLocation(program, "uWbMult")
        val uParamTone = GLES20.glGetUniformLocation(program, "uParamTone")
        val uColorA = GLES20.glGetUniformLocation(program, "uColorA")

        val uCalibA = GLES20.glGetUniformLocation(program, "uCalibA")
        val uCalibB = GLES20.glGetUniformLocation(program, "uCalibB")
        val uSplitSh = GLES20.glGetUniformLocation(program, "uSplitSh")
        val uSplitHi = GLES20.glGetUniformLocation(program, "uSplitHi")

        val uWheelSh = GLES20.glGetUniformLocation(program, "uWheelSh")
        val uWheelMid = GLES20.glGetUniformLocation(program, "uWheelMid")
        val uWheelHi = GLES20.glGetUniformLocation(program, "uWheelHi")
        val uWheelGl = GLES20.glGetUniformLocation(program, "uWheelGl")
        val uWheelMix = GLES20.glGetUniformLocation(program, "uWheelMix")

        val uPointA = GLES20.glGetUniformLocation(program, "uPointA")
        val uPointB = GLES20.glGetUniformLocation(program, "uPointB")

        val uHsl = IntArray(8) { i -> GLES20.glGetUniformLocation(program, "uHsl$i") }

        val uMaskMeta = GLES20.glGetUniformLocation(program, "uMaskMeta")
        val uMaskA = IntArray(4) { i -> GLES20.glGetUniformLocation(program, "uMask${i}A") }
        val uMaskB = IntArray(4) { i -> GLES20.glGetUniformLocation(program, "uMask${i}B") }
        val uMaskC = IntArray(4) { i -> GLES20.glGetUniformLocation(program, "uMask${i}C") }

        // Pre-allocated scratch buffers (zero heap allocation during live slider drags)
        val orientMat = FloatArray(9)
        val scratchMatA = FloatArray(9)
        val scratchMatB = FloatArray(9)
        val wbScratch = FloatArray(3)
        val splitShScratch = FloatArray(3)
        val splitHiScratch = FloatArray(3)
        val wheelShScratch = FloatArray(3)
        val wheelMidScratch = FloatArray(3)
        val wheelHiScratch = FloatArray(3)
        val wheelGlScratch = FloatArray(3)
    }

    class SpatialFinishUniformHandles(program: Int) {
        val aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        val aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")

        val uGradedTex = GLES20.glGetUniformLocation(program, "uGradedTex")
        val uLutTex = GLES20.glGetUniformLocation(program, "uLutTex")
        val uRawTex = GLES20.glGetUniformLocation(program, "uRawTex")

        val uTexelInfo = GLES20.glGetUniformLocation(program, "uTexelInfo")
        val uDetailA = GLES20.glGetUniformLocation(program, "uDetailA")
        val uDetailB = GLES20.glGetUniformLocation(program, "uDetailB")
        val uDetailC = GLES20.glGetUniformLocation(program, "uDetailC")
        val uEffectsA = GLES20.glGetUniformLocation(program, "uEffectsA")

        val uLutParams = GLES20.glGetUniformLocation(program, "uLutParams")
        val uLutDomainMin = GLES20.glGetUniformLocation(program, "uLutDomainMin")
        val uLutDomainMax = GLES20.glGetUniformLocation(program, "uLutDomainMax")

        val uCompare = GLES20.glGetUniformLocation(program, "uCompare")
        val uOrientMatrix = GLES20.glGetUniformLocation(program, "uOrientMatrix")
        val uCropRect = GLES20.glGetUniformLocation(program, "uCropRect")

        val orientMat = FloatArray(9)
        val scratchMatA = FloatArray(9)
        val scratchMatB = FloatArray(9)
    }

    fun compileToneColorProgram(): Int = compileLinkedProgram(VERTEX_SHADER, TONE_COLOR_FRAGMENT_SHADER)

    fun compileSpatialFinishProgram(): Int = compileLinkedProgram(VERTEX_SHADER, SPATIAL_FINISH_FRAGMENT_SHADER)

    private fun compileLinkedProgram(vertexSource: String, fragmentSource: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
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
            throw IllegalStateException("GLSL program link failed: $log")
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
            throw IllegalStateException("GLSL compile failed (type=$type): $log")
        }
        return shader
    }

    fun createQuadBuffer(readbackFlipY: Boolean): FloatBuffer {
        val vertices = if (readbackFlipY) {
            floatArrayOf(
                -1f, -1f, 0f, 0f,
                1f, -1f, 1f, 0f,
                -1f, 1f, 0f, 1f,
                1f, 1f, 1f, 1f
            )
        } else {
            floatArrayOf(
                -1f, -1f, 0f, 1f,
                1f, -1f, 1f, 1f,
                -1f, 1f, 0f, 0f,
                1f, 1f, 1f, 0f
            )
        }
        return ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(vertices)
                position(0)
            }
    }

    fun uploadTexture(texId: Int, bitmap: Bitmap) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
    }

    fun uploadTextureSubImage(texId: Int, bitmap: Bitmap) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap)
    }

    data class PackedLutTexture(
        val bitmap: Bitmap,
        val tilesPerRow: Int,
        val lutType: Int,
        val size: Int
    )

    fun packLutToTiledBitmap(lut: CubeLut): PackedLutTexture {
        if (lut.type == LutType.LUT_1D) {
            val size = lut.size.coerceAtLeast(2)
            val bmp = createBitmap(size, 1)
            val pixels = IntArray(size)
            for (i in 0 until size) {
                val base = i * 3
                val r = (lut.table[base].coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
                val g = (lut.table[base + 1].coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
                val b = (lut.table[base + 2].coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
                pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            bmp.setPixels(pixels, 0, size, 0, 0, size, 1)
            return PackedLutTexture(bmp, tilesPerRow = 1, lutType = 1, size = size)
        }

        val size = lut.size.coerceAtLeast(2)
        val tilesPerRow = ceil(sqrt(size.toDouble())).toInt().coerceAtLeast(1)
        val tileRows = ceil(size.toDouble() / tilesPerRow.toDouble()).toInt().coerceAtLeast(1)
        val atlasW = tilesPerRow * size
        val atlasH = tileRows * size
        val bmp = createBitmap(atlasW, atlasH)
        val pixels = IntArray(atlasW * atlasH)

        for (bIdx in 0 until size) {
            val tileCol = bIdx % tilesPerRow
            val tileRow = bIdx / tilesPerRow
            val baseX = tileCol * size
            val baseY = tileRow * size
            for (gIdx in 0 until size) {
                val rowOff = (baseY + gIdx) * atlasW + baseX
                for (rIdx in 0 until size) {
                    val lutIdx = ((bIdx * size + gIdx) * size + rIdx) * 3
                    val r = (lut.table[lutIdx].coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
                    val g = (lut.table[lutIdx + 1].coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
                    val b = (lut.table[lutIdx + 2].coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
                    pixels[rowOff + rIdx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
        bmp.setPixels(pixels, 0, atlasW, 0, 0, atlasW, atlasH)
        return PackedLutTexture(bmp, tilesPerRow = tilesPerRow, lutType = 2, size = size)
    }

    /**
     * Packs Master + R + G + B tone curves into a 256x2 opaque ARGB_8888 bitmap
     * (row 0 = RGB channels, row 1 = Master channel in RGB) to avoid premultiplied alpha issues.
     */
    fun packToneCurveInto(
        curve: ToneCurveData,
        scratchPixels: IntArray,
        targetBitmap: Bitmap
    ) {
        curve.buildPackedRgba512Into(scratchPixels)
        targetBitmap.setPixels(scratchPixels, 0, 256, 0, 0, 256, 2)
    }

    fun packToneCurveBitmap(curve: ToneCurveData): Bitmap {
        val bmp = createBitmap(256, 2)
        val pixels = IntArray(512)
        packToneCurveInto(curve, pixels, bmp)
        return bmp
    }

    fun bindToneColorUniforms(
        handles: ToneColorUniformHandles,
        params: GradingParams,
        imageWidth: Int,
        imageHeight: Int,
        applyCrop: Boolean,
        outputWidth: Int,
        outputHeight: Int
    ) {
        GeometryMath.buildOrientToRawSourceMatrixInto(
            srcW = imageWidth,
            srcH = imageHeight,
            crop = params.crop,
            out = handles.orientMat,
            scratchA = handles.scratchMatA,
            scratchB = handles.scratchMatB
        )
        GLES20.glUniformMatrix3fv(handles.uOrientMatrix, 1, false, handles.orientMat, 0)

        if (applyCrop) {
            val rect = GeometryMath.effectiveCropRect(imageWidth, imageHeight, params.crop)
            GLES20.glUniform4f(handles.uCropRect, rect.left, rect.top, rect.right, rect.bottom)
        } else {
            GLES20.glUniform4f(handles.uCropRect, 0f, 0f, 1f, 1f)
        }

        val activeProfile = CameraProfiles.byId(params.profileId)
        val profSt = params.profileAmount.coerceIn(0f, 2f)
        val hasProfile = activeProfile.isCreative && profSt > 0.001f
        val caNorm = (params.chromaticAberration / 50f).coerceIn(-1f, 1f)
        GLES20.glUniform4f(
            handles.uOptics,
            params.lensDistortion / 100f,
            caNorm,
            (params.lensBlurAmount / 100f).coerceIn(0f, 1f),
            if (hasProfile) 1f else 0f
        )

        GLES20.glUniform4f(
            handles.uProfTone,
            activeProfile.contrast * profSt,
            activeProfile.shadowLift * profSt,
            activeProfile.highlightCompress * profSt,
            activeProfile.midPunch * profSt
        )
        GLES20.glUniform4f(
            handles.uProfGain,
            1f + activeProfile.rGainDelta * profSt,
            1f + activeProfile.gGainDelta * profSt,
            1f + activeProfile.bGainDelta * profSt,
            0f
        )
        GLES20.glUniform4f(
            handles.uProfColor,
            activeProfile.satDelta * profSt,
            activeProfile.vibDelta * profSt,
            (activeProfile.monoMix * profSt).coerceIn(0f, 1f),
            0f
        )
        GLES20.glUniform4f(
            handles.uProfMono,
            activeProfile.monoWeightR,
            activeProfile.monoWeightG,
            activeProfile.monoWeightB,
            0f
        )
        GLES20.glUniform4f(
            handles.uProfShCast,
            activeProfile.shadowRedBias * profSt,
            activeProfile.shadowGreenBias * profSt,
            activeProfile.shadowBlueBias * profSt,
            0f
        )
        GLES20.glUniform4f(
            handles.uProfHiCast,
            activeProfile.highlightRedBias * profSt,
            activeProfile.highlightGreenBias * profSt,
            activeProfile.highlightBlueBias * profSt,
            0f
        )

        val outAspect = (outputWidth.coerceAtLeast(1).toFloat() / outputHeight.coerceAtLeast(1).toFloat())
        GLES20.glUniform4f(
            handles.uImageInfo,
            1f / max(1, imageWidth).toFloat(),
            1f / max(1, imageHeight).toFloat(),
            outAspect,
            if (params.toneCurve.isDefault) 0f else 1f
        )

        val exposureEV = params.exposure.coerceIn(-2f, 2f)
        val tempNorm = (params.temperature / 100f).coerceIn(-1f, 1f)
        val tintNorm = (params.tint / 100f).coerceIn(-1f, 1f)
        val contrastNorm = (params.contrast / 100f).coerceIn(-1f, 1f)
        val hiNorm = (params.highlights / 100f).coerceIn(-1f, 1f)
        val shNorm = (params.shadows / 100f).coerceIn(-1f, 1f)
        val whNorm = (params.whites / 100f).coerceIn(-1f, 1f)
        val blNorm = (params.blacks / 100f).coerceIn(-1f, 1f)

        computeLinearWhiteBalanceMultipliers(tempNorm, tintNorm, handles.wbScratch)
        val maxWb = max(handles.wbScratch[0], max(handles.wbScratch[1], handles.wbScratch[2]))
        val shoulderKnee = computeSoftShoulderKnee(exposureEV, maxWb, hiNorm, whNorm)

        GLES20.glUniform4f(handles.uToneA, exposureEV, tempNorm, tintNorm, contrastNorm)
        GLES20.glUniform4f(handles.uToneB, hiNorm, shNorm, whNorm, blNorm)
        GLES20.glUniform4f(
            handles.uWbMult,
            handles.wbScratch[0],
            handles.wbScratch[1],
            handles.wbScratch[2],
            shoulderKnee
        )
        GLES20.glUniform4f(
            handles.uParamTone,
            params.parametricHighlights / 100f,
            params.parametricLights / 100f,
            params.parametricDarks / 100f,
            params.parametricShadows / 100f
        )
        GLES20.glUniform4f(
            handles.uColorA,
            (params.vibrance / 100f).coerceIn(-1f, 1f),
            (params.saturation / 100f).coerceIn(-1f, 1f),
            if (params.isGrayscale) 1f else 0f,
            0f
        )

        val cal = params.calibration
        GLES20.glUniform4f(
            handles.uCalibA,
            cal.redHue / 100f,
            cal.redSaturation / 100f,
            cal.greenHue / 100f,
            cal.greenSaturation / 100f
        )
        GLES20.glUniform4f(
            handles.uCalibB,
            cal.blueHue / 100f,
            cal.blueSaturation / 100f,
            params.splitBalance / 100f,
            if (cal.isDefault) 0f else 1f
        )

        hueSatToRgbTintInto(params.splitShadowHue, params.splitShadowSat / 100f, handles.splitShScratch)
        hueSatToRgbTintInto(params.splitHighlightHue, params.splitHighlightSat / 100f, handles.splitHiScratch)
        GLES20.glUniform4f(
            handles.uSplitSh,
            handles.splitShScratch[0],
            handles.splitShScratch[1],
            handles.splitShScratch[2],
            (params.splitShadowSat / 100f).coerceIn(0f, 1f)
        )
        GLES20.glUniform4f(
            handles.uSplitHi,
            handles.splitHiScratch[0],
            handles.splitHiScratch[1],
            handles.splitHiScratch[2],
            (params.splitHighlightSat / 100f).coerceIn(0f, 1f)
        )

        val cg = params.colorGrading
        hueSatToRgbTintInto(cg.shadows.hueDeg, cg.shadows.saturation / 100f, handles.wheelShScratch)
        hueSatToRgbTintInto(cg.midtones.hueDeg, cg.midtones.saturation / 100f, handles.wheelMidScratch)
        hueSatToRgbTintInto(cg.highlights.hueDeg, cg.highlights.saturation / 100f, handles.wheelHiScratch)
        hueSatToRgbTintInto(cg.global.hueDeg, cg.global.saturation / 100f, handles.wheelGlScratch)
        val sSat = cg.shadows.saturation / 100f
        val mSat = cg.midtones.saturation / 100f
        val hSat = cg.highlights.saturation / 100f
        val gSat = cg.global.saturation / 100f
        GLES20.glUniform4f(handles.uWheelSh, handles.wheelShScratch[0] * sSat, handles.wheelShScratch[1] * sSat, handles.wheelShScratch[2] * sSat, cg.shadows.luminance / 100f)
        GLES20.glUniform4f(handles.uWheelMid, handles.wheelMidScratch[0] * mSat, handles.wheelMidScratch[1] * mSat, handles.wheelMidScratch[2] * mSat, cg.midtones.luminance / 100f)
        GLES20.glUniform4f(handles.uWheelHi, handles.wheelHiScratch[0] * hSat, handles.wheelHiScratch[1] * hSat, handles.wheelHiScratch[2] * hSat, cg.highlights.luminance / 100f)
        GLES20.glUniform4f(handles.uWheelGl, handles.wheelGlScratch[0] * gSat, handles.wheelGlScratch[1] * gSat, handles.wheelGlScratch[2] * gSat, cg.global.luminance / 100f)
        GLES20.glUniform4f(
            handles.uWheelMix,
            cg.blending / 100f,
            cg.balance / 100f,
            if (cg.isDefault) 0f else 1f,
            if (params.hsl.isDefault) 0f else 1f
        )

        val pt = params.pointColor
        GLES20.glUniform4f(handles.uPointA, pt.sourceHueDeg, pt.sourceSat, pt.sourceLuma, pt.range / 100f)
        GLES20.glUniform4f(
            handles.uPointB,
            pt.hueShift / 100f,
            pt.saturationShift / 100f,
            pt.luminanceShift / 100f,
            if (pt.isModified) 1f else 0f
        )

        val bands = HslBand.entries
        for (i in 0 until 8) {
            val adj = params.hsl[bands[i]]
            GLES20.glUniform4f(handles.uHsl[i], adj.hue, adj.saturation, adj.luminance, 0f)
        }

        val activeMasks = params.masks.filter { it.enabled && it.hasAdjustments }.take(4)
        GLES20.glUniform4f(handles.uMaskMeta, activeMasks.size.toFloat(), 0f, 0f, 0f)
        for (i in activeMasks.indices) {
            val m = activeMasks[i]
            val typeFloat = if (m.type == MaskType.RADIAL) 0f else 1f
            GLES20.glUniform4f(
                handles.uMaskA[i],
                typeFloat,
                if (m.inverted) 1f else 0f,
                m.feather.coerceIn(0.02f, 1f),
                m.exposure
            )
            if (m.type == MaskType.RADIAL) {
                GLES20.glUniform4f(handles.uMaskB[i], m.centerX, m.centerY, m.radiusX, m.radiusY)
            } else {
                GLES20.glUniform4f(handles.uMaskB[i], m.startX, m.startY, m.endX, m.endY)
            }
            GLES20.glUniform4f(
                handles.uMaskC[i],
                m.contrast / 100f,
                m.warmth / 100f,
                m.saturation / 100f,
                m.sharpness / 100f
            )
        }
    }

    fun bindSpatialFinishUniforms(
        handles: SpatialFinishUniformHandles,
        params: GradingParams,
        lut: CubeLut?,
        packedLut: PackedLutTexture?,
        fboWidth: Int,
        fboHeight: Int,
        imageWidth: Int,
        imageHeight: Int,
        isOriginal: Boolean,
        splitRatio: Float,
        applyCrop: Boolean,
        showHighlightClipping: Boolean = false,
        showShadowClipping: Boolean = false
    ) {
        val fw = max(1, fboWidth).toFloat()
        val fh = max(1, fboHeight).toFloat()
        GLES20.glUniform4f(handles.uTexelInfo, 1f / fw, 1f / fh, fw, fh)

        GLES20.glUniform4f(
            handles.uDetailA,
            (params.texture / 100f).coerceIn(-1f, 1f),
            (params.clarity / 100f).coerceIn(-1f, 1f),
            (params.dehaze / 100f).coerceIn(-1f, 1f),
            (params.sharpening / 150f).coerceIn(0f, 1f)
        )
        GLES20.glUniform4f(
            handles.uDetailB,
            params.sharpenRadius.coerceIn(0.5f, 3.0f),
            (params.sharpenDetail / 100f).coerceIn(0f, 1f),
            (params.sharpenMasking / 100f).coerceIn(0f, 1f),
            (params.noiseReduction / 100f).coerceIn(0f, 1f)
        )
        GLES20.glUniform4f(
            handles.uDetailC,
            (params.colorNoiseReduction / 100f).coerceIn(0f, 1f),
            (params.vignette / 100f).coerceIn(-1f, 1f),
            (params.grain / 100f).coerceIn(0f, 1f),
            (params.grainSize / 100f).coerceIn(0f, 1f)
        )
        GLES20.glUniform4f(
            handles.uEffectsA,
            (params.grainRoughness / 100f).coerceIn(0f, 1f),
            (params.halation / 100f).coerceIn(0f, 1f),
            (params.bloom / 100f).coerceIn(0f, 1f),
            (params.anamorphicStreak / 100f).coerceIn(0f, 1f)
        )

        if (lut != null && packedLut != null) {
            GLES20.glUniform4f(
                handles.uLutParams,
                packedLut.lutType.toFloat(),
                packedLut.size.toFloat(),
                params.lutIntensity.coerceIn(0f, 1f),
                packedLut.tilesPerRow.toFloat()
            )
            GLES20.glUniform3f(handles.uLutDomainMin, lut.domainMin[0], lut.domainMin[1], lut.domainMin[2])
            GLES20.glUniform3f(handles.uLutDomainMax, lut.domainMax[0], lut.domainMax[1], lut.domainMax[2])
        } else {
            GLES20.glUniform4f(handles.uLutParams, 0f, 0f, 0f, 1f)
            GLES20.glUniform3f(handles.uLutDomainMin, 0f, 0f, 0f)
            GLES20.glUniform3f(handles.uLutDomainMax, 1f, 1f, 1f)
        }

        val clippingMask = (if (showHighlightClipping) 1f else 0f) + (if (showShadowClipping) 2f else 0f)
        GLES20.glUniform4f(
            handles.uCompare,
            if (isOriginal) 1f else 0f,
            splitRatio,
            if (applyCrop) 1f else 0f,
            clippingMask
        )

        GeometryMath.buildOrientToRawSourceMatrixInto(
            srcW = imageWidth,
            srcH = imageHeight,
            crop = params.crop,
            out = handles.orientMat,
            scratchA = handles.scratchMatA,
            scratchB = handles.scratchMatB
        )
        GLES20.glUniformMatrix3fv(handles.uOrientMatrix, 1, false, handles.orientMat, 0)
        if (applyCrop) {
            val rect = GeometryMath.effectiveCropRect(imageWidth, imageHeight, params.crop)
            GLES20.glUniform4f(handles.uCropRect, rect.left, rect.top, rect.right, rect.bottom)
        } else {
            GLES20.glUniform4f(handles.uCropRect, 0f, 0f, 1f, 1f)
        }
    }

    private fun hueSatToRgbTintInto(hueDeg: Float, sat: Float, out: FloatArray) {
        if (sat <= 0.0005f) {
            out[0] = 0f
            out[1] = 0f
            out[2] = 0f
            return
        }
        val h = ((hueDeg % 360f) + 360f) % 360f / 60f
        val x = 1f - abs(h % 2f - 1f)
        val r: Float
        val g: Float
        val b: Float
        when {
            h < 1f -> { r = 1f; g = x; b = 0f }
            h < 2f -> { r = x; g = 1f; b = 0f }
            h < 3f -> { r = 0f; g = 1f; b = x }
            h < 4f -> { r = 0f; g = x; b = 1f }
            h < 5f -> { r = x; g = 0f; b = 1f }
            else -> { r = 1f; g = 0f; b = x }
        }
        val avg = (r + g + b) / 3f
        out[0] = r - avg
        out[1] = g - avg
        out[2] = b - avg
    }
}
