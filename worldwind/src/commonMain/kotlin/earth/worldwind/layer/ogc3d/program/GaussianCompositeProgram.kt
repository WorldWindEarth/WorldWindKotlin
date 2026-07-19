package earth.worldwind.layer.ogc3d.program

import earth.worldwind.draw.DrawContext
import earth.worldwind.render.RenderContext
import earth.worldwind.render.program.AbstractShaderProgram
import earth.worldwind.util.kgl.KglUniformLocation

/**
 * Screen composite for the reduced-resolution Gaussian-splat pass. Draws one full-screen
 * quad sampling the pass color texture (premultiplied alpha) over the scene; the global
 * `GL_ONE, GL_ONE_MINUS_SRC_ALPHA` blend state completes the over-compositing, so the result
 * matches direct full-resolution rendering up to the resolution of the offscreen target.
 *
 * The pass framebuffer grows monotonically and can be larger than the rendered area, so
 * [loadTexScale] maps the unit quad onto only the rendered sub-region of the texture.
 */
class GaussianCompositeProgram : AbstractShaderProgram() {

    override var programSources: Array<String> = arrayOf(VERTEX_SHADER, FRAGMENT_SHADER)

    override val attribBindings: Array<String> = arrayOf("vertexPoint")

    private var texSamplerId = KglUniformLocation.NONE
    private var texScaleId = KglUniformLocation.NONE
    private var texScaleX = 1f
    private var texScaleY = 1f

    override fun initProgram(dc: DrawContext) {
        super.initProgram(dc)
        texSamplerId = gl.getUniformLocation(program, "texSampler")
        gl.uniform1i(texSamplerId, 0)
        texScaleId = gl.getUniformLocation(program, "texScale")
        gl.uniform2f(texScaleId, texScaleX, texScaleY)
    }

    /** Fraction of the texture the rendered pass area occupies, per axis. */
    fun loadTexScale(x: Float, y: Float) {
        if (texScaleX != x || texScaleY != y) {
            texScaleX = x
            texScaleY = y
            gl.uniform2f(texScaleId, x, y)
        }
    }

    companion object {
        fun get(rc: RenderContext): GaussianCompositeProgram =
            rc.getShaderProgram { GaussianCompositeProgram() }

        private val VERTEX_SHADER: String = """
            uniform vec2 texScale;

            attribute vec2 vertexPoint;

            varying vec2 texCoord;

            void main() {
                gl_Position = vec4(vertexPoint * 2.0 - 1.0, 0.0, 1.0);
                texCoord = vertexPoint * texScale;
            }
        """.trimIndent()

        private val FRAGMENT_SHADER: String = """
            #ifdef GL_ES
            precision mediump float;
            #endif

            uniform sampler2D texSampler;

            varying vec2 texCoord;

            void main() {
                /* Premultiplied pass output; the global ONE/ONE_MINUS_SRC_ALPHA blend applies. */
                gl_FragColor = texture2D(texSampler, texCoord);
            }
        """.trimIndent()
    }
}
