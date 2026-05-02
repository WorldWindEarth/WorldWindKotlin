/*
 * Adapted from ebruneton/precomputed_atmospheric_scattering, BSD-3-Clause,
 * Copyright (c) 2017 Eric Bruneton.
 *
 * Runtime sky-dome shader: per-fragment sky radiance via Bruneton LUT lookups, tonemapped
 * for display. Drawn on the same sphere mesh as the legacy [earth.worldwind.layer.atmosphere.SkyProgram];
 * the geometry is just a "screen" — the per-fragment view direction (frag_world − eye)
 * drives the LUT sampling, the dome's altitude itself doesn't enter the radiance math.
 *
 * ES3 / WebGL2 / GL 3.3 core only — uses 3D textures, sized float-format sampling, and
 * `out vec4` fragment outputs.
 */
package earth.worldwind.layer.atmosphere.bruneton.programs

import earth.worldwind.draw.DrawContext
import earth.worldwind.geom.Matrix4
import earth.worldwind.geom.Vec3
import earth.worldwind.layer.atmosphere.bruneton.BrunetonShaders
import earth.worldwind.render.program.AbstractShaderProgram
import earth.worldwind.util.kgl.KglUniformLocation

internal class BrunetonSkyProgram : AbstractShaderProgram() {

    override var programSources = arrayOf(VERTEX_SOURCE, FRAGMENT_SOURCE)
    override val attribBindings = arrayOf("vertexPoint")

    override fun glslVersion(dc: DrawContext) = dc.gl.glslVersion3

    private var mvpMatrixId = KglUniformLocation.NONE
    private var transmittanceTexId = KglUniformLocation.NONE
    private var scatteringTexId = KglUniformLocation.NONE
    private var mieScatteringTexId = KglUniformLocation.NONE
    private var eyePointId = KglUniformLocation.NONE
    private var sunDirectionId = KglUniformLocation.NONE
    private var exposureId = KglUniformLocation.NONE
    private val matrixArray = FloatArray(16)

    override fun initProgram(dc: DrawContext) {
        super.initProgram(dc)
        mvpMatrixId = gl.getUniformLocation(program, "mvpMatrix")
        transmittanceTexId = gl.getUniformLocation(program, "transmittanceTex")
        scatteringTexId = gl.getUniformLocation(program, "scatteringTex")
        mieScatteringTexId = gl.getUniformLocation(program, "mieScatteringTex")
        eyePointId = gl.getUniformLocation(program, "eyePoint")
        sunDirectionId = gl.getUniformLocation(program, "sunDirection")
        exposureId = gl.getUniformLocation(program, "exposure")
        // Sampler unit assignments. Caller binds matching units before drawing.
        gl.uniform1i(transmittanceTexId, 0)
        gl.uniform1i(scatteringTexId, 1)
        gl.uniform1i(mieScatteringTexId, 2)
        // Defaults — caller overrides each frame.
        gl.uniform1f(exposureId, 10f)
    }

    fun loadModelviewProjection(matrix: Matrix4) {
        matrix.transposeToArray(matrixArray, 0)
        gl.uniformMatrix4fv(mvpMatrixId, 1, false, matrixArray, 0)
    }

    fun loadEyePoint(eye: Vec3) =
        gl.uniform3f(eyePointId, eye.x.toFloat(), eye.y.toFloat(), eye.z.toFloat())

    fun loadSunDirection(dir: Vec3) =
        gl.uniform3f(sunDirectionId, dir.x.toFloat(), dir.y.toFloat(), dir.z.toFloat())

    fun loadExposure(exposure: Float) = gl.uniform1f(exposureId, exposure)

    companion object {
        val KEY = BrunetonSkyProgram::class

        // Vertex shader: sphere mesh vertices arrive as ECEF positions (assembled by
        // [earth.worldwind.layer.atmosphere.AtmosphereLayer.assembleVertexPoints] with a
        // zero vertex origin). Pass the world position to the fragment unchanged.
        private val VERTEX_SOURCE = """
            precision highp float;
            uniform mat4 mvpMatrix;
            in vec3 vertexPoint;
            out vec3 worldPos;
            void main() {
                worldPos = vertexPoint;
                gl_Position = mvpMatrix * vec4(vertexPoint, 1.0);
            }
        """.trimIndent()

        private val FRAGMENT_SOURCE = """
            precision highp float;
            precision highp int;
            precision highp sampler2D;
            precision highp sampler3D;

            ${BrunetonShaders.COMMON}

            uniform sampler2D transmittanceTex;
            uniform sampler3D scatteringTex;
            uniform sampler3D mieScatteringTex;
            uniform vec3 eyePoint;
            uniform vec3 sunDirection;
            uniform float exposure;

            in  vec3 worldPos;
            out vec4 fragColor;

            void main() {
                // BOTTOM_RADIUS is set to WorldWind's WGS84 equatorial radius, so the ECEF
                // camera and sphere-mesh positions feed the LUT lookups directly — no
                // rescaling needed.
                vec3 view_ray = normalize(worldPos - eyePoint);
                vec3 transmittance;
                // shadow_length = 0 — WorldWind doesn't drive the volumetric-shaft path.
                vec3 radiance = GetSkyRadiance(
                    transmittanceTex, scatteringTex, mieScatteringTex,
                    eyePoint, view_ray, 0.0, sunDirection, transmittance);

                // Sun disk with limb darkening. When the view ray points within the sun's
                // angular radius, add the disk's radiance scaled by transmittance to the
                // camera. The standard Eddington limb-darkening law `1 − u·(1 − μ)` where
                // μ = cos(angle between view ray and disk centre as seen from the sun) and
                // u ≈ 0.6 for the visible band gives the disk a soft brighter centre fading
                // to ~40 % of centre brightness at the limb — what an unaided eye sees.
                // Centre-uniform irradiance E corresponds to centre radiance L = E / (π α²).
                float cosViewSun = dot(view_ray, sunDirection);
                float cosLimb = cos(SUN_ANGULAR_RADIUS);
                if (cosViewSun > cosLimb) {
                    // Map the disk-radius coordinate (0 = centre, 1 = limb) to the sphere-
                    // angle cosine μ = sqrt(1 − r²) used by the limb-darkening law.
                    float r = clamp((1.0 - cosViewSun) / (1.0 - cosLimb), 0.0, 1.0);
                    float muDisk = sqrt(max(1.0 - r * r, 0.0));
                    float darkening = 1.0 - 0.6 * (1.0 - muDisk);
                    vec3 sunRadiance = SOLAR_IRRADIANCE / (PI * SUN_ANGULAR_RADIUS * SUN_ANGULAR_RADIUS);
                    radiance += transmittance * sunRadiance * darkening;
                }

                vec3 color = BrunetonTonemap(radiance, exposure);
                fragColor = vec4(color, 1.0);
            }
        """.trimIndent()
    }
}
