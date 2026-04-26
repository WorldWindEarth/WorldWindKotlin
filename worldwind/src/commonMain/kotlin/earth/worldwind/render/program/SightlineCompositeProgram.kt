package earth.worldwind.render.program

import earth.worldwind.draw.DrawContext
import earth.worldwind.util.kgl.KglUniformLocation

/**
 * Bilateral filter that smooths the silhouette of a sightline visibility texture before
 * compositing it over the main framebuffer.
 *
 * Sightline visibility is rendered into an offscreen colour+depth FBO by
 * [earth.worldwind.draw.DrawableSightline]. The visibility silhouette traces individual
 * terrain mesh triangles, producing axis-aligned staircase patterns at the edge between
 * visible and occluded regions. This shader runs as a single full-screen quad pass that:
 *
 *  1. Samples the centre pixel of the visibility texture; pixels outside any sightline
 *     cone (alpha == 0) are written as-is so the early-out skips most of the screen.
 *  2. For pixels inside a sightline cone, takes 16 Poisson-disk taps in screen space and
 *     averages them with weights that fall off exponentially in the camera-depth
 *     difference between centre and tap. The depth weight stops the kernel from bleeding
 *     across geometry-edge discontinuities (e.g. terrain meeting a building) while still
 *     averaging neighbouring fragments on the same continuous surface - which is exactly
 *     what dissolves the triangle-aligned staircase.
 *  3. Outputs the (premultiplied-alpha) colour for standard `(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)`
 *     blending against the main framebuffer.
 *
 * Inputs:
 *   - texture unit 0: visibility colour, RGBA8 premultiplied
 *   - texture unit 1: visibility depth, used only for bilateral edge weighting
 *   - `viewportSize`: pixel dimensions of the visibility texture, used to convert the unit
 *     Poisson offsets to texel offsets
 *   - `bilateralRadius`: kernel radius in pixels
 *   - `depthSigma`: standard deviation of the depth-difference Gaussian, in normalised
 *     post-projection depth units `[0, 1]`. Smaller values make the filter more
 *     edge-preserving.
 */
class SightlineCompositeProgram : AbstractShaderProgram() {
    override var programSources = arrayOf(
        """
            attribute vec2 vertexPoint;
            varying vec2 texCoord;

            void main() {
                /* The unit-square vertex buffer has corners at (0,0), (0,1), (1,0), (1,1) -
                   reuse those coordinates as texture coordinates and rescale to NDC. */
                texCoord = vertexPoint;
                gl_Position = vec4(vertexPoint * 2.0 - 1.0, 0.0, 1.0);
            }
        """.trimIndent(),
        """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            uniform highp sampler2D visibilitySampler;
            uniform highp sampler2D depthSampler;
            #elif defined(GL_ES)
            precision mediump float;
            uniform mediump sampler2D visibilitySampler;
            uniform mediump sampler2D depthSampler;
            #else
            uniform sampler2D visibilitySampler;
            uniform sampler2D depthSampler;
            #endif

            uniform vec2 viewportSize;
            uniform float bilateralRadius;
            uniform float depthSigma;

            varying vec2 texCoord;

            /* Accumulate one Poisson-disk tap into sumVis/sumWeight. The weight is the
               Gaussian of the depth difference between centre and tap, so taps that fall
               on a surface at very different camera-space depth contribute almost nothing -
               that's what keeps the blur edge-aware. */
            #define TAP(ox, oy) { \
                vec2 sc = texCoord + vec2(ox, oy) * dxy; \
                vec4 sv = texture2D(visibilitySampler, sc); \
                float sd = texture2D(depthSampler, sc).r; \
                float dd = (sd - centerDepth) / depthSigma; \
                float w = exp(-dd * dd); \
                sumVis += sv * w; \
                sumWeight += w; \
            }

            void main() {
                vec4 centerVis = texture2D(visibilitySampler, texCoord);
                /* Pixels outside every sightline cone have alpha 0; skip the kernel. The
                   majority of the screen falls in this branch so this is a real win. */
                if (centerVis.a < 0.001) {
                    gl_FragColor = centerVis;
                    return;
                }

                float centerDepth = texture2D(depthSampler, texCoord).r;
                vec2 dxy = bilateralRadius / viewportSize;

                vec4 sumVis = centerVis;
                float sumWeight = 1.0;

                /* 16-tap Poisson disk - canonical Bart Wronski / NVIDIA distribution,
                   roughly even spacing inside a unit circle. Round footprint -> rounded
                   silhouette corners (a square grid kernel would dilate corners square). */
                TAP(-0.94201624, -0.39906216)
                TAP( 0.94558609, -0.76890725)
                TAP(-0.09418410, -0.92938870)
                TAP( 0.34495938,  0.29387760)
                TAP(-0.91588581,  0.45771432)
                TAP(-0.81544232, -0.87912464)
                TAP(-0.38277543,  0.27676845)
                TAP( 0.97484398,  0.75648379)
                TAP( 0.44323325, -0.97511554)
                TAP( 0.53742981, -0.47373420)
                TAP(-0.26496911, -0.41893023)
                TAP( 0.79197514,  0.19090188)
                TAP(-0.24188840,  0.99706507)
                TAP(-0.81409955,  0.91437590)
                TAP( 0.19984126,  0.78641367)
                TAP( 0.14383161, -0.14100790)

                gl_FragColor = sumVis / sumWeight;
            }
        """.trimIndent()
    )
    override val attribBindings = arrayOf("vertexPoint")

    private var visibilitySamplerId = KglUniformLocation.NONE
    private var depthSamplerId = KglUniformLocation.NONE
    private var viewportSizeId = KglUniformLocation.NONE
    private var bilateralRadiusId = KglUniformLocation.NONE
    private var depthSigmaId = KglUniformLocation.NONE

    override fun initProgram(dc: DrawContext) {
        super.initProgram(dc)
        visibilitySamplerId = gl.getUniformLocation(program, "visibilitySampler")
        gl.uniform1i(visibilitySamplerId, 0) // GL_TEXTURE0
        depthSamplerId = gl.getUniformLocation(program, "depthSampler")
        gl.uniform1i(depthSamplerId, 1) // GL_TEXTURE1
        viewportSizeId = gl.getUniformLocation(program, "viewportSize")
        gl.uniform2f(viewportSizeId, 1f, 1f)
        bilateralRadiusId = gl.getUniformLocation(program, "bilateralRadius")
        gl.uniform1f(bilateralRadiusId, 0f)
        depthSigmaId = gl.getUniformLocation(program, "depthSigma")
        gl.uniform1f(depthSigmaId, 1f)
    }

    fun loadViewportSize(width: Int, height: Int) {
        gl.uniform2f(viewportSizeId, width.toFloat(), height.toFloat())
    }

    fun loadBilateralRadius(radius: Float) {
        gl.uniform1f(bilateralRadiusId, radius)
    }

    fun loadDepthSigma(sigma: Float) {
        gl.uniform1f(depthSigmaId, sigma)
    }

    companion object {
        val KEY = SightlineCompositeProgram::class
    }
}
