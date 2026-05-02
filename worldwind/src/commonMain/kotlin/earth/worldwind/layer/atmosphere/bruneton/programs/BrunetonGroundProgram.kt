/*
 * Adapted from ebruneton/precomputed_atmospheric_scattering, BSD-3-Clause,
 * Copyright (c) 2017 Eric Bruneton.
 *
 * Runtime ground shader: per-fragment Bruneton aerial perspective on top of WorldWind's
 * already-rendered terrain. Same three-mode interface as the legacy
 * [earth.worldwind.layer.atmosphere.GroundProgram] so the two-pass DST_COLOR + ONE/ONE
 * blend pipeline keeps working unchanged:
 *
 *   PRIMARY            additive in-scatter (aerial perspective, atmospheric haze).
 *   SECONDARY          multiplicative `T × (sun + sky)/SOLAR_IRRADIANCE`. Includes shadow
 *                      occlusion of the sun term — see [ShadowReceiverProgram] integration.
 *   PRIMARY_TEX_BLEND  primary + a night-image emissive term gated by (1 - day_factor),
 *                      so city lights only show on the dark side.
 *
 * Implements [ShadowReceiverProgram] so cascaded PCF / MSM shadow maps darken the SECONDARY
 * pass under occluders. The Bruneton variant occludes only the *direct sun* term — sky
 * irradiance is unaffected by surface shadows, matching physical light behaviour and giving
 * shadowed terrain a natural cool-blue cast under a clear sky.
 *
 * ES3 / WebGL2 / GL 3.3 core only — uses 3D textures and `out vec4` output. The shadow
 * helper [ShadowReceiverGlsl] is GLSL ES 1.0 / 1.20 compatible (uses `texture2D`); a
 * `#define texture2D texture` macro at the top of the fragment source bridges that to the
 * ES3 / GL 3.3 `texture()` overload.
 */
package earth.worldwind.layer.atmosphere.bruneton.programs

import earth.worldwind.draw.DrawContext
import earth.worldwind.geom.Matrix3
import earth.worldwind.geom.Matrix4
import earth.worldwind.geom.Vec3
import earth.worldwind.layer.atmosphere.bruneton.BrunetonShaders
import earth.worldwind.layer.shadow.ShadowReceiverGlsl
import earth.worldwind.layer.shadow.ShadowReceiverProgram
import earth.worldwind.render.program.AbstractShaderProgram
import earth.worldwind.util.kgl.KglUniformLocation

internal class BrunetonGroundProgram : AbstractShaderProgram(), ShadowReceiverProgram {

    override var programSources = arrayOf(VERTEX_SOURCE, FRAGMENT_SOURCE)
    override val attribBindings = arrayOf("vertexPoint", "vertexTexCoord")

    override fun glslVersion(dc: DrawContext) = dc.gl.glslVersion3

    private var fragModeId = KglUniformLocation.NONE
    private var mvpMatrixId = KglUniformLocation.NONE
    private var texCoordMatrixId = KglUniformLocation.NONE
    private var vertexOriginId = KglUniformLocation.NONE
    private var nightTextureId = KglUniformLocation.NONE
    private var transmittanceTexId = KglUniformLocation.NONE
    private var irradianceTexId = KglUniformLocation.NONE
    private var scatteringTexId = KglUniformLocation.NONE
    private var mieScatteringTexId = KglUniformLocation.NONE
    private var eyePointId = KglUniformLocation.NONE
    private var sunDirectionId = KglUniformLocation.NONE
    private var exposureId = KglUniformLocation.NONE
    private var groundExposureId = KglUniformLocation.NONE
    private var nightEmissiveId = KglUniformLocation.NONE

    // Shadow uniform IDs. [applyShadowReceiverUniforms] hard-codes shadow-cascade textures
    // to GL_TEXTURE1..3, so our atmosphere LUT samplers are bound to units 5-8 (night image
    // stays on unit 0 like the legacy convention).
    private var applyShadowId = KglUniformLocation.NONE
    private var useMSMId = KglUniformLocation.NONE
    private var ambientShadowId = KglUniformLocation.NONE
    private val shadowMapIds = arrayOf(KglUniformLocation.NONE, KglUniformLocation.NONE, KglUniformLocation.NONE)
    private val lightProjectionViewIds = arrayOf(KglUniformLocation.NONE, KglUniformLocation.NONE, KglUniformLocation.NONE)
    private val cascadeFarDepthIds = arrayOf(KglUniformLocation.NONE, KglUniformLocation.NONE, KglUniformLocation.NONE)
    private val lightProjectionViewArray = FloatArray(16)

    override var shadowUploadStamp: Long = -1L

    private val matrixArray = FloatArray(16)
    private val matrix3Array = FloatArray(9)

    override fun initProgram(dc: DrawContext) {
        super.initProgram(dc)
        fragModeId = gl.getUniformLocation(program, "fragMode")
        gl.uniform1i(fragModeId, FRAGMODE_PRIMARY)
        mvpMatrixId = gl.getUniformLocation(program, "mvpMatrix")
        texCoordMatrixId = gl.getUniformLocation(program, "texCoordMatrix")
        vertexOriginId = gl.getUniformLocation(program, "vertexOrigin")
        nightTextureId = gl.getUniformLocation(program, "nightTexture")
        transmittanceTexId = gl.getUniformLocation(program, "transmittanceTex")
        irradianceTexId = gl.getUniformLocation(program, "irradianceTex")
        scatteringTexId = gl.getUniformLocation(program, "scatteringTex")
        mieScatteringTexId = gl.getUniformLocation(program, "mieScatteringTex")
        // Sampler unit assignments. Caller binds matching units before drawing:
        //   0       = night image            (legacy convention)
        //   1, 2, 3 = shadow cascades 0/1/2  (matches applyShadowReceiverUniforms)
        //   5       = transmittance LUT
        //   6       = irradiance LUT
        //   7       = scattering LUT
        //   8       = mie scattering LUT
        gl.uniform1i(nightTextureId, 0)
        gl.uniform1i(transmittanceTexId, 5)
        gl.uniform1i(irradianceTexId, 6)
        gl.uniform1i(scatteringTexId, 7)
        gl.uniform1i(mieScatteringTexId, 8)
        eyePointId = gl.getUniformLocation(program, "eyePoint")
        sunDirectionId = gl.getUniformLocation(program, "sunDirection")
        exposureId = gl.getUniformLocation(program, "exposure")
        groundExposureId = gl.getUniformLocation(program, "groundExposure")
        nightEmissiveId = gl.getUniformLocation(program, "nightEmissive")
        gl.uniform1f(exposureId, 10f)
        gl.uniform1f(groundExposureId, 10f) // match sky and reference Demo's exposure
        gl.uniform1f(nightEmissiveId, 1f)

        // Shadow uniforms (declared by ShadowReceiverGlsl.FRAGMENT_DECLARATIONS). Default to
        // shadow-disabled; the runtime drawable enables per-frame via applyShadowReceiverUniforms.
        applyShadowId = gl.getUniformLocation(program, "applyShadow")
        gl.uniform1i(applyShadowId, 0)
        useMSMId = gl.getUniformLocation(program, "useMSM")
        gl.uniform1i(useMSMId, 0)
        ambientShadowId = gl.getUniformLocation(program, "ambientShadow")
        gl.uniform1f(ambientShadowId, 0.4f) // unused by our SECONDARY (we use raw visibility)
        for (i in shadowMapIds.indices) {
            shadowMapIds[i] = gl.getUniformLocation(program, "shadowMap$i")
            gl.uniform1i(shadowMapIds[i], 1 + i)
            lightProjectionViewIds[i] = gl.getUniformLocation(program, "lightProjectionView$i")
            cascadeFarDepthIds[i] = gl.getUniformLocation(program, "cascadeFarDepth$i")
            gl.uniform1f(cascadeFarDepthIds[i], 0f)
        }
    }

    fun loadFragMode(mode: Int) = gl.uniform1i(fragModeId, mode)

    fun loadModelviewProjection(matrix: Matrix4) {
        matrix.transposeToArray(matrixArray, 0)
        gl.uniformMatrix4fv(mvpMatrixId, 1, false, matrixArray, 0)
    }

    fun loadTexCoordMatrix(matrix: Matrix3) {
        matrix.transposeToArray(matrix3Array, 0)
        gl.uniformMatrix3fv(texCoordMatrixId, 1, false, matrix3Array, 0)
    }

    fun loadVertexOrigin(origin: Vec3) =
        gl.uniform3f(vertexOriginId, origin.x.toFloat(), origin.y.toFloat(), origin.z.toFloat())

    fun loadEyePoint(eye: Vec3) =
        gl.uniform3f(eyePointId, eye.x.toFloat(), eye.y.toFloat(), eye.z.toFloat())

    fun loadSunDirection(dir: Vec3) =
        gl.uniform3f(sunDirectionId, dir.x.toFloat(), dir.y.toFloat(), dir.z.toFloat())

    fun loadExposure(exposure: Float) = gl.uniform1f(exposureId, exposure)
    fun loadGroundExposure(exposure: Float) = gl.uniform1f(groundExposureId, exposure)
    fun loadNightEmissive(scale: Float) = gl.uniform1f(nightEmissiveId, scale)

    override fun loadShadowDisabled() {
        gl.uniform1i(applyShadowId, 0)
    }

    override fun loadShadowEnabled(
        ambientShadow: Float,
        lightProjectionView0: Matrix4,
        lightProjectionView1: Matrix4,
        lightProjectionView2: Matrix4,
        cascadeFarDepth0: Float,
        cascadeFarDepth1: Float,
        cascadeFarDepth2: Float,
        useMSM: Boolean,
    ) {
        gl.uniform1i(applyShadowId, 1)
        gl.uniform1i(useMSMId, if (useMSM) 1 else 0)
        gl.uniform1f(ambientShadowId, ambientShadow)
        lightProjectionView0.transposeToArray(lightProjectionViewArray, 0)
        gl.uniformMatrix4fv(lightProjectionViewIds[0], 1, false, lightProjectionViewArray, 0)
        lightProjectionView1.transposeToArray(lightProjectionViewArray, 0)
        gl.uniformMatrix4fv(lightProjectionViewIds[1], 1, false, lightProjectionViewArray, 0)
        lightProjectionView2.transposeToArray(lightProjectionViewArray, 0)
        gl.uniformMatrix4fv(lightProjectionViewIds[2], 1, false, lightProjectionViewArray, 0)
        gl.uniform1f(cascadeFarDepthIds[0], cascadeFarDepth0)
        gl.uniform1f(cascadeFarDepthIds[1], cascadeFarDepth1)
        gl.uniform1f(cascadeFarDepthIds[2], cascadeFarDepth2)
    }

    companion object {
        val KEY = BrunetonGroundProgram::class

        const val FRAGMODE_PRIMARY = 1
        const val FRAGMODE_SECONDARY = 2
        const val FRAGMODE_PRIMARY_TEX_BLEND = 3

        // Vertex: world position = vertexPoint + vertexOrigin (terrain mesh uses tile-local
        // coordinates; vertexOrigin is the per-tile reference point, MVP already accounts
        // for the offset). Pass world position to fragment for Bruneton lookups, plus
        // viewDepth for the shadow cascade picker.
        private val VERTEX_SOURCE = """
            precision highp float;
            uniform mat4 mvpMatrix;
            uniform mat3 texCoordMatrix;
            uniform vec3 vertexOrigin;
            in vec4 vertexPoint;
            in vec2 vertexTexCoord;
            out vec3 worldPos;
            out vec2 texCoord;
            out float viewDepth;
            void main() {
                worldPos = vertexPoint.xyz + vertexOrigin;
                texCoord = (texCoordMatrix * vec3(vertexTexCoord, 1.0)).st;
                gl_Position = mvpMatrix * vertexPoint;
                viewDepth = gl_Position.w;
            }
        """.trimIndent()

        private val FRAGMENT_SOURCE = """
            precision highp float;
            precision highp int;
            precision highp sampler2D;
            precision highp sampler3D;

            // Bridge ShadowReceiverGlsl's GLSL-1.0 `texture2D(...)` calls to the ES3 / GL 3.3
            // overloaded `texture(...)` function. The legacy receivers (#version 120 / no
            // version) get the macro at no cost since they don't include this fragment.
            #define texture2D texture

            ${BrunetonShaders.COMMON}

            const int FRAGMODE_PRIMARY = 1;
            const int FRAGMODE_SECONDARY = 2;
            const int FRAGMODE_PRIMARY_TEX_BLEND = 3;

            uniform int fragMode;
            uniform sampler2D nightTexture;
            uniform sampler2D transmittanceTex;
            uniform sampler2D irradianceTex;
            uniform sampler3D scatteringTex;
            uniform sampler3D mieScatteringTex;
            uniform vec3 eyePoint;
            uniform vec3 sunDirection;
            uniform float exposure;
            uniform float groundExposure;
            uniform float nightEmissive;

            ${ShadowReceiverGlsl.FRAGMENT_DECLARATIONS}

            in vec3 worldPos;
            in vec2 texCoord;
            in float viewDepth;
            out vec4 fragColor;

            void main() {
                // BOTTOM_RADIUS is set to WorldWind's WGS84 equatorial radius, so the ECEF
                // terrain and camera positions feed the LUT lookups directly — no rescaling
                // needed. Surface normal approximated as the local zenith (acceptable at
                // planetary scale; terrain elevation perturbations are tiny vs Earth radius).
                vec3 point  = worldPos;
                vec3 normal = normalize(point);

                // Bruneton lookups — both passes need them; the fragMode switch picks what
                // to output. The cost is per-fragment but the LUT samples are cheap.
                vec3 sky_irradiance;
                vec3 sun_irradiance = GetSunAndSkyIrradiance(
                    transmittanceTex, irradianceTex, point, normal, sunDirection, sky_irradiance);
                vec3 transmittance;
                // shadow_length = 0 — WorldWind doesn't drive the volumetric-shaft path.
                vec3 in_scatter = GetSkyRadianceToPoint(
                    transmittanceTex, scatteringTex, mieScatteringTex,
                    eyePoint, point, 0.0, sunDirection, transmittance);

                if (fragMode == FRAGMODE_SECONDARY) {
                    // Reference math, multiplicative leg of the two-pass split. Single-pass
                    // reference computes `T · ground_radiance + in_scatter` where
                    // `ground_radiance = albedo / π · (sun_irradiance + sky_irradiance)`. To
                    // route that through the framebuffer's existing terrain texture (which we
                    // treat as a stand-in for albedo), output the per-channel multiplier
                    // `T · (visibility · sun + sky) / SOLAR_IRRADIANCE`. Cascaded shadow
                    // visibility (raw, no ambient floor) occludes ONLY the direct-sun term —
                    // physical: surface shadows block direct sunlight, not skylight. That gives
                    // shadowed terrain a natural cool-blue cast under a clear sky.
                    float visibility = computeRawShadowVisibility(worldPos, viewDepth);
                    vec3 lighting = (sun_irradiance * visibility + sky_irradiance) / SOLAR_IRRADIANCE;
                    fragColor = vec4(transmittance * lighting, 1.0);
                } else if (fragMode == FRAGMODE_PRIMARY) {
                    // Reference's tonemap `pow(1 − exp(−in_scatter · exposure), 1/2.2)`
                    // applied to the in-scatter contribution of the single-pass composite.
                    // Combined with additive blend in [DrawableBrunetonGround], the runtime
                    // result approximates `tonemap(T · ground_radiance + in_scatter)` for
                    // the cases where ground_radiance × T sits below 1.0.
                    fragColor = vec4(BrunetonTonemap(in_scatter, groundExposure), 1.0);
                } else if (fragMode == FRAGMODE_PRIMARY_TEX_BLEND) {
                    // Same tonemap; night image (scaled by [nightEmissive]) is added in
                    // before the tonemap so it composites in the same range as in_scatter.
                    float mu_s = dot(normal, sunDirection);
                    float day_factor = smoothstep(-0.05, 0.05, mu_s);
                    vec3 night = texture(nightTexture, texCoord).rgb *
                                 (1.0 - day_factor) * nightEmissive;
                    fragColor = vec4(BrunetonTonemap(in_scatter + transmittance * night,
                                                     groundExposure), 1.0);
                } else {
                    fragColor = vec4(1.0);
                }
            }
        """.trimIndent()
    }
}
