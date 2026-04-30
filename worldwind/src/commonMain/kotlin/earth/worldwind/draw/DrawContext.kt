package earth.worldwind.draw

import earth.worldwind.PickedObjectList
import earth.worldwind.geom.Matrix4
import earth.worldwind.geom.Vec2
import earth.worldwind.geom.Vec3
import earth.worldwind.geom.Viewport
import earth.worldwind.layer.shadow.ShadowState
import earth.worldwind.render.Color
import earth.worldwind.render.Framebuffer
import earth.worldwind.render.MultisampleFramebuffer
import earth.worldwind.render.Texture
import earth.worldwind.render.buffer.BufferObject
import earth.worldwind.render.buffer.BufferPool
import earth.worldwind.render.program.DepthToColorProgram
import earth.worldwind.util.LruMemoryCache
import earth.worldwind.util.NumericArray
import earth.worldwind.util.kgl.*

open class DrawContext(val gl: Kgl) {
    companion object {
        /**
         * Per-side resolution of the per-terrain-tile scratch framebuffer that surface shapes
         * are rasterized into. Edge antialiasing comes from MSAA on the multisample
         * framebuffer (see [MSAA_SAMPLES]); the texture itself stays at the historical 1024.
         */
        const val SCRATCH_FRAMEBUFFER_SIZE = 1024
        /**
         * Sample count for the multisample framebuffer. 4× MSAA is broadly supported across
         * desktop GL 3+ and OpenGL ES 3.0+ (Android API 18+). Effective sample count is
         * clamped to `GL_MAX_SAMPLES` at FBO creation time inside the GL driver.
         */
        const val MSAA_SAMPLES = 4
        /**
         * Per-side resolution of each cascaded shadow map. 1024 matches the existing scratch /
         * moments FBOs (GL implementations rarely fault when reusing the same allocation
         * footprint), and is enough to keep shadow texels under one screen pixel for typical
         * close-range cascades on a 1080p viewport. Used by [SHADOW_CASCADE_MAP_SIZES] as the
         * default for close cascades.
         */
        const val SHADOW_MAP_SIZE = 1024
        /**
         * Per-cascade shadow-map resolution. Index 0 is the closest cascade; cascades farther
         * from the camera occupy fewer screen pixels per shadow texel and benefit far less
         * from a large map, so the far cascade halves its side. At default 3 cascades the
         * footprint is `2 * 1024² + 1 * 512² ≈ 9 MB` of moments storage (RGBA32F) — `4 MB`
         * less than uniform 1024², and the visual difference is invisible at typical viewing
         * angles.
         */
        val SHADOW_CASCADE_MAP_SIZES = intArrayOf(SHADOW_MAP_SIZE, SHADOW_MAP_SIZE, SHADOW_MAP_SIZE / 2)
    }

    val eyePoint = Vec3()
    /**
     * World-space (Cartesian) unit vector pointing toward the light source. Mirrors the value
     * that [earth.worldwind.render.RenderContext.lightDirection] held at the end of the render
     * phase (so any AtmosphereLayer override is preserved). Drawables typically multiply this
     * by [modelviewNormalTransform] to get the eye-space light direction for shading.
     */
    val lightDirection = Vec3(0.0, 0.0, 1.0)
    /**
     * Per-frame cascaded shadow map state. Non-null when [earth.worldwind.layer.shadow.ShadowLayer]
     * is in the layer list; null otherwise (receivers should treat absence as "no shadows").
     * The state's cascade matrices and ambient factor drive the receiver shaders; the state
     * also signals which shadow framebuffers ([shadowCascadeFramebuffer]) hold valid moments
     * for this frame.
     */
    var shadowState: ShadowState? = null
    /**
     * Frame stamp of the last [ShadowState] whose cascade textures were bound to texture units
     * 1..3. Used by [earth.worldwind.layer.shadow.applyShadowReceiverUniforms] to skip the
     * (cheap but redundant) `activeTexture` + `bindTexture` pairs after the first receiver in
     * a frame. Reset to `-1` on context reset.
     */
    var lastShadowTextureBindStamp: Long = -1L
    val viewport = Viewport()
    val projection = Matrix4()
    val modelview = Matrix4()
    val modelviewProjection = Matrix4()
    val modelviewNormalTransform = Matrix4()
//    val infiniteProjection = Matrix4()
    val screenProjection = Matrix4()
    var uploadQueue: UploadQueue? = null
    var drawableQueue: DrawableQueue? = null
    var drawableTerrain: DrawableQueue? = null
    var pickedObjects: PickedObjectList? = null
    var pickViewport: Viewport? = null
    var pickPoint: Vec2? = null
    /** Pick-only depth-to-color packer; null on regular frames. See [DepthToColorProgram]. */
    var depthToColorProgram: DepthToColorProgram? = null
    var isPickMode = false
    private var framebuffer = KglFramebuffer.NONE
    private var program = KglProgram.NONE
    private var textureUnit = GL_TEXTURE0
    private val textures = Array(32) { KglTexture.NONE }
    private var arrayBuffer = KglBuffer.NONE
    private var elementArrayBuffer = KglBuffer.NONE
    private var pickFramebufferCache: Framebuffer? = null
    private var pickDepthReadbackFramebufferCache: Framebuffer? = null
    private var scratchFramebufferCache: Framebuffer? = null
    private var momentsFramebufferCache: Framebuffer? = null
    private var momentsBlurFramebufferCache: Framebuffer? = null
    private var momentsCubeMapTextureCache: Texture? = null
    private var momentsCubeMapFramebufferCache: Framebuffer? = null
    private val shadowCascadeFramebufferCache = arrayOfNulls<Framebuffer>(ShadowState.DEFAULT_CASCADE_COUNT)
    private val shadowBlurFramebufferCache = HashMap<Int, Framebuffer>(2)
    private var multisampleFramebufferCache: MultisampleFramebuffer? = null
    private var unitSquareBufferCache: BufferObject? = null
    private var rectangleElementsBufferCache: BufferObject? = null
    private var defaultTextureCache: Texture? = null
    private var scratchBuffer = ByteArray(4)
    private val pixelArray = ByteArray(4)
    private var bufferPool = BufferPool(GL_ARRAY_BUFFER, GL_DYNAMIC_DRAW)
    /**
     * Monotonic counter incremented each time [contextLost] runs (typically Android pause /
     * resume tearing down the GLSurfaceView's GL context). Renderables that own GPU handles
     * outside [RenderResourceCache] can compare this against a cached value to detect that
     * their handles are stale and need re-allocating. Propagated to [RenderContext] per frame
     * by the engine.
     */
    var contextVersion = 0L
        private set
    /**
     * Returns count of terrain drawables in queue
     */
    val drawableTerrainCount get() = drawableTerrain?.count?:0
    /**
     * Returns the name of the OpenGL framebuffer object that is currently active.
     */
    val currentFramebuffer get() = framebuffer
    /**
     * Returns the name of the OpenGL program object that is currently active.
     */
    val currentProgram get() = program
    /**
     * Returns the OpenGL multitexture unit that is currently active. Returns a value from the GL_TEXTUREi enumeration,
     * where i ranges from 0 to 32.
     */
    val currentTextureUnit get() = textureUnit
    /**
     * Returns the name of the OpenGL texture 2D object currently bound to the active multitexture unit. The active
     * multitexture unit may be determined by calling currentTextureUnit.
     */
    val currentTexture get() = currentTexture(textureUnit)

    /** Pick-pass FBO: RGBA8 color (pick IDs) + DEPTH16. Allocate via [ensurePickFramebuffer]. */
    val pickFramebuffer
        get() = pickFramebufferCache ?: error("Pick framebuffer not initialized")

    /**
     * Color-only FBO that receives [DepthToColorProgram]'s RG-pack of [pickFramebuffer]'s depth
     * texture, since `glReadPixels` can't portably read DEPTH_COMPONENT on WebGL1 / GLES2.
     * Allocate via [ensurePickFramebuffer].
     */
    val pickDepthReadbackFramebuffer
        get() = pickDepthReadbackFramebufferCache ?: error("Pick depth-readback framebuffer not initialized")

    /**
     * Returns an OpenGL framebuffer object suitable for offscreen drawing. The framebuffer has a 32-bit color buffer
     * and a 32-bit depth buffer, both attached as OpenGL texture 2D objects.
     * <br>
     * The framebuffer may be used by any drawable and for any purpose. However, the draw context makes no guarantees
     * about the framebuffer's contents. Drawables must clear the framebuffer before use, and must assume its contents
     * may be modified by another drawable, either during the current frame or in a subsequent frame.
     * <br>
     * The OpenGL framebuffer object is created on first use and cached. Subsequent calls to this method return the
     * cached buffer object.
     */
    val scratchFramebuffer get() = scratchFramebufferCache ?: Framebuffer().apply {
        // Sized internal formats (GLES3+/GL3+/WebGL2) so this texture matches the multisample
        // renderbuffer's GL_RGBA8 for blit-resolve compatibility. Unsized (= format) on WebGL1.
        val colorIF = if (gl.supportsSizedTextureFormats) GL_RGBA8 else GL_RGBA
        val depthIF = if (gl.supportsSizedTextureFormats) GL_DEPTH_COMPONENT16 else GL_DEPTH_COMPONENT
        val colorAttachment = Texture(SCRATCH_FRAMEBUFFER_SIZE, SCRATCH_FRAMEBUFFER_SIZE, GL_RGBA, GL_UNSIGNED_BYTE, true, colorIF)
        val depthAttachment = Texture(SCRATCH_FRAMEBUFFER_SIZE, SCRATCH_FRAMEBUFFER_SIZE, GL_DEPTH_COMPONENT, GL_UNSIGNED_SHORT, true, depthIF)
        // TODO consider modifying Texture's tex parameter behavior in order to make this unnecessary
        depthAttachment.setTexParameter(GL_TEXTURE_MIN_FILTER, GL_NEAREST)
        depthAttachment.setTexParameter(GL_TEXTURE_MAG_FILTER, GL_NEAREST)
        attachTexture(this@DrawContext, colorAttachment, GL_COLOR_ATTACHMENT0)
        attachTexture(this@DrawContext, depthAttachment, GL_DEPTH_ATTACHMENT)
    }.also { scratchFramebufferCache = it }

    /**
     * Returns an offscreen framebuffer used by [earth.worldwind.draw.DrawableSightline] when
     * running its depth pass with Moment Shadow Mapping (Hamburger 4-moment, Peters & Klein
     * 2015). Two attachments:
     *  - colour `RGBA32F` storing the four raw moments `(d, d^2, d^3, d^4)` of linear
     *    perpendicular depth from the sightline. Full-float gives 23 mantissa bits per
     *    channel; the higher-order moments (`d^3`, `d^4`) require it because the Cholesky
     *    reconstruction in the receiver subtracts close-magnitude products and any
     *    quantisation noise propagates into a noisy occlusion bound. Float textures require
     *    GLES3+ / WebGL2 / desktop GL3+; the path is taken only when
     *    [Kgl.supportsSizedTextureFormats] is true.
     *  - depth `GL_DEPTH_COMPONENT24` for terrain triangle ordering during the depth pass;
     *    the occlusion pass never reads it. See [createMomentsDepthAttachment] for why 24-bit.
     * Linear filtering is set on the colour attachment so a single hardware tap averages 2x2
     * neighbouring `(d, d^2, d^3, d^4)` values; the separable Gaussian in
     * [SightlineMomentsBlurProgram] widens the support further. Same per-side resolution as
     * [scratchFramebuffer]; lazily allocated and cached.
     */
    val momentsFramebuffer get() = momentsFramebufferCache ?: Framebuffer().apply {
        // RGBA32F render targets require sized formats; on platforms without them the
        // moments path falls back to RGBA8, which has the precision issues described above.
        // The MSM result is unusable on those platforms - prefer the bilateral path there.
        val colorAttachment = if (gl.supportsSizedTextureFormats) {
            Texture(SCRATCH_FRAMEBUFFER_SIZE, SCRATCH_FRAMEBUFFER_SIZE, GL_RGBA, GL_FLOAT, true, GL_RGBA32F)
        } else {
            Texture(SCRATCH_FRAMEBUFFER_SIZE, SCRATCH_FRAMEBUFFER_SIZE, GL_RGBA, GL_UNSIGNED_BYTE, true, GL_RGBA)
        }
        colorAttachment.setTexParameter(GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        colorAttachment.setTexParameter(GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        attachTexture(this@DrawContext, colorAttachment, GL_COLOR_ATTACHMENT0)
        attachTexture(this@DrawContext, createMomentsDepthAttachment(), GL_DEPTH_ATTACHMENT)
    }.also { momentsFramebufferCache = it }

    /**
     * 24-bit depth texture for the moments depth pass (shared between [momentsFramebuffer]
     * and [momentsCubeMapFramebuffer]). When the depth pass falls back to non-linear
     * `gl_FragCoord.z` (i.e. `EXT_frag_depth` not honoured), far-plane ridges resolve at
     * ~3 m at `range = 10 km` on 24-bit vs ~750 m on 16-bit - enough to avoid zebra
     * banding. Sized format requires GLES3+ / WebGL2 / desktop GL3+; falls back to
     * `GL_DEPTH_COMPONENT` (driver-default precision) on older platforms.
     */
    private fun createMomentsDepthAttachment(size: Int = SCRATCH_FRAMEBUFFER_SIZE): Texture {
        val sized = gl.supportsSizedTextureFormats
        return Texture(
            size, size,
            GL_DEPTH_COMPONENT, if (sized) GL_UNSIGNED_INT else GL_UNSIGNED_SHORT,
            true, if (sized) GL_DEPTH_COMPONENT24 else GL_DEPTH_COMPONENT,
        ).apply {
            setTexParameter(GL_TEXTURE_MIN_FILTER, GL_NEAREST)
            setTexParameter(GL_TEXTURE_MAG_FILTER, GL_NEAREST)
        }
    }

    /**
     * Cube-map RGBA32F moments texture used by the omnidirectional sightline's cube-map
     * receiver path. All six faces are allocated with linear filtering; only five are written
     * by the depth pass (POS_X, NEG_X, POS_Y, NEG_Y, NEG_Z) - the omitted POS_Z face is left
     * cleared (sentinel d=1) so any upward-pointing fragment direction reads as "visible".
     * The receiver does a single pass with `samplerCube`, which uses hardware seamless
     * filtering across face boundaries - the per-face 2D blur seam mismatch that paints a
     * square contour at the bottom-side seam is avoided entirely. Lazily allocated and
     * cached; requires `Kgl.supportsSizedTextureFormats` for RGBA32F.
     */
    val momentsCubeMapTexture get() = momentsCubeMapTextureCache ?: Texture(
        SCRATCH_FRAMEBUFFER_SIZE, SCRATCH_FRAMEBUFFER_SIZE,
        GL_RGBA, GL_FLOAT, true, GL_RGBA32F, target = GL_TEXTURE_CUBE_MAP
    ).apply {
        setTexParameter(GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        setTexParameter(GL_TEXTURE_MAG_FILTER, GL_LINEAR)
    }.also { momentsCubeMapTextureCache = it }

    /**
     * Framebuffer paired with [momentsCubeMapTexture] for cube-map depth-pass writes. The
     * depth-component16 texture is attached once and shared across all six face renders
     * (depth is cleared between faces). The colour attachment is rebound per face to the
     * matching cube-map face target via [Framebuffer.attachTexture] with
     * `GL_TEXTURE_CUBE_MAP_POSITIVE_X + i`. Lazily allocated and cached.
     */
    val momentsCubeMapFramebuffer get() = momentsCubeMapFramebufferCache ?: Framebuffer().apply {
        attachTexture(this@DrawContext, createMomentsDepthAttachment(), GL_DEPTH_ATTACHMENT)
    }.also { momentsCubeMapFramebufferCache = it }

    /**
     * Single-attachment companion to [momentsFramebuffer] used as the ping-pong target for
     * the separable Gaussian blur on the moments texture. Same colour format/size as the
     * moments FBO (so the blurred result can be sampled with the same filter parameters);
     * no depth attachment because the blur passes don't rasterise geometry.
     */
    val momentsBlurFramebuffer get() = momentsBlurFramebufferCache ?: Framebuffer().apply {
        val colorAttachment = if (gl.supportsSizedTextureFormats) {
            Texture(SCRATCH_FRAMEBUFFER_SIZE, SCRATCH_FRAMEBUFFER_SIZE, GL_RGBA, GL_FLOAT, true, GL_RGBA32F)
        } else {
            Texture(SCRATCH_FRAMEBUFFER_SIZE, SCRATCH_FRAMEBUFFER_SIZE, GL_RGBA, GL_UNSIGNED_BYTE, true, GL_RGBA)
        }
        colorAttachment.setTexParameter(GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        colorAttachment.setTexParameter(GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        attachTexture(this@DrawContext, colorAttachment, GL_COLOR_ATTACHMENT0)
    }.also { momentsBlurFramebufferCache = it }

    /**
     * Returns the per-cascade shadow framebuffer for the directional sun-shadow pipeline.
     * Each cascade gets its own [SHADOW_MAP_SIZE]^2 framebuffer with:
     *  - colour `RGBA32F` storing the four raw moments `(d, d^2, d^3, d^4)` of linear light-space
     *    depth (same Hamburger 4-moment formulation as [momentsFramebuffer]; the receiver's
     *    Cholesky reconstruction in the shape and terrain shaders subtracts close-magnitude
     *    products of these moments, so full-float precision is required to keep the bound
     *    quantisation noise below the perceptual threshold).
     *  - depth `GL_DEPTH_COMPONENT24` for triangle ordering during the depth pass.
     *
     * Linear filtering on the colour attachment lets a single hardware tap pre-blend
     * neighbouring `(d, d^k)` values, which the receiver-side dFdx/dFdy blur kernel widens
     * further. Lazily allocated and cached per cascade index.
     *
     * Cascades 0..n-1 (closest-to-farthest) reuse [createMomentsDepthAttachment] for the depth
     * texture so size / format stays consistent with the sightline pipeline. RGBA32F requires
     * `Kgl.supportsSizedTextureFormats`; on platforms without it the receiver path falls back
     * to PCF and never reads from these moments attachments.
     */
    fun shadowCascadeFramebuffer(cascadeIndex: Int): Framebuffer {
        require(cascadeIndex in shadowCascadeFramebufferCache.indices) {
            "shadowCascadeFramebuffer: cascadeIndex $cascadeIndex out of range"
        }
        return shadowCascadeFramebufferCache[cascadeIndex] ?: Framebuffer().apply {
            val size = SHADOW_CASCADE_MAP_SIZES[cascadeIndex]
            val colorAttachment = if (gl.supportsSizedTextureFormats) {
                Texture(size, size, GL_RGBA, GL_FLOAT, true, GL_RGBA32F)
            } else {
                Texture(size, size, GL_RGBA, GL_UNSIGNED_BYTE, true, GL_RGBA)
            }
            colorAttachment.setTexParameter(GL_TEXTURE_MIN_FILTER, GL_LINEAR)
            colorAttachment.setTexParameter(GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            // Clamp-to-edge so receiver UVs that fall just outside the cascade footprint
            // don't sample wrap-arounds from the opposite side of the shadow map.
            colorAttachment.setTexParameter(GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            colorAttachment.setTexParameter(GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
            attachTexture(this@DrawContext, colorAttachment, GL_COLOR_ATTACHMENT0)
            attachTexture(this@DrawContext, createMomentsDepthAttachment(size), GL_DEPTH_ATTACHMENT)
        }.also { shadowCascadeFramebufferCache[cascadeIndex] = it }
    }

    /**
     * Per-size ping-pong target for the separable Gaussian blur applied to each cascade's
     * moments texture before the receiver pass. Cached per `size` because [SHADOW_CASCADE_MAP_SIZES]
     * mixes resolutions across cascades — sampling a 512² cascade through a 1024² blur FBO
     * would either read uninitialised memory or need scale-aware UVs in the blur shader.
     * Allocating one blur FBO per unique cascade size sidesteps both. Same `RGBA32F` colour
     * format as the cascade FBOs.
     */
    fun shadowBlurFramebuffer(size: Int): Framebuffer = shadowBlurFramebufferCache.getOrPut(size) {
        Framebuffer().apply {
            val colorAttachment = if (gl.supportsSizedTextureFormats) {
                Texture(size, size, GL_RGBA, GL_FLOAT, true, GL_RGBA32F)
            } else {
                Texture(size, size, GL_RGBA, GL_UNSIGNED_BYTE, true, GL_RGBA)
            }
            colorAttachment.setTexParameter(GL_TEXTURE_MIN_FILTER, GL_LINEAR)
            colorAttachment.setTexParameter(GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            attachTexture(this@DrawContext, colorAttachment, GL_COLOR_ATTACHMENT0)
        }
    }

    /**
     * Returns the multisample framebuffer used as the render target for surface shape
     * rasterization, or `null` when the GL implementation doesn't support MSAA (WebGL1).
     * Callers blit (resolve) into [scratchFramebuffer]'s color attachment after rendering.
     * Created lazily on first access and cached.
     */
    val multisampleFramebuffer: MultisampleFramebuffer? get() {
        if (!gl.supportsMultisampleFBO) return null
        return multisampleFramebufferCache ?: MultisampleFramebuffer(
            SCRATCH_FRAMEBUFFER_SIZE, SCRATCH_FRAMEBUFFER_SIZE, MSAA_SAMPLES
        ).also { multisampleFramebufferCache = it }
    }
    /**
     * Returns an OpenGL buffer object containing a unit square expressed as four vertices at (0, 1), (0, 0), (1, 1) and
     * (1, 0). Each vertex is stored as two 32-bit floating point coordinates. The four vertices are in the order
     * required by a triangle strip.
     * <br>
     * The OpenGL buffer object is created on first use and cached. Subsequent calls to this method return the cached
     * buffer object.
     */
    val unitSquareBuffer get() = unitSquareBufferCache ?: BufferObject(
        GL_ARRAY_BUFFER, 0
    ).also {
        it.loadBuffer(this, NumericArray.Floats(floatArrayOf(0f, 1f, 0f, 0f, 1f, 1f, 1f, 0f)))
        unitSquareBufferCache = it
    }
    /**
     * Returns an OpenGL buffer object containing indices needed to render triangle
     * Expected vertex data layout for this buffer is something like this
     * 1 ---- 0
     * |     /|
     * |    / |
     * |   /  |
     * |  /   |
     * | /    |
     * 3 ---- 2
     * <br>
     * The OpenGL buffer object is created on first use and cached. Subsequent calls to this method return the cached
     * buffer object.
     */
    val rectangleElementsBuffer get() = rectangleElementsBufferCache ?: BufferObject(
        GL_ELEMENT_ARRAY_BUFFER, 0
    ).also {
        it.loadBuffer(this, NumericArray.Ints(intArrayOf(0, 1, 2, 2, 1, 3)))
        rectangleElementsBufferCache = it
    }

    /**
     * Returns 1x1 RGBA texture for binding to empty texture slot, initialized to 0
     */
    val defaultTexture get() = defaultTextureCache ?: Texture(1, 1, GL_RGBA, GL_UNSIGNED_BYTE, false).also {
        defaultTextureCache = it
    }
    /**
     * Returns a scratch list suitable for accumulating entries during drawing. The list is cleared before each frame,
     * otherwise its contents are undefined.
     */
    val scratchList = mutableListOf<Drawable>()
    /**
     * This cache can be used to store runtime-generated textures by DrawContext thread
     */
    val texturesCache = object : LruMemoryCache<Any, Texture>(128) {
        override fun entryRemoved(key: Any, oldValue: Texture, newValue: Texture?, evicted: Boolean) {
            oldValue.release(this@DrawContext)
        }
    }

    fun reset() {
        eyePoint.set(0.0, 0.0, 0.0)
        lightDirection.set(0.0, 0.0, 1.0)
        shadowState = null
        lastShadowTextureBindStamp = -1L
        viewport.setEmpty()
        projection.setToIdentity()
        modelview.setToIdentity()
        modelviewProjection.setToIdentity()
        modelviewNormalTransform.setToIdentity()
        screenProjection.setToIdentity()
//        infiniteProjection.setToIdentity()
        uploadQueue = null
        drawableQueue = null
        drawableTerrain = null
        pickedObjects = null
        pickViewport = null
        pickPoint = null
        depthToColorProgram = null
        isPickMode = false
        scratchBuffer.fill(0)
        scratchList.clear()
        bufferPool.reset()
    }

    fun contextLost() {
        // Bump the version FIRST so anything that happens to read it during the release pass
        // below already sees the new generation.
        contextVersion++
        // Clear objects and values associated with the current OpenGL context.
        pickFramebufferCache?.release(this)
        pickDepthReadbackFramebufferCache?.release(this)
        scratchFramebufferCache?.release(this)
        momentsFramebufferCache?.release(this)
        momentsBlurFramebufferCache?.release(this)
        momentsCubeMapTextureCache?.release(this)
        momentsCubeMapFramebufferCache?.release(this)
        for (i in shadowCascadeFramebufferCache.indices) shadowCascadeFramebufferCache[i]?.release(this)
        for (fb in shadowBlurFramebufferCache.values) fb.release(this)
        multisampleFramebufferCache?.release(this)
        unitSquareBufferCache?.release(this)
        rectangleElementsBufferCache?.release(this)
        defaultTextureCache?.release(this)
        framebuffer = KglFramebuffer.NONE
        program = KglProgram.NONE
        textureUnit = GL_TEXTURE0
        arrayBuffer = KglBuffer.NONE
        elementArrayBuffer = KglBuffer.NONE
        pickFramebufferCache = null
        pickDepthReadbackFramebufferCache = null
        scratchFramebufferCache = null
        momentsFramebufferCache = null
        momentsBlurFramebufferCache = null
        momentsCubeMapTextureCache = null
        momentsCubeMapFramebufferCache = null
        for (i in shadowCascadeFramebufferCache.indices) shadowCascadeFramebufferCache[i] = null
        shadowBlurFramebufferCache.clear()
        multisampleFramebufferCache = null
        unitSquareBufferCache = null
        rectangleElementsBufferCache = null
        defaultTextureCache = null
        textures.fill(KglTexture.NONE)
        bufferPool.contextLost()
        texturesCache.clear()
    }

    fun uploadBuffers() = uploadQueue?.processUploads(this)

    fun peekDrawable() = drawableQueue?.peekDrawable()

    fun pollDrawable() = drawableQueue?.pollDrawable()

    fun rewindDrawables() { drawableQueue?.rewindDrawables() }

    fun getDrawableTerrain(index: Int) = drawableTerrain?.getDrawable(index) as DrawableTerrain? ?: error("Invalid index")

    /**
     * Makes an OpenGL framebuffer object active. The active framebuffer becomes the target of all OpenGL commands that
     * render to the framebuffer or read from the framebuffer. This has no effect if the specified framebuffer object is
     * already active. The default is framebuffer 0, indicating that the default framebuffer provided by the windowing
     * system is active.
     *
     * @param framebuffer the name of the OpenGL framebuffer object to make active, or 0 to make the default
     * framebuffer provided by the windowing system active
     */
    fun bindFramebuffer(framebuffer: KglFramebuffer) {
        if (this.framebuffer != framebuffer) {
            this.framebuffer = framebuffer
            gl.bindFramebuffer(GL_FRAMEBUFFER, framebuffer)
        }
    }

    /**
     * Makes an OpenGL program object active as part of current rendering state. This has no effect if the specified
     * program object is already active. The default is program 0, indicating that no program is active.
     *
     * @param program the name of the OpenGL program object to make active, or 0 to make no program active
     */
    fun useProgram(program: KglProgram) {
        if (this.program != program) {
            this.program = program
            gl.useProgram(program)
        }
    }

    /**
     * Specifies the OpenGL multitexture unit to make active. This has no effect if the specified multitexture unit is
     * already active. The default is GL_TEXTURE0.
     *
     * @param textureUnit the multitexture unit, one of GL_TEXTUREi, where i ranges from 0 to 32.
     */
    fun activeTextureUnit(textureUnit: Int) {
        if (this.textureUnit != textureUnit) {
            this.textureUnit = textureUnit
            gl.activeTexture(textureUnit)
        }
    }

    /**
     * Returns the name of the OpenGL texture 2D object currently bound to the specified multitexture unit.
     *
     * @param textureUnit the multitexture unit, one of GL_TEXTUREi, where i ranges from 0 to 32.
     *
     * @return the currently bound texture 2D object, or 0 if no texture object is bound
     */
    fun currentTexture(textureUnit: Int) = textures[textureUnit - GL_TEXTURE0]

    /**
     * Makes an OpenGL texture 2D object bound to the current multitexture unit. This has no effect if the specified
     * texture object is already bound. The default is texture 0, indicating that no texture is bound.
     *
     * @param texture the name of the OpenGL texture 2D object to make active, or 0 to make no texture active
     */
    fun bindTexture(texture: KglTexture) {
        val textureUnitIndex = textureUnit - GL_TEXTURE0
        if (textures[textureUnitIndex] != texture) {
            textures[textureUnitIndex] = texture
            gl.bindTexture(GL_TEXTURE_2D, texture)
        }
    }

    /**
     * Returns the name of the OpenGL buffer object bound to the specified target buffer.
     *
     * @param target the target buffer, either GL_ARRAY_BUFFER or GL_ELEMENT_ARRAY_BUFFER
     *
     * @return the currently bound buffer object, or 0 if no buffer object is bound
     */
    fun currentBuffer(target: Int): KglBuffer {
        return when (target) {
            GL_ARRAY_BUFFER -> arrayBuffer
            GL_ELEMENT_ARRAY_BUFFER -> elementArrayBuffer
            else -> KglBuffer.NONE
        }
    }

    /**
     * Makes an OpenGL buffer object bound to a specified target buffer. This has no effect if the specified buffer
     * object is already bound. The default is buffer 0, indicating that no buffer object is bound.
     *
     * @param target   the target buffer, either GL_ARRAY_BUFFER or GL_ELEMENT_ARRAY_BUFFER
     * @param buffer the name of the OpenGL buffer object to make active
     */
    fun bindBuffer(target: Int, buffer: KglBuffer) {
        if (target == GL_ARRAY_BUFFER && arrayBuffer != buffer) {
            arrayBuffer = buffer
            gl.bindBuffer(target, buffer)
        } else if (target == GL_ELEMENT_ARRAY_BUFFER && elementArrayBuffer != buffer) {
            elementArrayBuffer = buffer
            gl.bindBuffer(target, buffer)
        } else {
            gl.bindBuffer(target, buffer)
        }
    }

    /**
     * Puts dynamic vertex data into the buffer pool and returns offset.
     *
     * @param vertexData   dynamic vertex data to be added in the buffer pool
     * @return offset in the buffer pool
     */
    fun bindBufferPool(vertexData: FloatArray) = bufferPool.bindBuffer(this, vertexData)

    /**
     * Reads the fragment color at a screen point in the currently active OpenGL frame buffer. The X and Y components
     * indicate OpenGL screen coordinates, which originate in the frame buffer's lower left corner.
     *
     * @param x      the screen point's X component
     * @param y      the screen point's Y component
     * @param result an optional pre-allocated Color in which to return the fragment color, or null to return a new
     * color
     *
     * @return the result argument set to the fragment color, or a new color if the result is null
     */
    fun readPixelColor(x: Int, y: Int, result: Color): Color {
        // Read the fragment pixel as an RGBA 8888 color.
        gl.readPixels(x, y, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixelArray)

        // Convert the RGBA 8888 color to a WorldWind color.
        result.red = (pixelArray[0].toInt() and 0xFF) / 0xFF.toFloat()
        result.green = (pixelArray[1].toInt() and 0xFF) / 0xFF.toFloat()
        result.blue = (pixelArray[2].toInt() and 0xFF) / 0xFF.toFloat()
        result.alpha = (pixelArray[3].toInt() and 0xFF) / 0xFF.toFloat()
        return result
    }

    /**
     * Reads the unique fragment colors within a screen rectangle in the currently active OpenGL frame buffer. The
     * components indicate OpenGL screen coordinates, which originate in the frame buffer's lower left corner.
     *
     * @param x      the screen rectangle's X component
     * @param y      the screen rectangle's Y component
     * @param width  the screen rectangle's width
     * @param height the screen rectangle's height
     *
     * @return a set containing the unique fragment colors
     */
    fun readPixelColors(x: Int, y: Int, width: Int, height: Int): Set<Color> {
        val pixelBuffer = readPixelsRgba8(x, y, width, height)
        val resultSet = mutableSetOf<Color>()
        var result = Color()
        for (i in 0 until width * height) {
            val idx = i * 4
            result.red = (pixelBuffer[idx + 0].toInt() and 0xFF) / 0xFF.toFloat()
            result.green = (pixelBuffer[idx + 1].toInt() and 0xFF) / 0xFF.toFloat()
            result.blue = (pixelBuffer[idx + 2].toInt() and 0xFF) / 0xFF.toFloat()
            result.alpha = (pixelBuffer[idx + 3].toInt() and 0xFF) / 0xFF.toFloat()
            // Accumulate the unique colors in a set; only allocate a new Color when one is added.
            if (resultSet.add(result)) result = Color()
        }
        return resultSet
    }

    /**
     * Reads fragment colors over a screen rectangle into a row-major list (no de-duplication),
     * indexed `row * width + col`. Companion to [readPixelDepths] so colors and depths can be
     * iterated in lockstep.
     */
    fun readPixelColorList(x: Int, y: Int, width: Int, height: Int): ArrayList<Color> {
        val pixelCount = width * height
        val pixelBuffer = readPixelsRgba8(x, y, width, height)
        val result = ArrayList<Color>(pixelCount)
        for (i in 0 until pixelCount) {
            val idx = i * 4
            result.add(
                Color(
                    (pixelBuffer[idx + 0].toInt() and 0xFF) / 0xFF.toFloat(),
                    (pixelBuffer[idx + 1].toInt() and 0xFF) / 0xFF.toFloat(),
                    (pixelBuffer[idx + 2].toInt() and 0xFF) / 0xFF.toFloat(),
                    (pixelBuffer[idx + 3].toInt() and 0xFF) / 0xFF.toFloat()
                )
            )
        }
        return result
    }

    /** Single-pixel depth from the RG-packed depth color attachment. See [DepthToColorProgram]. */
    fun readPixelDepth(x: Int, y: Int) = readPixelDepths(x, y, 1, 1)[0]

    /**
     * Row-major 16-bit normalized depths over a screen rectangle, in `[0, 1]`. Reads from the
     * RG-packed depth color attachment; see [DepthToColorProgram].
     */
    fun readPixelDepths(x: Int, y: Int, width: Int, height: Int): FloatArray {
        val pixelCount = width * height
        val pixelBuffer = readPixelsRgba8(x, y, width, height)
        val result = FloatArray(pixelCount)
        for (i in 0 until pixelCount) {
            val idx = i * 4
            val r = pixelBuffer[idx + 0].toInt() and 0xFF
            val g = pixelBuffer[idx + 1].toInt() and 0xFF
            // Depth lives in R (high byte) + G (low byte); see DepthToColorProgram.packD16.
            result[i] = r / 255f + g / (255f * 255f)
        }
        return result
    }

    /**
     * Reads `width × height` RGBA8 pixels from the active framebuffer into [scratchBuffer]. GL_RGBA
     * + GL_UNSIGNED_BYTE is the only readPixels combo guaranteed by GLES2 / WebGL — GL_RGB silently
     * returns zeros on Android and WebGL even though desktop drivers tolerate it.
     */
    private fun readPixelsRgba8(x: Int, y: Int, width: Int, height: Int): ByteArray {
        val pixelBuffer = scratchBuffer(width * height * 4)
        val packAlignment = gl.getParameteri(GL_PACK_ALIGNMENT)
        gl.pixelStorei(GL_PACK_ALIGNMENT, 1)
        gl.readPixels(x, y, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixelBuffer)
        gl.pixelStorei(GL_PACK_ALIGNMENT, packAlignment)
        return pixelBuffer
    }

    /**
     * Lazily allocates [pickFramebuffer] and [pickDepthReadbackFramebuffer], reallocating both
     * when the cached attachments are smaller than `width × height` (e.g. after viewport resize).
     */
    fun ensurePickFramebuffer(width: Int, height: Int) {
        val existing = pickFramebufferCache
        if (existing != null) {
            val color = existing.getAttachedTexture(GL_COLOR_ATTACHMENT0)
            if (color.width >= width && color.height >= height) return
            existing.release(this)
            pickDepthReadbackFramebufferCache?.release(this)
        }

        // Sized internal formats on WebGL2 / GLES3+ (WebGL2 leaves the FBO incomplete otherwise);
        // unsized on WebGL1 / GLES2.
        val colorIF = if (gl.supportsSizedTextureFormats) GL_RGBA8 else GL_RGBA
        val depthIF = if (gl.supportsSizedTextureFormats) GL_DEPTH_COMPONENT16 else GL_DEPTH_COMPONENT

        pickFramebufferCache = Framebuffer().apply {
            val color = Texture(width, height, GL_RGBA, GL_UNSIGNED_BYTE, true, colorIF)
            val depth = Texture(width, height, GL_DEPTH_COMPONENT, GL_UNSIGNED_SHORT, true, depthIF)
            depth.setTexParameter(GL_TEXTURE_MIN_FILTER, GL_NEAREST)
            depth.setTexParameter(GL_TEXTURE_MAG_FILTER, GL_NEAREST)
            attachTexture(this@DrawContext, color, GL_COLOR_ATTACHMENT0)
            attachTexture(this@DrawContext, depth, GL_DEPTH_ATTACHMENT)
        }
        pickDepthReadbackFramebufferCache = Framebuffer().apply {
            val color = Texture(width, height, GL_RGBA, GL_UNSIGNED_BYTE, true, colorIF)
            attachTexture(this@DrawContext, color, GL_COLOR_ATTACHMENT0)
        }
    }

    /**
     * Returns a scratch NIO buffer suitable for use during drawing. The returned buffer has capacity at least equal to
     * the specified capacity. The buffer is cleared before each frame, otherwise its contents, position, limit and mark
     * are undefined.
     *
     * @param capacity the buffer's minimum capacity in bytes
     *
     * @return the draw context's scratch buffer
     */
    fun scratchBuffer(capacity: Int): ByteArray {
        if (scratchBuffer.size < capacity) scratchBuffer = ByteArray(capacity)
        return scratchBuffer
    }
}