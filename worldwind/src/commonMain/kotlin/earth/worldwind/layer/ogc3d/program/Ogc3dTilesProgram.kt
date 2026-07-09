package earth.worldwind.layer.ogc3d.program

import earth.worldwind.draw.DrawContext
import earth.worldwind.geom.Matrix4
import earth.worldwind.geom.Vec3
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
import earth.worldwind.render.program.LightingGlsl
import earth.worldwind.util.kgl.KglUniformLocation

/**
 * Color-pass shader for b3dm / i3dm / cmpt / glTF mesh content. Four variants:
 * base, shadow-only, sightline-only, both — picked by [get] via sentinel subclasses.
 * Lambertian lighting; baseColorFactor × optional unit-0 texture.
 */
open class Ogc3dTilesProgram(
    protected val shadowsEnabled: Boolean = false,
    protected val sightlineEnabled: Boolean = false,
) : AbstractShaderProgram(), ShadowReceiverProgram, SightlineReceiverProgram {

    override var programSources: Array<String> = arrayOf(
        defines() + VERTEX_SHADER,
        defines() + FRAGMENT_SHADER,
    )

    override val attribBindings: Array<String> = arrayOf(
        "vertexPosition", "vertexNormal", "vertexTexCoord", "vertexColor", "vertexBatchId",
    )

    // Prepend [Kgl.glslDerivativesPrefix] (WW_HAS_DERIVATIVES + platform-aware extension
    // directive) so the shadow receiver's receiver-plane depth bias can use dFdx/dFdy.
    override fun glslVersion(dc: earth.worldwind.draw.DrawContext) = dc.gl.glslVersion + dc.gl.glslDerivativesPrefix


    private fun defines(): String {
        var out = ""
        if (shadowsEnabled) out += ShadowReceiverGlsl.SHADOWS_ENABLED_DEFINE
        if (sightlineEnabled) out += SightlineReceiverGlsl.SIGHTLINE_ENABLED_DEFINE
        return out
    }

    // --- uniform handles -----------------------------------------------------------

    private var mvpMatrixId = KglUniformLocation.NONE
    private var modelMatrixId = KglUniformLocation.NONE
    private var colorId = KglUniformLocation.NONE
    private var opacityId = KglUniformLocation.NONE
    private var enableTextureId = KglUniformLocation.NONE
    private var enableLightingId = KglUniformLocation.NONE
    private var enableVertexColorId = KglUniformLocation.NONE
    private var lightDirectionId = KglUniformLocation.NONE
    private var alphaCutoffId = KglUniformLocation.NONE
    private var enableAlphaMaskId = KglUniformLocation.NONE
    private var enablePickModeId = KglUniformLocation.NONE
    private var enableBatchPickId = KglUniformLocation.NONE
    private var pickColorId = KglUniformLocation.NONE
    private var pickIdBaseId = KglUniformLocation.NONE
    private var texSamplerId = KglUniformLocation.NONE

    // Shadow / sightline receiver uniforms (only present in the respective variants).
    private val shadowUniforms = ShadowReceiverUniforms(shadowsEnabled)
    private val sightlineUniforms = SightlineReceiverUniforms(sightlineEnabled)

    // --- uniform state caches (skip redundant GL calls) ----------------------------

    private val mvpMatrix = Matrix4()
    private val modelMatrix = Matrix4()
    private val matrixArray = FloatArray(16)
    private val color = Color()
    private var opacity = 1.0f
    private var enableTexture = false
    private var enableLighting = false
    private var enableVertexColor = false
    private var enableAlphaMask = false
    private var alphaCutoff = 0.5f
    private var enablePickMode = false
    private var enableBatchPick = false
    private val pickColor = Color(0f, 0f, 0f, 1f)
    private var pickIdBase = 0f
    private val lightDirection = Vec3(0.0, 0.0, 1.0)

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
        colorId = gl.getUniformLocation(program, "color")
        gl.uniform4f(colorId, color.red, color.green, color.blue, color.alpha)
        opacityId = gl.getUniformLocation(program, "opacity")
        gl.uniform1f(opacityId, opacity)
        enableTextureId = gl.getUniformLocation(program, "enableTexture")
        gl.uniform1i(enableTextureId, 0)
        enableLightingId = gl.getUniformLocation(program, "enableLighting")
        gl.uniform1i(enableLightingId, 0)
        enableVertexColorId = gl.getUniformLocation(program, "enableVertexColor")
        gl.uniform1i(enableVertexColorId, 0)
        lightDirectionId = gl.getUniformLocation(program, "lightDirection")
        gl.uniform3f(lightDirectionId, 0f, 0f, 1f)
        alphaCutoffId = gl.getUniformLocation(program, "alphaCutoff")
        gl.uniform1f(alphaCutoffId, 0.5f)
        enableAlphaMaskId = gl.getUniformLocation(program, "enableAlphaMask")
        gl.uniform1i(enableAlphaMaskId, 0)
        enablePickModeId = gl.getUniformLocation(program, "enablePickMode")
        gl.uniform1i(enablePickModeId, 0)
        enableBatchPickId = gl.getUniformLocation(program, "enableBatchPick")
        gl.uniform1i(enableBatchPickId, 0)
        pickColorId = gl.getUniformLocation(program, "pickColor")
        gl.uniform4f(pickColorId, 0f, 0f, 0f, 1f)
        pickIdBaseId = gl.getUniformLocation(program, "pickIdBase")
        gl.uniform1f(pickIdBaseId, 0f)
        texSamplerId = gl.getUniformLocation(program, "texSampler")
        gl.uniform1i(texSamplerId, 0) // GL_TEXTURE0

        shadowUniforms.init(gl, program)
        sightlineUniforms.init(gl, program)
    }

    // --- per-draw setters ----------------------------------------------------------

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

    fun loadColor(c: Color) {
        if (color != c) {
            color.copy(c)
            gl.uniform4f(colorId, c.red, c.green, c.blue, c.alpha)
        }
    }

    fun loadOpacity(value: Float) {
        if (opacity != value) {
            opacity = value
            gl.uniform1f(opacityId, value)
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

    fun enableVertexColor(enable: Boolean) {
        if (enableVertexColor != enable) {
            enableVertexColor = enable
            gl.uniform1i(enableVertexColorId, if (enable) 1 else 0)
        }
    }

    fun loadLightDirection(direction: Vec3) {
        if (lightDirection != direction) {
            lightDirection.copy(direction)
            gl.uniform3f(lightDirectionId, direction.x.toFloat(), direction.y.toFloat(), direction.z.toFloat())
        }
    }

    fun enableAlphaMask(enable: Boolean, cutoff: Float) {
        if (enableAlphaMask != enable) {
            enableAlphaMask = enable
            gl.uniform1i(enableAlphaMaskId, if (enable) 1 else 0)
        }
        if (alphaCutoff != cutoff) {
            alphaCutoff = cutoff
            gl.uniform1f(alphaCutoffId, cutoff)
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

    /** Enable per-feature pick mode. When true, the fragment shader uses the per-vertex
     *  `batchPickColor` varying (computed from `vertexBatchId + pickIdBase`) instead of
     *  the uniform [loadPickColor]. The drawable must have a valid `vertexBatchId`
     *  attribute bound and have called [loadPickIdBase] with the first pickedObjectId
     *  reserved for the tile's batches. */
    fun enableBatchPick(enable: Boolean) {
        if (enableBatchPick != enable) {
            enableBatchPick = enable
            gl.uniform1i(enableBatchPickId, if (enable) 1 else 0)
        }
    }

    /** First `rc.nextPickedObjectId()` reserved for this tile's batches. The vertex shader
     *  computes `pickId = pickIdBase + vertexBatchId` and decomposes that to RGB. */
    fun loadPickIdBase(base: Int) {
        val v = base.toFloat()
        if (pickIdBase != v) {
            pickIdBase = v
            gl.uniform1f(pickIdBaseId, v)
        }
    }

    // --- ShadowReceiverProgram impl -----------------------------------------------

    override fun loadShadowDisabled() = shadowUniforms.loadDisabled(gl)

    override fun loadShadowEnabled(state: ShadowState) = shadowUniforms.loadEnabled(gl, state)

    // --- SightlineReceiverProgram impl --------------------------------------------

    override fun loadSightlineDisabled() = sightlineUniforms.loadDisabled(gl)

    override fun loadSightlineEnabled(state: SightlineState, localMatrix: Matrix4) =
        sightlineUniforms.loadEnabled(gl, state, localMatrix)

    companion object {
        /** Always include the sightline splice. `hasActiveSightline` is set inside
         *  `RealtimeSightline.makeDrawable` during the render-traversal phase — the
         *  same phase 3D-Tiles layers traverse — so its value at shader-pick time depends on
         *  layer order. With the splice always present, `computeSightlineTint` early-outs to
         *  `vec4(0.0)` when `applySightline == false`, so per-fragment cost when no sightline
         *  is live is one uniform branch + no texture lookups. */
        fun get(rc: RenderContext): Ogc3dTilesProgram = if (rc.hasShadowLayer) {
            rc.getShaderProgram { Ogc3dTilesProgramBoth() }
        } else {
            rc.getShaderProgram { Ogc3dTilesProgramSightline() }
        }

        // GLSL — pure ASCII.

        private val VERTEX_SHADER: String = """
            uniform mat4 mvpMatrix;
            /* Camera-relative model matrix: translate(-eyePoint) * tileToWorld * nodeMatrix,
               composed in double precision on the CPU. Its rotation equals the world model
               matrix (normals unaffected); its translation is eye-relative so [worldPos]
               interpolates at full float32 precision near the camera - a raw ECEF varying
               quantizes to ~0.5 m and destroys street-scale shadow / sightline detail. */
            uniform mat4 modelMatrix;
            /* Batch picking: encode pickIdBase + vertexBatchId into 24-bit RGB. */
            uniform float pickIdBase;

            attribute vec3 vertexPosition;
            attribute vec3 vertexNormal;
            attribute vec2 vertexTexCoord;
            attribute vec4 vertexColor;
            attribute float vertexBatchId;

            varying vec2 texCoord;
            varying vec3 worldNormal;
            varying vec4 vertColor;
            varying vec4 batchPickColor;
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

                texCoord = vertexTexCoord;
                vertColor = vertexColor;
                /* Normalise in case the tile transform has non-uniform scale. */
                worldNormal = normalize(mat3(modelMatrix) * vertexNormal);

                /* Pack pickId as 24-bit RGB matching PickedObject.identifierToUniqueColor. */
                float pickId = pickIdBase + vertexBatchId;
                float pickR = floor(pickId / 65536.0);
                float pickG = floor(mod(pickId, 65536.0) / 256.0);
                float pickB = mod(pickId, 256.0);
                batchPickColor = vec4(pickR, pickG, pickB, 255.0) / 255.0;

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

            uniform vec4 color;
            uniform float opacity;
            uniform bool enableTexture;
            uniform bool enableLighting;
            uniform bool enableVertexColor;
            uniform bool enableAlphaMask;
            uniform float alphaCutoff;
            uniform bool enablePickMode;
            uniform bool enableBatchPick;
            uniform vec4 pickColor;
            uniform vec3 lightDirection;
            uniform sampler2D texSampler;

            varying vec2 texCoord;
            varying vec3 worldNormal;
            varying vec4 vertColor;
            varying vec4 batchPickColor;

            ${LightingGlsl.DECLARATIONS}
            #if defined(SHADOWS_ENABLED) || defined(SIGHTLINE_ENABLED)
            varying vec3 worldPos;
            #endif
            #ifdef SHADOWS_ENABLED
            varying float viewDepth;
            ${ShadowReceiverGlsl.fragmentDeclarations(lit = true)}
            #endif

            #ifdef SIGHTLINE_ENABLED
            ${SightlineReceiverGlsl.FRAGMENT_DECLARATIONS}
            #endif

            void main() {
                if (enablePickMode) {
                    /* enableBatchPick selects between per-feature (batchPickColor varying,
                       baked from pickIdBase + vertexBatchId in the vertex shader) and
                       tile-level (uniform pickColor) picking. The branch is uniform so
                       it's free on any modern GPU. */
                    gl_FragColor = enableBatchPick ? batchPickColor : pickColor;
                    return;
                }

                vec4 baseColor = color;
                if (enableVertexColor) baseColor *= vertColor;
                if (enableTexture) baseColor *= texture2D(texSampler, texCoord);

                if (enableAlphaMask && baseColor.a < alphaCutoff) discard;

                if (enableLighting) {
                    /* Lambert against world-space sun direction. Adjacent triangles share
                       per-vertex normals so a true diffuse term works (unlike the OSM-buildings
                       dFdx fallback). enableLighting == real per-vertex normals present, so
                       the shadow path gets a normal-offset receiver bias too. */
                    float lambert = max(dot(normalize(worldNormal), lightDirection), 0.0);
                    #ifdef SHADOWS_ENABLED
                    baseColor.rgb *= shadowLitFactor(lambert, worldPos, viewDepth, normalize(worldNormal));
                    #else
                    baseColor.rgb *= litShadingFactor(lambert, 1.0);
                    #endif
                }
                #ifdef SHADOWS_ENABLED
                else {
                    /* Photogrammetry (baked light, no normals): shadow is the only sun shading. */
                    baseColor.rgb *= shadowAlbedoFactor(worldPos, viewDepth);
                }
                #endif

                baseColor *= opacity;

                #ifdef SIGHTLINE_ENABLED
                vec4 tint = computeSightlineTint();
                baseColor.rgb = baseColor.rgb * (1.0 - tint.a) + tint.rgb;
                baseColor.a = max(baseColor.a, tint.a);
                #endif

                gl_FragColor = baseColor;
            }
        """.trimIndent()
    }
}

/** Sentinel subclass for shadow-only variant — distinct cache key. */
class Ogc3dTilesProgramShadow : Ogc3dTilesProgram(shadowsEnabled = true, sightlineEnabled = false)

/** Sentinel subclass for sightline-only variant. */
class Ogc3dTilesProgramSightline : Ogc3dTilesProgram(shadowsEnabled = false, sightlineEnabled = true)

/** Sentinel subclass for both shadow + sightline variant. */
class Ogc3dTilesProgramBoth : Ogc3dTilesProgram(shadowsEnabled = true, sightlineEnabled = true)
