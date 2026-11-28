package earth.worldwind.render.program

import earth.worldwind.draw.DrawContext
import earth.worldwind.geom.Matrix4
import earth.worldwind.util.kgl.KglUniformLocation

/**
 * Depth-only caster program of the sensor frames' depth atlas
 * ([earth.worldwind.draw.DrawableSensorFrameDepth]): [DirectionalDepthProgram]'s pass with the
 * frame transform as its projection, and on a 2D globe the second-order term added back the way
 * [SensorFrameProgram] adds it ([loadFrameBend]), so the depth the overlay compares against is
 * taken on the same ground it draws on.
 */
class SensorFrameDepthProgram : AbstractShaderProgram() {
    override var programSources = arrayOf(
        """
            /* The frame transform with the tile origin folded in, composed in double on the CPU */
            uniform mat4 mvpMatrix;
            /* As SensorFrameProgram's frameBend, for the one frame */
            uniform vec4 frameBend[5];

            attribute vec4 vertexPoint;

${SensorFrameProgram.BEND_GLSL.prependIndent("            ")}

            void main() {
                gl_Position = mvpMatrix * vertexPoint + frameDrop(frameBend[0], frameBend[1], frameBend[2], frameBend[3], frameBend[4]);
                /* Caster pancaking, as DirectionalDepthProgram's */
                gl_Position.z = max(gl_Position.z, -gl_Position.w);
            }
        """.trimIndent(),
        """
            #ifdef GL_ES
            precision mediump float;
            #endif

            void main() {
                /* Depth-only pass; colour writes are masked off by the drawable. */
                gl_FragColor = vec4(1.0);
            }
        """.trimIndent()
    )
    override val attribBindings = arrayOf("vertexPoint")

    private var mvpMatrixId = KglUniformLocation.NONE
    private var frameBendId = KglUniformLocation.NONE
    private val array = FloatArray(16)

    override fun initProgram(dc: DrawContext) {
        super.initProgram(dc)
        mvpMatrixId = gl.getUniformLocation(program, "mvpMatrix")
        gl.uniformMatrix4fv(mvpMatrixId, 1, false, array, 0)
        frameBendId = gl.getUniformLocation(program, "frameBend")
        gl.uniform4fv(frameBendId, SensorFrameProgram.BEND_VECTORS, FloatArray(SensorFrameProgram.BEND_FLOATS), 0)
    }

    fun loadModelviewProjection(matrix: Matrix4) {
        matrix.transposeToArray(array, 0)
        gl.uniformMatrix4fv(mvpMatrixId, 1, false, array, 0)
    }

    /** The frame's east row, north row and clip changes, [SensorFrameProgram.BEND_FLOATS] floats at [offset], as [SensorFrameProgram.loadFrameBends] takes per frame. */
    fun loadFrameBend(bends: FloatArray, offset: Int) = gl.uniform4fv(frameBendId, SensorFrameProgram.BEND_VECTORS, bends, offset)
}
