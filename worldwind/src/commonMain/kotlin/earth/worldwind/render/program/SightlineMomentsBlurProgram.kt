package earth.worldwind.render.program

import earth.worldwind.draw.DrawContext
import earth.worldwind.util.kgl.KglUniformLocation

/**
 * Separable Gaussian blur of the sightline moments texture, run between the depth pass and
 * the occlusion pass to widen the variance support beyond the 2x2 hardware bilinear footprint.
 *
 * The depth pass writes per-pixel `(d, d^2)` to `momentsFramebuffer`. Without blur, Chebyshev's
 * variance is computed from the four texels touched by one bilinear tap - inside a depth-pass
 * triangle that's a vanishingly small variance, and at the triangle's edge it's a thin band of
 * sharp variance. Both regimes show through as visible mesh-aligned stripes in the receiver.
 * Blurring the moments before sampling spreads variance over many texels so Chebyshev
 * computes a smooth probabilistic occlusion across whole triangles, not along their borders.
 *
 * Implementation: classic 5-tap binomial Gaussian (weights `1, 4, 6, 4, 1` / 16) at 2-texel
 * spacing along [blurDirection]. Run twice - horizontal then vertical - against
 * `momentsBlurFramebuffer` ping-pong, ending with the blurred result back in the original
 * moments texture for the occlusion pass to sample. Per-pass cost is one fullscreen quad
 * with 5 texture fetches.
 */
class SightlineMomentsBlurProgram : AbstractShaderProgram() {
    override var programSources = arrayOf(
        """
            attribute vec2 vertexPoint;
            varying vec2 texCoord;

            void main() {
                /* Unit-square buffer corners (0,0), (0,1), (1,0), (1,1) double as both texture
                   coordinates and NDC coordinates with a [0,1] -> [-1,1] remap. */
                texCoord = vertexPoint;
                gl_Position = vec4(vertexPoint * 2.0 - 1.0, 0.0, 1.0);
            }
        """.trimIndent(),
        """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            uniform highp sampler2D momentsSampler;
            #elif defined(GL_ES)
            precision mediump float;
            uniform mediump sampler2D momentsSampler;
            #else
            uniform sampler2D momentsSampler;
            #endif

            uniform vec2 blurDirection;

            varying vec2 texCoord;

            void main() {
                /* 5-tap separable Gaussian (binomial weights 1:4:6:4:1, normalised /16).
                   Run horizontally then vertically; combined effective kernel is 5x5 with
                   the central tap weighted heavily and outer taps falling off smoothly. */
                vec4 sum = vec4(0.0);
                sum += texture2D(momentsSampler, texCoord - 2.0 * blurDirection) * 0.0625;
                sum += texture2D(momentsSampler, texCoord -       blurDirection) * 0.25;
                sum += texture2D(momentsSampler, texCoord                       ) * 0.375;
                sum += texture2D(momentsSampler, texCoord +       blurDirection) * 0.25;
                sum += texture2D(momentsSampler, texCoord + 2.0 * blurDirection) * 0.0625;
                gl_FragColor = sum;
            }
        """.trimIndent()
    )
    override val attribBindings = arrayOf("vertexPoint")

    private var momentsSamplerId = KglUniformLocation.NONE
    private var blurDirectionId = KglUniformLocation.NONE

    override fun initProgram(dc: DrawContext) {
        super.initProgram(dc)
        momentsSamplerId = gl.getUniformLocation(program, "momentsSampler")
        gl.uniform1i(momentsSamplerId, 0) // GL_TEXTURE0
        blurDirectionId = gl.getUniformLocation(program, "blurDirection")
        gl.uniform2f(blurDirectionId, 0f, 0f)
    }

    /**
     * Sets the per-tap offset between adjacent samples. For a horizontal pass on a 1024-wide
     * texture with 2-texel spacing, pass `(2.0/1024.0, 0)`; for the vertical pass on the same
     * texture, pass `(0, 2.0/1024.0)`. Larger spacing gives a wider effective kernel at the
     * cost of skipping intermediate texels.
     */
    fun loadBlurDirection(x: Float, y: Float) {
        gl.uniform2f(blurDirectionId, x, y)
    }

    companion object {
        val KEY = SightlineMomentsBlurProgram::class
    }
}
