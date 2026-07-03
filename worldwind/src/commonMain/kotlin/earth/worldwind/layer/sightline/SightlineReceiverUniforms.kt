package earth.worldwind.layer.sightline

import earth.worldwind.geom.Matrix4
import earth.worldwind.render.Color
import earth.worldwind.util.kgl.Kgl
import earth.worldwind.util.kgl.KglProgram
import earth.worldwind.util.kgl.KglUniformLocation

/**
 * The GL uniform-location + upload machinery every [SightlineReceiverProgram] needs, factored out of
 * the 3D-Tiles mesh + points programs that each copy-pasted the identical field block, [init]
 * resolution, and [loadEnabled]/[loadDisabled] bodies. A program owns one instance, resolves it in
 * `initProgram`, and forwards its interface methods to it (see [SightlineReceiverProgram]).
 *
 * [enabled] mirrors the program's `sightlineEnabled` flag and gates every method, so the no-sightline
 * variants stay inert: [init] skips resolution (locations remain [KglUniformLocation.NONE]) and the
 * load methods early-return — behaviourally identical to the previous per-program code.
 */
class SightlineReceiverUniforms(private val enabled: Boolean) {
    private var applySightlineId = KglUniformLocation.NONE
    private var sightlineOmnidirectionalId = KglUniformLocation.NONE
    private var sightlineMvMatrixId = KglUniformLocation.NONE
    private var sightlineProjMatrixId = KglUniformLocation.NONE
    private var sightlineLocalMatrixId = KglUniformLocation.NONE
    private var sightlineRangeId = KglUniformLocation.NONE
    private var sightlineColorsId = KglUniformLocation.NONE
    private var sightlineMomentsSamplerId = KglUniformLocation.NONE
    private var sightlineMomentsCubeSamplerId = KglUniformLocation.NONE
    private val matrixArray = FloatArray(16)

    /** Identity of the last uploaded [SightlineState] — backs [SightlineReceiverProgram.lastSightlineState]. */
    var lastState: SightlineState? = null

    /** Resolve the receiver uniforms and seed their defaults. Call from the owning program's
     *  `initProgram` after the program links. No-op for the no-sightline variant. */
    fun init(gl: Kgl, program: KglProgram) {
        if (!enabled) return
        applySightlineId = gl.getUniformLocation(program, "applySightline")
        gl.uniform1i(applySightlineId, 0)
        sightlineOmnidirectionalId = gl.getUniformLocation(program, "sightlineOmnidirectional")
        gl.uniform1i(sightlineOmnidirectionalId, 0)
        sightlineMvMatrixId = gl.getUniformLocation(program, "sightlineMvMatrix")
        sightlineProjMatrixId = gl.getUniformLocation(program, "sightlineProjMatrix")
        sightlineLocalMatrixId = gl.getUniformLocation(program, "sightlineLocalMatrix")
        sightlineRangeId = gl.getUniformLocation(program, "sightlineRange")
        gl.uniform1f(sightlineRangeId, 0f)
        sightlineColorsId = gl.getUniformLocation(program, "sightlineColors")
        sightlineMomentsSamplerId = gl.getUniformLocation(program, "sightlineMomentsSampler")
        gl.uniform1i(sightlineMomentsSamplerId, 4) // GL_TEXTURE4
        sightlineMomentsCubeSamplerId = gl.getUniformLocation(program, "sightlineMomentsCubeSampler")
        gl.uniform1i(sightlineMomentsCubeSamplerId, 5) // GL_TEXTURE5
    }

    fun loadDisabled(gl: Kgl) {
        if (enabled) gl.uniform1i(applySightlineId, 0)
    }

    fun loadEnabled(
        gl: Kgl,
        omnidirectional: Boolean,
        sightlineMv: Matrix4,
        cubeMapProjection: Matrix4,
        sightlineLocal: Matrix4,
        range: Float,
        visibleColor: Color,
        occludedColor: Color,
    ) {
        if (!enabled) return
        gl.uniform1i(applySightlineId, 1)
        gl.uniform1i(sightlineOmnidirectionalId, if (omnidirectional) 1 else 0)
        sightlineMv.transposeToArray(matrixArray, 0)
        gl.uniformMatrix4fv(sightlineMvMatrixId, 1, false, matrixArray, 0)
        cubeMapProjection.transposeToArray(matrixArray, 0)
        gl.uniformMatrix4fv(sightlineProjMatrixId, 1, false, matrixArray, 0)
        sightlineLocal.transposeToArray(matrixArray, 0)
        gl.uniformMatrix4fv(sightlineLocalMatrixId, 1, false, matrixArray, 0)
        gl.uniform1f(sightlineRangeId, range)
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
}
