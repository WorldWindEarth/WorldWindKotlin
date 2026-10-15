package earth.worldwind.render.program

import earth.worldwind.draw.DrawContext
import earth.worldwind.geom.Matrix4
import earth.worldwind.util.kgl.KglUniformLocation

/**
 * Height caster for [earth.worldwind.draw.DrawablePathProbe]. The probe looks straight down on its
 * corridor with an orthographic camera, so a fragment's window depth is its height; the nearest
 * (highest) fragment wins the depth test and writes that depth packed into RGB, with alpha 1
 * marking a covered pixel. Packing mirrors [PackedDepthProgram]: depth = r + g/255 + b/255^2.
 */
class ProbeOccupancyProgram : AbstractShaderProgram(), DepthCasterProgram {
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
            precision highp float;
            #endif

            vec3 packD24(float depth) {
                float scaled = depth * 255.0;
                float hi = floor(scaled);
                float remMid = (scaled - hi) * 255.0;
                float mid = floor(remMid);
                float lo = remMid - mid;
                return vec3(hi / 255.0, mid / 255.0, lo);
            }

            void main() {
                gl_FragColor = vec4(packD24(clamp(gl_FragCoord.z, 0.0, 0.99999)), 1.0);
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

    override fun loadModelviewProjection(matrix: Matrix4) {
        matrix.transposeToArray(array, 0)
        gl.uniformMatrix4fv(mvpMatrixId, 1, false, array, 0)
    }
}
