package earth.worldwind.layer.shadow

import earth.worldwind.geom.Vec3
import earth.worldwind.util.kgl.Kgl
import kotlin.math.max
import earth.worldwind.util.kgl.KglProgram
import earth.worldwind.util.kgl.KglUniformLocation

/**
 * The GL uniform-location + upload machinery every [ShadowReceiverProgram] needs, factored out of the
 * receiver programs (triangle / basic-texture / surface-texture / 3D-Tiles mesh + points) that would
 * each copy-paste the identical field block, [init] resolution loop, and [loadEnabled]/[loadDisabled]
 * bodies. A program owns one instance, resolves it in `initProgram`, and forwards its interface
 * methods to it (see [ShadowReceiverProgram]).
 *
 * [enabled] mirrors the program's `shadowsEnabled` flag and gates every method, so the no-shadow
 * program variants stay inert: [init] skips resolution (locations remain [KglUniformLocation.NONE])
 * and the load methods early-return — behaviourally identical to per-program code where the same
 * calls hit `NONE` locations as silent no-ops.
 */
class ShadowReceiverUniforms(
    private val enabled: Boolean,
    /** Receiver constant depth bias in world metres — normalized per cascade at upload. */
    private val depthBiasMeters: Float = ShadowReceiverGlsl.PRIMITIVE_DEPTH_BIAS_METERS,
) {
    private var applyShadowId = KglUniformLocation.NONE
    private var ambientShadowId = KglUniformLocation.NONE
    private var lightDirectionId = KglUniformLocation.NONE
    private var maxDistanceId = KglUniformLocation.NONE
    private var cascadeFarDepthsId = KglUniformLocation.NONE
    private var cascadeTexelWorldSizesId = KglUniformLocation.NONE
    private var cascadeDepthBiasesId = KglUniformLocation.NONE
    private var cascadeConstBiasesId = KglUniformLocation.NONE
    private var debugCascadesId = KglUniformLocation.NONE
    private val shadowMapIds = Array(ShadowReceiverGlsl.CASCADE_COUNT) { KglUniformLocation.NONE }
    private val shadowMatrixIds = Array(ShadowReceiverGlsl.CASCADE_COUNT) { KglUniformLocation.NONE }
    private var terrainLambertId = KglUniformLocation.NONE
    private var terrainLambert = 0f
    private val matrixArray = FloatArray(16)
    private val vec4Array = FloatArray(4)

    /** Frame stamp of the last uploaded [ShadowState] — backs [ShadowReceiverProgram.shadowUploadStamp]. */
    var uploadStamp = -1L

    private companion object {
        /** Texel scale for the per-cascade receiver bias: the constant fallback where
         *  derivatives are unavailable and the clamp base for the receiver-plane term. */
        const val KERNEL_BIAS_TEXELS = 1.0

        /** Floor for the normalized constant bias: four 24-bit depth-buffer quanta. A
         *  globe-scale depth window dilutes the metre bias below the map's own quantization
         *  (terrain casts true depth), which reads back as concentric ring acne; at street
         *  ranges the metre bias dominates and this floor is far below it. */
        const val DEPTH_QUANTUM_FLOOR = 4.0 / 16777216.0
    }

    /** Resolve the receiver uniforms and seed their defaults. Call from the owning program's
     *  `initProgram` after the program links. No-op for the no-shadow variant. */
    fun init(gl: Kgl, program: KglProgram) {
        if (!enabled) return
        applyShadowId = gl.getUniformLocation(program, "applyShadow")
        gl.uniform1i(applyShadowId, 0)
        ambientShadowId = gl.getUniformLocation(program, "ambientShadow")
        gl.uniform1f(ambientShadowId, ShadowState.DEFAULT_AMBIENT_SHADOW)
        lightDirectionId = gl.getUniformLocation(program, "shadowLightDirection")
        gl.uniform3f(lightDirectionId, 0f, 0f, 1f)
        maxDistanceId = gl.getUniformLocation(program, "shadowMaxDistance")
        gl.uniform1f(maxDistanceId, 0f)
        cascadeFarDepthsId = gl.getUniformLocation(program, "cascadeFarDepths")
        gl.uniform4f(cascadeFarDepthsId, 0f, 0f, 0f, 0f)
        cascadeTexelWorldSizesId = gl.getUniformLocation(program, "cascadeTexelWorldSizes")
        gl.uniform4f(cascadeTexelWorldSizesId, 0f, 0f, 0f, 0f)
        cascadeDepthBiasesId = gl.getUniformLocation(program, "cascadeDepthBiases")
        gl.uniform4f(cascadeDepthBiasesId, 0f, 0f, 0f, 0f)
        cascadeConstBiasesId = gl.getUniformLocation(program, "cascadeConstBiases")
        gl.uniform4f(cascadeConstBiasesId, 0f, 0f, 0f, 0f)
        debugCascadesId = gl.getUniformLocation(program, "debugShadowMode")
        gl.uniform1i(debugCascadesId, 0)
        for (i in shadowMapIds.indices) {
            shadowMapIds[i] = gl.getUniformLocation(program, "shadowMap$i")
            gl.uniform1i(shadowMapIds[i], 1 + i) // GL_TEXTURE1 + i
            shadowMatrixIds[i] = gl.getUniformLocation(program, "shadowMatrix$i")
        }
        // NONE (silent no-op) on receivers whose GLSL is not lit - only lit receivers declare it.
        terrainLambertId = gl.getUniformLocation(program, "terrainLambert")
        terrainLambert = 0f
        gl.uniform1f(terrainLambertId, terrainLambert)
    }

    /**
     * Sets the Lambertian terrain relief strength (see [ShadowLayer.terrainLambert]) for the
     * next draw. Per-drawable state - callers must reset to 0 after a relief draw so other
     * draws through the same program stay unshaded. Relief is cascade-independent, so
     * [lightDirection] re-uploads the sun for frames where the depth pipeline is disabled
     * and [loadEnabled] never ran.
     */
    fun loadTerrainRelief(gl: Kgl, strength: Float, lightDirection: Vec3?) {
        if (!enabled) return
        if (terrainLambert != strength) {
            terrainLambert = strength
            gl.uniform1f(terrainLambertId, strength)
        }
        if (strength > 0f && lightDirection != null) {
            gl.uniform3f(
                lightDirectionId,
                lightDirection.x.toFloat(), lightDirection.y.toFloat(), lightDirection.z.toFloat(),
            )
        }
    }

    fun loadDisabled(gl: Kgl) {
        if (enabled) gl.uniform1i(applyShadowId, 0)
    }

    fun loadEnabled(gl: Kgl, state: ShadowState) {
        if (!enabled) return
        gl.uniform1i(applyShadowId, 1)
        gl.uniform1f(ambientShadowId, state.ambientShadow)
        gl.uniform3f(
            lightDirectionId,
            state.lightDirection.x.toFloat(), state.lightDirection.y.toFloat(), state.lightDirection.z.toFloat(),
        )
        gl.uniform1f(maxDistanceId, state.shadowDistance.toFloat())
        gl.uniform1i(debugCascadesId, state.debugShadowMode)
        // Sanitize far depths for any degenerate cascade: carrying the previous cascade's far
        // forward makes the shader's branch chain skip the invalid slice entirely.
        var previousFar = 0f
        for (i in 0 until ShadowReceiverGlsl.CASCADE_COUNT) {
            val cascade = state.cascades[i]
            if (cascade.isValid) previousFar = cascade.farViewDepth.toFloat()
            vec4Array[i] = previousFar
        }
        gl.uniform4f(cascadeFarDepthsId, vec4Array[0], vec4Array[1], vec4Array[2], vec4Array[3])
        for (i in 0 until ShadowReceiverGlsl.CASCADE_COUNT) {
            vec4Array[i] = state.cascades[i].texelWorldSize.toFloat()
        }
        gl.uniform4f(cascadeTexelWorldSizesId, vec4Array[0], vec4Array[1], vec4Array[2], vec4Array[3])
        // Kernel-slope bias: the PCF kernel reaches [KERNEL_BIAS_TEXELS] texels from the
        // receiver, where a moderately sloped surface legitimately sits that many texel
        // world-sizes closer to the sun. Normalized by each cascade's depth range so the
        // shader can subtract it from the [0,1] receiver depth directly.
        for (i in 0 until ShadowReceiverGlsl.CASCADE_COUNT) {
            val cascade = state.cascades[i]
            vec4Array[i] = if (cascade.range > 0.0) {
                (KERNEL_BIAS_TEXELS * cascade.texelWorldSize / cascade.range).toFloat()
            } else 0f
        }
        gl.uniform4f(cascadeDepthBiasesId, vec4Array[0], vec4Array[1], vec4Array[2], vec4Array[3])
        // Constant receiver bias: world metres normalized by each cascade's depth range so an
        // inflated depth window (high-altitude caster) can't balloon the world-space bias,
        // floored at a few depth-buffer quanta so a huge window can't dilute it below the
        // map's own quantization.
        for (i in 0 until ShadowReceiverGlsl.CASCADE_COUNT) {
            val cascade = state.cascades[i]
            vec4Array[i] = if (cascade.range > 0.0) {
                max(depthBiasMeters / cascade.range, DEPTH_QUANTUM_FLOOR).toFloat()
            } else 0f
        }
        gl.uniform4f(cascadeConstBiasesId, vec4Array[0], vec4Array[1], vec4Array[2], vec4Array[3])
        for (i in 0 until ShadowReceiverGlsl.CASCADE_COUNT) {
            state.cascades[i].shadowMatrix.transposeToArray(matrixArray, 0)
            gl.uniformMatrix4fv(shadowMatrixIds[i], 1, false, matrixArray, 0)
        }
    }
}
