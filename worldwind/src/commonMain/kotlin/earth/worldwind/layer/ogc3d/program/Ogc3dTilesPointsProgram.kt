package earth.worldwind.layer.ogc3d.program

import earth.worldwind.draw.DrawContext
import earth.worldwind.geom.Matrix4
import earth.worldwind.layer.shadow.ShadowReceiverGlsl
import earth.worldwind.layer.shadow.ShadowReceiverProgram
import earth.worldwind.layer.sightline.SightlineReceiverGlsl
import earth.worldwind.layer.sightline.SightlineReceiverProgram
import earth.worldwind.render.Color
import earth.worldwind.render.RenderContext
import earth.worldwind.render.program.AbstractShaderProgram
import earth.worldwind.util.kgl.KglUniformLocation

/**
 * Color-pass shader for 3D Tiles point cloud content (.pnts). Renders `GL_POINTS` with
 * size-attenuated round splats: each point's `gl_PointSize` is set to
 * `basePointSize * focalLengthPixels / eyeDepth` so points look uniform across the view,
 * and the fragment shader uses a `gl_PointCoord` disc mask to clip to a circle.
 *
 * Receiver gating mirrors [Ogc3dTilesProgram]: same `#ifdef SHADOWS_ENABLED` +
 * `#ifdef SIGHTLINE_ENABLED` splices, same four variants resolved by [get] from
 * [RenderContext.hasShadowLayer] + [RenderContext.hasActiveSightline]. Points receive both
 * shadows and sightlines.
 */
open class Ogc3dTilesPointsProgram(
    protected val shadowsEnabled: Boolean = false,
    protected val sightlineEnabled: Boolean = false,
) : AbstractShaderProgram(), ShadowReceiverProgram, SightlineReceiverProgram {

    override var programSources: Array<String> = arrayOf(
        defines() + VERTEX_SHADER,
        defines() + FRAGMENT_SHADER,
    )

    override val attribBindings: Array<String> = arrayOf("vertexPosition", "vertexColor")

    private fun defines(): String {
        var out = ""
        if (shadowsEnabled) out += ShadowReceiverGlsl.SHADOWS_ENABLED_DEFINE
        if (sightlineEnabled) out += SightlineReceiverGlsl.SIGHTLINE_ENABLED_DEFINE
        return out
    }

    // --- uniform handles -----------------------------------------------------------

    private var mvpMatrixId = KglUniformLocation.NONE
    private var modelMatrixId = KglUniformLocation.NONE
    private var basePointSizeId = KglUniformLocation.NONE
    private var focalLengthPixelsId = KglUniformLocation.NONE
    private var opacityId = KglUniformLocation.NONE
    private var enablePickModeId = KglUniformLocation.NONE
    private var pickColorId = KglUniformLocation.NONE

    private var applyShadowId = KglUniformLocation.NONE
    private var useMSMId = KglUniformLocation.NONE
    private var ambientShadowId = KglUniformLocation.NONE
    private val shadowMapIds = arrayOf(KglUniformLocation.NONE, KglUniformLocation.NONE, KglUniformLocation.NONE)
    private val lightProjectionViewIds = arrayOf(KglUniformLocation.NONE, KglUniformLocation.NONE, KglUniformLocation.NONE)
    private val cascadeFarDepthIds = arrayOf(KglUniformLocation.NONE, KglUniformLocation.NONE, KglUniformLocation.NONE)

    private var applySightlineId = KglUniformLocation.NONE
    private var sightlineOmnidirectionalId = KglUniformLocation.NONE
    private var sightlineMvMatrixId = KglUniformLocation.NONE
    private var sightlineProjMatrixId = KglUniformLocation.NONE
    private var sightlineLocalMatrixId = KglUniformLocation.NONE
    private var sightlineRangeId = KglUniformLocation.NONE
    private var sightlineColorsId = KglUniformLocation.NONE
    private var sightlineMomentsSamplerId = KglUniformLocation.NONE
    private var sightlineMomentsCubeSamplerId = KglUniformLocation.NONE

    private val mvpMatrix = Matrix4()
    private val modelMatrix = Matrix4()
    private val matrixArray = FloatArray(16)
    private var basePointSize = 1f
    private var focalLengthPixels = 1f
    private var opacity = 1f
    private var enablePickMode = false
    private val pickColor = Color(0f, 0f, 0f, 1f)

    override var shadowUploadStamp: Long = -1L
    override var sightlineUploadStamp: Long = -1L

    override fun initProgram(dc: DrawContext) {
        super.initProgram(dc)
        mvpMatrixId = gl.getUniformLocation(program, "mvpMatrix")
        mvpMatrix.transposeToArray(matrixArray, 0)
        gl.uniformMatrix4fv(mvpMatrixId, 1, false, matrixArray, 0)
        modelMatrixId = gl.getUniformLocation(program, "modelMatrix")
        modelMatrix.transposeToArray(matrixArray, 0)
        gl.uniformMatrix4fv(modelMatrixId, 1, false, matrixArray, 0)
        basePointSizeId = gl.getUniformLocation(program, "basePointSize")
        gl.uniform1f(basePointSizeId, basePointSize)
        focalLengthPixelsId = gl.getUniformLocation(program, "focalLengthPixels")
        gl.uniform1f(focalLengthPixelsId, focalLengthPixels)
        opacityId = gl.getUniformLocation(program, "opacity")
        gl.uniform1f(opacityId, opacity)
        enablePickModeId = gl.getUniformLocation(program, "enablePickMode")
        gl.uniform1i(enablePickModeId, 0)
        pickColorId = gl.getUniformLocation(program, "pickColor")
        gl.uniform4f(pickColorId, 0f, 0f, 0f, 1f)

        if (shadowsEnabled) {
            applyShadowId = gl.getUniformLocation(program, "applyShadow")
            gl.uniform1i(applyShadowId, 0)
            useMSMId = gl.getUniformLocation(program, "useMSM")
            gl.uniform1i(useMSMId, 0)
            ambientShadowId = gl.getUniformLocation(program, "ambientShadow")
            gl.uniform1f(ambientShadowId, 0.4f)
            for (i in shadowMapIds.indices) {
                shadowMapIds[i] = gl.getUniformLocation(program, "shadowMap$i")
                gl.uniform1i(shadowMapIds[i], 1 + i)
                lightProjectionViewIds[i] = gl.getUniformLocation(program, "lightProjectionView$i")
                cascadeFarDepthIds[i] = gl.getUniformLocation(program, "cascadeFarDepth$i")
                gl.uniform1f(cascadeFarDepthIds[i], 0f)
            }
        }

        if (sightlineEnabled) {
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
            gl.uniform1i(sightlineMomentsSamplerId, 4)
            sightlineMomentsCubeSamplerId = gl.getUniformLocation(program, "sightlineMomentsCubeSampler")
            gl.uniform1i(sightlineMomentsCubeSamplerId, 5)
        }
    }

    fun loadModelviewProjection(matrix: Matrix4) {
        if (mvpMatrix != matrix) {
            mvpMatrix.copy(matrix)
            matrix.transposeToArray(matrixArray, 0)
            gl.uniformMatrix4fv(mvpMatrixId, 1, false, matrixArray, 0)
        }
    }

    fun loadModelMatrix(matrix: Matrix4) {
        if (modelMatrix != matrix) {
            modelMatrix.copy(matrix)
            matrix.transposeToArray(matrixArray, 0)
            gl.uniformMatrix4fv(modelMatrixId, 1, false, matrixArray, 0)
        }
    }

    /** Per-tile point size in pixels at 1 meter eye distance (size attenuation scales this
     *  inversely with depth). Defaults to 4. */
    fun loadBasePointSize(size: Float) {
        if (basePointSize != size) {
            basePointSize = size
            gl.uniform1f(basePointSizeId, size)
        }
    }

    /** Focal length in pixels = viewportHeight / (2 * tan(fov / 2)). The size attenuation
     *  formula `basePointSize * focalLengthPixels / eyeDepth` gives uniform on-screen size. */
    fun loadFocalLengthPixels(value: Float) {
        if (focalLengthPixels != value) {
            focalLengthPixels = value
            gl.uniform1f(focalLengthPixelsId, value)
        }
    }

    fun loadOpacity(value: Float) {
        if (opacity != value) {
            opacity = value
            gl.uniform1f(opacityId, value)
        }
    }

    fun enablePickMode(enable: Boolean) {
        if (enablePickMode != enable) {
            enablePickMode = enable
            gl.uniform1i(enablePickModeId, if (enable) 1 else 0)
        }
    }

    fun loadPickColor(c: Color) {
        if (pickColor != c) {
            pickColor.copy(c)
            gl.uniform4f(pickColorId, c.red, c.green, c.blue, c.alpha)
        }
    }

    override fun loadShadowDisabled() {
        if (shadowsEnabled) gl.uniform1i(applyShadowId, 0)
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
        if (!shadowsEnabled) return
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

    override fun loadSightlineDisabled() {
        if (sightlineEnabled) gl.uniform1i(applySightlineId, 0)
    }

    override fun loadSightlineEnabled(
        omnidirectional: Boolean,
        sightlineMv: Matrix4,
        cubeMapProjection: Matrix4,
        sightlineLocal: Matrix4,
        range: Float,
        visibleColor: Color,
        occludedColor: Color,
    ) {
        if (!sightlineEnabled) return
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

    companion object {
        fun get(rc: RenderContext): Ogc3dTilesPointsProgram {
            val s = rc.hasShadowLayer
            val l = rc.hasActiveSightline
            return when {
                s && l -> rc.getShaderProgram { Ogc3dTilesPointsProgramBoth() }
                s -> rc.getShaderProgram { Ogc3dTilesPointsProgramShadow() }
                l -> rc.getShaderProgram { Ogc3dTilesPointsProgramSightline() }
                else -> rc.getShaderProgram { Ogc3dTilesPointsProgram() }
            }
        }

        private val VERTEX_SHADER: String = """
            uniform mat4 mvpMatrix;
            uniform mat4 modelMatrix;
            uniform float basePointSize;
            uniform float focalLengthPixels;

            attribute vec3 vertexPosition;
            attribute vec4 vertexColor;

            varying vec4 pointColor;
            #if defined(SHADOWS_ENABLED) || defined(SIGHTLINE_ENABLED)
            varying vec3 worldPos;
            #endif
            #ifdef SHADOWS_ENABLED
            varying float viewDepth;
            #endif

            #ifdef SIGHTLINE_ENABLED
            ${SightlineReceiverGlsl.VERTEX_DECLARATIONS}
            #endif

            void main() {
                vec4 localPos = vec4(vertexPosition, 1.0);
                vec4 worldPos4 = modelMatrix * localPos;
                gl_Position = mvpMatrix * localPos;

                /* Size attenuation: at eye-depth `gl_Position.w` (positive going away from
                   the camera in a perspective projection), a point that should subtend the
                   same projected size as `basePointSize * focalLengthPixels / gl_Position.w`.
                   Floor at 1 pixel so points stay visible at extreme distances; cap at 64 to
                   stay below the typical driver gl_PointSize maximum. */
                float attenuatedSize = basePointSize * focalLengthPixels / max(gl_Position.w, 1e-3);
                gl_PointSize = clamp(attenuatedSize, 1.0, 64.0);

                pointColor = vertexColor;

                #if defined(SHADOWS_ENABLED) || defined(SIGHTLINE_ENABLED)
                worldPos = worldPos4.xyz;
                #endif
                #ifdef SHADOWS_ENABLED
                viewDepth = gl_Position.w;
                #endif
                #ifdef SIGHTLINE_ENABLED
                emitSightlineVaryings(worldPos4);
                #endif
            }
        """.trimIndent()

        private val FRAGMENT_SHADER: String = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #elif defined(GL_ES)
            precision mediump float;
            #endif

            uniform float opacity;
            uniform bool enablePickMode;
            uniform vec4 pickColor;

            varying vec4 pointColor;
            #if defined(SHADOWS_ENABLED) || defined(SIGHTLINE_ENABLED)
            varying vec3 worldPos;
            #endif
            #ifdef SHADOWS_ENABLED
            varying float viewDepth;
            ${ShadowReceiverGlsl.FRAGMENT_DECLARATIONS}
            #endif

            #ifdef SIGHTLINE_ENABLED
            ${SightlineReceiverGlsl.FRAGMENT_DECLARATIONS}
            #endif

            void main() {
                /* Round point sprite. gl_PointCoord is [0, 1]^2 across the point's screen
                   footprint; the disc test discards corner fragments for a round splat. */
                vec2 coord = gl_PointCoord - vec2(0.5);
                if (dot(coord, coord) > 0.25) discard;

                if (enablePickMode) {
                    gl_FragColor = pickColor;
                    return;
                }

                vec4 color = pointColor * opacity;

                #ifdef SHADOWS_ENABLED
                color.rgb *= computeShadowVisibility(worldPos, viewDepth);
                #endif
                #ifdef SIGHTLINE_ENABLED
                vec4 tint = computeSightlineTint();
                color.rgb = color.rgb * (1.0 - tint.a) + tint.rgb;
                color.a = max(color.a, tint.a);
                #endif

                gl_FragColor = color;
            }
        """.trimIndent()
    }
}

class Ogc3dTilesPointsProgramShadow : Ogc3dTilesPointsProgram(shadowsEnabled = true, sightlineEnabled = false)
class Ogc3dTilesPointsProgramSightline : Ogc3dTilesPointsProgram(shadowsEnabled = false, sightlineEnabled = true)
class Ogc3dTilesPointsProgramBoth : Ogc3dTilesPointsProgram(shadowsEnabled = true, sightlineEnabled = true)
