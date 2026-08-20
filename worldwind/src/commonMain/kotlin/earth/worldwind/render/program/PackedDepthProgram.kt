package earth.worldwind.render.program

import earth.worldwind.draw.DrawContext
import earth.worldwind.geom.Matrix4
import earth.worldwind.util.kgl.KglUniformLocation

/**
 * Common surface of the sightline depth casters: [DirectionalDepthProgram] writes true
 * hardware depth (hardware depth-compare platforms), [PackedDepthProgram] packs depth into
 * an RGBA8 color target (software-compare fallback). [earth.worldwind.draw.DrawableSightline]
 * picks one per pass via [earth.worldwind.draw.DrawContext.sightlineUsesPackedDepth].
 */
interface DepthCasterProgram {
    fun useProgram(dc: DrawContext): Boolean
    fun loadModelviewProjection(matrix: Matrix4)
}

/**
 * Depth caster that packs the fragment's window depth into the RGB channels of an RGBA8
 * color target (red = high byte, green = middle, blue = low). Used by the sightline depth
 * pass on platforms without hardware depth-compare samplers
 * ([earth.worldwind.util.kgl.Kgl.hasShadowSamplers]): there receivers read the cube through
 * a plain `samplerCube`, and sampling a real `DEPTH_COMPONENT` texture that way is
 * driver-dependent — Adreno 512 returns ~8-bit quantized depth, which collapses the
 * sightline's `1/d` depth mapping into kilometre-scale buckets and classified entire scenes
 * occluded (worked at 2.0.5's moment-map color cube, broke at 2.0.6's depth cube). RGBA8
 * color reads are exact on every GPU. Packing mirrors [DepthToColorProgram] /
 * `DrawContext.readPixelDepth`: `depth = r + g/255 + b/255^2` on normalized channels.
 */
class PackedDepthProgram : AbstractShaderProgram(), DepthCasterProgram {
    override var programSources = arrayOf(
        """
            uniform mat4 mvpMatrix;

            attribute vec4 vertexPoint;

            /* Linear eye depth. For the sightline's perspective cube projection,
               gl_Position.w IS the eye-space distance along the face axis, and passing w
               through perspective-correct varying interpolation reconstructs the true
               per-fragment value. Deliberately NOT gl_FragCoord.z: the 1/d window mapping
               packs all far-range information into the last fraction near 1.0, and some
               drivers (Adreno 512) degrade its fragment-stage precision - a highp varying
               of linear distance has uniform metre-scale precision instead. */
            varying highp float eyeDepth;

            void main() {
                gl_Position = mvpMatrix * vertexPoint;
                eyeDepth = gl_Position.w;
            }
        """.trimIndent(),
        """
            /* highp mandatory: the pack quantises a 24-bit value into three bytes. */
            #ifdef GL_ES
            precision highp float;
            #endif

            /* Face-axis range of the sightline's cube projection; normalizes eyeDepth. */
            uniform float range;

            varying highp float eyeDepth;

            /* Mirror of the unpack in SightlineReceiverGlsl (r + g/255 + b/255^2). */
            vec3 packD24(float depth) {
                float scaled = depth * 255.0;
                float hi = floor(scaled);
                float remMid = (scaled - hi) * 255.0;
                float mid = floor(remMid);
                float lo = remMid - mid;                    /* [0, 1), framebuffer quantizes to byte */
                return vec3(hi / 255.0, mid / 255.0, lo);
            }

            void main() {
                gl_FragColor = vec4(packD24(clamp(eyeDepth / range, 0.0, 1.0)), 1.0);
            }
        """.trimIndent()
    )
    override val attribBindings = arrayOf("vertexPoint")

    private var mvpMatrixId = KglUniformLocation.NONE
    private var rangeId = KglUniformLocation.NONE
    private val array = FloatArray(16)

    override fun initProgram(dc: DrawContext) {
        super.initProgram(dc)
        mvpMatrixId = gl.getUniformLocation(program, "mvpMatrix")
        gl.uniformMatrix4fv(mvpMatrixId, 1, false, array, 0)
        rangeId = gl.getUniformLocation(program, "range")
        gl.uniform1f(rangeId, 1f)
    }

    override fun loadModelviewProjection(matrix: Matrix4) {
        matrix.transposeToArray(array, 0)
        gl.uniformMatrix4fv(mvpMatrixId, 1, false, array, 0)
    }

    /** Face-axis range the packed linear depth is normalized by; load once per pass. */
    fun loadRange(range: Float) {
        gl.uniform1f(rangeId, range)
    }
}
