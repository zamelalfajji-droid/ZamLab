package com.zamalfajji.zamlab.gl.shaders

/**
 * Modular GLSL ES 3.00 / WebGL2 Rubylith mask overlay shader rendering a 50% opacity
 * Rubylith red (`#FF2A2A`) tint over the live composited and edge-snapped mask alpha.
 */
object MaskRubylithOverlayShader {

    const val RUBYLITH_HEX = "#FF2A2A"

    val RUBYLITH_OVERLAY_FRAGMENT_SHADER_ES3: String = """
${MaskShaderCommon.PRECISION_HEADER_ES3}
in vec2 vTexCoord;
out vec4 fragColor;

uniform sampler2D uPhotoTex;
uniform sampler2D uMaskTex;
// uOverlayParams = (enabled [0/1], opacity [0.50], 0.0, 0.0)
uniform vec4 uOverlayParams;

const vec3 RUBYLITH_RED = vec3(1.0, 0.16470588, 0.16470588); // #FF2A2A

void main() {
    vec4 base = texture(uPhotoTex, vTexCoord);
    if (uOverlayParams.x > 0.5) {
        float maskAlpha = clamp(texture(uMaskTex, vTexCoord).r, 0.0, 1.0);
        float mixAmt = clamp(maskAlpha * uOverlayParams.y, 0.0, uOverlayParams.y);
        base.rgb = mix(base.rgb, RUBYLITH_RED, mixAmt);
    }
    fragColor = base;
}
""".trimIndent()
}
