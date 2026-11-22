package com.example.gl

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import androidx.core.graphics.createBitmap
import com.example.editor.CameraProfiles
import com.example.editor.GradingParams
import com.example.editor.ToneCurveData
import com.example.lut.CubeLut
import com.example.lut.LutSourceFormat
import com.example.lut.LutType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.sqrt

object GlGradingShaders {

    const val VERTEX_SHADER = """
        attribute vec2 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = vec4(aPosition, 0.0, 1.0);
            vTexCoord = aTexCoord;
        }
    """

    const val FRAGMENT_SHADER = """
        precision highp float;

        varying vec2 vTexCoord;

        uniform sampler2D uImageTex;
        uniform sampler2D uLutTex;
        uniform sampler2D uCurveTex;

        uniform float uIsOriginal;
        uniform float uSplitRatio;
        uniform vec2 uTexelSize;
        uniform vec2 uImageRes;

        uniform float uHasGeometry;
        uniform vec3 uGeomRow0;
        uniform vec3 uGeomRow1;
        uniform vec3 uGeomRow2;
        uniform vec4 uCropRect;

        uniform float uHasOptics;
        uniform float uLensDistortion;
        uniform float uChromaticAberration;

        uniform float uLensBlurAmount;
        uniform float uBokehFocus;

        uniform float uHasSpatial;
        uniform float uTextureAmount;
        uniform float uClarityAmount;
        uniform vec4 uSharpenParams;
        uniform vec3 uNrParams;
        uniform vec3 uColorNrParams;

        uniform float uHasProfile;
        uniform float uProfStrength;
        uniform vec4 uProfTone;
        uniform vec3 uProfGains;
        uniform vec2 uProfSat;
        uniform vec3 uProfShadowTint;
        uniform vec3 uProfHighlightTint;
        uniform vec4 uProfMono;
        uniform vec3 uProfSelective;

        uniform float uExposureMult;
        uniform vec3 uTempMult;
        uniform float uDehazeNorm;
        uniform float uWhiteGain;
        uniform float uBlackLift;
        uniform float uHighlightsStrength;
        uniform float uShadowsStrength;
        uniform float uHasParametric;
        uniform vec4 uParametric;
        uniform float uContrastFactor;

        uniform float uHasCurve;

        uniform float uHasCal;
        uniform vec3 uCalHue;
        uniform vec3 uCalSat;

        uniform float uHasHsl;
        uniform vec3 uHsl0;
        uniform vec3 uHsl1;
        uniform vec3 uHsl2;
        uniform vec3 uHsl3;
        uniform vec3 uHsl4;
        uniform vec3 uHsl5;
        uniform vec3 uHsl6;
        uniform vec3 uHsl7;

        uniform float uIsGrayscale;
        uniform float uVibranceNorm;
        uniform float uSaturationFactor;

        uniform float uHasSplitTone;
        uniform vec3 uSplitShadowTint;
        uniform vec3 uSplitHighlightTint;
        uniform vec4 uSplitParams;

        uniform float uHasPointColor;
        uniform vec3 uPointSource;
        uniform vec4 uPointShift;

        uniform float uHasColorGrading;
        uniform vec4 uCgShadowTint;
        uniform vec4 uCgMidTint;
        uniform vec4 uCgHighlightTint;
        uniform vec4 uCgGlobalTint;
        uniform vec2 uCgBlendBalance;

        uniform vec4 uVignetteParams1;
        uniform vec2 uVignetteParams2;
        uniform vec3 uGrainParams;

        uniform float uHasLut;
        uniform float uLutSize;
        uniform float uLutCols;
        uniform float uLutRows;
        uniform float uLutIs1D;
        uniform vec3 uDomainMin;
        uniform vec3 uDomainRangeInv;
        uniform float uLutIntensity;

        uniform float uMaskCount;
        uniform vec4 uMaskGeomA0;
        uniform vec4 uMaskGeomA1;
        uniform vec4 uMaskGeomA2;
        uniform vec4 uMaskGeomA3;
        uniform vec4 uMaskGeomB0;
        uniform vec4 uMaskGeomB1;
        uniform vec4 uMaskGeomB2;
        uniform vec4 uMaskGeomB3;
        uniform vec4 uMaskAdjA0;
        uniform vec4 uMaskAdjA1;
        uniform vec4 uMaskAdjA2;
        uniform vec4 uMaskAdjA3;
        uniform float uMaskSharp0;
        uniform float uMaskSharp1;
        uniform float uMaskSharp2;
        uniform float uMaskSharp3;

        float lumaRec709(vec3 c) {
            return dot(c, vec3(0.2126, 0.7152, 0.0722));
        }

        float bandWeight(float v, float center, float halfW) {
            float d = abs(v - center);
            if (d >= halfW) return 0.0;
            float t = 1.0 - d / halfW;
            return t * t * (3.0 - 2.0 * t);
        }

        float grainHash21(vec2 p) {
            return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123);
        }

        float smoothNoise2D(vec2 p) {
            vec2 i = floor(p);
            vec2 f = fract(p);
            vec2 u = f * f * (3.0 - 2.0 * f);
            float a = grainHash21(i);
            float b = grainHash21(i + vec2(1.0, 0.0));
            float c = grainHash21(i + vec2(0.0, 1.0));
            float d = grainHash21(i + vec2(1.0, 1.0));
            return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
        }

        vec3 rgbToHsl(vec3 c) {
            float maxC = max(c.r, max(c.g, c.b));
            float minC = min(c.r, min(c.g, c.b));
            float delta = maxC - minC;
            float l = (maxC + minC) * 0.5;
            if (delta < 0.00002) {
                return vec3(0.0, 0.0, l);
            }
            float s = (l > 0.5) ? (delta / (2.0 - maxC - minC)) : (delta / (maxC + minC));
            float h = 0.0;
            if (maxC == c.r) {
                h = ((c.g - c.b) / delta) + ((c.g < c.b) ? 6.0 : 0.0);
            } else if (maxC == c.g) {
                h = ((c.b - c.r) / delta) + 2.0;
            } else {
                h = ((c.r - c.g) / delta) + 4.0;
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
            if (s < 0.00002) {
                return vec3(l);
            }
            float q = (l < 0.5) ? (l * (1.0 + s)) : (l + s - l * s);
            float p = 2.0 * l - q;
            float hk = h / 360.0;
            return vec3(
                clamp(hue2rgb(p, q, hk + 1.0 / 3.0), 0.0, 1.0),
                clamp(hue2rgb(p, q, hk), 0.0, 1.0),
                clamp(hue2rgb(p, q, hk - 1.0 / 3.0), 0.0, 1.0)
            );
        }

        void accumHsl(float hueDeg, float center, float span, vec3 adj, inout vec3 acc, inout float wSum) {
            float dist = abs(hueDeg - center);
            if (dist > 180.0) dist = 360.0 - dist;
            if (dist < span) {
                float w = 1.0 - dist / span;
                wSum += w;
                acc += adj * w;
            }
        }

        vec3 applyHslMixer(vec3 c) {
            vec3 hsl = rgbToHsl(c);
            if (hsl.y <= 0.002) return c;
            vec3 acc = vec3(0.0);
            float wSum = 0.0;
            accumHsl(hsl.x, 0.0, 45.0, uHsl0, acc, wSum);
            accumHsl(hsl.x, 30.0, 45.0, uHsl1, acc, wSum);
            accumHsl(hsl.x, 60.0, 45.0, uHsl2, acc, wSum);
            accumHsl(hsl.x, 120.0, 55.0, uHsl3, acc, wSum);
            accumHsl(hsl.x, 180.0, 55.0, uHsl4, acc, wSum);
            accumHsl(hsl.x, 240.0, 55.0, uHsl5, acc, wSum);
            accumHsl(hsl.x, 285.0, 55.0, uHsl6, acc, wSum);
            accumHsl(hsl.x, 330.0, 55.0, uHsl7, acc, wSum);
            if (wSum > 0.0001) {
                acc /= wSum;
            }
            float newH = mod(hsl.x + (acc.x / 100.0) * 32.0 + 360.0, 360.0);
            float newS = clamp(hsl.y * (1.0 + acc.y / 100.0), 0.0, 1.0);
            float newL = clamp(hsl.z + (acc.z / 100.0) * 0.22 * hsl.y, 0.0, 1.0);
            return hslToRgb(vec3(newH, newS, newL));
        }

        float smoothRamp(float e0, float e1, float x) {
            float t = clamp((x - e0) / max(e1 - e0, 0.0001), 0.0, 1.0);
            return t * t * (3.0 - 2.0 * t);
        }

        vec3 srgbToLinear(vec3 c) {
            vec3 cc = clamp(c, 0.0, 1.0);
            vec3 low = cc / 12.92;
            vec3 high = pow(max((cc + 0.055) / 1.055, vec3(0.0001)), vec3(2.4));
            vec3 mask = step(vec3(0.04045), cc);
            return mix(low, high, mask);
        }

        vec3 linearToSrgb(vec3 c) {
            vec3 cc = max(c, vec3(0.0));
            vec3 low = cc * 12.92;
            vec3 high = 1.055 * pow(max(cc, vec3(0.0031308)), vec3(1.0 / 2.4)) - 0.055;
            vec3 mask = step(vec3(0.0031308), cc);
            return clamp(mix(low, high, mask), 0.0, 1.0);
        }

        vec3 applyPointColor(vec3 c) {
            vec3 hsl = rgbToHsl(c);
            if (hsl.y <= 0.01) return c;
            float rangeNorm = clamp(uPointShift.w / 100.0, 0.0, 1.0);
            float dh = abs(hsl.x - uPointSource.x);
            if (dh > 180.0) dh = 360.0 - dh;
            float hueW = mix(14.0, 75.0, rangeNorm);
            float hw = 1.0 - smoothRamp(0.0, hueW, dh);

            float satW = mix(0.20, 0.88, rangeNorm);
            float sw = clamp(1.0 - abs(hsl.y - uPointSource.y) / satW, 0.0, 1.0);

            float lumW = mix(0.28, 0.92, rangeNorm);
            float lw = clamp(1.0 - abs(hsl.z - uPointSource.z) / lumW, 0.0, 1.0);

            float mask = hw * mix(0.45, 1.0, sw) * mix(0.55, 1.0, lw);
            if (mask <= 0.001) return c;

            float newH = mod(hsl.x + (uPointShift.x / 100.0) * 45.0 * mask + 360.0, 360.0);
            float newS = clamp(hsl.y * (1.0 + (uPointShift.y / 100.0) * mask), 0.0, 1.0);
            float newL = clamp(hsl.z + (uPointShift.z / 100.0) * 0.25 * mask, 0.0, 1.0);
            return hslToRgb(vec3(newH, newS, newL));
        }

        vec3 applyColorGradingWheels(vec3 cIn) {
            float l = lumaRec709(cIn);
            float blend = clamp(uCgBlendBalance.x, 0.0, 1.0);
            float pivot = clamp(0.5 + uCgBlendBalance.y * 0.24, 0.22, 0.78);
            float halfW = mix(0.22, 0.60, blend);

            float shW = 1.0 - smoothRamp(max(0.0, pivot - halfW), min(1.0, pivot + halfW * 0.35), l);
            float hiW = smoothRamp(max(0.0, pivot - halfW * 0.35), min(1.0, pivot + halfW), l);
            float dMid = (l - pivot) / max(halfW * 0.9, 0.16);
            float midW = exp(-dMid * dMid * 1.45);

            vec3 outC = cIn;
            outC += uCgShadowTint.rgb * shW * (1.0 - l * 0.35) + vec3(uCgShadowTint.a * shW * 0.65);
            outC += uCgMidTint.rgb * midW + vec3(uCgMidTint.a * midW * 0.75);
            outC += uCgHighlightTint.rgb * hiW * (0.45 + l * 0.55) + vec3(uCgHighlightTint.a * hiW * 0.65);
            outC += uCgGlobalTint.rgb + vec3(uCgGlobalTint.a);
            return clamp(outC, 0.0, 1.0);
        }

        vec3 sampleLut3DTiled(vec3 rgb) {
            vec3 norm = clamp((rgb - uDomainMin) * uDomainRangeInv, 0.0, 1.0);
            float n = uLutSize;
            float maxIdx = n - 1.0;

            float fb = norm.b * maxIdx;
            float b0 = floor(fb);
            float b1 = min(b0 + 1.0, maxIdx);
            float db = fb - b0;

            float tileW = n;
            float totalW = uLutCols * n;
            float totalH = uLutRows * n;

            float col0 = mod(b0, uLutCols);
            float row0 = floor(b0 / uLutCols);
            float u0 = (col0 * tileW + 0.5 + norm.r * maxIdx) / totalW;
            float v0 = (row0 * tileW + 0.5 + norm.g * maxIdx) / totalH;

            float col1 = mod(b1, uLutCols);
            float row1 = floor(b1 / uLutCols);
            float u1 = (col1 * tileW + 0.5 + norm.r * maxIdx) / totalW;
            float v1 = (row1 * tileW + 0.5 + norm.g * maxIdx) / totalH;

            vec3 c0 = texture2D(uLutTex, vec2(u0, v0)).rgb;
            vec3 c1 = texture2D(uLutTex, vec2(u1, v1)).rgb;
            return mix(c0, c1, db);
        }

        vec3 sampleLut1D(vec3 rgb) {
            vec3 norm = clamp((rgb - uDomainMin) * uDomainRangeInv, 0.0, 1.0);
            float n = uLutSize;
            float maxIdx = n - 1.0;
            float ur = (0.5 + norm.r * maxIdx) / n;
            float ug = (0.5 + norm.g * maxIdx) / n;
            float ub = (0.5 + norm.b * maxIdx) / n;
            float rOut = texture2D(uLutTex, vec2(ur, 0.5)).r;
            float gOut = texture2D(uLutTex, vec2(ug, 0.5)).g;
            float bOut = texture2D(uLutTex, vec2(ub, 0.5)).b;
            return vec3(rOut, gOut, bOut);
        }

        vec3 applyCameraProfile(vec3 cIn) {
            float st = uProfStrength;
            vec3 p = clamp(cIn * (vec3(1.0) + uProfGains * st), 0.0, 1.0);

            if (uProfSelective.x != 0.0 || uProfSelective.y != 0.0 || uProfSelective.z != 0.0) {
                float lumaS = lumaRec709(p);
                float greenMask = clamp((p.g - max(p.r, p.b) * 0.88) * 3.0, 0.0, 1.0);
                float skyMask = clamp((p.b - max(p.r, p.g) * 0.92) * 3.0, 0.0, 1.0);
                float skinMask = clamp((p.r - p.b) * 2.2, 0.0, 1.0)
                    * clamp((p.g - p.b * 0.9) * 2.0, 0.0, 1.0)
                    * clamp(1.0 - abs(lumaS - 0.58) * 1.4, 0.0, 1.0);

                float foil = uProfSelective.x * st * greenMask;
                p.g = clamp(p.g + foil * 0.07, 0.0, 1.0);
                p.b = clamp(p.b + foil * 0.03, 0.0, 1.0);

                float skin = uProfSelective.y * st * skinMask;
                p.r = clamp(p.r + skin * 0.05, 0.0, 1.0);
                p.g = clamp(p.g + skin * 0.025, 0.0, 1.0);

                float sky = uProfSelective.z * st * skyMask;
                p.b = clamp(p.b + sky * 0.06, 0.0, 1.0);
                p.r = clamp(p.r - sky * 0.025, 0.0, 1.0);
            }

            float monoT = clamp(uProfMono.x * st, 0.0, 1.0);
            if (monoT > 0.001) {
                float monoLuma = clamp(dot(p, uProfMono.yzw), 0.0, 1.0);
                p = mix(p, vec3(monoLuma), monoT);
            }

            if (monoT < 0.999) {
                if (uProfSat.y != 0.0) {
                    float maxC = max(p.r, max(p.g, p.b));
                    float minC = min(p.r, min(p.g, p.b));
                    float vibMult = max(0.0, 1.0 + uProfSat.y * st * (1.0 - (maxC - minC)) * 0.85);
                    float lum = lumaRec709(p);
                    p = clamp(mix(vec3(lum), p, vibMult), 0.0, 1.0);
                }
                if (uProfSat.x != 0.0) {
                    float satMult = max(0.0, 1.0 + uProfSat.x * st);
                    float lum = lumaRec709(p);
                    p = clamp(mix(vec3(lum), p, satMult), 0.0, 1.0);
                }
            }

            float pLuma = lumaRec709(p);
            float midMask = 4.0 * pLuma * (1.0 - pLuma);
            float cGain = 1.0 + uProfTone.x * st + uProfTone.w * st * midMask;
            p = clamp((p - 0.5) * cGain + 0.5, 0.0, 1.0);

            if (uProfTone.y != 0.0) {
                float toeW = pow(clamp(1.0 - lumaRec709(p), 0.0, 1.0), 2.0);
                p = clamp(p + vec3(uProfTone.y * st * toeW), 0.0, 1.0);
            }
            if (uProfTone.z != 0.0) {
                float hiW = pow(clamp(lumaRec709(p), 0.0, 1.0), 1.8);
                p = clamp(p - vec3(uProfTone.z * st * hiW * 0.35), 0.0, 1.0);
            }

            float l2 = lumaRec709(p);
            float shCastW = pow(max(1.0 - l2, 0.0), 1.6) * st;
            float hiCastW = pow(max(l2, 0.0), 1.6) * st;
            p = clamp(p + uProfShadowTint * shCastW + uProfHighlightTint * hiCastW, 0.0, 1.0);

            return p;
        }

        vec2 mapViewUvToSource(vec2 viewUv, out bool oob) {
            vec2 oUv = uCropRect.xy + viewUv * uCropRect.zw;
            if (uHasGeometry < 0.5) {
                oob = false;
                return clamp(oUv, 0.0, 1.0);
            }
            vec3 h = vec3(oUv, 1.0);
            float w = dot(uGeomRow2, h);
            if (w <= 0.0001) {
                oob = true;
                return vec2(0.0);
            }
            vec2 su = vec2(dot(uGeomRow0, h), dot(uGeomRow1, h)) / w;
            oob = (su.x < 0.0 || su.x > 1.0 || su.y < 0.0 || su.y > 1.0);
            return su;
        }

        vec3 sampleSourceClamped(vec2 viewUv) {
            bool dummyOob;
            vec2 su = mapViewUvToSource(clamp(viewUv, 0.0, 1.0), dummyOob);
            return texture2D(uImageTex, clamp(su, 0.0, 1.0)).rgb;
        }

        float evaluateMaskWeight(vec2 viewUv, vec4 geomA, vec4 geomB) {
            float w = 0.0;
            if (geomA.x < 0.5) {
                // Radial gradient: geomA=(0, cx, cy, inv), geomB=(rx, ry, feather, 0)
                float rx = max(geomB.x, 0.02);
                float ry = max(geomB.y, 0.02);
                vec2 d = vec2((viewUv.x - geomA.y) / rx, (viewUv.y - geomA.z) / ry);
                float dist = length(d);
                float f = clamp(geomB.z, 0.0, 1.0);
                float inner = clamp(1.0 - f, 0.0, 0.98);
                float outer = max(1.0 + f * 0.15, inner + 0.02);
                w = 1.0 - smoothRamp(inner, outer, dist);
            } else {
                // Linear gradient: geomA=(1, sx, sy, inv), geomB=(ex, ey, aspect, 0)
                float safeAspect = max(geomB.z, 0.1);
                vec2 p = vec2((viewUv.x - geomA.y) * safeAspect, viewUv.y - geomA.z);
                vec2 d = vec2((geomB.x - geomA.y) * safeAspect, geomB.y - geomA.z);
                float len2 = max(dot(d, d), 0.0001);
                float proj = clamp(dot(p, d) / len2, 0.0, 1.0);
                w = 1.0 - (proj * proj * (3.0 - 2.0 * proj));
            }
            if (geomA.w > 0.5) {
                w = 1.0 - w;
            }
            return clamp(w, 0.0, 1.0);
        }

        vec3 applySingleLocalMask(vec3 cIn, vec2 viewUv, vec4 geomA, vec4 geomB, vec4 adjA, float sharpNorm) {
            float w = evaluateMaskWeight(viewUv, geomA, geomB);
            if (w <= 0.001) return cIn;
            vec3 mc = cIn;
            // Exposure EV shift inside mask in linear light
            if (abs(adjA.x) > 0.001) {
                mc = linearToSrgb(srgbToLinear(mc) * pow(2.0, adjA.x * 1.15 * w));
            }
            // Local Warmth
            if (abs(adjA.z) > 0.001) {
                float wn = adjA.z * w;
                mc.r = clamp(mc.r * max(0.15, 1.0 + wn * 0.44), 0.0, 1.0);
                mc.g = clamp(mc.g * max(0.15, 1.0 + wn * 0.06), 0.0, 1.0);
                mc.b = clamp(mc.b * max(0.15, 1.0 - wn * 0.46), 0.0, 1.0);
            }
            // Local Contrast
            if (abs(adjA.y) > 0.001) {
                float cScale = (adjA.y >= 0.0) ? 1.25 : 0.85;
                float cFac = max(0.15, 1.0 + adjA.y * cScale * w);
                mc = clamp((mc - 0.5) * cFac + 0.5, 0.0, 1.0);
            }
            // Local Saturation
            if (abs(adjA.w) > 0.001) {
                float lum = lumaRec709(mc);
                float satScale = (adjA.w >= 0.0) ? 1.40 : 1.0;
                float satFac = max(0.0, 1.0 + adjA.w * satScale * w);
                mc = clamp(mix(vec3(lum), mc, satFac), 0.0, 1.0);
            }
            // Local Sharpness (only samples neighbors for masked pixels)
            if (sharpNorm > 0.001) {
                vec2 sOff = uTexelSize * 1.35;
                float lC = lumaRec709(sampleSourceClamped(viewUv));
                float lL = lumaRec709(sampleSourceClamped(viewUv - vec2(sOff.x, 0.0)));
                float lR = lumaRec709(sampleSourceClamped(viewUv + vec2(sOff.x, 0.0)));
                float lU = lumaRec709(sampleSourceClamped(viewUv - vec2(0.0, sOff.y)));
                float lD = lumaRec709(sampleSourceClamped(viewUv + vec2(0.0, sOff.y)));
                float avgNearL = (lL + lR + lU + lD) * 0.25;
                mc = clamp(mc + vec3((lC - avgNearL) * sharpNorm * 1.55 * w), 0.0, 1.0);
            }
            return mc;
        }

        void main() {
            vec2 uv = vTexCoord;
            bool oob = false;
            vec2 srcUv = mapViewUvToSource(uv, oob);
            vec4 texel;
            if (uHasOptics > 0.5) {
                vec2 d = uv - vec2(0.5);
                float r2 = dot(d, d);
                float radialScale = 1.0 + uLensDistortion * r2;
                uv = clamp(vec2(0.5) + d * radialScale, 0.0, 1.0);
                srcUv = mapViewUvToSource(uv, oob);
                if (oob) {
                    gl_FragColor = vec4(0.043, 0.055, 0.078, 1.0);
                    return;
                }
                texel = texture2D(uImageTex, clamp(srcUv, 0.0, 1.0));
                if (uChromaticAberration > 0.0) {
                    texel.r = sampleSourceClamped(vec2(0.5) + d * (radialScale + uChromaticAberration)).r;
                    texel.b = sampleSourceClamped(vec2(0.5) + d * (radialScale - uChromaticAberration)).b;
                }
            } else {
                if (oob) {
                    gl_FragColor = vec4(0.043, 0.055, 0.078, 1.0);
                    return;
                }
                texel = texture2D(uImageTex, clamp(srcUv, 0.0, 1.0));
            }

            if (uIsOriginal > 0.5 || (uSplitRatio >= 0.0 && vTexCoord.x < uSplitRatio)) {
                gl_FragColor = texel;
                return;
            }

            vec3 c = texel.rgb;

            // 0. Lens Blur (radial depth-of-field bokeh disk blur)
            if (uLensBlurAmount > 0.001) {
                float radial = length(uv - vec2(0.5)) * 1.4142135;
                float focusRadius = uBokehFocus * 0.62;
                float blurMask = smoothstep(focusRadius * 0.45, focusRadius + 0.36, radial);
                if (blurMask > 0.01) {
                    float minDim = min(uImageRes.x, uImageRes.y);
                    vec2 blurRadius = (minDim / uImageRes) * (uLensBlurAmount * 0.018 * blurMask);
                    vec3 acc = vec3(0.0);
                    float wTotal = 0.0;

                    // Center + 12-tap Poisson bokeh disk with highlight bloom weighting
                    vec2 taps[12];
                    taps[0] = vec2(-0.326, -0.406);
                    taps[1] = vec2(-0.840, -0.074);
                    taps[2] = vec2(-0.696,  0.457);
                    taps[3] = vec2(-0.203,  0.621);
                    taps[4] = vec2( 0.962, -0.195);
                    taps[5] = vec2( 0.473, -0.480);
                    taps[6] = vec2( 0.519,  0.767);
                    taps[7] = vec2( 0.185, -0.893);
                    taps[8] = vec2( 0.507,  0.064);
                    taps[9] = vec2( 0.896,  0.412);
                    taps[10] = vec2(-0.322, -0.933);
                    taps[11] = vec2(-0.792, -0.598);

                    float cLuma = lumaRec709(c);
                    float cw = 1.0 + cLuma * cLuma * 1.4;
                    acc += c * cw;
                    wTotal += cw;

                    for (int i = 0; i < 12; i++) {
                        vec3 s = sampleSourceClamped(uv + taps[i] * blurRadius);
                        float sLuma = lumaRec709(s);
                        float w = 1.0 + sLuma * sLuma * 1.6;
                        acc += s * w;
                        wTotal += w;
                    }
                    vec3 blurred = acc / max(wTotal, 0.001);
                    c = mix(c, blurred, clamp(blurMask * 1.25, 0.0, 1.0));
                }
            }

            // Spatial Detail: Luminance Noise Reduction, Color Noise Reduction, Texture, Sharpening, Clarity
            if (uHasSpatial > 0.5) {
                vec3 cL = sampleSourceClamped(uv - vec2(uTexelSize.x, 0.0));
                vec3 cR = sampleSourceClamped(uv + vec2(uTexelSize.x, 0.0));
                vec3 cU = sampleSourceClamped(uv - vec2(0.0, uTexelSize.y));
                vec3 cD = sampleSourceClamped(uv + vec2(0.0, uTexelSize.y));
                vec3 avgNear = (cL + cR + cU + cD) * 0.25;

                // Luminance Noise Reduction (Amount + Detail + Contrast)
                if (uNrParams.x > 0.001) {
                    vec2 nrOff = uTexelSize * 2.5;
                    vec3 nrL = sampleSourceClamped(uv - vec2(nrOff.x, 0.0));
                    vec3 nrR = sampleSourceClamped(uv + vec2(nrOff.x, 0.0));
                    vec3 nrU = sampleSourceClamped(uv - vec2(0.0, nrOff.y));
                    vec3 nrD = sampleSourceClamped(uv + vec2(0.0, nrOff.y));
                    float nrWideLuma = lumaRec709((nrL + nrR + nrU + nrD) * 0.25);
                    float nrNearLuma = lumaRec709(avgNear);
                    float nrTargetLuma = mix(nrWideLuma, nrNearLuma, 0.30 + uNrParams.y * 0.50);

                    float curLuma = lumaRec709(c);
                    float dLumaMin = min(
                        min(abs(curLuma - lumaRec709(cL)), abs(curLuma - lumaRec709(cR))),
                        min(abs(curLuma - lumaRec709(cU)), abs(curLuma - lumaRec709(cD)))
                    );
                    float coherentEdge = max(abs(curLuma - nrTargetLuma) - dLumaMin * 0.85, 0.0);
                    float detailScale = mix(1.8, 15.0, uNrParams.y);
                    float edgeProtect = clamp(1.0 - coherentEdge * detailScale, 0.08, 1.0);
                    float smoothedLuma = mix(curLuma, nrTargetLuma, uNrParams.x * 0.84 * edgeProtect);
                    float contrastRestore = (smoothedLuma - nrWideLuma) * uNrParams.z * uNrParams.x * 0.50;
                    float nrLumaDelta = (smoothedLuma + contrastRestore) - curLuma;
                    c = clamp(c + vec3(nrLumaDelta), 0.0, 1.0);
                }

                // Color Noise Reduction (Amount + Detail + Smoothness; preserves luminance Y)
                if (uColorNrParams.x > 0.001) {
                    float curLumaCnr = lumaRec709(c);
                    vec3 chromaC = c - vec3(curLumaCnr);

                    float cRadius = mix(1.4, 4.5, uColorNrParams.z);
                    vec2 cOff = uTexelSize * cRadius;
                    vec3 cn0 = sampleSourceClamped(uv + vec2(-cOff.x, -cOff.y));
                    vec3 cn1 = sampleSourceClamped(uv + vec2( cOff.x, -cOff.y));
                    vec3 cn2 = sampleSourceClamped(uv + vec2(-cOff.x,  cOff.y));
                    vec3 cn3 = sampleSourceClamped(uv + vec2( cOff.x,  cOff.y));

                    vec3 ch0 = cn0 - vec3(lumaRec709(cn0));
                    vec3 ch1 = cn1 - vec3(lumaRec709(cn1));
                    vec3 ch2 = cn2 - vec3(lumaRec709(cn2));
                    vec3 ch3 = cn3 - vec3(lumaRec709(cn3));
                    vec3 chNear = avgNear - vec3(lumaRec709(avgNear));
                    vec3 avgChroma = (ch0 + ch1 + ch2 + ch3 + chNear * 2.0) / 6.0;

                    vec3 chL = cL - vec3(lumaRec709(cL));
                    vec3 chR = cR - vec3(lumaRec709(cR));
                    vec3 chU = cU - vec3(lumaRec709(cU));
                    vec3 chD = cD - vec3(lumaRec709(cD));
                    float dChromaMin = min(
                        min(length(chromaC - chL), length(chromaC - chR)),
                        min(length(chromaC - chU), length(chromaC - chD))
                    );
                    float chromaEdge = max(length(chromaC - avgChroma) - dChromaMin * 0.85, 0.0);
                    float chromaProtect = clamp(1.0 - chromaEdge * mix(0.25, 4.2, uColorNrParams.y), 0.14, 1.0);
                    vec3 cleanChroma = mix(chromaC, avgChroma, uColorNrParams.x * 0.94 * chromaProtect);
                    c = clamp(vec3(curLumaCnr) + cleanChroma, 0.0, 1.0);
                }

                float lumaC = lumaRec709(c);
                float lumaNear = lumaRec709(avgNear);
                float localDelta = (lumaC - lumaNear) * uTextureAmount;

                // Sharpening (Amount + Radius + Detail + Masking)
                if (uSharpenParams.x > 0.001) {
                    vec2 sOff = uTexelSize * uSharpenParams.y;
                    vec3 sL = sampleSourceClamped(uv - vec2(sOff.x, 0.0));
                    vec3 sR = sampleSourceClamped(uv + vec2(sOff.x, 0.0));
                    vec3 sU = sampleSourceClamped(uv - vec2(0.0, sOff.y));
                    vec3 sD = sampleSourceClamped(uv + vec2(0.0, sOff.y));
                    float lumaRing = lumaRec709((sL + sR + sU + sD) * 0.25);

                    float unsharpDelta = lumaC - lumaRing;
                    float fineDelta = lumaC - lumaNear;
                    float detailN = uSharpenParams.z;
                    float sharpSignal = unsharpDelta * (0.85 - 0.15 * detailN) + fineDelta * (0.15 + detailN * 0.95);

                    float edgeMask = 1.0;
                    if (uSharpenParams.w > 0.001) {
                        float g1 = abs(lumaRec709(cR) - lumaRec709(cL)) + abs(lumaRec709(cD) - lumaRec709(cU));
                        float gR = abs(lumaRec709(sR) - lumaRec709(sL)) + abs(lumaRec709(sD) - lumaRec709(sU));
                        float edgeMag = max(g1, gR) * 0.5;
                        float thresh = uSharpenParams.w * 0.18;
                        edgeMask = smoothRamp(thresh * 0.45, max(thresh * 1.45, 0.002), edgeMag);
                    }
                    localDelta += sharpSignal * uSharpenParams.x * 1.45 * edgeMask;
                }

                if (uClarityAmount != 0.0) {
                    vec2 wideOff = uTexelSize * 4.0;
                    vec3 wL = sampleSourceClamped(uv - vec2(wideOff.x, 0.0));
                    vec3 wR = sampleSourceClamped(uv + vec2(wideOff.x, 0.0));
                    vec3 wU = sampleSourceClamped(uv - vec2(0.0, wideOff.y));
                    vec3 wD = sampleSourceClamped(uv + vec2(0.0, wideOff.y));
                    float wideLuma = lumaRec709((wL + wR + wU + wD) * 0.25);
                    float midMask = clamp(1.0 - abs(lumaC - 0.5) * 1.8, 0.2, 1.0);
                    localDelta += (lumaC - wideLuma) * uClarityAmount * midMask;
                }
                c = clamp(c + vec3(localDelta), 0.0, 1.0);
            }

            // 0.5. Foundational Camera Profile (non-destructive base layer before user sliders)
            if (uHasProfile > 0.5) {
                c = applyCameraProfile(c);
            }

            // 1. Exposure (in linear light) + Temperature + Tint
            if (abs(uExposureMult - 1.0) > 0.0005) {
                c = linearToSrgb(srgbToLinear(c) * uExposureMult);
            }
            c *= uTempMult;
            c = clamp(c, 0.0, 1.0);

            // 2. Dehaze
            if (uDehazeNorm != 0.0) {
                float dContrast = max(0.25, 1.0 + uDehazeNorm * 0.48);
                float dShift = -uDehazeNorm * 0.11;
                c = clamp((c - 0.5) * dContrast + 0.5 + dShift, 0.0, 1.0);
                float luma = lumaRec709(c);
                c = clamp(mix(vec3(luma), c, max(0.0, 1.0 + uDehazeNorm * 0.36)), 0.0, 1.0);
            }

            // 3. Whites & Blacks (tonally tapered so negative Whites don't crush shadows)
            if (uBlackLift != 0.0 || uWhiteGain != 1.0) {
                float wbLuma = lumaRec709(c);
                float hiMask = smoothRamp(0.28, 0.96, wbLuma);
                float shMask = 1.0 - smoothRamp(0.0, 0.62, wbLuma);
                vec3 whiteAdj = mix(c, c * uWhiteGain, hiMask) + vec3((uWhiteGain - 1.0) * 0.22 * hiMask * hiMask);
                c = clamp(whiteAdj + vec3(uBlackLift * shMask), 0.0, 1.0);
            }

            // 4. Highlights, Shadows & Parametric Tones
            if (uHighlightsStrength != 0.0 || uShadowsStrength != 0.0 || uHasParametric > 0.5) {
                float luma = lumaRec709(c);
                float delta = 0.0;
                if (uShadowsStrength != 0.0) {
                    float shT = clamp((0.64 - luma) / 0.64, 0.0, 1.0);
                    delta += uShadowsStrength * (shT * shT * (3.0 - 2.0 * shT)) * 0.56;
                }
                if (uHighlightsStrength != 0.0) {
                    float hiT = clamp((luma - 0.36) / 0.64, 0.0, 1.0);
                    delta += uHighlightsStrength * (hiT * hiT * (3.0 - 2.0 * hiT)) * 0.56;
                }
                if (uHasParametric > 0.5) {
                    delta += (
                        uParametric.x * bandWeight(luma, 0.12, 0.28) +
                        uParametric.y * bandWeight(luma, 0.35, 0.26) +
                        uParametric.z * bandWeight(luma, 0.65, 0.26) +
                        uParametric.w * bandWeight(luma, 0.88, 0.28)
                    ) * 0.22;
                }
                c = clamp(c + vec3(delta), 0.0, 1.0);
            }

            // 5. Contrast
            c = clamp((c - 0.5) * uContrastFactor + 0.5, 0.0, 1.0);

            // 6. Tone Curves (Master in row 1 v=0.75, R/G/B in row 0 v=0.25)
            if (uHasCurve > 0.5) {
                float mR = texture2D(uCurveTex, vec2((0.5 + c.r * 255.0) / 256.0, 0.75)).r;
                float mG = texture2D(uCurveTex, vec2((0.5 + c.g * 255.0) / 256.0, 0.75)).r;
                float mB = texture2D(uCurveTex, vec2((0.5 + c.b * 255.0) / 256.0, 0.75)).r;
                c.r = texture2D(uCurveTex, vec2((0.5 + mR * 255.0) / 256.0, 0.25)).r;
                c.g = texture2D(uCurveTex, vec2((0.5 + mG * 255.0) / 256.0, 0.25)).g;
                c.b = texture2D(uCurveTex, vec2((0.5 + mB * 255.0) / 256.0, 0.25)).b;
            }

            // 7. RGB Primary Calibration (row-normalized to preserve neutral grays)
            if (uHasCal > 0.5) {
                float rShift = uCalHue.r * 0.18;
                float gShift = uCalHue.g * 0.18;
                float bShift = uCalHue.b * 0.18;
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

                vec3 calRgb = vec3(nr, ng, nb);
                float luma = lumaRec709(calRgb);
                c = clamp(vec3(luma) + (calRgb - vec3(luma)) * uCalSat, 0.0, 1.0);
            }

            // 8. 8-Band HSL Color Mixer
            if (uHasHsl > 0.5 && uIsGrayscale < 0.5) {
                c = applyHslMixer(c);
            }

            // 8.5. Point Color (targeted single-color isolation)
            if (uHasPointColor > 0.5 && uIsGrayscale < 0.5) {
                c = applyPointColor(c);
            }

            // 9. Grayscale or Vibrance + Saturation
            if (uIsGrayscale > 0.5) {
                c = vec3(lumaRec709(c));
            } else {
                if (uVibranceNorm != 0.0) {
                    float maxC = max(c.r, max(c.g, c.b));
                    float minC = min(c.r, min(c.g, c.b));
                    float vibMult = max(0.0, 1.0 + uVibranceNorm * (1.0 - (maxC - minC) * 0.75) * 1.35);
                    float luma = lumaRec709(c);
                    c = clamp(mix(vec3(luma), c, vibMult), 0.0, 1.0);
                }
                if (uSaturationFactor != 1.0) {
                    float luma = lumaRec709(c);
                    c = clamp(mix(vec3(luma), c, uSaturationFactor), 0.0, 1.0);
                }
            }

            // 10. Split Toning
            if (uHasSplitTone > 0.5) {
                float luma = lumaRec709(c);
                float shW = pow(max(1.0 - luma, 0.0), 1.4) * uSplitParams.x * 0.35 * uSplitParams.z;
                float hiW = pow(max(luma, 0.0), 1.4) * uSplitParams.y * 0.35 * uSplitParams.w;
                c = clamp(c + uSplitShadowTint * shW + uSplitHighlightTint * hiW, 0.0, 1.0);
            }

            // 10.5. Color Grading (Shadows / Midtones / Highlights / Global Color Wheels)
            if (uHasColorGrading > 0.5) {
                c = applyColorGradingWheels(c);
            }

            // 10.8. Selective Local Masks (Radial & Linear gradients)
            if (uMaskCount > 0.5) {
                c = applySingleLocalMask(c, vTexCoord, uMaskGeomA0, uMaskGeomB0, uMaskAdjA0, uMaskSharp0);
                if (uMaskCount > 1.5) {
                    c = applySingleLocalMask(c, vTexCoord, uMaskGeomA1, uMaskGeomB1, uMaskAdjA1, uMaskSharp1);
                }
                if (uMaskCount > 2.5) {
                    c = applySingleLocalMask(c, vTexCoord, uMaskGeomA2, uMaskGeomB2, uMaskAdjA2, uMaskSharp2);
                }
                if (uMaskCount > 3.5) {
                    c = applySingleLocalMask(c, vTexCoord, uMaskGeomA3, uMaskGeomB3, uMaskAdjA3, uMaskSharp3);
                }
            }

            // 11. Vignette (Highlight Priority, Color Priority, Paint Overlay + Midpoint/Feather/Roundness/Highlights)
            if (abs(uVignetteParams1.x) > 0.001) {
                float vigNorm = uVignetteParams1.x;
                float vigMidpoint = uVignetteParams1.y;
                float vigFeather = uVignetteParams1.z;
                float vigRoundness = uVignetteParams1.w;
                float vigStyle = uVignetteParams2.x;
                float vigHiProtect = uVignetteParams2.y;

                float minRes = max(min(uImageRes.x, uImageRes.y), 1.0);
                vec2 aspectVec = uImageRes / minRes;
                vec2 dNorm = (vTexCoord - vec2(0.5)) * 2.0;
                vec2 dSquare = dNorm * aspectVec;
                vec2 dShape = (vigRoundness >= 0.0) ? mix(dNorm, dSquare, vigRoundness * 0.72) : dNorm;
                float pExp = (vigRoundness < 0.0) ? mix(2.0, 5.0, -vigRoundness) : 2.0;
                float dist = pow(
                    pow(max(abs(dShape.x), 0.0001), pExp) + pow(max(abs(dShape.y), 0.0001), pExp),
                    1.0 / pExp
                );
                if (vigRoundness < 0.0) {
                    dist *= mix(1.0, 1.18, -vigRoundness);
                }

                float midR = mix(0.16, 1.14, vigMidpoint);
                float halfFeather = mix(0.025, 0.96, vigFeather);
                float edge0 = max(midR - halfFeather * 0.5, 0.0);
                float edge1 = max(midR + halfFeather * 0.5, edge0 + 0.02);
                float mask = smoothstep(edge0, edge1, dist);

                float lum = lumaRec709(c);
                float hiFactor = smoothstep(0.52, 0.98, lum);
                float protect = (vigNorm < 0.0) ? (1.0 - hiFactor * vigHiProtect * 0.88) : 1.0;

                if (vigStyle > 1.5) {
                    // Paint Overlay
                    vec3 target = (vigNorm < 0.0) ? vec3(0.0) : vec3(1.0);
                    float amt = clamp(abs(vigNorm) * mask * protect * 0.92, 0.0, 0.95);
                    c = mix(c, target, amt);
                } else if (vigStyle > 0.5) {
                    // Color Priority (preserves chroma richness while darkening/brightening)
                    float effectiveVig = vigNorm * mask * protect;
                    float targetLum = clamp(lum * (1.0 + effectiveVig * 0.92), 0.0, 1.0);
                    float ratio = (lum > 0.002) ? (targetLum / lum) : (1.0 + effectiveVig);
                    vec3 colored = clamp(c * ratio, 0.0, 1.0);
                    if (effectiveVig < 0.0) {
                        float chromaKeep = 1.0 + (-effectiveVig) * 0.16;
                        colored = clamp(mix(vec3(targetLum), colored, chromaKeep), 0.0, 1.0);
                    }
                    c = colored;
                } else {
                    // Highlight Priority (smooth exposure rolloff + highlight recovery protection)
                    float vigGain = clamp(1.0 + vigNorm * mask * protect, 0.04, 1.92);
                    vec3 vigCol = c * vigGain;
                    if (vigNorm < 0.0 && vigHiProtect > 0.001) {
                        vigCol = mix(vigCol, max(vigCol, c * (1.0 - mask * 0.20)), hiFactor * vigHiProtect);
                    }
                    c = clamp(vigCol, 0.0, 1.0);
                }
            }

            // 12. Film Grain (Amount + Size + Roughness clumping)
            if (uGrainParams.x > 0.0005) {
                float gStrength = uGrainParams.x;
                float gSize = uGrainParams.y;
                float gRough = uGrainParams.z;

                float cellPx = mix(0.75, 4.6, gSize * gSize * 0.65 + gSize * 0.35);
                vec2 gCoord = (vTexCoord * uImageRes) / cellPx;

                float nFine = smoothNoise2D(gCoord) - 0.5;
                float nClump = smoothNoise2D(gCoord * 0.42 + vec2(17.3, 9.2)) - 0.5;
                float nCrisp = grainHash21(floor(gCoord)) - 0.5;

                // Roughness blends uniform fine noise toward organic clumped silver-halide grain
                float uniformNoise = mix(nFine, nCrisp, 0.35);
                float clumpedNoise = nFine * 0.45 + nClump * 0.65 + nCrisp * 0.35;
                float noise = mix(uniformNoise, clumpedNoise, gRough);
                float luma = lumaRec709(c);
                float midWeight = clamp(1.0 - abs(luma - 0.5) * 1.25, 0.32, 1.0);
                float amp = mix(0.85, 1.35, gRough);
                c = clamp(c + vec3(noise * gStrength * midWeight * amp), 0.0, 1.0);
            }

            // 13. 3D / 1D .cube LUT + Intensity blend
            if (uHasLut > 0.5 && uLutIntensity > 0.001) {
                vec3 lutColor = (uLutIs1D > 0.5) ? sampleLut1D(c) : sampleLut3DTiled(c);
                c = mix(c, clamp(lutColor, 0.0, 1.0), uLutIntensity);
            }

            gl_FragColor = vec4(c, texel.a);
        }
    """

    fun createQuadBuffer(readbackFlipY: Boolean): FloatBuffer {
        val vTop = if (readbackFlipY) 1f else 0f
        val vBot = if (readbackFlipY) 0f else 1f
        val coords = floatArrayOf(
            -1f, -1f, 0f, vBot,
             1f, -1f, 1f, vBot,
            -1f,  1f, 0f, vTop,
             1f,  1f, 1f, vTop
        )
        return ByteBuffer.allocateDirect(coords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(coords)
                position(0)
            }
    }

    fun compileProgram(): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val status = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(prog)
            GLES20.glDeleteProgram(prog)
            throw IllegalStateException("GL program link failed: $log")
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return prog
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
            throw IllegalStateException("GL shader compile failed: $log")
        }
        return shader
    }

    data class PackedLutTexture(
        val bitmap: Bitmap,
        val size: Int,
        val cols: Int,
        val rows: Int,
        val is1D: Boolean
    )

    fun packLutToTiledBitmap(lut: CubeLut): PackedLutTexture {
        val n = lut.size.coerceAtLeast(2)
        val t = lut.table
        if (lut.type == LutType.LUT_1D) {
            val pixels = IntArray(n)
            for (i in 0 until n) {
                val baseIdx = i * 3
                if (baseIdx + 2 < t.size) {
                    val rf = t[baseIdx].takeIf { it.isFinite() } ?: 0f
                    val gf = t[baseIdx + 1].takeIf { it.isFinite() } ?: 0f
                    val bf = t[baseIdx + 2].takeIf { it.isFinite() } ?: 0f
                    val r = (rf * 255f + 0.5f).toInt().coerceIn(0, 255)
                    val g = (gf * 255f + 0.5f).toInt().coerceIn(0, 255)
                    val b = (bf * 255f + 0.5f).toInt().coerceIn(0, 255)
                    pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                } else {
                    val v = ((i.toFloat() / (n - 1).coerceAtLeast(1)) * 255f + 0.5f).toInt().coerceIn(0, 255)
                    pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                }
            }
            val bmp = createBitmap(n, 1)
            bmp.setPixels(pixels, 0, n, 0, 0, n, 1)
            return PackedLutTexture(bmp, n, 1, 1, true)
        }

        val cols = ceil(sqrt(n.toDouble())).toInt().coerceAtLeast(1)
        val rows = ceil(n.toDouble() / cols.toDouble()).toInt().coerceAtLeast(1)
        val texW = cols * n
        val texH = rows * n
        val pixels = IntArray(texW * texH)
        val s2 = n * n

        for (b in 0 until n) {
            val tileCol = b % cols
            val tileRow = b / cols
            val baseX = tileCol * n
            val baseY = tileRow * n
            for (g in 0 until n) {
                val rowOffset = (baseY + g) * texW + baseX
                for (r in 0 until n) {
                    val idx = (r + g * n + b * s2) * 3
                    if (idx + 2 < t.size) {
                        val rf = t[idx].takeIf { it.isFinite() } ?: 0f
                        val gf = t[idx + 1].takeIf { it.isFinite() } ?: 0f
                        val bf = t[idx + 2].takeIf { it.isFinite() } ?: 0f
                        val ri = (rf * 255f + 0.5f).toInt().coerceIn(0, 255)
                        val gi = (gf * 255f + 0.5f).toInt().coerceIn(0, 255)
                        val bi = (bf * 255f + 0.5f).toInt().coerceIn(0, 255)
                        pixels[rowOffset + r] = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
                    }
                }
            }
        }

        val bmp = createBitmap(texW, texH)
        bmp.setPixels(pixels, 0, texW, 0, 0, texW, texH)
        return PackedLutTexture(bmp, n, cols, rows, false)
    }

    /**
     * Caches every OpenGL attribute and uniform location once upon shader link, along with
     * reusable scratch arrays, eliminating ~65 glGetUniformLocation JNI calls and heap
     * allocations on every 120Hz frame.
     */
    class ShaderUniformHandles(val program: Int) {
        val aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        val aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")

        val uImageTex = GLES20.glGetUniformLocation(program, "uImageTex")
        val uLutTex = GLES20.glGetUniformLocation(program, "uLutTex")
        val uCurveTex = GLES20.glGetUniformLocation(program, "uCurveTex")

        val uIsOriginal = GLES20.glGetUniformLocation(program, "uIsOriginal")
        val uSplitRatio = GLES20.glGetUniformLocation(program, "uSplitRatio")
        val uTexelSize = GLES20.glGetUniformLocation(program, "uTexelSize")
        val uImageRes = GLES20.glGetUniformLocation(program, "uImageRes")

        val uHasGeometry = GLES20.glGetUniformLocation(program, "uHasGeometry")
        val uGeomRow0 = GLES20.glGetUniformLocation(program, "uGeomRow0")
        val uGeomRow1 = GLES20.glGetUniformLocation(program, "uGeomRow1")
        val uGeomRow2 = GLES20.glGetUniformLocation(program, "uGeomRow2")
        val uCropRect = GLES20.glGetUniformLocation(program, "uCropRect")

        val uHasOptics = GLES20.glGetUniformLocation(program, "uHasOptics")
        val uLensDistortion = GLES20.glGetUniformLocation(program, "uLensDistortion")
        val uChromaticAberration = GLES20.glGetUniformLocation(program, "uChromaticAberration")

        val uLensBlurAmount = GLES20.glGetUniformLocation(program, "uLensBlurAmount")
        val uBokehFocus = GLES20.glGetUniformLocation(program, "uBokehFocus")

        val uHasSpatial = GLES20.glGetUniformLocation(program, "uHasSpatial")
        val uTextureAmount = GLES20.glGetUniformLocation(program, "uTextureAmount")
        val uClarityAmount = GLES20.glGetUniformLocation(program, "uClarityAmount")
        val uSharpenParams = GLES20.glGetUniformLocation(program, "uSharpenParams")
        val uNrParams = GLES20.glGetUniformLocation(program, "uNrParams")
        val uColorNrParams = GLES20.glGetUniformLocation(program, "uColorNrParams")

        val uHasProfile = GLES20.glGetUniformLocation(program, "uHasProfile")
        val uProfStrength = GLES20.glGetUniformLocation(program, "uProfStrength")
        val uProfTone = GLES20.glGetUniformLocation(program, "uProfTone")
        val uProfGains = GLES20.glGetUniformLocation(program, "uProfGains")
        val uProfSat = GLES20.glGetUniformLocation(program, "uProfSat")
        val uProfShadowTint = GLES20.glGetUniformLocation(program, "uProfShadowTint")
        val uProfHighlightTint = GLES20.glGetUniformLocation(program, "uProfHighlightTint")
        val uProfMono = GLES20.glGetUniformLocation(program, "uProfMono")
        val uProfSelective = GLES20.glGetUniformLocation(program, "uProfSelective")

        val uExposureMult = GLES20.glGetUniformLocation(program, "uExposureMult")
        val uTempMult = GLES20.glGetUniformLocation(program, "uTempMult")
        val uDehazeNorm = GLES20.glGetUniformLocation(program, "uDehazeNorm")
        val uWhiteGain = GLES20.glGetUniformLocation(program, "uWhiteGain")
        val uBlackLift = GLES20.glGetUniformLocation(program, "uBlackLift")
        val uHighlightsStrength = GLES20.glGetUniformLocation(program, "uHighlightsStrength")
        val uShadowsStrength = GLES20.glGetUniformLocation(program, "uShadowsStrength")
        val uHasParametric = GLES20.glGetUniformLocation(program, "uHasParametric")
        val uParametric = GLES20.glGetUniformLocation(program, "uParametric")
        val uContrastFactor = GLES20.glGetUniformLocation(program, "uContrastFactor")

        val uHasCurve = GLES20.glGetUniformLocation(program, "uHasCurve")

        val uHasCal = GLES20.glGetUniformLocation(program, "uHasCal")
        val uCalHue = GLES20.glGetUniformLocation(program, "uCalHue")
        val uCalSat = GLES20.glGetUniformLocation(program, "uCalSat")

        val uHasHsl = GLES20.glGetUniformLocation(program, "uHasHsl")
        val uHsl = IntArray(8) { i -> GLES20.glGetUniformLocation(program, "uHsl$i") }

        val uIsGrayscale = GLES20.glGetUniformLocation(program, "uIsGrayscale")
        val uVibranceNorm = GLES20.glGetUniformLocation(program, "uVibranceNorm")
        val uSaturationFactor = GLES20.glGetUniformLocation(program, "uSaturationFactor")

        val uHasSplitTone = GLES20.glGetUniformLocation(program, "uHasSplitTone")
        val uSplitShadowTint = GLES20.glGetUniformLocation(program, "uSplitShadowTint")
        val uSplitHighlightTint = GLES20.glGetUniformLocation(program, "uSplitHighlightTint")
        val uSplitParams = GLES20.glGetUniformLocation(program, "uSplitParams")

        val uHasPointColor = GLES20.glGetUniformLocation(program, "uHasPointColor")
        val uPointSource = GLES20.glGetUniformLocation(program, "uPointSource")
        val uPointShift = GLES20.glGetUniformLocation(program, "uPointShift")

        val uHasColorGrading = GLES20.glGetUniformLocation(program, "uHasColorGrading")
        val uCgShadowTint = GLES20.glGetUniformLocation(program, "uCgShadowTint")
        val uCgMidTint = GLES20.glGetUniformLocation(program, "uCgMidTint")
        val uCgHighlightTint = GLES20.glGetUniformLocation(program, "uCgHighlightTint")
        val uCgGlobalTint = GLES20.glGetUniformLocation(program, "uCgGlobalTint")
        val uCgBlendBalance = GLES20.glGetUniformLocation(program, "uCgBlendBalance")

        val uVignetteParams1 = GLES20.glGetUniformLocation(program, "uVignetteParams1")
        val uVignetteParams2 = GLES20.glGetUniformLocation(program, "uVignetteParams2")
        val uGrainParams = GLES20.glGetUniformLocation(program, "uGrainParams")

        val uHasLut = GLES20.glGetUniformLocation(program, "uHasLut")
        val uLutSize = GLES20.glGetUniformLocation(program, "uLutSize")
        val uLutCols = GLES20.glGetUniformLocation(program, "uLutCols")
        val uLutRows = GLES20.glGetUniformLocation(program, "uLutRows")
        val uLutIs1D = GLES20.glGetUniformLocation(program, "uLutIs1D")
        val uDomainMin = GLES20.glGetUniformLocation(program, "uDomainMin")
        val uDomainRangeInv = GLES20.glGetUniformLocation(program, "uDomainRangeInv")
        val uLutIntensity = GLES20.glGetUniformLocation(program, "uLutIntensity")

        val uMaskCount = GLES20.glGetUniformLocation(program, "uMaskCount")
        val uMaskGeomA = IntArray(4) { i -> GLES20.glGetUniformLocation(program, "uMaskGeomA$i") }
        val uMaskGeomB = IntArray(4) { i -> GLES20.glGetUniformLocation(program, "uMaskGeomB$i") }
        val uMaskAdjA = IntArray(4) { i -> GLES20.glGetUniformLocation(program, "uMaskAdjA$i") }
        val uMaskSharp = IntArray(4) { i -> GLES20.glGetUniformLocation(program, "uMaskSharp$i") }

        // Pre-allocated scratch buffers owned by the GL thread (zero per-frame GC allocations)
        val geomOut = FloatArray(9)
        val geomScratchA = FloatArray(9)
        val geomScratchB = FloatArray(9)
        val tintScratch = FloatArray(3)
    }

    fun packToneCurveBitmap(curve: ToneCurveData): Bitmap {
        val pixels = curve.buildPackedRgba512()
        val bmp = createBitmap(256, 2)
        bmp.setPixels(pixels, 0, 256, 0, 0, 256, 2)
        return bmp
    }

    /**
     * Zero-allocation tone curve packer into caller-owned 512-entry pixel array and 256x2 bitmap.
     */
    fun packToneCurveInto(curve: ToneCurveData, pixelBuffer: IntArray, targetBitmap: Bitmap) {
        curve.buildPackedRgba512Into(pixelBuffer)
        targetBitmap.setPixels(pixelBuffer, 0, 256, 0, 0, 256, 2)
    }

    fun uploadTexture(texId: Int, bitmap: Bitmap) {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
    }

    /**
     * Updates an already-allocated GPU texture in-place without reallocating GPU storage.
     */
    fun uploadTextureSubImage(texId: Int, bitmap: Bitmap) {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap)
    }

    fun bindUniforms(
        program: Int,
        params: GradingParams,
        lut: CubeLut?,
        packedLut: PackedLutTexture?,
        imageWidth: Int,
        imageHeight: Int,
        isOriginal: Boolean,
        splitRatio: Float = -1f,
        applyCrop: Boolean = true,
        outputWidth: Int = imageWidth,
        outputHeight: Int = imageHeight
    ) {
        bindUniforms(
            handles = ShaderUniformHandles(program),
            params = params,
            lut = lut,
            packedLut = packedLut,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            isOriginal = isOriginal,
            splitRatio = splitRatio,
            applyCrop = applyCrop,
            outputWidth = outputWidth,
            outputHeight = outputHeight
        )
    }

    fun bindUniforms(
        handles: ShaderUniformHandles,
        params: GradingParams,
        lut: CubeLut?,
        packedLut: PackedLutTexture?,
        imageWidth: Int,
        imageHeight: Int,
        isOriginal: Boolean,
        splitRatio: Float = -1f,
        applyCrop: Boolean = true,
        outputWidth: Int = imageWidth,
        outputHeight: Int = imageHeight
    ) {
        GLES20.glUniform1f(handles.uIsOriginal, if (isOriginal) 1f else 0f)
        GLES20.glUniform1f(handles.uSplitRatio, splitRatio)

        val safeOutW = outputWidth.coerceAtLeast(1).toFloat()
        val safeOutH = outputHeight.coerceAtLeast(1).toFloat()
        GLES20.glUniform2f(handles.uTexelSize, 1f / safeOutW, 1f / safeOutH)
        GLES20.glUniform2f(handles.uImageRes, safeOutW, safeOutH)

        // Crop & Projective Geometry / Orientation
        val crop = params.crop
        val hasGeom = crop.hasOrientationEdits || crop.hasGeometryEdits
        GLES20.glUniform1f(handles.uHasGeometry, if (hasGeom) 1f else 0f)
        if (hasGeom) {
            val m = handles.geomOut
            com.example.editor.GeometryMath.buildOrientToRawSourceMatrixInto(
                srcW = imageWidth.coerceAtLeast(1),
                srcH = imageHeight.coerceAtLeast(1),
                crop = crop,
                out = m,
                scratchA = handles.geomScratchA,
                scratchB = handles.geomScratchB
            )
            GLES20.glUniform3f(handles.uGeomRow0, m[0], m[1], m[2])
            GLES20.glUniform3f(handles.uGeomRow1, m[3], m[4], m[5])
            GLES20.glUniform3f(handles.uGeomRow2, m[6], m[7], m[8])
        } else {
            GLES20.glUniform3f(handles.uGeomRow0, 1f, 0f, 0f)
            GLES20.glUniform3f(handles.uGeomRow1, 0f, 1f, 0f)
            GLES20.glUniform3f(handles.uGeomRow2, 0f, 0f, 1f)
        }
        if (applyCrop) {
            val eff = com.example.editor.GeometryMath.effectiveCropRect(
                srcW = imageWidth.coerceAtLeast(1),
                srcH = imageHeight.coerceAtLeast(1),
                crop = crop
            )
            GLES20.glUniform4f(handles.uCropRect, eff.left, eff.top, eff.width, eff.height)
        } else {
            GLES20.glUniform4f(handles.uCropRect, 0f, 0f, 1f, 1f)
        }

        // Optics
        val hasOptics = params.lensDistortion != 0f || params.chromaticAberration > 0f
        GLES20.glUniform1f(handles.uHasOptics, if (hasOptics) 1f else 0f)
        GLES20.glUniform1f(handles.uLensDistortion, -(params.lensDistortion / 100f) * 0.35f)
        GLES20.glUniform1f(handles.uChromaticAberration, (params.chromaticAberration / 100f) * 0.008f)

        // Lens Blur
        GLES20.glUniform1f(handles.uLensBlurAmount, (params.lensBlurAmount / 100f).coerceIn(0f, 1f))
        GLES20.glUniform1f(handles.uBokehFocus, (params.bokehFocus / 100f).coerceIn(0f, 1f))

        // Spatial
        val hasSpatial = params.texture != 0f ||
            params.clarity != 0f ||
            params.sharpening > 0f ||
            params.noiseReduction > 0f ||
            params.colorNoiseReduction > 0f
        GLES20.glUniform1f(handles.uHasSpatial, if (hasSpatial) 1f else 0f)
        GLES20.glUniform1f(handles.uTextureAmount, (params.texture / 100f) * 0.85f)
        GLES20.glUniform1f(handles.uClarityAmount, (params.clarity / 100f) * 1.05f)
        GLES20.glUniform4f(
            handles.uSharpenParams,
            (params.sharpening / 150f).coerceIn(0f, 1f),
            params.sharpenRadius.coerceIn(0.5f, 3.0f),
            (params.sharpenDetail / 100f).coerceIn(0f, 1f),
            (params.sharpenMasking / 100f).coerceIn(0f, 1f)
        )
        GLES20.glUniform3f(
            handles.uNrParams,
            (params.noiseReduction / 100f).coerceIn(0f, 1f),
            (params.noiseReductionDetail / 100f).coerceIn(0f, 1f),
            (params.noiseReductionContrast / 100f).coerceIn(0f, 1f)
        )
        GLES20.glUniform3f(
            handles.uColorNrParams,
            (params.colorNoiseReduction / 100f).coerceIn(0f, 1f),
            (params.colorNoiseReductionDetail / 100f).coerceIn(0f, 1f),
            (params.colorNoiseReductionSmoothness / 100f).coerceIn(0f, 1f)
        )

        // Foundational Camera Profile
        val profile = CameraProfiles.byId(params.profileId)
        val hasProfile = profile.isCreative && params.profileAmount > 0.001f
        GLES20.glUniform1f(handles.uHasProfile, if (hasProfile) 1f else 0f)
        GLES20.glUniform1f(handles.uProfStrength, params.profileAmount.coerceIn(0f, 2f))
        GLES20.glUniform4f(
            handles.uProfTone,
            profile.contrast,
            profile.shadowLift,
            profile.highlightCompress,
            profile.midPunch
        )
        GLES20.glUniform3f(
            handles.uProfGains,
            profile.rGainDelta,
            profile.gGainDelta,
            profile.bGainDelta
        )
        GLES20.glUniform2f(handles.uProfSat, profile.satDelta, profile.vibDelta)
        GLES20.glUniform3f(
            handles.uProfShadowTint,
            profile.shadowRedBias,
            profile.shadowGreenBias,
            profile.shadowBlueBias
        )
        GLES20.glUniform3f(
            handles.uProfHighlightTint,
            profile.highlightRedBias,
            profile.highlightGreenBias,
            profile.highlightBlueBias
        )
        GLES20.glUniform4f(
            handles.uProfMono,
            profile.monoMix,
            profile.monoWeightR,
            profile.monoWeightG,
            profile.monoWeightB
        )
        GLES20.glUniform3f(
            handles.uProfSelective,
            profile.foliageBoost,
            profile.skinSoftWarmth,
            profile.skyDepth
        )

        // Light & Color Basics (calibrated for Lightroom-style strength at ±50 and full range at ±100)
        val expMult = 2.0.pow(params.exposure.toDouble() * 1.15).toFloat()
        val tempNorm = params.temperature / 100f
        val tintNorm = params.tint / 100f
        GLES20.glUniform1f(handles.uExposureMult, expMult)
        GLES20.glUniform3f(
            handles.uTempMult,
            (1f + tempNorm * 0.44f + tintNorm * 0.16f).coerceAtLeast(0.15f),
            (1f + tempNorm * 0.06f - tintNorm * 0.34f).coerceAtLeast(0.15f),
            (1f - tempNorm * 0.46f + tintNorm * 0.16f).coerceAtLeast(0.15f)
        )
        GLES20.glUniform1f(handles.uDehazeNorm, params.dehaze / 100f)
        GLES20.glUniform1f(handles.uWhiteGain, 1f + (params.whites / 100f) * 0.45f)
        GLES20.glUniform1f(handles.uBlackLift, (params.blacks / 100f) * 0.28f)
        GLES20.glUniform1f(handles.uHighlightsStrength, (params.highlights / 100f) * 0.88f)
        GLES20.glUniform1f(handles.uShadowsStrength, (params.shadows / 100f) * 0.88f)

        val hasParametric = params.parametricShadows != 0f ||
            params.parametricDarks != 0f ||
            params.parametricLights != 0f ||
            params.parametricHighlights != 0f
        GLES20.glUniform1f(handles.uHasParametric, if (hasParametric) 1f else 0f)
        GLES20.glUniform4f(
            handles.uParametric,
            params.parametricShadows / 100f,
            params.parametricDarks / 100f,
            params.parametricLights / 100f,
            params.parametricHighlights / 100f
        )
        val contrastNorm = params.contrast / 100f
        val contrastFactor = if (contrastNorm >= 0f) {
            1f + contrastNorm * 1.25f
        } else {
            (1f + contrastNorm * 0.85f).coerceAtLeast(0.15f)
        }
        GLES20.glUniform1f(handles.uContrastFactor, contrastFactor)

        // Tone Curve
        GLES20.glUniform1f(handles.uHasCurve, if (!params.toneCurve.isDefault) 1f else 0f)

        // Calibration
        val cal = params.calibration
        GLES20.glUniform1f(handles.uHasCal, if (!cal.isDefault) 1f else 0f)
        GLES20.glUniform3f(handles.uCalHue, cal.redHue / 100f, cal.greenHue / 100f, cal.blueHue / 100f)
        GLES20.glUniform3f(
            handles.uCalSat,
            1f + (cal.redSaturation / 100f) * 0.5f,
            1f + (cal.greenSaturation / 100f) * 0.5f,
            1f + (cal.blueSaturation / 100f) * 0.5f
        )

        // 8-Band HSL
        val hsl = params.hsl
        GLES20.glUniform1f(handles.uHasHsl, if (!hsl.isDefault) 1f else 0f)
        for (i in 0..7) {
            val b = hsl.bands[i]
            GLES20.glUniform3f(handles.uHsl[i], b.hue, b.saturation, b.luminance)
        }

        // Grayscale, Vibrance, Saturation
        GLES20.glUniform1f(handles.uIsGrayscale, if (params.isGrayscale) 1f else 0f)
        GLES20.glUniform1f(handles.uVibranceNorm, params.vibrance / 100f)
        val satNorm = params.saturation / 100f
        val satFactor = if (satNorm >= 0f) {
            1f + satNorm * 1.40f
        } else {
            (1f + satNorm).coerceAtLeast(0f)
        }
        GLES20.glUniform1f(handles.uSaturationFactor, satFactor)

        // Split Toning (zero allocations using scratch array)
        val tintScratch = handles.tintScratch
        val hasSplitTone = params.splitShadowSat > 0f || params.splitHighlightSat > 0f
        GLES20.glUniform1f(handles.uHasSplitTone, if (hasSplitTone) 1f else 0f)
        hueSatToRgbTintInto(params.splitShadowHue, params.splitShadowSat, tintScratch)
        GLES20.glUniform3f(handles.uSplitShadowTint, tintScratch[0], tintScratch[1], tintScratch[2])
        hueSatToRgbTintInto(params.splitHighlightHue, params.splitHighlightSat, tintScratch)
        GLES20.glUniform3f(handles.uSplitHighlightTint, tintScratch[0], tintScratch[1], tintScratch[2])
        val bal = params.splitBalance / 100f
        GLES20.glUniform4f(
            handles.uSplitParams,
            params.splitShadowSat / 100f,
            params.splitHighlightSat / 100f,
            (1f - bal * 0.5f).coerceIn(0.25f, 1.75f),
            (1f + bal * 0.5f).coerceIn(0.25f, 1.75f)
        )

        // Point Color
        val pc = params.pointColor
        GLES20.glUniform1f(handles.uHasPointColor, if (pc.isModified) 1f else 0f)
        GLES20.glUniform3f(handles.uPointSource, pc.sourceHueDeg, pc.sourceSat, pc.sourceLuma)
        GLES20.glUniform4f(handles.uPointShift, pc.hueShift, pc.saturationShift, pc.luminanceShift, pc.range)

        // Color Grading (3-Way + Global Wheels — zero allocations)
        val cg = params.colorGrading
        GLES20.glUniform1f(handles.uHasColorGrading, if (!cg.isDefault) 1f else 0f)
        bindWheelUniform(handles.uCgShadowTint, cg.shadows, tintScratch)
        bindWheelUniform(handles.uCgMidTint, cg.midtones, tintScratch)
        bindWheelUniform(handles.uCgHighlightTint, cg.highlights, tintScratch)
        bindWheelUniform(handles.uCgGlobalTint, cg.global, tintScratch)
        GLES20.glUniform2f(
            handles.uCgBlendBalance,
            (cg.blending / 100f).coerceIn(0f, 1f),
            (cg.balance / 100f).coerceIn(-1f, 1f)
        )

        // Vignette & Grain
        GLES20.glUniform4f(
            handles.uVignetteParams1,
            (params.vignette / 100f) * 0.90f,
            (params.vignetteMidpoint / 100f).coerceIn(0f, 1f),
            (params.vignetteFeather / 100f).coerceIn(0f, 1f),
            (params.vignetteRoundness / 100f).coerceIn(-1f, 1f)
        )
        GLES20.glUniform2f(
            handles.uVignetteParams2,
            params.vignetteStyle.shaderIndex.toFloat(),
            (params.vignetteHighlights / 100f).coerceIn(0f, 1f)
        )
        GLES20.glUniform3f(
            handles.uGrainParams,
            (params.grain / 100f) * 0.16f,
            (params.grainSize / 100f).coerceIn(0f, 1f),
            (params.grainRoughness / 100f).coerceIn(0f, 1f)
        )

        // External .cube LUT (XMP presets populate sliders directly and do NOT apply baked LUT)
        val effectiveLut = if (lut != null && lut.sourceFormat != LutSourceFormat.XMP) lut else null
        val hasLut = effectiveLut != null && packedLut != null && params.lutIntensity > 0.001f
        GLES20.glUniform1f(handles.uHasLut, if (hasLut) 1f else 0f)
        if (hasLut) {
            GLES20.glUniform1f(handles.uLutSize, packedLut.size.toFloat())
            GLES20.glUniform1f(handles.uLutCols, packedLut.cols.toFloat())
            GLES20.glUniform1f(handles.uLutRows, packedLut.rows.toFloat())
            GLES20.glUniform1f(handles.uLutIs1D, if (packedLut.is1D) 1f else 0f)
            GLES20.glUniform3f(
                handles.uDomainMin,
                effectiveLut.domainMin[0],
                effectiveLut.domainMin[1],
                effectiveLut.domainMin[2]
            )
            GLES20.glUniform3f(
                handles.uDomainRangeInv,
                1f / (effectiveLut.domainMax[0] - effectiveLut.domainMin[0]).coerceAtLeast(1e-5f),
                1f / (effectiveLut.domainMax[1] - effectiveLut.domainMin[1]).coerceAtLeast(1e-5f),
                1f / (effectiveLut.domainMax[2] - effectiveLut.domainMin[2]).coerceAtLeast(1e-5f)
            )
            GLES20.glUniform1f(handles.uLutIntensity, params.lutIntensity.coerceIn(0f, 1f))
        } else {
            GLES20.glUniform1f(handles.uLutIntensity, 0f)
        }

        // Selective Local Masks (Radial & Linear gradients)
        val activeMasks = params.masks
        val outAspect = safeOutW / safeOutH
        var boundMaskCount = 0
        for (idx in activeMasks.indices) {
            if (boundMaskCount >= com.example.editor.MAX_LOCAL_MASKS) break
            val m = activeMasks[idx]
            if (!m.enabled || !m.hasAdjustments) continue
            val invFlag = if (m.inverted) 1f else 0f
            if (m.type == com.example.editor.MaskType.RADIAL) {
                GLES20.glUniform4f(
                    handles.uMaskGeomA[boundMaskCount],
                    0f,
                    m.centerX,
                    m.centerY,
                    invFlag
                )
                GLES20.glUniform4f(
                    handles.uMaskGeomB[boundMaskCount],
                    m.radiusX.coerceAtLeast(0.02f),
                    m.radiusY.coerceAtLeast(0.02f),
                    m.feather.coerceIn(0f, 1f),
                    0f
                )
            } else {
                GLES20.glUniform4f(
                    handles.uMaskGeomA[boundMaskCount],
                    1f,
                    m.startX,
                    m.startY,
                    invFlag
                )
                GLES20.glUniform4f(
                    handles.uMaskGeomB[boundMaskCount],
                    m.endX,
                    m.endY,
                    outAspect,
                    0f
                )
            }
            GLES20.glUniform4f(
                handles.uMaskAdjA[boundMaskCount],
                m.exposure.coerceIn(-2f, 2f),
                (m.contrast / 100f).coerceIn(-1f, 1f),
                (m.warmth / 100f).coerceIn(-1f, 1f),
                (m.saturation / 100f).coerceIn(-1f, 1f)
            )
            GLES20.glUniform1f(
                handles.uMaskSharp[boundMaskCount],
                (m.sharpness / 100f).coerceIn(0f, 1f)
            )
            boundMaskCount++
        }
        GLES20.glUniform1f(handles.uMaskCount, boundMaskCount.toFloat())
    }

    private fun bindWheelUniform(
        location: Int,
        wheel: com.example.editor.ColorWheelValue,
        scratch: FloatArray
    ) {
        hueSatToRgbTintInto(wheel.hueDeg, wheel.saturation, scratch)
        val satGain = (wheel.saturation / 100f) * 0.42f
        val lumDelta = (wheel.luminance / 100f) * 0.22f
        GLES20.glUniform4f(location, scratch[0] * satGain, scratch[1] * satGain, scratch[2] * satGain, lumDelta)
    }

    private fun hueSatToRgbTintInto(hueDeg: Float, sat: Float, out: FloatArray) {
        if (sat <= 0f) {
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
