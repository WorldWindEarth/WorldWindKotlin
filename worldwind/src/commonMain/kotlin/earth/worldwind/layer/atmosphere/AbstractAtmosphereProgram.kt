package earth.worldwind.layer.atmosphere

import earth.worldwind.draw.DrawContext
import earth.worldwind.geom.Matrix3
import earth.worldwind.geom.Matrix4
import earth.worldwind.geom.Vec3
import earth.worldwind.render.program.AbstractShaderProgram
import earth.worldwind.util.kgl.KglUniformLocation
import kotlin.math.PI
import kotlin.math.pow

// TODO Correctly compute the atmosphere color for eye positions beneath the atmosphere
// TODO Test the effect of working in local coordinates (reference point) on the GLSL atmosphere programs
abstract class AbstractAtmosphereProgram: AbstractShaderProgram() {
    private var fragModeId = KglUniformLocation.NONE
    private var mvpMatrixId = KglUniformLocation.NONE
    private var texCoordMatrixId = KglUniformLocation.NONE
    private var texSamplerId = KglUniformLocation.NONE
    private var vertexOriginId = KglUniformLocation.NONE
    private var eyePointId = KglUniformLocation.NONE
    private var eyeMagnitudeId = KglUniformLocation.NONE
    private var eyeMagnitude2Id = KglUniformLocation.NONE
    private var lightDirectionId = KglUniformLocation.NONE
    private var invWavelengthId = KglUniformLocation.NONE
    private var atmosphereRadiusId = KglUniformLocation.NONE
    private var atmosphereRadius2Id = KglUniformLocation.NONE
    private var globeRadiusId = KglUniformLocation.NONE
    private var krESunId = KglUniformLocation.NONE
    private var kmESunId = KglUniformLocation.NONE
    private var kr4PIId = KglUniformLocation.NONE
    private var km4PIId = KglUniformLocation.NONE
    private var scaleId = KglUniformLocation.NONE
    private var scaleDepthId = KglUniformLocation.NONE
    private var scaleOverScaleDepthId = KglUniformLocation.NONE
    private var gId = KglUniformLocation.NONE
    private var g2Id = KglUniformLocation.NONE
    private var exposureId = KglUniformLocation.NONE
    private val array = FloatArray(16)

    /**
     * Frag color indicates the atmospheric scattering color components written to the fragment color. Accepted values
     * are [PRIMARY], [SECONDARY] and [PRIMARY_TEX_BLEND].
     */
    enum class FragMode(val asInt : Int) {
        PRIMARY(1),
        SECONDARY(2),
        PRIMARY_TEX_BLEND(3)
    }

    override fun initProgram(dc: DrawContext) {
        super.initProgram(dc)
        val invWavelength = Vec3(
            1 / 0.650.pow(4.0),  // 650 nm for red
            1 / 0.570.pow(4.0),  // 570 nm for green
            1 / 0.475.pow(4.0)   // 475 nm for blue
        )
        val kr = 0.0025 // Rayleigh scattering constant
        val km = 0.0010 // Mie scattering constant
        val eSun = 20.0 // Sun brightness constant
        val g = -0.990 // The Mie phase asymmetry factor
        val exposure = 2.0
        fragModeId = gl.getUniformLocation(program, "fragMode")
        gl.uniform1i(fragModeId, FragMode.PRIMARY.asInt)
        mvpMatrixId = gl.getUniformLocation(program, "mvpMatrix")
        Matrix4().transposeToArray(array, 0) // 4 x 4 identity matrix
        gl.uniformMatrix4fv(mvpMatrixId, 1, false, array, 0)
        texCoordMatrixId = gl.getUniformLocation(program, "texCoordMatrix")
        Matrix3().transposeToArray(array, 0) // 3 x 3 identity matrix
        gl.uniformMatrix3fv(texCoordMatrixId, 1, false, array, 0)
        texSamplerId = gl.getUniformLocation(program, "texSampler")
        gl.uniform1i(texSamplerId, 0) // GL_TEXTURE0
        vertexOriginId = gl.getUniformLocation(program, "vertexOrigin")
        array.fill(0f)
        gl.uniform3fv(vertexOriginId, 1, array, 0)
        eyePointId = gl.getUniformLocation(program, "eyePoint")
        array.fill(0f)
        gl.uniform3fv(eyePointId, 1, array, 0)
        eyeMagnitudeId = gl.getUniformLocation(program, "eyeMagnitude")
        gl.uniform1f(eyeMagnitudeId, 0f)
        eyeMagnitude2Id = gl.getUniformLocation(program, "eyeMagnitude2")
        gl.uniform1f(eyeMagnitude2Id, 0f)
        lightDirectionId = gl.getUniformLocation(program, "lightDirection")
        array.fill(0f)
        gl.uniform3fv(lightDirectionId, 1, array, 0)
        invWavelengthId = gl.getUniformLocation(program, "invWavelength")
        invWavelength.toArray(array, 0)
        gl.uniform3fv(invWavelengthId, 1, array, 0)
        atmosphereRadiusId = gl.getUniformLocation(program, "atmosphereRadius")
        atmosphereRadius2Id = gl.getUniformLocation(program, "atmosphereRadius2")
        globeRadiusId = gl.getUniformLocation(program, "globeRadius")
        krESunId = gl.getUniformLocation(program, "KrESun")
        gl.uniform1f(krESunId, (kr * eSun).toFloat())
        kmESunId = gl.getUniformLocation(program, "KmESun")
        gl.uniform1f(kmESunId, (km * eSun).toFloat())
        kr4PIId = gl.getUniformLocation(program, "Kr4PI")
        gl.uniform1f(kr4PIId, (kr * 4 * PI).toFloat())
        km4PIId = gl.getUniformLocation(program, "Km4PI")
        gl.uniform1f(km4PIId, (km * 4 * PI).toFloat())
        scaleId = gl.getUniformLocation(program, "scale")
        scaleDepthId = gl.getUniformLocation(program, "scaleDepth")
        scaleOverScaleDepthId = gl.getUniformLocation(program, "scaleOverScaleDepth")
        gId = gl.getUniformLocation(program, "g")
        gl.uniform1f(gId, g.toFloat())
        g2Id = gl.getUniformLocation(program, "g2")
        gl.uniform1f(g2Id, (g * g).toFloat())
        exposureId = gl.getUniformLocation(program, "exposure")
        gl.uniform1f(exposureId, exposure.toFloat())
    }

    fun loadFragMode(fragMode: FragMode) { gl.uniform1i(fragModeId, fragMode.asInt) }

    fun loadModelviewProjection(matrix: Matrix4) {
        matrix.transposeToArray(array, 0)
        gl.uniformMatrix4fv(mvpMatrixId, 1, false, array, 0)
    }

    fun loadTexCoordMatrix(matrix: Matrix3) {
        matrix.transposeToArray(array, 0)
        gl.uniformMatrix3fv(texCoordMatrixId, 1, false, array, 0)
    }

    fun loadVertexOrigin(origin: Vec3) {
        origin.toArray(array, 0)
        gl.uniform3fv(vertexOriginId, 1, array, 0)
    }

    fun loadVertexOrigin(x: Double, y: Double, z: Double) {
        gl.uniform3f(vertexOriginId, x.toFloat(), y.toFloat(), z.toFloat())
    }

    fun loadLightDirection(direction: Vec3) {
        direction.toArray(array, 0)
        gl.uniform3fv(lightDirectionId, 1, array, 0)
    }

    fun loadEyePoint(eyePoint: Vec3) {
        eyePoint.toArray(array, 0)
        gl.uniform3fv(eyePointId, 1, array, 0)
        gl.uniform1f(eyeMagnitudeId, eyePoint.magnitude.toFloat())
        gl.uniform1f(eyeMagnitude2Id, eyePoint.magnitudeSquared.toFloat())
    }

    fun loadAtmosphereParams(equatorialRadius: Double, atmosphereAltitude: Double) {
        val rayleighScaleDepth = 0.25
        val ar = equatorialRadius + atmosphereAltitude
        gl.uniform1f(globeRadiusId, equatorialRadius.toFloat())
        gl.uniform1f(atmosphereRadiusId, ar.toFloat())
        gl.uniform1f(atmosphereRadius2Id, (ar * ar).toFloat())
        gl.uniform1f(scaleId, (1 / atmosphereAltitude).toFloat())
        gl.uniform1f(scaleDepthId, rayleighScaleDepth.toFloat())
        gl.uniform1f(scaleOverScaleDepthId, (1 / atmosphereAltitude / rayleighScaleDepth).toFloat())
    }
}