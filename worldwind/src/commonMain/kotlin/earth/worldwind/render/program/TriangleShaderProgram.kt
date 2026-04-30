package earth.worldwind.render.program

import earth.worldwind.draw.DrawContext
import earth.worldwind.geom.Matrix3
import earth.worldwind.geom.Matrix4
import earth.worldwind.layer.shadow.ShadowReceiverGlsl
import earth.worldwind.layer.shadow.ShadowReceiverProgram
import earth.worldwind.render.Color
import earth.worldwind.util.kgl.KglUniformLocation

class TriangleShaderProgram : AbstractShaderProgram(), ShadowReceiverProgram {
    override var programSources = arrayOf(
        """
            uniform mat4 mvpMatrix;
            uniform mat4 modelMatrix;
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
            varying vec3 worldPos;
            varying float viewDepth;

            void main() {
                if (enableOneVertexMode) {
                    /* Transform the vertex position by the modelview-projection matrix. */
                    gl_Position = mvpMatrix * vec4(pointA.xyz, 1.0);
                    worldPos = (modelMatrix * vec4(pointA.xyz, 1.0)).xyz;
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
                    /* Line mode: use pointB (the middle of the 3-vertex line stencil) as the
                       fragment's world-space position for shadow sampling. Coarse but visually
                       acceptable since lines are typically narrow on-screen. */
                    worldPos = (modelMatrix * vec4(pointB.xyz, 1.0)).xyz;
                }
                viewDepth = gl_Position.w;

                /* Transform the vertex tex coord by the tex coord matrix. */
                if (enableTexture) {
                    texCoord = (texCoordMatrix * vec3(vertexTexCoord, 1.0)).st;
                }
            }
        """.trimIndent(),
        """
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
            varying vec3 worldPos;
            varying float viewDepth;

            ${ShadowReceiverGlsl.FRAGMENT_DECLARATIONS}

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
                /* Shadow attenuation. computeShadowVisibility returns 1.0 when applyShadow is
                   false, so this is a no-op when no [ShadowLayer] is active. Pick mode skips
                   the multiply so pick IDs aren't darkened. */
                if (!enablePickMode) {
                    gl_FragColor.rgb *= computeShadowVisibility(worldPos, viewDepth);
                }
            }
        """.trimIndent()
    )
    override val attribBindings = arrayOf("pointA", "pointB", "pointC", "vertexTexCoord")

    private var enablePickMode = false
    private var enableTexture = false
    private var enableOneVertexMode = false
    private val mvpMatrix = Matrix4()
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

        // Shadow receiver uniforms. modelMatrix defaults to identity so a drawable that does
        // not load it still produces correct world positions (vertices already in world).
        modelMatrixId = gl.getUniformLocation(program, "modelMatrix")
        Matrix4().transposeToArray(array, 0)
        gl.uniformMatrix4fv(modelMatrixId, 1, false, array, 0)
        applyShadowId = gl.getUniformLocation(program, "applyShadow")
        gl.uniform1i(applyShadowId, 0)
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
        // Don't bother testing whether mvpMatrix has changed, the common case is to load a different matrix.
        matrix.transposeToArray(array, 0)
        gl.uniformMatrix4fv(mvpMatrixId, 1, false, array, 0)
    }

    /**
     * Loads the model -> world transform for the shadow receiver. Pass the same translation /
     * compose the drawable applied to [mvpMatrix] minus the camera modelview-projection.
     */
    fun loadModelMatrix(matrix: Matrix4) {
        matrix.transposeToArray(array, 0)
        gl.uniformMatrix4fv(modelMatrixId, 1, false, array, 0)
    }

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
    ) {
        gl.uniform1i(applyShadowId, 1)
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
        val KEY = TriangleShaderProgram::class
    }
}