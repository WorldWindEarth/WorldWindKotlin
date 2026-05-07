package earth.worldwind.render.program

import earth.worldwind.draw.DrawContext
import earth.worldwind.geom.Matrix3
import earth.worldwind.geom.Matrix4
import earth.worldwind.layer.shadow.ShadowReceiverGlsl
import earth.worldwind.layer.shadow.ShadowReceiverProgram
import earth.worldwind.render.Color
import earth.worldwind.render.RenderContext
import earth.worldwind.util.kgl.KglUniformLocation

/**
 * Triangle / line strip program. A single GLSL source carries both the no-shadow (default)
 * and shadow-aware paths, gated by a `#define SHADOWS_ENABLED` preprocessor symbol that
 * [shadowsEnabled] toggles. Default `TriangleShaderProgram()` is the smaller-binary
 * no-shadow variant; [TriangleShaderProgramShadow] - selected by [get] when an
 * [earth.worldwind.layer.shadow.ShadowLayer] is in the layer list - flips the `#define` on.
 */
open class TriangleShaderProgram(
    protected val shadowsEnabled: Boolean = false,
) : AbstractShaderProgram(), ShadowReceiverProgram {
    override var programSources = arrayOf(
        defines() + """
            uniform mat4 mvpMatrix;
            #ifdef SHADOWS_ENABLED
            uniform mat4 modelMatrix;
            #endif
            uniform float lineWidth;
            uniform vec2 miterLengthCutoff;
            uniform vec4 screen;
            uniform bool enableTexture;
            uniform bool enableOneVertexMode;
            uniform mat3 texCoordMatrix;
            uniform float clipDistance;

            attribute vec4 pointA;
            attribute vec4 pointB;
            attribute vec4 pointC;
            attribute vec2 vertexTexCoord;

            varying vec2 texCoord;
            #ifdef SHADOWS_ENABLED
            varying vec3 worldPos;
            varying float viewDepth;
            #endif

            void main() {
                if (enableOneVertexMode) {
                    /* Transform the vertex position by the modelview-projection matrix. */
                    gl_Position = mvpMatrix * vec4(pointA.xyz, 1.0);
                    #ifdef SHADOWS_ENABLED
                    worldPos = (modelMatrix * vec4(pointA.xyz, 1.0)).xyz;
                    #endif
                } else {
                    /* Transform the vertex position by the modelview-projection matrix. */
                    vec4 pointAScreen = mvpMatrix * vec4(pointA.xyz, 1);
                    vec4 pointBScreen = mvpMatrix * vec4(pointB.xyz, 1);
                    vec4 pointCScreen = mvpMatrix * vec4(pointC.xyz, 1);
                    vec4 interpolationPoint = pointB.w < 0.0 ? pointAScreen : pointCScreen; // not a mistake, this should be assigned here

                    if (pointBScreen.w < 0.0) {
                        pointBScreen = mix(pointBScreen, interpolationPoint, clamp((clipDistance - pointBScreen.w)/(interpolationPoint.w - pointBScreen.w), 0.0, 1.0));
                        if (pointB.w < 0.0) {
                            pointCScreen = pointBScreen;
                        } else {
                            pointAScreen = pointBScreen;
                        }
                    }

                    if (pointAScreen.w < 0.0) {
                        pointAScreen  = mix(pointAScreen, pointBScreen, clamp((clipDistance - pointAScreen.w)/(pointBScreen.w - pointAScreen.w), 0.0, 1.0));
                    }

                    if (pointCScreen.w < 0.0) {
                        pointCScreen  = mix(pointCScreen, pointBScreen, clamp((clipDistance - pointCScreen.w)/(pointBScreen.w - pointCScreen.w), 0.0, 1.0));
                    }

                    pointAScreen.xy = pointAScreen.xy / pointAScreen.w;
                    pointBScreen.xy = pointBScreen.xy / pointBScreen.w;
                    pointCScreen.xy = pointCScreen.xy / pointCScreen.w;

                    float eps = 0.2 * length(screen.zw);

                    if (length(pointBScreen.xy - pointAScreen.xy) < eps) {
                        pointAScreen.xy = pointBScreen.xy + normalize(pointBScreen.xy - pointCScreen.xy);
                    }
                    if (length(pointBScreen.xy - pointCScreen.xy) <  eps) {
                        pointCScreen.xy = pointBScreen.xy + normalize(pointBScreen.xy - pointAScreen.xy);
                    }
                    if (length(pointAScreen.xy - pointCScreen.xy) < eps) {
                        pointCScreen.xy = pointBScreen.xy + normalize(pointBScreen.xy - pointAScreen.xy);
                    }

                    vec2 AB = normalize((pointBScreen.xy - pointAScreen.xy) * screen.xy);
                    vec2 BC = normalize((pointCScreen.xy - pointBScreen.xy) * screen.xy);
                    vec2 tangent = normalize(AB + BC);
                    vec2 point = normalize(AB - BC);

                    vec2 miter = vec2(-tangent.y, tangent.x);
                    vec2 normalA = vec2(-AB.y, AB.x);
                    float miterLength = 1.0 / max(dot(miter, normalA), miterLengthCutoff.y);

                    float cornerX = sign(pointB.w);
                    float cornerY = (pointB.w - cornerX) * 2.0;
                    if (abs(miterLength - miterLengthCutoff.x) < eps && cornerY * dot(miter, point) > 0.0) {
                      // trim the corner
                        gl_Position.xy = pointBScreen.w * (pointBScreen.xy - (cornerX * cornerY * lineWidth * normalA) * screen.zw);
                    } else {
                        gl_Position.xy = pointBScreen.w * (pointBScreen.xy + (cornerY * miter * lineWidth * miterLength) * screen.zw);
                    }
                    gl_Position.zw = pointBScreen.zw;
                    #ifdef SHADOWS_ENABLED
                    /* Line mode: use pointB (the middle of the 3-vertex line stencil) as the
                       fragment's world-space position for shadow sampling. Coarse but visually
                       acceptable since lines are typically narrow on-screen. */
                    worldPos = (modelMatrix * vec4(pointB.xyz, 1.0)).xyz;
                    #endif
                }
                #ifdef SHADOWS_ENABLED
                viewDepth = gl_Position.w;
                #endif

                /* Transform the vertex tex coord by the tex coord matrix. */
                if (enableTexture) {
                    texCoord = (texCoordMatrix * vec3(vertexTexCoord, 1.0)).st;
                }
            }
        """.trimIndent(),
        defines() + """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #elif defined(GL_ES)
            precision mediump float;
            #endif

            uniform bool enablePickMode;
            uniform bool enableTexture;
            uniform vec4 color;
            uniform float opacity;
            uniform sampler2D texSampler;

            varying vec2 texCoord;
            #ifdef SHADOWS_ENABLED
            varying vec3 worldPos;
            varying float viewDepth;

            ${ShadowReceiverGlsl.FRAGMENT_DECLARATIONS}
            #endif

            void main() {
                if (enablePickMode && enableTexture) {
                    /* Modulate the RGBA color with the 2D texture's Alpha component (rounded to 0.0 or 1.0). */
                    float texMask = floor(texture2D(texSampler, texCoord).a + 0.5);
                    gl_FragColor = color * texMask;
                } else if (!enablePickMode && enableTexture) {
                    /* Modulate the RGBA color with the 2D texture's RGBA color. */
                    gl_FragColor = color * texture2D(texSampler, texCoord) * opacity;
                } else {
                    /* Return the RGBA color as-is. */
                    gl_FragColor = color * opacity;
                }
                #ifdef SHADOWS_ENABLED
                /* Skip shadow attenuation in pick mode so pick IDs aren't darkened. */
                if (!enablePickMode) {
                    gl_FragColor.rgb *= computeShadowVisibility(worldPos, viewDepth);
                }
                #endif
            }
        """.trimIndent()
    )
    override val attribBindings = arrayOf("pointA", "pointB", "pointC", "vertexTexCoord")

    private fun defines() = if (shadowsEnabled) ShadowReceiverGlsl.SHADOWS_ENABLED_DEFINE else ""

    private var enablePickMode = false
    private var enableTexture = false
    private var enableOneVertexMode = false
    private val mvpMatrix = Matrix4()
    /** Cached value of the last uploaded `modelMatrix`; defaults to identity. Used by
     *  [loadModelMatrix] to skip redundant uploads. */
    private val modelMatrix = Matrix4()
    private val texCoordMatrix = Matrix3()
    private val color = Color()
    private var opacity = 1.0f
    private var lineWidth = 1.0f
    private var miterLengthCutoff = 2.0f // should be greater than 1.0
    private var screenX = 1.0f
    private var screenY = 1.0f
    private var clipDistance = 0.0f

    private var mvpMatrixId = KglUniformLocation.NONE
    private var modelMatrixId = KglUniformLocation.NONE
    private var colorId = KglUniformLocation.NONE
    private var opacityId = KglUniformLocation.NONE
    private var lineWidthId = KglUniformLocation.NONE
    private var miterLengthCutoffId = KglUniformLocation.NONE
    private var screenId = KglUniformLocation.NONE
    private var enablePickModeId = KglUniformLocation.NONE
    private var enableTextureId = KglUniformLocation.NONE
    private var enableOneVertexModeId = KglUniformLocation.NONE
    private var texCoordMatrixId = KglUniformLocation.NONE
    private var texSamplerId = KglUniformLocation.NONE
    private var clipDistanceId = KglUniformLocation.NONE
    private var applyShadowId = KglUniformLocation.NONE
    private var useMSMId = KglUniformLocation.NONE
    private var ambientShadowId = KglUniformLocation.NONE
    private val shadowMapIds = arrayOf(KglUniformLocation.NONE, KglUniformLocation.NONE, KglUniformLocation.NONE)
    private val lightProjectionViewIds = arrayOf(KglUniformLocation.NONE, KglUniformLocation.NONE, KglUniformLocation.NONE)
    private val cascadeFarDepthIds = arrayOf(KglUniformLocation.NONE, KglUniformLocation.NONE, KglUniformLocation.NONE)
    private val lightProjectionViewArray = FloatArray(16)
    private val array = FloatArray(16)

    override fun initProgram(dc: DrawContext) {
        super.initProgram(dc)
        mvpMatrixId = gl.getUniformLocation(program, "mvpMatrix")
        mvpMatrix.transposeToArray(array, 0) // 4 x 4 identity matrix
        gl.uniformMatrix4fv(mvpMatrixId, 1, false, array, 0)
        colorId = gl.getUniformLocation(program, "color")
        val alpha = color.alpha
        gl.uniform4f(colorId, color.red * alpha, color.green * alpha, color.blue * alpha, alpha)

        opacityId = gl.getUniformLocation(program, "opacity")
        gl.uniform1f(opacityId, opacity)
        lineWidthId = gl.getUniformLocation(program, "lineWidth")
        gl.uniform1f(lineWidthId, lineWidth)
        miterLengthCutoffId = gl.getUniformLocation(program, "miterLengthCutoff")
        gl.uniform2f(miterLengthCutoffId, miterLengthCutoff, 1f / miterLengthCutoff)
        screenId = gl.getUniformLocation(program, "screen")
        gl.uniform4f(screenId, screenX, screenY, 1f / screenX, 1f / screenY)
        clipDistanceId = gl.getUniformLocation(program, "clipDistance")
        gl.uniform1f(clipDistanceId, clipDistance)

        enablePickModeId = gl.getUniformLocation(program, "enablePickMode")
        gl.uniform1i(enablePickModeId, if (enablePickMode) 1 else 0)
        enableTextureId = gl.getUniformLocation(program, "enableTexture")
        gl.uniform1i(enableTextureId, if (enableTexture) 1 else 0)
        enableOneVertexModeId = gl.getUniformLocation(program, "enableOneVertexMode")
        gl.uniform1i(enableOneVertexModeId, if (enableOneVertexMode) 1 else 0)

        texCoordMatrixId = gl.getUniformLocation(program, "texCoordMatrix")
        texCoordMatrix.transposeToArray(array, 0) // 3 x 3 identity matrix
        gl.uniformMatrix3fv(texCoordMatrixId, 1, false, array, 0)
        texSamplerId = gl.getUniformLocation(program, "texSampler")
        gl.uniform1i(texSamplerId, 0) // GL_TEXTURE0

        // Shadow receiver uniforms - see SurfaceTextureProgram.initProgram for the no-shadow
        // GL-no-op rationale.
        modelMatrixId = gl.getUniformLocation(program, "modelMatrix")
        modelMatrix.transposeToArray(array, 0) // 4 x 4 identity matrix
        gl.uniformMatrix4fv(modelMatrixId, 1, false, array, 0)
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

    fun enablePickMode(enable: Boolean) {
        if (enablePickMode != enable) {
            enablePickMode = enable
            gl.uniform1i(enablePickModeId, if (enable) 1 else 0)
        }
    }
    fun enableTexture(enable: Boolean) {
        if (enableTexture != enable) {
            enableTexture = enable
            gl.uniform1i(enableTextureId, if (enable) 1 else 0)
        }
    }
    fun enableOneVertexMode(enable: Boolean) {
        if (enableOneVertexMode != enable) {
            enableOneVertexMode = enable
            gl.uniform1i(enableOneVertexModeId, if (enable) 1 else 0)
        }
    }
    fun loadTexCoordMatrix(matrix: Matrix3) {
        if (texCoordMatrix != matrix) {
            texCoordMatrix.copy(matrix)
            matrix.transposeToArray(array, 0)
            gl.uniformMatrix3fv(texCoordMatrixId, 1, false, array, 0)
        }
    }
    fun loadModelviewProjection(matrix: Matrix4) {
        if (mvpMatrix != matrix) {
            mvpMatrix.copy(matrix)
            matrix.transposeToArray(array, 0)
            gl.uniformMatrix4fv(mvpMatrixId, 1, false, array, 0)
        }
    }

    /**
     * Loads the model -> world transform for the shadow receiver. Dirty-checked: shapes that
     * share the same world transform across multiple draws (line + fill, multi-segment paths)
     * see the same matrix repeatedly. Drawables only call this when
     * [earth.worldwind.draw.DrawContext.shadowState] is non-null, so it doesn't run on
     * no-shadow frames at all.
     */
    fun loadModelMatrix(matrix: Matrix4) {
        if (modelMatrix != matrix) {
            modelMatrix.copy(matrix)
            matrix.transposeToArray(array, 0)
            gl.uniformMatrix4fv(modelMatrixId, 1, false, array, 0)
        }
    }

    override var shadowUploadStamp: Long = -1L

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

    fun loadColor(color: Color) {
        if (this.color != color) {
            this.color.copy(color)
            val alpha = color.alpha
            gl.uniform4f(colorId, color.red * alpha, color.green * alpha, color.blue * alpha, alpha)
        }
    }

    fun loadOpacity(opacity: Float) {
        if (this.opacity != opacity) {
            this.opacity = opacity
            gl.uniform1f(opacityId, opacity)
        }
    }

    fun loadLineWidth(lineWidth : Float) {
        if (this.lineWidth != lineWidth) {
            this.lineWidth = lineWidth
            gl.uniform1f(lineWidthId, lineWidth)
        }
    }

    fun loadMiterLengthCutoff(miterLengthCutoff : Float) {
        if (this.miterLengthCutoff != miterLengthCutoff) {
            this.miterLengthCutoff = miterLengthCutoff
            gl.uniform2f(miterLengthCutoffId, miterLengthCutoff, 1f / miterLengthCutoff)
        }
    }

    fun loadClipDistance(clipDistance : Float) {
        if (this.clipDistance != clipDistance) {
            this.clipDistance = clipDistance
            gl.uniform1f(clipDistanceId, clipDistance)
        }
    }

    fun loadScreen(screenX : Float, screenY : Float) {
        if ((this.screenX != screenX) and (this.screenY != screenY) ) {
            this.screenX = screenX
            this.screenY = screenY
            gl.uniform4f(screenId, this.screenX, this.screenY, 1f / screenX, 1f / screenY)
        }
    }

    companion object {
        /**
         * Resolves the [TriangleShaderProgram] variant for [rc] - the shadow-aware variant
         * when an enabled [earth.worldwind.layer.shadow.ShadowLayer] is in the layer list,
         * the smaller-binary no-shadow variant otherwise.
         */
        fun get(rc: RenderContext): TriangleShaderProgram = if (rc.hasShadowLayer) {
            rc.getShaderProgram { TriangleShaderProgramShadow() }
        } else {
            rc.getShaderProgram { TriangleShaderProgram() }
        }
    }
}

/** Sentinel subclass: distinct cache key for the shadow-aware GLSL variant. See [TriangleShaderProgram]. */
class TriangleShaderProgramShadow : TriangleShaderProgram(shadowsEnabled = true)
