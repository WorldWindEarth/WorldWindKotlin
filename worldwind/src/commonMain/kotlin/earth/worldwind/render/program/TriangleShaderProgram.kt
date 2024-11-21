package earth.worldwind.render.program

import earth.worldwind.draw.DrawContext
import earth.worldwind.geom.Matrix3
import earth.worldwind.geom.Matrix4
import earth.worldwind.render.Color
import earth.worldwind.util.kgl.KglUniformLocation

open class TriangleShaderProgram : AbstractShaderProgram() {
    override var programSources = arrayOf(
        """
            uniform mat4 mvpMatrix;
            uniform float lineWidth;
            uniform float invMiterLengthCutoff;
            uniform vec2 screen;
            uniform bool enableTexture;
            uniform bool enableOneVertexMode;
            uniform mat3 texCoordMatrix;
            uniform float clipDistance;
            
            attribute vec4 pointA;
            attribute vec4 pointB;
            attribute vec4 pointC;
            attribute vec2 vertexTexCoord;
            
            varying vec2 texCoord;
            
            void main() {
                if (enableOneVertexMode) {
                    /* Transform the vertex position by the modelview-projection matrix. */
                    gl_Position = mvpMatrix * pointA;
                } else {
                    /* Transform the vertex position by the modelview-projection matrix. */
                    vec4 pointAScreen = mvpMatrix * vec4(pointA.xyz, 1);
                    vec4 pointBScreen = mvpMatrix * vec4(pointB.xyz, 1);
                    vec4 pointCScreen = mvpMatrix * vec4(pointC.xyz, 1);
                    vec4 interpolationPoint = pointB.w < 2.0 ? pointAScreen : pointCScreen; // not a mistake, this should be assigned here
                    
                    if(pointBScreen.w < 0.0)
                    {
                        pointBScreen = mix(pointBScreen, interpolationPoint, clamp((clipDistance - pointBScreen.w)/(interpolationPoint.w - pointBScreen.w), 0.0, 1.0));
                        if(pointB.w < 2.0)
                        { 
                            pointCScreen = pointBScreen;
                        } else
                        {
                            pointAScreen = pointBScreen;
                        }
                    }

                    if(pointAScreen.w < 0.0)
                    {
                        pointAScreen  = mix(pointAScreen, pointBScreen, clamp((clipDistance - pointAScreen.w)/(pointBScreen.w - pointAScreen.w), 0.0, 1.0));
                    }

                    if(pointCScreen.w < 0.0)
                    {
                        pointCScreen  = mix(pointCScreen, pointBScreen, clamp((clipDistance - pointCScreen.w)/(pointBScreen.w - pointCScreen.w), 0.0, 1.0));
                    }
                    
                    pointAScreen.xy = pointAScreen.xy / pointAScreen.w;
                    pointBScreen.xy = pointBScreen.xy / pointBScreen.w;
                    pointCScreen.xy = pointCScreen.xy / pointCScreen.w;
                    
                    float eps = 0.1 * length(vec2(2.0 / screen.x, 2.0 / screen.y));
                    
                    if (length(pointBScreen.xy - pointAScreen.xy) < eps) {
                        pointAScreen.xy = pointBScreen.xy + normalize(pointBScreen.xy - pointCScreen.xy);
                    }
                    if (length(pointBScreen.xy - pointCScreen.xy) <  eps) {
                        pointCScreen.xy = pointBScreen.xy + normalize(pointBScreen.xy - pointAScreen.xy);
                    }
                    if (length(pointAScreen.xy - pointCScreen.xy) < eps) {
                        pointCScreen.xy = pointBScreen.xy + normalize(pointBScreen.xy - pointAScreen.xy);
                    }
                    
                    vec2 AB = normalize((pointBScreen.xy - pointAScreen.xy) * screen);
                    vec2 BC = normalize((pointCScreen.xy - pointBScreen.xy) * screen);
                    vec2 tangent = normalize(AB + BC);
                    vec2 point = normalize(AB - BC);
                    
                    vec2 miter = vec2(-tangent.y, tangent.x);
                    vec2 normalA = vec2(-AB.y, AB.x);
                    float miterLength = 1.0 / max(dot(miter, normalA), invMiterLengthCutoff);
                    
                    float cornerX = floor((pointB.w + 0.5) * 0.5);
                    float cornerY = mod(pointB.w, 2.0);
                    cornerX = 2.0 * cornerX - 1.0;
                    cornerY = 2.0 * cornerY - 1.0;
                    if (abs(miterLength - (1.0 / invMiterLengthCutoff)) < eps && cornerY * dot(miter, point) > 0.0) {
                      // trim the corner
                        gl_Position.xy = pointBScreen.w * (pointBScreen.xy - (cornerX * cornerY * lineWidth * normalA) / screen.xy);
                    } else {
                        gl_Position.xy = pointBScreen.w * (pointBScreen.xy + (cornerY * miter * lineWidth * miterLength) / screen.xy);
                    }
                    gl_Position.zw = pointBScreen.zw;
                }
                
                /* Transform the vertex tex coord by the tex coord matrix. */
                if (enableTexture) {
                    texCoord = (texCoordMatrix * vec3(vertexTexCoord, 1.0)).st;
                }
            }
        """.trimIndent(),
        """
            precision mediump float;
            
            uniform bool enablePickMode;
            uniform bool enableTexture;
            uniform vec4 color;
            uniform float opacity;
            uniform sampler2D texSampler;
            
            varying vec2 texCoord;
            
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
            }
        """.trimIndent()
    )
    override val attribBindings = arrayOf("pointA", "pointB", "pointC", "vertexTexCoord")

    protected var enablePickMode = false
    protected var enableTexture = false
    protected var enableOneVertexMode = false
    protected val mvpMatrix = Matrix4()
    protected val texCoordMatrix = Matrix3()
    protected val color = Color()
    protected var opacity = 1.0f
    protected var lineWidth = 1.0f
    protected var invMiterLengthCutoff = 1.0f / 2.0f // should be in (0;1) range, values close to 1 will trigger cutoff for straight lines
    protected var screenX = 0.0f
    protected var screenY = 0.0f
    protected var clipDistance = 0.0f

    protected var mvpMatrixId = KglUniformLocation.NONE
    protected var colorId = KglUniformLocation.NONE
    protected var opacityId = KglUniformLocation.NONE
    protected var lineWidthId = KglUniformLocation.NONE
    protected var invMiterLengthCutoffId = KglUniformLocation.NONE
    protected var screenId = KglUniformLocation.NONE
    protected var enablePickModeId = KglUniformLocation.NONE
    protected var enableTextureId = KglUniformLocation.NONE
    protected var enableOneVertexModeId = KglUniformLocation.NONE
    protected var texCoordMatrixId = KglUniformLocation.NONE
    protected var texSamplerId = KglUniformLocation.NONE
    protected var clipDistanceId = KglUniformLocation.NONE
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
        invMiterLengthCutoffId = gl.getUniformLocation(program, "invMiterLengthCutoff")
        gl.uniform1f(invMiterLengthCutoffId, invMiterLengthCutoff)
        screenId = gl.getUniformLocation(program, "screen")
        gl.uniform2f(screenId, screenX, screenY)
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
        if (this.invMiterLengthCutoff != 1.0f / miterLengthCutoff) {
            this.invMiterLengthCutoff = 1.0f / miterLengthCutoff
            gl.uniform1f(invMiterLengthCutoffId, invMiterLengthCutoff)
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
            gl.uniform2f(screenId, this.screenX, this.screenY)
        }
    }
}