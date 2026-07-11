package earth.worldwind.render.program

import earth.worldwind.draw.DrawContext
import earth.worldwind.geom.Matrix4
import earth.worldwind.util.kgl.KglUniformLocation

/**
 * Alpha-tested variant of [DirectionalDepthProgram] for cutout-textured casters (tree
 * billboards, fences, alpha-masked 3D-Tile submeshes): samples the caster's base-colour
 * texture on unit 0 and discards fragments below the cutoff, so the cascade maps carry the
 * cutout silhouette instead of the full quad. Kept separate from the opaque program - a
 * discard-capable shader disables early depth optimisations, which opaque casters keep.
 */
class AlphaDepthProgram : AbstractShaderProgram() {
    override var programSources = arrayOf(
        """
            uniform mat4 mvpMatrix;

            attribute vec4 vertexPoint;
            attribute vec2 vertexTexCoord;

            varying vec2 texCoord;

            void main() {
                gl_Position = mvpMatrix * vertexPoint;
                /* Caster pancaking - see DirectionalDepthProgram. */
                gl_Position.z = max(gl_Position.z, -gl_Position.w);
                texCoord = vertexTexCoord;
            }
        """.trimIndent(),
        """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #elif defined(GL_ES)
            precision mediump float;
            #endif

            uniform sampler2D texSampler;
            uniform float alphaCutoff;

            varying vec2 texCoord;

            void main() {
                if (texture2D(texSampler, texCoord).a < alphaCutoff) discard;
                gl_FragColor = vec4(1.0);
            }
        """.trimIndent()
    )
    override val attribBindings = arrayOf("vertexPoint", "vertexTexCoord")

    private var mvpMatrixId = KglUniformLocation.NONE
    private var alphaCutoffId = KglUniformLocation.NONE
    private val array = FloatArray(16)
    private var cutoff = -1f

    override fun initProgram(dc: DrawContext) {
        super.initProgram(dc)
        mvpMatrixId = gl.getUniformLocation(program, "mvpMatrix")
        gl.uniformMatrix4fv(mvpMatrixId, 1, false, array, 0)
        alphaCutoffId = gl.getUniformLocation(program, "alphaCutoff")
        gl.uniform1f(alphaCutoffId, 0.5f)
        cutoff = 0.5f
        gl.uniform1i(gl.getUniformLocation(program, "texSampler"), 0)
    }

    fun loadModelviewProjection(matrix: Matrix4) {
        matrix.transposeToArray(array, 0)
        gl.uniformMatrix4fv(mvpMatrixId, 1, false, array, 0)
    }

    fun loadAlphaCutoff(value: Float) {
        if (value != cutoff) {
            cutoff = value
            gl.uniform1f(alphaCutoffId, value)
        }
    }
}
