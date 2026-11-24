package com.zamalfajji.zamlab.gl.shaders

/**
 * Shared GLSL ES 3.00 / WebGL2 shader primitives, precision headers, color space conversions
 * (Rec. 709 luminance, sRGB<->Linear, D65 XYZ -> CIELAB), and an automatic ES 2.0 compatibility
 * down-translator for devices whose EGL driver falls back to OpenGL ES 2.0.
 */
object MaskShaderCommon {

    const val PRECISION_HEADER_ES3 = """#version 300 es
precision highp float;
precision highp int;
"""

    const val FULLSCREEN_VERTEX_SHADER_ES3 = """#version 300 es
precision highp float;
in vec2 aPosition;
in vec2 aTexCoord;
out vec2 vTexCoord;
void main() {
    vTexCoord = aTexCoord;
    gl_Position = vec4(aPosition, 0.0, 1.0);
}
"""

    /**
     * Shared GLSL ES 3.00 color-science and geometry helpers included in modular masking shaders.
     */
    const val COLOR_SCIENCE_GLSL = """
float luma709(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

vec3 srgbToLinear(vec3 c) {
    vec3 x = clamp(c, 0.0, 1.0);
    vec3 lo = x / 12.92;
    vec3 hi = pow((x + 0.055) / 1.055, vec3(2.4));
    return mix(hi, lo, step(x, vec3(0.04045)));
}

vec3 linearToSrgb(vec3 c) {
    vec3 x = clamp(c, 0.0, 1.0);
    vec3 lo = x * 12.92;
    vec3 hi = 1.055 * pow(max(x, vec3(0.000001)), vec3(1.0 / 2.4)) - 0.055;
    return clamp(mix(hi, lo, step(x, vec3(0.0031308))), 0.0, 1.0);
}

float labF(float t) {
    float c = max(t, 0.0);
    return (c > 0.008856) ? pow(c, 1.0 / 3.0) : (7.787 * c + 16.0 / 116.0);
}

vec3 srgbToLab(vec3 srgb) {
    vec3 lin = srgbToLinear(srgb);
    float x = (0.4124564 * lin.r + 0.3575761 * lin.g + 0.1804375 * lin.b) / 0.95047;
    float y = (0.2126729 * lin.r + 0.7151522 * lin.g + 0.0721750 * lin.b) / 1.00000;
    float z = (0.0193339 * lin.r + 0.1191920 * lin.g + 0.9503041 * lin.b) / 1.08883;
    float fx = labF(x);
    float fy = labF(y);
    float fz = labF(z);
    return vec3(116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz));
}

float capsuleDist(vec2 p, vec2 a, vec2 b) {
    vec2 pa = p - a;
    vec2 ba = b - a;
    float h = clamp(dot(pa, ba) / max(dot(ba, ba), 1e-6), 0.0, 1.0);
    return length(pa - ba * h);
}
"""

    /**
     * Returns native `#version 300 es` source when running on an OpenGL ES 3.0 / WebGL2 context,
     * or cleanly adapts declarations (`in`/`out`/`texture`/`fragColor`) when an older GPU driver
     * falls back to OpenGL ES 2.0.
     */
    fun adaptForGlContext(sourceEs3: String, isVertex: Boolean, isEs3: Boolean): String {
        if (isEs3) return sourceEs3
        var s = sourceEs3
            .replace("#version 300 es", "")
            .replace("precision highp int;", "")
            .replace("texture(", "texture2D(")
        if (isVertex) {
            s = s.replace(Regex("(?m)^\\s*in\\s+"), "attribute ")
                .replace(Regex("(?m)^\\s*out\\s+"), "varying ")
        } else {
            s = s.replace(
                "precision highp float;",
                "#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n#else\nprecision mediump float;\n#endif"
            )
                .replace(Regex("(?m)^\\s*in\\s+"), "varying ")
                .replace(Regex("(?m)^\\s*out\\s+vec4\\s+fragColor\\s*;"), "")
                .replace("fragColor", "gl_FragColor")
        }
        return s.trim()
    }
}
