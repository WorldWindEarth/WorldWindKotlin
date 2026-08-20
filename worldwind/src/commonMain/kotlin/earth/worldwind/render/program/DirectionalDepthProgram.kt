package earth.worldwind.render.program

import earth.worldwind.draw.DrawContext
import earth.worldwind.geom.Matrix4
import earth.worldwind.util.kgl.KglUniformLocation

/**
 * Depth-only caster program for the cascaded sun-shadow pipeline. Rasterises caster geometry
 * through each cascade's orthographic light projection; the cascade framebuffer's
 * `DEPTH_COMPONENT` texture receives the hardware depth value, which receivers later sample
 * and compare directly. There is no meaningful colour output — the caller masks colour writes
 * off for the whole pass.
 *
 * Because the stored value is true hardware depth, `glPolygonOffset` applies slope-scaled
 * bias in the caster pass itself — steeply-lit triangles get proportionally more bias, which
 * a receiver-side constant can never do without erasing contact shadows.
 */
class DirectionalDepthProgram : AbstractShaderProgram(), DepthCasterProgram {
    override var programSources = arrayOf(
        """
            /* Full lightProjection * lightView * model, composed in double on the CPU so the
               ECEF-magnitude light-frame translations cancel before the float32 upload. */
            uniform mat4 mvpMatrix;

            attribute vec4 vertexPoint;

            void main() {
                gl_Position = mvpMatrix * vertexPoint;
                /* Caster pancaking: geometry sunward of the cascade's near plane (clip z
                   < -w) rasterises AT the near plane instead of being clipped away - a
                   mountain kilometres toward the sun must still darken a street-scale
                   cascade whose depth window can't reach it. Exact for the orthographic
                   light projection (w = 1); flattened depth stays conservatively nearer
                   to the sun, which cannot un-shadow a receiver. */
                gl_Position.z = max(gl_Position.z, -gl_Position.w);
            }
        """.trimIndent(),
        """
            #ifdef GL_ES
            precision mediump float;
            #endif

            void main() {
                /* Depth-only pass; colour writes are masked off by DrawableShadow. */
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

    override fun loadModelviewProjection(matrix: Matrix4) {
        matrix.transposeToArray(array, 0)
        gl.uniformMatrix4fv(mvpMatrixId, 1, false, array, 0)
    }
}
