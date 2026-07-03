package earth.worldwind.layer.shadow

import earth.worldwind.geom.Matrix4
import earth.worldwind.util.kgl.Kgl
import earth.worldwind.util.kgl.KglProgram
import earth.worldwind.util.kgl.KglUniformLocation

/**
 * The GL uniform-location + upload machinery every [ShadowReceiverProgram] needs, factored out of the
 * five programs (triangle / basic-texture / surface-texture / 3D-Tiles mesh + points) that each
 * copy-pasted the identical field block, [init] resolution loop, and [loadEnabled]/[loadDisabled]
 * bodies. A program owns one instance, resolves it in `initProgram`, and forwards its interface
 * methods to it (see [ShadowReceiverProgram]).
 *
 * [enabled] mirrors the program's `shadowsEnabled` flag and gates every method, so the no-shadow
 * program variants stay inert: [init] skips resolution (locations remain [KglUniformLocation.NONE])
 * and the load methods early-return — behaviourally identical to the previous per-program code,
 * where the same calls hit `NONE` locations as silent no-ops.
 */
class ShadowReceiverUniforms(private val enabled: Boolean) {
    private var applyShadowId = KglUniformLocation.NONE
    private var useMSMId = KglUniformLocation.NONE
    private var ambientShadowId = KglUniformLocation.NONE
    private val shadowMapIds = arrayOf(KglUniformLocation.NONE, KglUniformLocation.NONE, KglUniformLocation.NONE)
    private val lightProjectionViewIds = arrayOf(KglUniformLocation.NONE, KglUniformLocation.NONE, KglUniformLocation.NONE)
    private val cascadeFarDepthIds = arrayOf(KglUniformLocation.NONE, KglUniformLocation.NONE, KglUniformLocation.NONE)
    private val matrixArray = FloatArray(16)

    /** Frame stamp of the last uploaded [ShadowState] — backs [ShadowReceiverProgram.shadowUploadStamp]. */
    var uploadStamp = -1L

    /** Resolve the receiver uniforms and seed their defaults. Call from the owning program's
     *  `initProgram` after the program links. No-op for the no-shadow variant. */
    fun init(gl: Kgl, program: KglProgram) {
        if (!enabled) return
        applyShadowId = gl.getUniformLocation(program, "applyShadow")
        gl.uniform1i(applyShadowId, 0)
        useMSMId = gl.getUniformLocation(program, "useMSM")
        gl.uniform1i(useMSMId, 0)
        ambientShadowId = gl.getUniformLocation(program, "ambientShadow")
        gl.uniform1f(ambientShadowId, 0.4f)
        for (i in shadowMapIds.indices) {
            shadowMapIds[i] = gl.getUniformLocation(program, "shadowMap$i")
            gl.uniform1i(shadowMapIds[i], 1 + i) // GL_TEXTURE1 + i
            lightProjectionViewIds[i] = gl.getUniformLocation(program, "lightProjectionView$i")
            cascadeFarDepthIds[i] = gl.getUniformLocation(program, "cascadeFarDepth$i")
            gl.uniform1f(cascadeFarDepthIds[i], 0f)
        }
    }

    fun loadDisabled(gl: Kgl) {
        if (enabled) gl.uniform1i(applyShadowId, 0)
    }

    fun loadEnabled(
        gl: Kgl,
        ambientShadow: Float,
        lightProjectionView0: Matrix4,
        lightProjectionView1: Matrix4,
        lightProjectionView2: Matrix4,
        cascadeFarDepth0: Float,
        cascadeFarDepth1: Float,
        cascadeFarDepth2: Float,
        useMSM: Boolean,
    ) {
        if (!enabled) return
        gl.uniform1i(applyShadowId, 1)
        gl.uniform1i(useMSMId, if (useMSM) 1 else 0)
        gl.uniform1f(ambientShadowId, ambientShadow)
        lightProjectionView0.transposeToArray(matrixArray, 0)
        gl.uniformMatrix4fv(lightProjectionViewIds[0], 1, false, matrixArray, 0)
        lightProjectionView1.transposeToArray(matrixArray, 0)
        gl.uniformMatrix4fv(lightProjectionViewIds[1], 1, false, matrixArray, 0)
        lightProjectionView2.transposeToArray(matrixArray, 0)
        gl.uniformMatrix4fv(lightProjectionViewIds[2], 1, false, matrixArray, 0)
        gl.uniform1f(cascadeFarDepthIds[0], cascadeFarDepth0)
        gl.uniform1f(cascadeFarDepthIds[1], cascadeFarDepth1)
        gl.uniform1f(cascadeFarDepthIds[2], cascadeFarDepth2)
    }
}
