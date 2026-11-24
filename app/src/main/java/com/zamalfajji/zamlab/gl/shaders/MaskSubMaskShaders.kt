package com.zamalfajji.zamlab.gl.shaders

/**
 * Modular GLSL ES 3.00 / WebGL2 fragment shaders for non-destructive mask generation and
 * offscreen Boolean sub-mask compositing:
 *  - Rotatable Radial & Linear gradients
 *  - Rec. 709 Luminance Range parametric mask
 *  - CIELAB perceptual Color Range parametric mask
 *  - Resolution-independent Vector Brush segment stamping in normalized [0.0, 1.0] UV space
 *  - Boolean ADD (`clamp(base + sub, 0, 1)`), SUBTRACT (`clamp(base - sub, 0, 1)`),
 *    INTERSECT (`base * sub`), and Invert (`1.0 - alpha`)
 */
object MaskSubMaskShaders {

    /**
     * Max vector brush segments stamped per GPU draw call (48 vec4 uniforms fits cleanly in all
     * ES 3.0 / WebGL2 minimum uniform budgets; longer strokes automatically batch across passes).
     */
    const val MAX_BRUSH_SEGMENTS_PER_BATCH = 48

    val SUBMASK_COMPOSITE_FRAGMENT_SHADER_ES3: String = """
${MaskShaderCommon.PRECISION_HEADER_ES3}
in vec2 vTexCoord;
out vec4 fragColor;

uniform sampler2D uBaseMaskTex;
uniform sampler2D uGuidePhotoTex;
uniform sampler2D uBrushMaskTex;

// uSubMeta = (subType [0=Radial,1=Linear,2=Brush,3=Luma,4=Color], boolOp [0=Add,1=Sub,2=Intersect], initialized [0/1], aspect)
uniform vec4 uSubMeta;
// Radial: (centerX, centerY, radiusX, radiusY); Linear: (startX, startY, endX, endY);
// Luma: (lumMin, lumMax, lumFalloff, 0); Color: (targetL, targetA, targetB, sampled)
uniform vec4 uParamA;
// Radial: (rotationRad, feather, 0, 0); Linear: (feather, 0, 0, 0);
// Color: (tolerance, smoothness, 0, 0)
uniform vec4 uParamB;

${MaskShaderCommon.COLOR_SCIENCE_GLSL}

void main() {
    vec2 uv = vTexCoord;
    float baseAlpha = texture(uBaseMaskTex, uv).r;
    float subType = uSubMeta.x;
    float boolOp = uSubMeta.y;
    float initialized = uSubMeta.z;
    float aspect = max(uSubMeta.w, 0.1);

    float subAlpha = 0.0;

    if (subType < 0.5) {
        // 0: Rotatable Radial Gradient
        vec2 dRaw = uv - uParamA.xy;
        float rad = -uParamB.x;
        float c = cos(rad);
        float s = sin(rad);
        vec2 dRot = vec2(dRaw.x * c - dRaw.y * s, dRaw.x * s + dRaw.y * c);
        vec2 r = max(uParamA.zw, vec2(0.02));
        float dist = length(dRot / r);
        float f = clamp(uParamB.y, 0.0, 1.0);
        float inner = clamp(1.0 - f, 0.0, 0.98);
        float outer = max(inner + 0.02, 1.0 + f * 0.15);
        subAlpha = 1.0 - smoothstep(inner, outer, dist);
    } else if (subType < 1.5) {
        // 1: Rotatable Linear Gradient (start -> end) with feather falloff
        vec2 p = vec2((uv.x - uParamA.x) * aspect, uv.y - uParamA.y);
        vec2 d = vec2((uParamA.z - uParamA.x) * aspect, uParamA.w - uParamA.y);
        float len2 = max(dot(d, d), 1e-4);
        float proj = dot(p, d) / len2;
        float f = clamp(uParamB.x, 0.02, 1.0);
        float halfSpan = 0.5 * f;
        subAlpha = 1.0 - smoothstep(0.5 - halfSpan, 0.5 + halfSpan, proj);
    } else if (subType < 2.5) {
        // 2: Pre-evaluated resolution-independent Vector Brush sub-mask
        subAlpha = texture(uBrushMaskTex, uv).r;
    } else if (subType < 3.5) {
        // 3: Parametric Luminance Range (Rec. 709)
        vec3 rgb = texture(uGuidePhotoTex, uv).rgb;
        float y = luma709(rgb);
        float lo = min(uParamA.x, uParamA.y);
        float hi = max(uParamA.x, uParamA.y);
        float f = clamp(uParamA.z, 0.005, 0.5);
        float lowPass = (lo <= 0.001) ? 1.0 : smoothstep(lo - f, lo + f * 0.25, y);
        float highPass = (hi >= 0.999) ? 1.0 : (1.0 - smoothstep(hi - f * 0.25, hi + f, y));
        subAlpha = clamp(lowPass * highPass, 0.0, 1.0);
    } else {
        // 4: Parametric Color Range (CIELAB perceptual distance)
        float sampled = uParamA.w;
        if (sampled > 0.5) {
            vec3 rgb = texture(uGuidePhotoTex, uv).rgb;
            vec3 lab = srgbToLab(rgb);
            float deltaE = length(lab - uParamA.xyz);
            float tol = clamp(uParamB.x, 2.0, 120.0);
            float smoothBand = max(1.5, tol * clamp(uParamB.y, 0.02, 1.0));
            float inner = max(0.0, tol - smoothBand * 0.5);
            float outer = tol + smoothBand * 0.5;
            subAlpha = 1.0 - smoothstep(inner, outer, deltaE);
        }
    }

    float outAlpha = subAlpha;
    if (initialized < 0.5) {
        outAlpha = (boolOp > 0.5 && boolOp < 1.5) ? 0.0 : subAlpha;
    } else if (boolOp < 0.5) {
        // ADD
        outAlpha = clamp(baseAlpha + subAlpha, 0.0, 1.0);
    } else if (boolOp < 1.5) {
        // SUBTRACT
        outAlpha = clamp(baseAlpha - subAlpha, 0.0, 1.0);
    } else {
        // INTERSECT
        outAlpha = clamp(baseAlpha * subAlpha, 0.0, 1.0);
    }

    fragColor = vec4(outAlpha, outAlpha, outAlpha, 1.0);
}
""".trimIndent()

    /**
     * Stamps up to 48 normalized [0.0, 1.0] UV brush segments onto the existing brush scratch mask
     * with aspect-correct circular dabs, configurable hardness, and flow buildup.
     */
    val BRUSH_STROKE_STAMP_FRAGMENT_SHADER_ES3: String = """
${MaskShaderCommon.PRECISION_HEADER_ES3}
in vec2 vTexCoord;
out vec4 fragColor;

uniform sampler2D uPrevBrushTex;
// uBrushParams = (radius, hardness, flow, aspect)
uniform vec4 uBrushParams;
uniform int uSegmentCount;
uniform vec4 uSegments[$MAX_BRUSH_SEGMENTS_PER_BATCH];

${MaskShaderCommon.COLOR_SCIENCE_GLSL}

void main() {
    float prevAlpha = texture(uPrevBrushTex, vTexCoord).r;
    float radius = clamp(uBrushParams.x, 0.005, 0.35);
    float hardness = clamp(uBrushParams.y, 0.0, 1.0);
    float flow = clamp(uBrushParams.z, 0.02, 1.0);
    float aspect = max(uBrushParams.w, 0.1);
    vec2 scale = (aspect >= 1.0) ? vec2(aspect, 1.0) : vec2(1.0, 1.0 / aspect);
    vec2 p = vTexCoord * scale;

    float minDist = 10.0;
    for (int i = 0; i < $MAX_BRUSH_SEGMENTS_PER_BATCH; i++) {
        if (i >= uSegmentCount) break;
        vec4 seg = uSegments[i];
        float d = capsuleDist(p, seg.xy * scale, seg.zw * scale);
        minDist = min(minDist, d);
    }

    float inner = radius * hardness * 0.95;
    float dab = (minDist < radius) ? ((1.0 - smoothstep(inner, radius, minDist)) * flow) : 0.0;
    float outAlpha = clamp(prevAlpha + dab * (1.0 - prevAlpha), 0.0, 1.0);
    fragColor = vec4(outAlpha, outAlpha, outAlpha, 1.0);
}
""".trimIndent()

    /**
     * Applies optional layer Invert (`1.0 - alpha`) to finalize the raw composited mask before
     * Guided Image Filtering.
     */
    val MASK_INVERT_FRAGMENT_SHADER_ES3: String = """
${MaskShaderCommon.PRECISION_HEADER_ES3}
in vec2 vTexCoord;
out vec4 fragColor;

uniform sampler2D uMaskTex;
uniform float uInvert;

void main() {
    float a = texture(uMaskTex, vTexCoord).r;
    float outA = (uInvert > 0.5) ? (1.0 - a) : a;
    fragColor = vec4(outA, outA, outA, 1.0);
}
""".trimIndent()
}
