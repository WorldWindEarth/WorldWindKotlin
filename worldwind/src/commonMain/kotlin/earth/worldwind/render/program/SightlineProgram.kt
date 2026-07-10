package earth.worldwind.render.program

import earth.worldwind.draw.DrawContext
import earth.worldwind.geom.Matrix4
import earth.worldwind.layer.shadow.ShadowReceiverGlsl
import earth.worldwind.layer.sightline.SightlineReceiverGlsl
import earth.worldwind.layer.sightline.SightlineReceiverProgram
import earth.worldwind.layer.sightline.SightlineReceiverUniforms
import earth.worldwind.layer.sightline.SightlineState
import earth.worldwind.util.kgl.KglUniformLocation

/**
 * Terrain overlay pass of the sightline: re-rasterises the visible terrain tiles and outputs
 * the visible/occluded tint from [SightlineReceiverGlsl]'s depth-cube PCF — the same receiver
 * GLSL that embedded receivers (3D tiles) splice into their own programs, so the overlay and
 * embedded tints resolve identically. Vertices arrive tile-origin-relative; the per-tile
 * `sightlineLocalMatrix` is loaded via [loadSightlineLocalMatrix].
 */
class SightlineProgram : AbstractShaderProgram(), SightlineReceiverProgram {
    override var programSources = arrayOf(
        """
            uniform mat4 mvpMatrix;

            attribute vec4 vertexPoint;

            ${SightlineReceiverGlsl.VERTEX_DECLARATIONS}

            void main() {
                gl_Position = mvpMatrix * vertexPoint;
                emitSightlineVaryings(vertexPoint);
            }
        """.trimIndent(),
        """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #elif defined(GL_ES)
            precision mediump float;
            #endif

            ${SightlineReceiverGlsl.FRAGMENT_DECLARATIONS}

            void main() {
                /* Premultiplied tint; vec4(0) outside the sightline volume is a no-op blend. */
                gl_FragColor = computeSightlineTint();
            }
        """.trimIndent()
    )
    override val attribBindings = arrayOf("vertexPoint")

    // Receiver prefix: hardware compare samplers where available + dFdx/dFdy enablement.
    override fun glslVersion(dc: DrawContext) = ShadowReceiverGlsl.glslPrefix(dc, true)

    private var mvpMatrixId = KglUniformLocation.NONE
    private val uniforms = SightlineReceiverUniforms(enabled = true)
    private val array = FloatArray(16)
    override var lastSightlineState: SightlineState?
        get() = uniforms.lastState
        set(value) { uniforms.lastState = value }

    override fun initProgram(dc: DrawContext) {
        super.initProgram(dc)
        mvpMatrixId = gl.getUniformLocation(program, "mvpMatrix")
        gl.uniformMatrix4fv(mvpMatrixId, 1, false, array, 0)
        uniforms.init(gl, program)
    }

    fun loadModelviewProjection(matrix: Matrix4) {
        matrix.transposeToArray(array, 0)
        gl.uniformMatrix4fv(mvpMatrixId, 1, false, array, 0)
    }

    override fun loadSightlineEnabled(state: SightlineState, localMatrix: Matrix4) =
        uniforms.loadEnabled(gl, state, localMatrix)

    override fun loadSightlineDisabled() = uniforms.loadDisabled(gl)

    fun loadSightlineLocalMatrix(matrix: Matrix4) = uniforms.loadLocalMatrix(gl, matrix)
}
