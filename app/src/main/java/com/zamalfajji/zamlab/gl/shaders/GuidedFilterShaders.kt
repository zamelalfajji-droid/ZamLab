package com.zamalfajji.zamlab.gl.shaders

/**
 * Multi-pass GPU Guided Image Filter (He, Sun, Tang 2010) in GLSL ES 3.00 / WebGL2.
 *
 * Uses the underlying RGB photograph's Rec. 709 luminance `I` as the edge-preserving guide image
 * to refine a soft mask `p` without bleeding across real edges (hair, foliage, horizon lines):
 *
 *  Pass 1 (`MOMENTS_H`): Horizontal box filter of `(I, p, I*I, I*p)`
 *  Pass 2 (`COEFFS_V`):  Vertical box filter -> `varI`, `covIP`, `a = covIP/(varI + eps)`, `b = meanP - a*meanI`
 *  Pass 3 (`AB_H`):      Horizontal box filter of `(a, b)`
 *  Pass 4 (`APPLY_V`):   Vertical box filter of `(a, b)` -> `q = clamp(meanA * I + meanB, 0.0, 1.0)`
 *
 * Supports both floating-point FBOs (`RGBA16F`, `uEncodeRgba8 == 0.0`) and universal 8-bit FBO
 * fallback (`RGBA8`, `uEncodeRgba8 == 1.0`, encoding signed `a, b` cleanly in `[-4.0, 4.0] -> [0, 1]`).
 */
object GuidedFilterShaders {

    const val MAX_GUIDED_RADIUS = 24

    val GUIDED_MOMENTS_H_FRAGMENT_SHADER_ES3: String = """
${MaskShaderCommon.PRECISION_HEADER_ES3}
in vec2 vTexCoord;
out vec4 fragColor;

uniform sampler2D uGuidePhotoTex;
uniform sampler2D uMaskTex;
// uFilterParams = (texelW, texelH, radiusFloat, eps)
uniform vec4 uFilterParams;

${MaskShaderCommon.COLOR_SCIENCE_GLSL}

void main() {
    int radius = int(clamp(uFilterParams.z, 1.0, float($MAX_GUIDED_RADIUS)));
    float stepX = uFilterParams.x;
    vec4 sum = vec4(0.0);
    float count = 0.0;

    for (int k = -$MAX_GUIDED_RADIUS; k <= $MAX_GUIDED_RADIUS; k++) {
        if (k < -radius || k > radius) continue;
        vec2 uv = vec2(clamp(vTexCoord.x + float(k) * stepX, 0.0, 1.0), vTexCoord.y);
        float I = luma709(texture(uGuidePhotoTex, uv).rgb);
        float p = texture(uMaskTex, uv).r;
        sum += vec4(I, p, I * I, I * p);
        count += 1.0;
    }

    fragColor = sum / max(count, 1.0);
}
""".trimIndent()

    val GUIDED_COEFFS_V_FRAGMENT_SHADER_ES3: String = """
${MaskShaderCommon.PRECISION_HEADER_ES3}
in vec2 vTexCoord;
out vec4 fragColor;

uniform sampler2D uMomentsHTex;
// uFilterParams = (texelW, texelH, radiusFloat, eps)
uniform vec4 uFilterParams;
// 0.0 = native float FBO (RGBA16F), 1.0 = range-encoded RGBA8 FBO [-4..4] -> [0..1]
uniform float uEncodeRgba8;

void main() {
    int radius = int(clamp(uFilterParams.z, 1.0, float($MAX_GUIDED_RADIUS)));
    float stepY = uFilterParams.y;
    float eps = max(uFilterParams.w, 0.0002);
    vec4 sum = vec4(0.0);
    float count = 0.0;

    for (int k = -$MAX_GUIDED_RADIUS; k <= $MAX_GUIDED_RADIUS; k++) {
        if (k < -radius || k > radius) continue;
        vec2 uv = vec2(vTexCoord.x, clamp(vTexCoord.y + float(k) * stepY, 0.0, 1.0));
        sum += texture(uMomentsHTex, uv);
        count += 1.0;
    }

    vec4 m = sum / max(count, 1.0);
    float meanI = m.x;
    float meanP = m.y;
    float corrI = m.z;
    float corrIP = m.w;

    float varI = max(0.0, corrI - meanI * meanI);
    float covIP = corrIP - meanI * meanP;
    float a = clamp(covIP / (varI + eps), -4.0, 4.0);
    float b = clamp(meanP - a * meanI, -4.0, 4.0);

    if (uEncodeRgba8 > 0.5) {
        fragColor = vec4((a + 4.0) / 8.0, (b + 4.0) / 8.0, meanP, 1.0);
    } else {
        fragColor = vec4(a, b, meanP, 1.0);
    }
}
""".trimIndent()

    val GUIDED_AB_H_FRAGMENT_SHADER_ES3: String = """
${MaskShaderCommon.PRECISION_HEADER_ES3}
in vec2 vTexCoord;
out vec4 fragColor;

uniform sampler2D uCoeffsTex;
// uFilterParams = (texelW, texelH, radiusFloat, eps)
uniform vec4 uFilterParams;

void main() {
    int radius = int(clamp(uFilterParams.z, 1.0, float($MAX_GUIDED_RADIUS)));
    float stepX = uFilterParams.x;
    vec4 sum = vec4(0.0);
    float count = 0.0;

    for (int k = -$MAX_GUIDED_RADIUS; k <= $MAX_GUIDED_RADIUS; k++) {
        if (k < -radius || k > radius) continue;
        vec2 uv = vec2(clamp(vTexCoord.x + float(k) * stepX, 0.0, 1.0), vTexCoord.y);
        sum += texture(uCoeffsTex, uv);
        count += 1.0;
    }

    fragColor = sum / max(count, 1.0);
}
""".trimIndent()

    val GUIDED_APPLY_V_FRAGMENT_SHADER_ES3: String = """
${MaskShaderCommon.PRECISION_HEADER_ES3}
in vec2 vTexCoord;
out vec4 fragColor;

uniform sampler2D uAbHTex;
uniform sampler2D uGuidePhotoTex;
// uFilterParams = (texelW, texelH, radiusFloat, eps)
uniform vec4 uFilterParams;
uniform float uEncodeRgba8;

${MaskShaderCommon.COLOR_SCIENCE_GLSL}

void main() {
    int radius = int(clamp(uFilterParams.z, 1.0, float($MAX_GUIDED_RADIUS)));
    float stepY = uFilterParams.y;
    vec2 sumAB = vec2(0.0);
    float count = 0.0;

    for (int k = -$MAX_GUIDED_RADIUS; k <= $MAX_GUIDED_RADIUS; k++) {
        if (k < -radius || k > radius) continue;
        vec2 uv = vec2(vTexCoord.x, clamp(vTexCoord.y + float(k) * stepY, 0.0, 1.0));
        sumAB += texture(uAbHTex, uv).xy;
        count += 1.0;
    }

    vec2 meanAB = sumAB / max(count, 1.0);
    float meanA = (uEncodeRgba8 > 0.5) ? (meanAB.x * 8.0 - 4.0) : meanAB.x;
    float meanB = (uEncodeRgba8 > 0.5) ? (meanAB.y * 8.0 - 4.0) : meanAB.y;

    float I = luma709(texture(uGuidePhotoTex, vTexCoord).rgb);
    float q = clamp(meanA * I + meanB, 0.0, 1.0);
    fragColor = vec4(q, q, q, 1.0);
}
""".trimIndent()
}
