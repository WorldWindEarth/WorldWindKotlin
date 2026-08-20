package earth.worldwind.layer.ogc3d.program

import earth.worldwind.draw.DrawContext
import earth.worldwind.geom.Matrix4
import earth.worldwind.layer.shadow.ShadowReceiverGlsl
import earth.worldwind.layer.shadow.ShadowReceiverProgram
import earth.worldwind.layer.shadow.ShadowReceiverUniforms
import earth.worldwind.layer.shadow.ShadowState
import earth.worldwind.layer.sightline.SightlineReceiverGlsl
import earth.worldwind.layer.sightline.SightlineReceiverProgram
import earth.worldwind.layer.sightline.SightlineReceiverUniforms
import earth.worldwind.layer.sightline.SightlineState
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

    // Prepend [Kgl.glslDerivativesPrefix] (WW_HAS_DERIVATIVES + platform-aware extension
    // directive) so the shadow receiver's receiver-plane depth bias can use dFdx/dFdy.
    override fun glslVersion(dc: earth.worldwind.draw.DrawContext) = ShadowReceiverGlsl.glslPrefix(dc, shadowsEnabled || sightlineEnabled)


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

    private val shadowUniforms = ShadowReceiverUniforms(shadowsEnabled)
    private val sightlineUniforms = SightlineReceiverUniforms(sightlineEnabled)

    private val mvpMatrix = Matrix4()
    private val modelMatrix = Matrix4()
    private val matrixArray = FloatArray(16)
    private var basePointSize = 1f
    private var focalLengthPixels = 1f
    private var opacity = 1f
    private var enablePickMode = false
    private val pickColor = Color(0f, 0f, 0f, 1f)

    override var shadowUploadStamp: Long
        get() = shadowUniforms.uploadStamp
        set(value) { shadowUniforms.uploadStamp = value }
    override var lastSightlineState: SightlineState?
        get() = sightlineUniforms.lastState
        set(value) { sightlineUniforms.lastState = value }

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

        shadowUniforms.init(gl, program)
        sightlineUniforms.init(gl, program)
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

    override fun loadShadowDisabled() = shadowUniforms.loadDisabled(gl)

    override fun loadShadowEnabled(state: ShadowState) = shadowUniforms.loadEnabled(gl, state)

    override fun loadSightlineDisabled() = sightlineUniforms.loadDisabled(gl)

    override fun loadSightlineEnabled(state: SightlineState, localMatrix: Matrix4) =
        sightlineUniforms.loadEnabled(gl, state, localMatrix)

    companion object {
        /** Always include the sightline splice — same race rationale as [Ogc3dTilesProgram.get]. */
        fun get(rc: RenderContext): Ogc3dTilesPointsProgram = if (rc.hasShadowLayer) {
            rc.getShaderProgram { Ogc3dTilesPointsProgramBoth() }
        } else {
            rc.getShaderProgram { Ogc3dTilesPointsProgramSightline() }
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
            #if defined(GL_ES) && (defined(SHADOWS_ENABLED) || defined(SIGHTLINE_ENABLED)) && defined(GL_FRAGMENT_PRECISION_HIGH)
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
            ${ShadowReceiverGlsl.fragmentDeclarations()}
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
                color.rgb *= shadowAlbedoFactor(worldPos, viewDepth);
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
