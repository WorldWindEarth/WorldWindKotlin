package earth.worldwind.render.program

import earth.worldwind.draw.DrawContext
import earth.worldwind.geom.Matrix4
import earth.worldwind.util.kgl.KglUniformLocation

/**
 * Depth-pass shader for the Variance Shadow Mapping path.
 *
 * Where standard shadow mapping writes only depth into a 16-bit depth attachment, VSM writes
 * `(d, d^2)` into the colour attachment as well so the receiver can compute mean and variance
 * over a kernel and apply Chebyshev's inequality. The whole point of the colour-attachment
 * write is that colour textures are filterable - a hardware bilinear sample averages 2x2
 * neighbouring `(d, d^2)` values and the separable Gaussian in [SightlineMomentsBlurProgram]
 * widens the support further. Filtering depth values directly is meaningless; filtering
 * moments is the unlock.
 *
 * The stored depth is `gl_Position.w / range` - linear perpendicular distance from the
 * sightline plane, normalised to `[0, 1]`. Linear depth distributes precision uniformly
 * across the range; the post-perspective `gl_FragCoord.z` would crowd far depths into the
 * top few percent of the colour-channel range and produce visible banding. The receiver
 * reconstructs the same metric with `sightlinePosition.w / range`.
 *
 * The colour attachment is `RG32F`; full-precision floats remove all quantisation noise
 * from `M2 - M1^2`, which an 8-bit RGBA8 buffer cannot do (the difference is dominated by
 * 1-byte rounding error).
 */
class SightlineMomentsProgram : AbstractShaderProgram() {
    override var programSources = arrayOf(
        """
            uniform mat4 mvpMatrix;
            uniform float invRange;

            attribute vec4 vertexPoint;

            varying float depth;

            void main() {
                /* gl_Position.w = -eye_z = perpendicular distance from the sightline plane,
                   linearly distributed across [near, far]. Dividing by range maps it into
                   [0, 1] with uniform precision - the post-perspective-divide z (gl_FragCoord.z)
                   would crowd far depths into the last few values of an 8-bit channel and
                   turn the moments texture into a flat M1 ~ 1 wash. */
                gl_Position = mvpMatrix * vertexPoint;
                depth = gl_Position.w * invRange;
            }
        """.trimIndent(),
        """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #elif defined(GL_ES)
            precision mediump float;
            #endif

            varying float depth;

            void main() {
                /* Store both d and d^2; the receiver reconstructs mean and variance from a
                   filtered tap and runs Chebyshev's inequality. */
                float d = depth;
                gl_FragColor = vec4(d, d * d, 0.0, 1.0);
            }
        """.trimIndent()
    )
    override val attribBindings = arrayOf("vertexPoint")

    private var mvpMatrixId = KglUniformLocation.NONE
    private var invRangeId = KglUniformLocation.NONE
    private val array = FloatArray(16)

    override fun initProgram(dc: DrawContext) {
        super.initProgram(dc)
        mvpMatrixId = gl.getUniformLocation(program, "mvpMatrix")
        gl.uniformMatrix4fv(mvpMatrixId, 1, false, array, 0)
        invRangeId = gl.getUniformLocation(program, "invRange")
        gl.uniform1f(invRangeId, 1f)
    }

    fun loadModelviewProjection(matrix: Matrix4) {
        matrix.transposeToArray(array, 0)
        gl.uniformMatrix4fv(mvpMatrixId, 1, false, array, 0)
    }

    fun loadRange(range: Float) {
        gl.uniform1f(invRangeId, if (range > 0f) 1f / range else 1f)
    }

    companion object {
        val KEY = SightlineMomentsProgram::class
    }
}
