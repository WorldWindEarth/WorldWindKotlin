package earth.worldwind.render.program

import earth.worldwind.draw.DrawContext
import earth.worldwind.geom.Matrix4
import earth.worldwind.util.kgl.KglUniformLocation

/**
 * Depth-only program for camera-perspective offscreen prepasses (e.g. the Gaussian-splat
 * pass's terrain occlusion depth). Unlike [DirectionalDepthProgram] there is no caster
 * pancaking: under a perspective projection, geometry in front of the near plane must clip
 * exactly as the main color pass clips it — pancaking it onto the near plane would write
 * bogus near depth into the prepass target and wrongly occlude everything behind it.
 */
class SceneDepthProgram : AbstractShaderProgram() {
    override var programSources = arrayOf(
        """
            uniform mat4 mvpMatrix;

            attribute vec4 vertexPoint;

            void main() {
                gl_Position = mvpMatrix * vertexPoint;
            }
        """.trimIndent(),
        """
            #ifdef GL_ES
            precision mediump float;
            #endif

            void main() {
                /* Depth-only pass; colour writes are masked off by the caller. */
                gl_FragColor = vec4(1.0);
            }
        """.trimIndent()
    )
    override val attribBindings = arrayOf("vertexPoint")

    private var mvpMatrixId = KglUniformLocation.NONE
    private val array = FloatArray(16)

    override fun initProgram(dc: DrawContext) {
        super.initProgram(dc)
        mvpMatrixId = gl.getUniformLocation(program, "mvpMatrix")
        gl.uniformMatrix4fv(mvpMatrixId, 1, false, array, 0)
    }

    fun loadModelviewProjection(matrix: Matrix4) {
        matrix.transposeToArray(array, 0)
        gl.uniformMatrix4fv(mvpMatrixId, 1, false, array, 0)
    }
}
