package com.zamalfajji.zamlab.gl.shaders

/**
 * Modular GLSL ES 3.00 / WebGL2 fragment shader applying all 8 selective local adjustments
 * weighted by the final composited and edge-snapped mask alpha:
 *  1. Exposure (linear stop math)
 *  2. Contrast
 *  3. Highlights
 *  4. Shadows
 *  5. Temperature
 *  6. Tint
 *  7. Saturation
 *  8. Sharpness (USM) & Clarity (midtone local contrast)
 */
object MaskLocalAdjustShader {

    val MASK_LOCAL_ADJUST_FRAGMENT_SHADER_ES3: String = """
${MaskShaderCommon.PRECISION_HEADER_ES3}
in vec2 vTexCoord;
out vec4 fragColor;

uniform sampler2D uPhotoTex;
uniform sampler2D uMaskTex;

// uAdjA = (exposureEV, contrastNorm, highlightsNorm, shadowsNorm)
uniform vec4 uAdjA;
// uAdjB = (temperatureNorm, tintNorm, saturationNorm, sharpnessNorm)
uniform vec4 uAdjB;
// uAdjC = (clarityNorm, texelW, texelH, 0.0)
uniform vec4 uAdjC;

${MaskShaderCommon.COLOR_SCIENCE_GLSL}

vec3 softClipLinear3(vec3 v) {
    vec3 clamped = max(v, vec3(0.0));
    vec3 excess = max(clamped - 0.85, vec3(0.0));
    vec3 rolled = 0.85 + 0.15 * (excess / (excess + 0.15));
    return mix(clamped, rolled, step(vec3(0.85), clamped));
}

void main() {
    vec4 orig = texture(uPhotoTex, vTexCoord);
    float w = clamp(texture(uMaskTex, vTexCoord).r, 0.0, 1.0);
    if (w <= 0.0005) {
        fragColor = orig;
        return;
    }

    float exposureEV = uAdjA.x;
    float contrast = uAdjA.y;
    float highlights = uAdjA.z;
    float shadows = uAdjA.w;
    float temp = uAdjB.x;
    float tint = uAdjB.y;
    float sat = uAdjB.z;
    float sharp = clamp(uAdjB.w, 0.0, 1.0);
    float clarity = clamp(uAdjC.x, -1.0, 1.0);
    vec2 texel = uAdjC.yz;

    // 1. Linear Exposure + White Balance (Temperature & Tint)
    vec3 lin = srgbToLinear(orig.rgb);
    if (abs(exposureEV) > 0.001) {
        lin *= pow(2.0, exposureEV);
    }
    if (abs(temp) > 0.001 || abs(tint) > 0.001) {
        vec3 wbMul = clamp(
            vec3(
                1.0 + temp * 0.28 + tint * 0.14,
                1.0 - tint * 0.22,
                1.0 - temp * 0.28 + tint * 0.14
            ),
            vec3(0.45),
            vec3(1.85)
        );
        float wbNorm = 1.0 / max(luma709(wbMul), 0.001);
        lin *= wbMul * wbNorm;
    }

    vec3 c = linearToSrgb(softClipLinear3(lin));

    // 2. Contrast + Highlights + Shadows
    if (abs(contrast) > 0.001) {
        c = clamp((c - 0.5) * (1.0 + contrast * 0.65) + 0.5, 0.0, 1.0);
    }
    if (abs(highlights) > 0.001 || abs(shadows) > 0.001) {
        float yLuma = luma709(c);
        float hiMask = smoothstep(0.45, 0.98, yLuma);
        float shMask = 1.0 - smoothstep(0.02, 0.55, yLuma);
        float toneDelta = highlights * 0.32 * hiMask + shadows * 0.34 * shMask;
        c = clamp(c + vec3(toneDelta), 0.0, 1.0);
    }

    // 3. Saturation
    if (abs(sat) > 0.001) {
        float yLuma = luma709(c);
        c = clamp(mix(vec3(yLuma), c, clamp(1.0 + sat, 0.0, 2.2)), 0.0, 1.0);
    }

    // 4. Spatial Sharpness (USM) & Clarity (midtone local contrast)
    if (sharp > 0.005 || abs(clarity) > 0.005) {
        float centerL = luma709(orig.rgb);
        if (sharp > 0.005) {
            float lL = luma709(texture(uPhotoTex, clamp(vTexCoord + vec2(-texel.x, 0.0), 0.0, 1.0)).rgb);
            float lR = luma709(texture(uPhotoTex, clamp(vTexCoord + vec2( texel.x, 0.0), 0.0, 1.0)).rgb);
            float lT = luma709(texture(uPhotoTex, clamp(vTexCoord + vec2(0.0, -texel.y), 0.0, 1.0)).rgb);
            float lB = luma709(texture(uPhotoTex, clamp(vTexCoord + vec2(0.0,  texel.y), 0.0, 1.0)).rgb);
            float usm = (centerL - (lL + lR + lT + lB) * 0.25) * (sharp * 1.15);
            c = clamp(c + vec3(usm), 0.0, 1.0);
        }
        if (abs(clarity) > 0.005) {
            vec2 wideStep = texel * 3.0;
            float wL = luma709(texture(uPhotoTex, clamp(vTexCoord + vec2(-wideStep.x, 0.0), 0.0, 1.0)).rgb);
            float wR = luma709(texture(uPhotoTex, clamp(vTexCoord + vec2( wideStep.x, 0.0), 0.0, 1.0)).rgb);
            float wT = luma709(texture(uPhotoTex, clamp(vTexCoord + vec2(0.0, -wideStep.y), 0.0, 1.0)).rgb);
            float wB = luma709(texture(uPhotoTex, clamp(vTexCoord + vec2(0.0,  wideStep.y), 0.0, 1.0)).rgb);
            float midWeight = clamp(1.0 - abs(centerL * 2.0 - 1.0), 0.0, 1.0);
            float clarDelta = (centerL - (wL + wR + wT + wB) * 0.25) * (clarity * 0.72) * (0.35 + 0.65 * midWeight);
            c = clamp(c + vec3(clarDelta), 0.0, 1.0);
        }
    }

    fragColor = vec4(mix(orig.rgb, c, w), orig.a);
}
""".trimIndent()
}
