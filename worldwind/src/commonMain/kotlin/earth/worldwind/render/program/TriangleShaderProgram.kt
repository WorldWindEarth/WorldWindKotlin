package earth.worldwind.render.program

import earth.worldwind.draw.DrawContext
import earth.worldwind.geom.Matrix3
import earth.worldwind.geom.Matrix4
import earth.worldwind.render.Color
import earth.worldwind.util.kgl.KglUniformLocation

open class TriangleShaderProgram : AbstractShaderProgram() {
    override var programSources = arrayOf(
        """
            #version 300 es
            
            uniform mat4 mvpMatrix;
            uniform float lineWidth;
            uniform vec2 miterLengthCutoff;
            uniform vec4 screen;
            uniform bool enableTexture;
            uniform bool enableLinesMode;
            uniform bool enableVertexColorAndWidth;
            uniform mat3 texCoordMatrix;
            uniform vec4 color;
            uniform int pickIdOffset;
            uniform bool enablePickMode;
            uniform float clipDistance;
            
            in vec4 pointA;
            in vec4 pointB;
            in vec4 pointC;
            in vec2 vertexTexCoord;
            in vec4 vertexColor;
            in float vertexLineWidth;
            
            out vec2 texCoord;
            out vec4 outVertexColor;
            
            void main() {
                if (!enableLinesMode) {
                    /* Transform the vertex position by the modelview-projection matrix. */
                    gl_Position = mvpMatrix * vec4(pointA.xyz, 1.0);
                } else {
                    /* Transform the vertex position by the modelview-projection matrix. */
                    vec4 pointAScreen = mvpMatrix * vec4(pointA.xyz, 1);
                    vec4 pointBScreen = mvpMatrix * vec4(pointB.xyz, 1);
                    vec4 pointCScreen = mvpMatrix * vec4(pointC.xyz, 1);
                    vec4 interpolationPoint = pointB.w < 0.0 ? pointAScreen : pointCScreen; // not a mistake, this should be assigned here
                    float useLineWidth = enableVertexColorAndWidth ? vertexLineWidth : lineWidth;
                    
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
                        gl_Position.xy = pointBScreen.w * (pointBScreen.xy - (cornerX * cornerY * useLineWidth * normalA) * screen.zw);
                    } else {
                        gl_Position.xy = pointBScreen.w * (pointBScreen.xy + (cornerY * miter * useLineWidth * miterLength) * screen.zw);
                    }
                    gl_Position.zw = pointBScreen.zw;
                }
                   
                outVertexColor = enableVertexColorAndWidth ? vertexColor : color;
                if(enablePickMode && pickIdOffset != 0) {
                    ivec3 colorAsInt = ivec3(int(outVertexColor.r * 255.0), int(outVertexColor.g * 255.0), int(outVertexColor.b * 255.0));
                    int colorId = (colorAsInt.r << 16 | colorAsInt.g << 8 | colorAsInt.b);
                    int resultId = pickIdOffset + colorId;
                    
                    outVertexColor.r = float((resultId >> 16) & 0xFF) / 255.0;
                    outVertexColor.g = float((resultId >> 8) & 0xFF) / 255.0;
                    outVertexColor.b = float(resultId & 0xFF) / 255.0;
                    outVertexColor.a = 1.0;
                }
                
                /* Transform the vertex tex coord by the tex coord matrix. */
                if (enableTexture) {
                    texCoord = (texCoordMatrix * vec3(vertexTexCoord, 1.0)).st;
                }
            }
        """.trimIndent(),
        """
            #version 300 es
            precision mediump float;
            
            uniform bool enablePickMode;
            uniform bool enableTexture;
            uniform bool enableLinesMode;
            uniform float opacity;
            uniform sampler2D texSampler;
            
            in vec2 texCoord;
            in vec4 outVertexColor;
            
            out vec4 fragColor;
            
            void main() {
                if (enablePickMode && enableTexture) {
                    /* Modulate the RGBA color with the 2D texture's Alpha component (rounded to 0.0 or 1.0). */
                    float texMask = floor(texture(texSampler, texCoord).a + 0.5);
                    fragColor = outVertexColor * texMask;
                } else if (!enablePickMode && enableTexture) {
                    /* Modulate the RGBA color with the 2D texture's RGBA color. */
                    fragColor = outVertexColor * texture(texSampler, texCoord) * opacity;
                } else {
                    /* Return the RGBA color as-is. */
                    fragColor = outVertexColor * opacity;
                }
            }
        """.trimIndent()
    )
    override val attribBindings = arrayOf("pointA", "pointB", "pointC", "vertexTexCoord", "vertexColor", "vertexLineWidth")

    protected var enablePickMode = false
    protected var enableTexture = false
    protected var enableLinesMode = false
    protected var enableVertexColorAndWidth = false
    protected val mvpMatrix = Matrix4()
    protected val texCoordMatrix = Matrix3()
    protected val color = Color()
    protected var pickIdOffset = 0
    protected var opacity = 1.0f
    protected var lineWidth = 1.0f
    protected var miterLengthCutoff = 2.0f // should be greater than 1.0
    protected var screenX = 1.0f
    protected var screenY = 1.0f
    protected var clipDistance = 0.0f

    protected var mvpMatrixId = KglUniformLocation.NONE
    protected var colorId = KglUniformLocation.NONE
    protected var pickIdOffsetId = KglUniformLocation.NONE
    protected var opacityId = KglUniformLocation.NONE
    protected var lineWidthId = KglUniformLocation.NONE
    protected var miterLengthCutoffId = KglUniformLocation.NONE
    protected var screenId = KglUniformLocation.NONE
    protected var enablePickModeId = KglUniformLocation.NONE
    protected var enableTextureId = KglUniformLocation.NONE
    protected var enableLinesModeId = KglUniformLocation.NONE
    protected var enableVertexColorAndWidthId = KglUniformLocation.NONE
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
        pickIdOffsetId = gl.getUniformLocation(program, "pickIdOffset")
        gl.uniform1i(pickIdOffsetId, pickIdOffset)

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
        enableLinesModeId = gl.getUniformLocation(program, "enableLinesMode")
        gl.uniform1i(enableLinesModeId, if (enableLinesMode) 1 else 0)
        enableVertexColorAndWidthId = gl.getUniformLocation(program, "enableVertexColorAndWidth")
        gl.uniform1i(enableVertexColorAndWidthId, if (enableVertexColorAndWidth) 1 else 0)

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
    fun enableLinesMode(enable: Boolean) {
        if (enableLinesMode != enable) {
            enableLinesMode = enable
            gl.uniform1i(enableLinesModeId, if (enable) 1 else 0)
        }
    }
    fun enableVertexColorAndWidth(enable: Boolean) {
        if (enableVertexColorAndWidth != enable) {
            enableVertexColorAndWidth = enable
            gl.uniform1i(enableVertexColorAndWidthId, if (enable) 1 else 0)
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

    fun loadPickIdOffset(pickIdOffset: Int) {
        if (this.pickIdOffset != pickIdOffset) {
            this.pickIdOffset = pickIdOffset
            gl.uniform1i(pickIdOffsetId, pickIdOffset)
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