package earth.worldwind.render.program

import earth.worldwind.draw.DrawContext
import earth.worldwind.draw.DrawableSensorFrames
import earth.worldwind.draw.DrawableSensorMask
import earth.worldwind.util.kgl.KglUniformLocation

/**
 * Draws, over the whole screen, the boundary of each sensor's seen ground from the masks
 * [earth.worldwind.draw.DrawableSensorMask] wrote: a line [edgeWidth] pixels wide, in the
 * sensor's colour, wherever that sensor's channel crosses one half. The width comes from the
 * channel's screen derivative, so it is the same at every zoom and on a mask of any resolution.
 * Output premultiplied; the global ONE/ONE_MINUS_SRC_ALPHA blend composes it over the scene.
 */
class SensorEdgeProgram : AbstractShaderProgram() {
    override var programSources = arrayOf(VERTEX_SHADER, fragmentShader())
    override val attribBindings = arrayOf("vertexPoint")

    override fun glslVersion(dc: DrawContext) = dc.gl.glslVersion + dc.gl.glslDerivativesPrefix

    private var texScaleId = KglUniformLocation.NONE
    private var maskCountId = KglUniformLocation.NONE
    private var frameCountId = KglUniformLocation.NONE
    private var edgeColorId = KglUniformLocation.NONE
    private var edgeWidthId = KglUniformLocation.NONE
    private var texelSizeId = KglUniformLocation.NONE

    override fun initProgram(dc: DrawContext) {
        super.initProgram(dc)
        texScaleId = gl.getUniformLocation(program, "texScale")
        maskCountId = gl.getUniformLocation(program, "maskCount")
        frameCountId = gl.getUniformLocation(program, "frameCount")
        edgeColorId = gl.getUniformLocation(program, "edgeColor")
        edgeWidthId = gl.getUniformLocation(program, "edgeWidth")
        texelSizeId = gl.getUniformLocation(program, "texelSize")
        for (j in 0 until TARGETS) gl.uniform1i(gl.getUniformLocation(program, "mask$j"), j)
        gl.uniform1i(maskCountId, 0)
        gl.uniform1i(frameCountId, 0)
    }

    fun loadTexScale(x: Float, y: Float) = gl.uniform2f(texScaleId, x, y)

    /** One mask texel in texture coordinates. */
    fun loadTexelSize(x: Float, y: Float) = gl.uniform2f(texelSizeId, x, y)

    /** [colors] premultiplied, 4 floats per frame, in the masks' channel order. */
    fun loadEdges(frameCount: Int, maskCount: Int, colors: FloatArray, width: Float) {
        gl.uniform1i(frameCountId, frameCount)
        gl.uniform1i(maskCountId, maskCount)
        gl.uniform4fv(edgeColorId, DrawableSensorMask.MAX_FRAMES, colors, 0)
        gl.uniform1f(edgeWidthId, width)
    }

    companion object {
        private const val TARGETS = DrawContext.SENSOR_MASK_TARGETS
        private const val PER_TARGET = SensorFrameProgram.MAX_FRAMES

        private val VERTEX_SHADER = """
            uniform vec2 texScale;
            attribute vec2 vertexPoint;
            varying vec2 texCoord;
            void main() {
                gl_Position = vec4(vertexPoint * 2.0 - 1.0, 0.0, 1.0);
                texCoord = vertexPoint * texScale;
            }
        """.trimIndent()

        private fun fragmentShader() = buildString {
            append("""
                #ifdef GL_ES
                precision mediump float;
                #endif
            """.trimIndent()).append('\n')
            for (j in 0 until TARGETS) append("uniform sampler2D mask$j;\n")
            append("""
                uniform int maskCount;
                uniform int frameCount;
                uniform vec4 edgeColor[${DrawableSensorMask.MAX_FRAMES}];
                uniform float edgeWidth;
                uniform vec2 texelSize;
                varying vec2 texCoord;

                /* The mask averaged over the nine texels around this pixel: a lone wrong texel is a ninth
                   and never crosses one half, a boundary a ramp crossing it once */
                vec4 average(sampler2D mask) {
                    vec4 m = vec4(0.0);
                    for (int j = -1; j <= 1; j++) {
                        for (int i = -1; i <= 1; i++) {
                            m += texture2D(mask, texCoord + vec2(float(i), float(j)) * texelSize);
                        }
                    }
                    return m / 9.0;
                }

                /* Coverage of a line edgeWidth pixels wide along the mask's one-half crossing */
                float edge(float m) {
                #ifdef WW_HAS_DERIVATIVES
                    float d = (m - 0.5) / max(fwidth(m), 1e-6);
                #else
                    float d = (m - 0.5) * 8.0;
                #endif
                    return clamp(0.5 * edgeWidth + 0.5 - abs(d), 0.0, 1.0);
                }

                void main() {
                    vec4 color = vec4(0.0);
                    vec4 m;
                    float a;
            """.trimIndent()).append('\n')
            for (j in 0 until TARGETS) {
                append("    if (maskCount > $j) {\n        m = average(mask$j);\n")
                val channels = arrayOf("r", "g", "b", "a")
                for (c in 0 until PER_TARGET) {
                    val k = j * PER_TARGET + c
                    append("        if (frameCount > $k) { a = edge(m.${channels[c]}); color = color * (1.0 - a) + edgeColor[$k] * a; }\n")
                }
                append("    }\n")
            }
            append("    gl_FragColor = color;\n}\n")
        }
    }
}
