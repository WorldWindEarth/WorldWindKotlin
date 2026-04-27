package earth.worldwind.render.program

import earth.worldwind.draw.DrawContext
import earth.worldwind.geom.Matrix4
import earth.worldwind.util.kgl.KglUniformLocation

/**
 * Depth-pass shader for Moment Shadow Mapping (Hamburger 4-moment, Peters & Klein 2015).
 *
 * Stores the four raw moments `(d, d^2, d^3, d^4)` of *perpendicular* depth `d = -eye_pos.z / range`,
 * i.e. the projection of the world point onto the face's outward look axis (which is `+X` for the
 * canonical-OpenGL POS_X face, etc.). Perpendicular depth is **linear** in screen space
 * (it's `gl_Position.w / range`), so adjacent fragments hold linearly-varying moments and the
 * hardware bilinear blend at sample time returns the exact `d` at the sample direction - no
 * non-linear sampling bias, no zoom-dependent grid aliasing.
 *
 * Crucially, with the canonical OpenGL cube-map face matrices, `-eye_pos.z` for face `i` equals
 * the dominant centerTransform-local-axis projection that `samplerCube` selects for that
 * direction (e.g. POS_X stores `P.x / range`, NEG_Z stores `-P.z / range`, etc.). At face
 * seams these match exactly because the seam direction has equal projections on both adjacent
 * face axes - so the cube-map receiver can recover the metric as `max(|P.x|, |P.y|, |P.z|) / range`
 * (the L∞ norm of the centerTransform-local fragment position) and compare against the sampled
 * moment without any per-face logic.
 *
 * The colour attachment is `RGBA32F`; full-precision floats are required because the Cholesky
 * reconstruction subtracts close-magnitude products of moments and any quantisation noise
 * propagates into the occlusion bound.
 */
class SightlineMomentsProgram : AbstractShaderProgram() {
    override var programSources = arrayOf(
        """
            uniform mat4 mvMatrix;
            uniform mat4 projMatrix;
            uniform float invRange;

            attribute vec4 vertexPoint;

            varying float perpDepth;

            void main() {
                /* Face-eye-frame position: vertex transformed by the view matrix
                   (= sightline view * tile translate). Perpendicular depth is the projection
                   onto the face's outward look axis = -eye_pos.z. Linear in screen space, so
                   the receiver's bilinear blend at sample time reproduces the exact value. */
                vec4 ep = mvMatrix * vertexPoint;
                perpDepth = -ep.z * invRange;
                gl_Position = projMatrix * ep;
            }
        """.trimIndent(),
        """
            #extension GL_EXT_frag_depth : enable
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #elif defined(GL_ES)
            precision mediump float;
            #endif

            varying float perpDepth;

            void main() {
                /* Store the four raw moments (d, d^2, d^3, d^4) of the perpendicular depth.
                   Computing higher powers via incremental multiplies keeps precision uniform
                   across all four channels. */
                float d = perpDepth;
                float d2 = d * d;
                float d3 = d2 * d;
                float d4 = d2 * d2;
                gl_FragColor = vec4(d, d2, d3, d4);
                /* Override gl_FragCoord.z with linear perpendicular depth so the depth-buffer
                   step is uniform across [0, 1]. Without it, non-linear precision at the far
                   plane lets adjacent terrain ridges share a depth-buffer step and z-fight,
                   alternating which moment gets stored - zebra banding at far-from-centre
                   fragments. The macro is defined only when `#extension : enable` actually
                   succeeds; WebGL2 implementations that refuse the directive fall through to
                   neither branch and rely on the cube FBO's DEPTH_COMPONENT24 attachment. */
                #ifdef GL_EXT_frag_depth
                gl_FragDepthEXT = clamp(perpDepth, 0.0, 1.0);
                #elif !defined(GL_ES)
                gl_FragDepth = clamp(perpDepth, 0.0, 1.0);
                #endif
            }
        """.trimIndent()
    )
    override val attribBindings = arrayOf("vertexPoint")

    private var mvMatrixId = KglUniformLocation.NONE
    private var projMatrixId = KglUniformLocation.NONE
    private var invRangeId = KglUniformLocation.NONE
    private val array = FloatArray(16)

    override fun initProgram(dc: DrawContext) {
        super.initProgram(dc)
        mvMatrixId = gl.getUniformLocation(program, "mvMatrix")
        gl.uniformMatrix4fv(mvMatrixId, 1, false, array, 0)
        projMatrixId = gl.getUniformLocation(program, "projMatrix")
        gl.uniformMatrix4fv(projMatrixId, 1, false, array, 0)
        invRangeId = gl.getUniformLocation(program, "invRange")
        gl.uniform1f(invRangeId, 1f)
    }

    fun loadModelview(matrix: Matrix4) {
        matrix.transposeToArray(array, 0)
        gl.uniformMatrix4fv(mvMatrixId, 1, false, array, 0)
    }

    fun loadProjection(matrix: Matrix4) {
        matrix.transposeToArray(array, 0)
        gl.uniformMatrix4fv(projMatrixId, 1, false, array, 0)
    }

    fun loadRange(range: Float) {
        gl.uniform1f(invRangeId, if (range > 0f) 1f / range else 1f)
    }

    companion object {
        val KEY = SightlineMomentsProgram::class
    }
}
