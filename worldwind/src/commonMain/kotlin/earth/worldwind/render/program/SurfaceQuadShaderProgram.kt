package earth.worldwind.render.program

import earth.worldwind.draw.DrawContext
import earth.worldwind.geom.Matrix3
import earth.worldwind.geom.Matrix4
import earth.worldwind.geom.Vec2
import earth.worldwind.geom.Vec3
import earth.worldwind.render.Color
import earth.worldwind.util.kgl.KglUniformLocation

class SurfaceQuadShaderProgram : AbstractShaderProgram() {
    override var programSources = arrayOf(
        """
            uniform mat4 mvpMatrix;
            uniform bool enableTexture;
            uniform vec2 A;
            uniform vec2 B;
            uniform vec2 C;
            uniform vec2 D;
            
            attribute vec4 pointA;

            varying vec2 q;
            varying vec2 b1;
            varying vec2 b2;
            varying vec2 b3;
            
            void main() {
                /* Transform the vertex position by the modelview-projection matrix. */
                gl_Position = mvpMatrix * vec4(pointA.xyz, 1.0);

                /* Transform the vertex tex coord by the tex coord matrix. */
                if (enableTexture) {
                    // Set up inverse bilinear interpolation
                    vec2 P = pointA.xy;
                    q = P - A;
                    b1 = B - A;
                    b2 = D - A;
                    b3 = A - B - D + C;
                }
            }
        """.trimIndent(),
        """
            precision mediump float;
            
            uniform mat3 texCoordMatrix;
            uniform bool enablePickMode;
            uniform bool enableTexture;
            uniform vec4 color;
            uniform float opacity;
            uniform sampler2D texSampler;
            
            varying vec2 q;
            varying vec2 b1;
            varying vec2 b2;
            varying vec2 b3;
            
            float Wedge2D(vec2 v, vec2 w)
            {
              return v.x*w.y - v.y*w.x;
            }
            
            void main() {
                vec2 uv = vec2(0.0, 0.0);

                // Set up quadratic formula
                float A = Wedge2D(b2, b3);
                float B = Wedge2D(b3, q) - Wedge2D(b1, b2);
                float C = Wedge2D(b1, q);
                
                // Solve for v

                if (abs(A) < 0.001)
                {
                  // Linear form
                  uv.y = -C/B;
                }
                else
                {
                  // Quadratic form. Take positive root for CCW winding with V-up
                  float discrim = max(B * B - 4.0 * A * C, 0.0);
                  float sqrtD = sqrt(discrim);

                    float v1 = (-B + sqrtD) / (2.0 * A);
                    float v2 = (-B - sqrtD) / (2.0 * A);
                    uv.y = (v1 >= 0.0 && v1 <= 1.0) ? v1 : v2;
                }
                
                // Solve for u, using largest-magnitude component
                vec2 denom = b1 + uv.y * b3;
                if (abs(denom.x) > abs(denom.y))
                  uv.x = (q.x - b2.x * uv.y) / denom.x;
                else
                  uv.x = (q.y - b2.y * uv.y) / denom.y;
                  
                uv = (texCoordMatrix * vec3(uv, 1.0)).xy;
                
                if (enablePickMode && enableTexture) {
                    /* Modulate the RGBA color with the 2D texture's Alpha component (rounded to 0.0 or 1.0). */
                    float texMask = floor(texture2D(texSampler, uv).a + 0.5);
                    gl_FragColor = color * texMask;
                } else if (!enablePickMode && enableTexture) {
                    /* Modulate the RGBA color with the 2D texture's RGBA color. */
                    gl_FragColor = color * texture2D(texSampler, uv) * opacity;
                } else {
                    /* Return the RGBA color as-is. */
                    gl_FragColor = color * opacity;
                }
            }
        """.trimIndent()
    )
    override val attribBindings = arrayOf("pointA")

    private var enablePickMode = false
    private var enableTexture = false
    private val mvpMatrix = Matrix4()
    private val texCoordMatrix = Matrix3()
    private val color = Color()
    private var opacity = 1.0f
    private var mvpMatrixId = KglUniformLocation.NONE
    private var colorId = KglUniformLocation.NONE
    private var opacityId = KglUniformLocation.NONE
    private var enablePickModeId = KglUniformLocation.NONE
    private var enableTextureId = KglUniformLocation.NONE
    private var texCoordMatrixId = KglUniformLocation.NONE
    private var texSamplerId = KglUniformLocation.NONE

    private var AId = KglUniformLocation.NONE
    private var BId = KglUniformLocation.NONE
    private var CId = KglUniformLocation.NONE
    private var DId = KglUniformLocation.NONE

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

        enablePickModeId = gl.getUniformLocation(program, "enablePickMode")
        gl.uniform1i(enablePickModeId, if (enablePickMode) 1 else 0)
        enableTextureId = gl.getUniformLocation(program, "enableTexture")
        gl.uniform1i(enableTextureId, if (enableTexture) 1 else 0)

        texCoordMatrixId = gl.getUniformLocation(program, "texCoordMatrix")
        texCoordMatrix.transposeToArray(array, 0) // 3 x 3 identity matrix
        gl.uniformMatrix3fv(texCoordMatrixId, 1, false, array, 0)
        texSamplerId = gl.getUniformLocation(program, "texSampler")
        gl.uniform1i(texSamplerId, 0) // GL_TEXTURE0


        AId = gl.getUniformLocation(program, "A")
        BId = gl.getUniformLocation(program, "B")
        CId = gl.getUniformLocation(program, "C")
        DId = gl.getUniformLocation(program, "D")
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

    fun loadABCD(a : Vec2, b : Vec2, c : Vec2, d : Vec2)
    {
        gl.uniform2f(AId, a.x.toFloat(), a.y.toFloat());
        gl.uniform2f(BId, b.x.toFloat(), b.y.toFloat());
        gl.uniform2f(CId, c.x.toFloat(), c.y.toFloat());
        gl.uniform2f(DId, d.x.toFloat(), d.y.toFloat());
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

    companion object {
        val KEY = SurfaceQuadShaderProgram::class
    }
}