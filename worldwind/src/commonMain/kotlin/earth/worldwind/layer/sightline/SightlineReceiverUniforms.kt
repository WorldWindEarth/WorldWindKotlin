package earth.worldwind.layer.sightline

import earth.worldwind.geom.Matrix4
import earth.worldwind.util.kgl.Kgl
import earth.worldwind.util.kgl.KglProgram
import earth.worldwind.util.kgl.KglUniformLocation
import kotlin.math.max

/**
 * The GL uniform-location + upload machinery every [SightlineReceiverProgram] needs, factored out of
 * the 3D-Tiles mesh + points programs that each copy-pasted the identical field block, [init]
 * resolution, and [loadEnabled]/[loadDisabled] bodies. A program owns one instance, resolves it in
 * `initProgram`, and forwards its interface methods to it (see [SightlineReceiverProgram]).
 *
 * [enabled] mirrors the program's `sightlineEnabled` flag and gates every method, so the no-sightline
 * variants stay inert: [init] skips resolution (locations remain [KglUniformLocation.NONE]) and the
 * load methods early-return.
 */
class SightlineReceiverUniforms(private val enabled: Boolean) {
    private var applySightlineId = KglUniformLocation.NONE
    private var sightlineLocalMatrixId = KglUniformLocation.NONE
    private var sightlineRangeId = KglUniformLocation.NONE
    private var sightlineDepthScaleId = KglUniformLocation.NONE
    private var sightlineForwardAzId = KglUniformLocation.NONE
    private var sightlineCosHalfFovId = KglUniformLocation.NONE
    private var sightlineSinHalfFovId = KglUniformLocation.NONE
    private var sightlineColorsId = KglUniformLocation.NONE
    private var sightlineDepthSamplerId = KglUniformLocation.NONE
    private val matrixArray = FloatArray(16)

    /** Identity of the last uploaded [SightlineState] — backs [SightlineReceiverProgram.lastSightlineState]. */
    var lastState: SightlineState? = null

    /** Resolve the receiver uniforms and seed their defaults. Call from the owning program's
     *  `initProgram` after the program links. No-op for the no-sightline variant. */
    fun init(gl: Kgl, program: KglProgram) {
        if (!enabled) return
        applySightlineId = gl.getUniformLocation(program, "applySightline")
        gl.uniform1i(applySightlineId, 0)
        sightlineLocalMatrixId = gl.getUniformLocation(program, "sightlineLocalMatrix")
        sightlineRangeId = gl.getUniformLocation(program, "sightlineRange")
        gl.uniform1f(sightlineRangeId, 0f)
        sightlineDepthScaleId = gl.getUniformLocation(program, "sightlineDepthScale")
        sightlineForwardAzId = gl.getUniformLocation(program, "sightlineForwardAz")
        sightlineCosHalfFovId = gl.getUniformLocation(program, "sightlineCosHalfFov")
        sightlineSinHalfFovId = gl.getUniformLocation(program, "sightlineSinHalfFov")
        sightlineColorsId = gl.getUniformLocation(program, "sightlineColors")
        sightlineDepthSamplerId = gl.getUniformLocation(program, "sightlineDepthSampler")
        gl.uniform1i(sightlineDepthSamplerId, 5) // GL_TEXTURE5 — units 1..4 hold the shadow cascades
    }

    fun loadDisabled(gl: Kgl) {
        if (enabled) gl.uniform1i(applySightlineId, 0)
    }

    /** Uploads the per-frame sightline scalars and colors plus the world -> local matrix. */
    fun loadEnabled(gl: Kgl, state: SightlineState, localMatrix: Matrix4) {
        if (!enabled) return
        gl.uniform1i(applySightlineId, 1)
        loadLocalMatrix(gl, localMatrix)
        val range = max(state.range, 1.001f) // near plane is 1; degenerate ranges tint nothing anyway
        gl.uniform1f(sightlineRangeId, state.range)
        gl.uniform1f(sightlineDepthScaleId, range / (range - 1f))
        gl.uniform2f(sightlineForwardAzId, state.forwardX, state.forwardY)
        gl.uniform1f(sightlineCosHalfFovId, state.cosHalfFov)
        gl.uniform1f(sightlineSinHalfFovId, state.sinHalfFov)
        val visibleColor = state.visibleColor
        val occludedColor = state.occludedColor
        val colorsArray = floatArrayOf(
            visibleColor.red * visibleColor.alpha,
            visibleColor.green * visibleColor.alpha,
            visibleColor.blue * visibleColor.alpha,
            visibleColor.alpha,
            occludedColor.red * occludedColor.alpha,
            occludedColor.green * occludedColor.alpha,
            occludedColor.blue * occludedColor.alpha,
            occludedColor.alpha,
        )
        gl.uniform4fv(sightlineColorsId, 2, colorsArray, 0)
    }

    /** Uploads only the world -> local matrix (per-tile overlay path). */
    fun loadLocalMatrix(gl: Kgl, localMatrix: Matrix4) {
        if (!enabled) return
        localMatrix.transposeToArray(matrixArray, 0)
        gl.uniformMatrix4fv(sightlineLocalMatrixId, 1, false, matrixArray, 0)
    }
}
