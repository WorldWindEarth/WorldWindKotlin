package earth.worldwind.render.program

import earth.worldwind.draw.DrawContext
import earth.worldwind.geom.Matrix3
import earth.worldwind.geom.Matrix4
import earth.worldwind.geom.Vec3
import earth.worldwind.layer.shadow.ShadowReceiverGlsl
import earth.worldwind.layer.shadow.ShadowReceiverProgram
import earth.worldwind.layer.shadow.ShadowReceiverUniforms
import earth.worldwind.layer.shadow.ShadowState
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
            #ifdef SHADOWS_ENABLED
            /* Bound only by the surface-shape composite pass, where the geometry is the
               terrain mesh; disabled (degenerate) for every other draw. */
            attribute vec3 vertexNormal;
            #endif

            varying vec2 texCoord;
            /* Shape-local position (small magnitudes around the vertex origin). The fragment
               shader uses this for dFdx/dFdy-derived face normals AND reconstructs world-space
               position by adding modelMatrix[3].xyz for shadow sampling. Doing derivatives on
               world-space ECEF coordinates directly produces speckled shading: at ~6.4 Mm
               magnitudes, float cancellation drowns the sub-metre per-pixel position delta. */
            varying vec3 localPos;
            #ifdef SHADOWS_ENABLED
            varying float viewDepth;
            varying vec3 terrainNormal;
            #endif

            void main() {
                if (enableOneVertexMode) {
                    /* Transform the vertex position by the modelview-projection matrix. */
                    gl_Position = mvpMatrix * vec4(pointA.xyz, 1.0);
                    localPos = pointA.xyz;
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
                       fragment's local-space position. Coarse but visually acceptable since
                       lines are typically narrow on-screen and lighting is not enabled for them. */
                    localPos = pointB.xyz;
                }
                #ifdef SHADOWS_ENABLED
                viewDepth = gl_Position.w;
                terrainNormal = vertexNormal;
                #endif

                /* Transform the vertex tex coord by the tex coord matrix. */
                if (enableTexture) {
                    texCoord = (texCoordMatrix * vec3(vertexTexCoord, 1.0)).st;
                }
            }
        """.trimIndent(),
        // `#extension GL_OES_standard_derivatives` lives in [Kgl.glslDerivativesPrefix] now
        // (platform-aware: WebGL1 emits the directive, WebGL2 emits only the [WW_HAS_DERIVATIVES]
        // `#define` since the WebGL2 compiler rejects the directive even though derivatives are
        // core). It's prepended by [glslVersion] below — extension directives must appear before
        // any non-extension token, ahead of [defines]. The `#ifdef WW_HAS_DERIVATIVES` guard in
        // the fragment shader gates the `dFdx` call so a platform that doesn't opt in (currently
        // none) would still compile - lighting silently no-ops there.
        defines() + """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #elif defined(GL_ES)
            precision mediump float;
            #endif

            uniform bool enablePickMode;
            uniform bool enableTexture;
            uniform bool enableLighting;
            uniform vec4 color;
            uniform float opacity;
            uniform vec3 lightDirection;
            /* World-space globe-radial up at the camera, for the hemispheric ambient. */
            uniform vec3 upDirection;
            uniform sampler2D texSampler;

            varying vec2 texCoord;
            varying vec3 localPos;

            ${LightingGlsl.DECLARATIONS}
            #ifdef SHADOWS_ENABLED
            uniform mat4 modelMatrix;
            varying float viewDepth;
            varying vec3 terrainNormal;

            ${ShadowReceiverGlsl.fragmentDeclarations(lit = true)}
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
                /* Flat per-face Lambertian. Face normal comes from dFdx/dFdy of localPos,
                   so adjacent triangles within a flat face share a normal and the shading
                   "naturally" jumps at edges - the right look for schematic buildings.
                   [WW_HAS_DERIVATIVES] is defined by [Kgl.glslDerivativesPrefix] (every
                   supported platform sets it - the guard is just so a future platform that
                   can't opt in compiles cleanly with lighting silently disabled, instead of
                   failing the shader). modelMatrix is translation-only, so the local-space
                   normal is also the world-space normal - dot directly with lightDirection.
                   [lambert] is hoisted out of the lighting block so the shadow path below
                   can reuse it. */
                float lambert;
                float upFactor;
                #ifdef WW_HAS_DERIVATIVES
                vec3 n = normalize(cross(dFdx(localPos), dFdy(localPos)));
                if (!gl_FrontFacing) n = -n;
                lambert = max(dot(n, lightDirection), 0.0);
                upFactor = dot(n, upDirection) * 0.5 + 0.5;
                #else
                lambert = 1.0;
                upFactor = 0.5;
                #endif
                #ifdef SHADOWS_ENABLED
                if (!enablePickMode) {
                    /* Reconstruct the camera-relative position for shadow-map sampling.
                       modelMatrix is translation-only here; its 4th column carries the
                       eye-relative vertexOrigin offset (composed on the CPU in double). */
                    vec3 worldPos = localPos + modelMatrix[3].xyz;
                    if (enableLighting) {
                        /* Zero normal: view-winding face normals (two-sided walls) are
                           unusable for the normal-offset bias - sample depth-only. */
                        gl_FragColor.rgb *= shadowLitFactor(lambert, upFactor, worldPos, viewDepth, vec3(0.0));
                    } else if (terrainLambert > 0.0) {
                        /* Surface-shape composite: the rasterized geometry is the terrain mesh. */
                        gl_FragColor.rgb *= terrainReliefFactor(terrainNormal, worldPos, viewDepth);
                    } else {
                        gl_FragColor.rgb *= shadowAlbedoFactor(worldPos, viewDepth);
                    }
                }
                #else
                if (enableLighting && !enablePickMode) {
                    gl_FragColor.rgb *= litShadingFactor(lambert, upFactor, 1.0);
                }
                #endif
            }
        """.trimIndent()
    )
    override val attribBindings = arrayOf("pointA", "pointB", "pointC", "vertexTexCoord", "vertexNormal")

    // Prepend [Kgl.glslDerivativesPrefix] (directive + macro on most platforms, macro-only on
    // JS WebGL2 where the compiler rejects the directive) ahead of [defines]. Harmless in the
    // vertex shader - the rules let `#extension` and a custom `#define` appear in either shader
    // stage so we keep the API symmetric instead of plumbing per-stage prefixes.
    override fun glslVersion(dc: DrawContext) = ShadowReceiverGlsl.glslPrefix(dc, shadowsEnabled)

    private fun defines() = if (shadowsEnabled) ShadowReceiverGlsl.SHADOWS_ENABLED_DEFINE else ""

    private var enablePickMode = false
    private var enableTexture = false
    private var enableLighting = false
    private var enableOneVertexMode = false
    private val lightDirection = Vec3(0.0, 0.0, 1.0)
    private val upDirection = Vec3(0.0, 0.0, 1.0)
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
    private var enableLightingId = KglUniformLocation.NONE
    private var enableOneVertexModeId = KglUniformLocation.NONE
    private var lightDirectionId = KglUniformLocation.NONE
    private var upDirectionId = KglUniformLocation.NONE
    private var texCoordMatrixId = KglUniformLocation.NONE
    private var texSamplerId = KglUniformLocation.NONE
    private var clipDistanceId = KglUniformLocation.NONE
    private val shadowUniforms = ShadowReceiverUniforms(shadowsEnabled)
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
        enableLightingId = gl.getUniformLocation(program, "enableLighting")
        gl.uniform1i(enableLightingId, if (enableLighting) 1 else 0)
        enableOneVertexModeId = gl.getUniformLocation(program, "enableOneVertexMode")
        gl.uniform1i(enableOneVertexModeId, if (enableOneVertexMode) 1 else 0)
        lightDirectionId = gl.getUniformLocation(program, "lightDirection")
        gl.uniform3f(lightDirectionId, lightDirection.x.toFloat(), lightDirection.y.toFloat(), lightDirection.z.toFloat())
        upDirectionId = gl.getUniformLocation(program, "upDirection")
        gl.uniform3f(upDirectionId, upDirection.x.toFloat(), upDirection.y.toFloat(), upDirection.z.toFloat())

        texCoordMatrixId = gl.getUniformLocation(program, "texCoordMatrix")
        texCoordMatrix.transposeToArray(array, 0) // 3 x 3 identity matrix
        gl.uniformMatrix3fv(texCoordMatrixId, 1, false, array, 0)
        texSamplerId = gl.getUniformLocation(program, "texSampler")
        gl.uniform1i(texSamplerId, 0) // GL_TEXTURE0

        // modelMatrix is the model -> world (vertexOrigin translation) transform. Uploaded
        // every draw now (the fragment shader reconstructs world position from localPos plus
        // this matrix's 4th column for shadow sampling). For the no-shadow variant the uniform
        // is unused at the GL level - see SurfaceTextureProgram.initProgram for that rationale.
        modelMatrixId = gl.getUniformLocation(program, "modelMatrix")
        modelMatrix.transposeToArray(array, 0) // 4 x 4 identity matrix
        gl.uniformMatrix4fv(modelMatrixId, 1, false, array, 0)
        shadowUniforms.init(gl, program)
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
    fun enableLighting(enable: Boolean) {
        if (enableLighting != enable) {
            enableLighting = enable
            gl.uniform1i(enableLightingId, if (enable) 1 else 0)
        }
    }
    /** Upload a unit-length world-space sun direction. Dirty-checked so identical directions
     *  across many draws (the typical case for one frame) skip the GL call. */
    fun loadLightDirection(direction: Vec3) {
        if (lightDirection != direction) {
            lightDirection.copy(direction)
            gl.uniform3f(lightDirectionId, direction.x.toFloat(), direction.y.toFloat(), direction.z.toFloat())
        }
    }
    /** Upload the unit world-space globe-radial up at the camera (hemispheric ambient). */
    fun loadUpDirection(direction: Vec3) {
        if (upDirection != direction) {
            upDirection.copy(direction)
            gl.uniform3f(upDirectionId, direction.x.toFloat(), direction.y.toFloat(), direction.z.toFloat())
        }
    }
    fun enableOneVertexMode(enable: Boolean) {
        if (enableOneVertexMode != enable) {
            enableOneVertexMode = enable
            gl.uniform1i(enableOneVertexModeId, if (enable) 1 else 0)
        }
    }
    /** Forwards to [ShadowReceiverUniforms.loadTerrainRelief]; callers must reset to 0 after
     *  a relief draw so regular shape draws stay unshaded. No-op on the no-shadow variant. */
    fun loadTerrainRelief(strength: Float, lightDirection: Vec3?) =
        shadowUniforms.loadTerrainRelief(gl, strength, lightDirection)
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
     * Loads the model -> world transform. Used by the shadow receiver to reconstruct world
     * position for shadow-map sampling. Dirty-checked, since shapes commonly issue several
     * draws against the same world transform (line + fill, multi-segment paths).
     */
    fun loadModelMatrix(matrix: Matrix4) {
        if (modelMatrix != matrix) {
            modelMatrix.copy(matrix)
            matrix.transposeToArray(array, 0)
            gl.uniformMatrix4fv(modelMatrixId, 1, false, array, 0)
        }
    }

    override var shadowUploadStamp: Long
        get() = shadowUniforms.uploadStamp
        set(value) { shadowUniforms.uploadStamp = value }

    override fun loadShadowDisabled() = shadowUniforms.loadDisabled(gl)

    override fun loadShadowEnabled(state: ShadowState) = shadowUniforms.loadEnabled(gl, state)

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
