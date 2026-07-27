package earth.worldwind.draw

import earth.worldwind.PickedObjectList
import earth.worldwind.geom.Matrix4
import earth.worldwind.geom.Vec2
import earth.worldwind.geom.Vec3
import earth.worldwind.geom.Viewport
import earth.worldwind.layer.shadow.ShadowState
import earth.worldwind.layer.sightline.SightlineState
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

/** A composited surface-shape RTT texture plus the shape-set content hash it was rendered for, so a
 *  terrain tile can keep reusing its last complete texture until its shapes change AND finish uploading. */
class SurfaceTileTexture(val texture: Texture, val contentHash: Int)

open class DrawContext(val gl: Kgl) {
    companion object {
        /**
         * Per-side resolution of the per-terrain-tile scratch framebuffer that surface shapes
         * are rasterized into. Edge antialiasing comes from MSAA on the multisample
         * framebuffer (see [MSAA_SAMPLES]); the texture itself stays at the historical 1024.
         */
        const val SCRATCH_FRAMEBUFFER_SIZE = 1024
        /** Byte budget for [surfaceShapeTiles] (composited surface-shape RTT textures, ~5 MB each at
         *  1024² RGBA+mip). Bounds GPU memory regardless of how many terrain tiles are panned through;
         *  sized to hold a typical visible terrain-tile set plus a pan margin. */
        const val SURFACE_SHAPE_CACHE_BYTES = 192L * 1024L * 1024L
        /**
         * Sample count for the multisample framebuffer. 4× MSAA is broadly supported across
         * desktop GL 3+ and OpenGL ES 3.0+ (Android API 18+). Effective sample count is
         * clamped to `GL_MAX_SAMPLES` at FBO creation time inside the GL driver.
         */
        const val MSAA_SAMPLES = 4
        /**
         * Baseline per-cascade shadow-map resolution ([SHADOW_CASCADE_MAP_SIZES]). 2048² matches
         * the reference-engine default; depth-pass fill isn't the shadow bottleneck (the receiver
         * resolve is), so it's near-free on frame time - drop to 1024² for lighter VRAM.
         */
        const val SHADOW_MAP_SIZE = 2048
        /**
         * Per-face resolution of the sightline depth cube map. One cube serves both the
         * omnidirectional and directional sightline kinds.
         */
        const val SIGHTLINE_MAP_SIZE = 1024
        /**
         * Per-cascade shadow-map resolution — reference-tier 2048² per cascade. Apps may assign
         * a different tier **before the first frame only**: receiver shaders bake these sizes
         * as constants when programs are built and the cascade framebuffers allocate on
         * first use, so the setter validates the shape and refuses changes once any consumer
         * has read the values (late mutation would silently desync shaders, framebuffers,
         * and the layer's texel math). Process-global — shared by all WorldWindows.
         */
        var SHADOW_CASCADE_MAP_SIZES = intArrayOf(SHADOW_MAP_SIZE, SHADOW_MAP_SIZE, SHADOW_MAP_SIZE, SHADOW_MAP_SIZE)
            set(value) {
                check(!shadowCascadeSizesLocked) {
                    "SHADOW_CASCADE_MAP_SIZES must be assigned before the first frame"
                }
                require(value.size == ShadowState.DEFAULT_CASCADE_COUNT && value.all { it > 0 }) {
                    "SHADOW_CASCADE_MAP_SIZES needs ${ShadowState.DEFAULT_CASCADE_COUNT} positive sizes"
                }
                field = value
            }

        private var shadowCascadeSizesLocked = false

        /** Reads (and permanently locks) the cascade map size for [index]. */
        fun shadowCascadeMapSize(index: Int): Int {
            shadowCascadeSizesLocked = true
            return SHADOW_CASCADE_MAP_SIZES[index]
        }

        /**
         * Soft shadows: `true` = 3x3 nine-tap PCF kernel; `false` (default) = a single
         * depth-compare tap whose free 2x2 hardware bilinear still softens the edge one texel.
         * The nine-tap kernel is the dominant shadow fragment cost on mobile (~55% of shadow
         * GPU time measured), so it's opt-in. Baked into receiver shaders - assign before the
         * first frame, like [SHADOW_CASCADE_MAP_SIZES].
         */
        var SOFT_SHADOWS = false
            set(value) {
                check(!shadowCascadeSizesLocked) {
                    "SOFT_SHADOWS must be assigned before the first frame"
                }
                field = value
            }

        /** Reads (and permanently locks) the soft-shadow flag. */
        fun softShadows(): Boolean {
            shadowCascadeSizesLocked = true
            return SOFT_SHADOWS
        }
        /** Reusable `[GL_NONE]` argument for depth-only framebuffer draw-buffer setup. */
        private val NO_DRAW_BUFFERS = intArrayOf(GL_NONE)
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
     * World-space unit globe-radial up at the camera, for hemispheric ambient shading.
     * Up varies negligibly over shading-relevant distances, so one per-frame vector serves
     * every lit drawable; eye-space consumers multiply by [modelviewNormalTransform].
     */
    val upDirection = Vec3(0.0, 0.0, 1.0)
    /**
     * Per-frame cascaded shadow map state. Non-null when [earth.worldwind.layer.shadow.ShadowLayer]
     * is in the layer list; null otherwise (receivers should treat absence as "no shadows").
     * The state's cascade matrices and ambient factor drive the receiver shaders; the state
     * also signals which shadow framebuffers ([shadowCascadeFramebuffer]) hold valid depth
     * for this frame.
     */
    var shadowState: ShadowState? = null
    /**
     * Frame stamp of the last [ShadowState] whose cascade textures were bound to texture units
     * 1..4. Used by [earth.worldwind.layer.shadow.applyShadowReceiverUniforms] to skip the
     * (cheap but redundant) `activeTexture` + `bindTexture` pairs after the first receiver in
     * a frame. Reset to `-1` on context reset.
     */
    var lastShadowTextureBindStamp: Long = -1L
    /**
     * Per-frame sightline-receiver state. Populated by [DrawableSightline] when its depth pass
     * runs (BACKGROUND group); read by every shape program that splices in
     * [earth.worldwind.layer.sightline.SightlineReceiverGlsl] so the shape's own fragment
     * shader can sample the depth cube and self-shadow without an overlay re-rasterisation.
     */
    var sightlineState: SightlineState? = null
    /** Identity of the [SightlineState] whose depth cube is currently bound on unit 5.
     *  `null` after the binding is cleared on a no-sightline frame. */
    var lastSightlineTextureBind: SightlineState? = null
    /** Identity of the sightline shape whose depth pass last filled [sightlineDepthCubeTexture].
     *  With several sightlines in a scene each one's overlay pass re-renders the cube only when
     *  another sightline has overwritten it since its own depth pass ran. */
    var sightlineCubeOwner: Any? = null
    /** Whether benign 2D/cube textures are bound on units 5 / 6 this frame so the no-sightline path's
     *  never-sampled samplers don't trip macOS's empty-unit validator. Reset per frame. */
    var sightlineBenignBound = false
    /** Scratch for the camera-relative sightline matrix composed in
     *  [earth.worldwind.layer.sightline.applySightlineReceiverUniforms]. Owned by the draw
     *  context (not file scope) so each WorldWindow's GL thread composes into its own matrix -
     *  multiple windows draw concurrently on separate threads. Fully overwritten per use. */
    val sightlineLocalScratch = Matrix4()
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
    /** `true` when at least one 3D-Tile mesh layer enqueued into [DrawableGroup.SURFACE] this
     *  frame — terrain stencil-tests against GROUND_COVERED_BIT. Propagated from RC via Frame. */
    var hasGroundCoverageMask = false
    /** Per-frame snapshot of 3D-Tile mesh layer coverage sectors. Empty when
     *  [hasGroundCoverageMask] is `false`; consumed by [BasicDrawableTerrain]. */
    val groundCoverageRegions = mutableListOf<earth.worldwind.geom.Sector>()
    /** Mesh drawables that receive draped surface-shape textures this frame. */
    val groundOverlaySurfaces = mutableListOf<GroundOverlaySurface>()
    /** Draping program; non-null only on frames with [groundOverlaySurfaces]. */
    var surfaceOverlayProgram: earth.worldwind.render.program.SurfaceOverlayProgram? = null
    private var framebuffer = KglFramebuffer.NONE
    private var program = KglProgram.NONE
    private var textureUnit = GL_TEXTURE0
    private val textures = Array(32) { KglTexture.NONE }
    private var arrayBuffer = KglBuffer.NONE
    private var elementArrayBuffer = KglBuffer.NONE
    private var pickFramebufferCache: Framebuffer? = null
    private var pickDepthReadbackFramebufferCache: Framebuffer? = null
    private var scratchFramebufferCache: Framebuffer? = null
    private var sightlineDepthCubeTextureCache: Texture? = null
    private var nullShadowDepthTextureCache: Texture? = null
    private var nullShadowDepthCubeTextureCache: Texture? = null
    private var sightlineCubeFramebufferCache: Framebuffer? = null
    private val shadowCascadeFramebufferCache = arrayOfNulls<Framebuffer>(ShadowState.DEFAULT_CASCADE_COUNT)
    private var multisampleFramebufferCache: MultisampleFramebuffer? = null
    private val gaussianPassFramebufferCache = mutableMapOf<Any, Array<Framebuffer?>>()
    private val gaussianPassFramebufferParity = mutableMapOf<Any, Int>()
    private var unitSquareBufferCache: BufferObject? = null
    private var rectangleElementsBufferCache: BufferObject? = null
    private var defaultTextureCache: Texture? = null
    private var defaultCubeTextureCache: Texture? = null
    private var scratchBuffer = ByteArray(4)
    private val pixelArray = ByteArray(4)
    private var bufferPool = BufferPool(GL_ARRAY_BUFFER, GL_DYNAMIC_DRAW)
    /**
     * Monotonic counter incremented each time [contextLost] runs (typically Android pause /
     * resume tearing down the GLSurfaceView's GL context). Renderables that own GPU handles
     * outside [earth.worldwind.render.RenderResourceCache] can compare this against a cached
     * value to detect that their handles are stale and need re-allocating. Propagated to
     * [earth.worldwind.render.RenderContext] per frame by the engine.
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

    /**
     * Pick-pass FBO: RGBA8 color (pick IDs) + DEPTH24 (DEPTH16 on WebGL1 / GLES2 fallback).
     * Allocate via [ensurePickFramebuffer]. The depth precision controls how far from the
     * camera a non-terrain pick can land before depth-readback drift recovers the surface
     * point on the far clipping plane (Earth-scale view: antipode / under-ground artefacts).
     */
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
     * 24-bit depth texture used as the depth attachment of the shadow-cascade and sightline
     * depth passes. 24-bit keeps far-range ridges resolvable (~3 m at `range = 10 km` vs
     * ~750 m on 16-bit). Sized format requires GLES3+ / WebGL2 / desktop GL3+; falls back to
     * `GL_DEPTH_COMPONENT` (driver-default precision) on older platforms. `GL_NEAREST` is
     * mandatory for sampling a depth texture without a compare mode.
     */
    private fun createDepthTextureAttachment(size: Int = SCRATCH_FRAMEBUFFER_SIZE, target: Int = GL_TEXTURE_2D): Texture {
        val sized = gl.supportsSizedTextureFormats
        return Texture(
            size, size,
            GL_DEPTH_COMPONENT, if (sized) GL_UNSIGNED_INT else GL_UNSIGNED_SHORT,
            true, if (sized) GL_DEPTH_COMPONENT24 else GL_DEPTH_COMPONENT,
            target = target,
        ).apply {
            setTexParameter(GL_TEXTURE_MIN_FILTER, GL_NEAREST)
            setTexParameter(GL_TEXTURE_MAG_FILTER, GL_NEAREST)
        }
    }

    /**
     * 1x1 compare-mode depth texture bound to the cascade units when no shadow state is
     * active. WebGL2 validates sampler type vs texture format at draw time even when the
     * shader never samples (`applyShadow` false), so `sampler2DShadow` receivers must always
     * see a compare-mode depth texture on units 1..4. Only used when [Kgl.hasShadowSamplers].
     */
    val nullShadowDepthTexture get() = nullShadowDepthTextureCache
        ?: createNullDepthTexture(GL_TEXTURE_2D).also { nullShadowDepthTextureCache = it }

    /** Cube counterpart of [nullShadowDepthTexture] for the sightline unit (`samplerCubeShadow`). */
    val nullShadowDepthCubeTexture get() = nullShadowDepthCubeTextureCache
        ?: createNullDepthTexture(GL_TEXTURE_CUBE_MAP).also { nullShadowDepthCubeTextureCache = it }

    private fun createNullDepthTexture(target: Int) = Texture(
        1, 1,
        GL_DEPTH_COMPONENT, if (gl.supportsSizedTextureFormats) GL_UNSIGNED_INT else GL_UNSIGNED_SHORT,
        false, if (gl.supportsSizedTextureFormats) GL_DEPTH_COMPONENT24 else GL_DEPTH_COMPONENT,
        target = target,
    ).apply {
        setTexParameter(GL_TEXTURE_MIN_FILTER, GL_NEAREST)
        setTexParameter(GL_TEXTURE_MAG_FILTER, GL_NEAREST)
        setTexParameter(GL_TEXTURE_COMPARE_MODE, GL_COMPARE_REF_TO_TEXTURE)
        setTexParameter(GL_TEXTURE_COMPARE_FUNC, GL_LEQUAL)
    }

    /**
     * Depth-only cube map for the sightline depth pass. Five faces hold true hardware depth
     * from the sightline's point of view (POS_Z is never rendered — terrain is not visible
     * looking straight up; receivers mask upward directions instead). On
     * [Kgl.hasShadowSamplers] platforms receivers tap it through `samplerCubeShadow`
     * (LINEAR + compare mode); otherwise they read raw depth with `textureCube(...).r` and
     * compare in-shader. Either way the receiver's percentage-closer filter does its own
     * tent weighting across taps. Lazily allocated and cached.
     */
    val sightlineDepthCubeTexture get() = sightlineDepthCubeTextureCache
        ?: createDepthTextureAttachment(SIGHTLINE_MAP_SIZE, GL_TEXTURE_CUBE_MAP).apply {
            if (gl.hasShadowSamplers) {
                setTexParameter(GL_TEXTURE_MIN_FILTER, GL_LINEAR)
                setTexParameter(GL_TEXTURE_MAG_FILTER, GL_LINEAR)
                setTexParameter(GL_TEXTURE_COMPARE_MODE, GL_COMPARE_REF_TO_TEXTURE)
                setTexParameter(GL_TEXTURE_COMPARE_FUNC, GL_LEQUAL)
            }
        }.also { sightlineDepthCubeTextureCache = it }

    /**
     * Depth-only framebuffer paired with [sightlineDepthCubeTexture]. The depth pass
     * re-attaches the face being rendered to `GL_DEPTH_ATTACHMENT` via
     * [Framebuffer.attachTexture] with the matching `GL_TEXTURE_CUBE_MAP_*` target. With no
     * colour attachment, desktop GL reports FRAMEBUFFER_INCOMPLETE_DRAW_BUFFER unless the
     * draw and read buffers are explicitly set to NONE (FBO state, set once here).
     */
    val sightlineCubeFramebuffer get() = sightlineCubeFramebufferCache ?: Framebuffer().apply {
        attachTexture(this@DrawContext, sightlineDepthCubeTexture, GL_DEPTH_ATTACHMENT, GL_TEXTURE_CUBE_MAP_POSITIVE_X)
        val previousFramebuffer = currentFramebuffer
        bindFramebuffer(this@DrawContext)
        gl.drawBuffers(NO_DRAW_BUFFERS)
        gl.readBuffer(GL_NONE)
        this@DrawContext.bindFramebuffer(previousFramebuffer)
    }.also { sightlineCubeFramebufferCache = it }

    /**
     * Returns the per-cascade shadow framebuffer for the directional sun-shadow pipeline.
     * Each cascade is **depth-only**: a `GL_DEPTH_COMPONENT24` texture holds true hardware
     * depth written by [DirectionalDepthProgram]'s pass (which lets `glPolygonOffset` apply
     * slope-scaled caster bias). On [Kgl.hasShadowSamplers] platforms the texture carries
     * LINEAR + COMPARE_REF_TO_TEXTURE and receivers tap it through `sampler2DShadow`
     * (hardware 2x2 PCF per tap); otherwise receivers read raw depth from the red channel,
     * where `GL_NEAREST` is mandatory and the software percentage-closer filter does its own
     * bilinear weighting across texels. Clamp-to-edge keeps out-of-footprint UVs
     * from wrapping to the opposite side of the map. Lazily allocated and cached per cascade.
     */
    fun shadowCascadeFramebuffer(cascadeIndex: Int): Framebuffer {
        require(cascadeIndex in shadowCascadeFramebufferCache.indices) {
            "shadowCascadeFramebuffer: cascadeIndex $cascadeIndex out of range"
        }
        return shadowCascadeFramebufferCache[cascadeIndex] ?: Framebuffer().apply {
            val size = shadowCascadeMapSize(cascadeIndex)
            val depthAttachment = createDepthTextureAttachment(size)
            depthAttachment.setTexParameter(GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            depthAttachment.setTexParameter(GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
            if (gl.hasShadowSamplers) {
                // Hardware depth-compare sampling (sampler2DShadow receivers): LINEAR +
                // compare mode gives spec-defined 2x2 PCF per tap.
                depthAttachment.setTexParameter(GL_TEXTURE_MIN_FILTER, GL_LINEAR)
                depthAttachment.setTexParameter(GL_TEXTURE_MAG_FILTER, GL_LINEAR)
                depthAttachment.setTexParameter(GL_TEXTURE_COMPARE_MODE, GL_COMPARE_REF_TO_TEXTURE)
                depthAttachment.setTexParameter(GL_TEXTURE_COMPARE_FUNC, GL_LEQUAL)
            }
            attachTexture(this@DrawContext, depthAttachment, GL_DEPTH_ATTACHMENT)
            // With no colour attachment, desktop GL reports FRAMEBUFFER_INCOMPLETE_DRAW_BUFFER
            // unless the draw and read buffers are explicitly set to NONE (FBO state).
            val previousFramebuffer = currentFramebuffer
            bindFramebuffer(this@DrawContext)
            gl.drawBuffers(NO_DRAW_BUFFERS)
            gl.readBuffer(GL_NONE)
            this@DrawContext.bindFramebuffer(previousFramebuffer)
        }.also { shadowCascadeFramebufferCache[cascadeIndex] = it }
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
    /** 1x1 RGBA cube-map (six faces) for binding to an empty `samplerCube` slot, the cube counterpart
     *  to [defaultTexture]. Lazily allocated and cached. */
    val defaultCubeTexture get() = defaultCubeTextureCache
        ?: Texture(1, 1, GL_RGBA, GL_UNSIGNED_BYTE, false, target = GL_TEXTURE_CUBE_MAP).also {
            defaultCubeTextureCache = it
        }

    /**
     * Parks a benign cube on unit 5 and invalidates the sightline bind cache. Called by
     * [DrawableSightline] whenever it clobbers unit 5 for feedback safety (a texture bound for
     * sampling can't also be a render target). Without the [lastSightlineTextureBind] reset a
     * later receiver holding the same [SightlineState] would cache-hit and sample this benign
     * cube instead of the real depth cube - safe today only by drawable ordering. The parked
     * cube is compare-mode ([nullShadowDepthCubeTexture]) under hardware samplers, matching the
     * receiver path so WebGL2's draw-time sampler-vs-format validation stays consistent.
     */
    fun parkBenignSightlineCube() {
        activeTextureUnit(GL_TEXTURE5)
        (if (gl.hasShadowSamplers) nullShadowDepthCubeTexture else defaultCubeTexture).bindTexture(this)
        activeTextureUnit(GL_TEXTURE0)
        sightlineBenignBound = true
        lastSightlineTextureBind = null
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
    /**
     * Per-terrain-tile composited surface-shape RTT textures, keyed by tile (sector + globe offset),
     * bounded by [SURFACE_SHAPE_CACHE_BYTES]. Keyed by tile — NOT tile+shapes — so [DrawableSurfaceShape]
     * reuses a tile's last fully-composited texture while its shapes reassemble (retain-last-good), avoiding
     * both the base-layer flash on pan and the per-frame hash churn that filled the count-bounded
     * [texturesCache] with 5 MB textures and OOM'd. Releases the GL texture on eviction or in-place replace.
     */
    val surfaceShapeTiles = object : LruMemoryCache<Any, SurfaceTileTexture>(SURFACE_SHAPE_CACHE_BYTES) {
        override fun entryRemoved(key: Any, oldValue: SurfaceTileTexture, newValue: SurfaceTileTexture?, evicted: Boolean) {
            if (newValue?.texture !== oldValue.texture) oldValue.texture.release(this@DrawContext)
        }
    }

    fun reset() {
        eyePoint.set(0.0, 0.0, 0.0)
        lightDirection.set(0.0, 0.0, 1.0)
        upDirection.set(0.0, 0.0, 1.0)
        shadowState = null
        lastShadowTextureBindStamp = -1L
        sightlineState = null
        lastSightlineTextureBind = null
        sightlineBenignBound = false
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
        hasGroundCoverageMask = false
        groundCoverageRegions.clear()
        groundOverlaySurfaces.clear()
        surfaceOverlayProgram = null
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
        sightlineDepthCubeTextureCache?.release(this)
        nullShadowDepthTextureCache?.release(this)
        nullShadowDepthCubeTextureCache?.release(this)
        sightlineCubeFramebufferCache?.release(this)
        for (i in shadowCascadeFramebufferCache.indices) shadowCascadeFramebufferCache[i]?.release(this)
        multisampleFramebufferCache?.release(this)
        for (slots in gaussianPassFramebufferCache.values) for (framebuffer in slots) framebuffer?.release(this)
        unitSquareBufferCache?.release(this)
        rectangleElementsBufferCache?.release(this)
        defaultTextureCache?.release(this)
        defaultCubeTextureCache?.release(this)
        framebuffer = KglFramebuffer.NONE
        program = KglProgram.NONE
        textureUnit = GL_TEXTURE0
        arrayBuffer = KglBuffer.NONE
        elementArrayBuffer = KglBuffer.NONE
        pickFramebufferCache = null
        pickDepthReadbackFramebufferCache = null
        scratchFramebufferCache = null
        sightlineDepthCubeTextureCache = null
        nullShadowDepthTextureCache = null
        nullShadowDepthCubeTextureCache = null
        sightlineCubeFramebufferCache = null
        for (i in shadowCascadeFramebufferCache.indices) shadowCascadeFramebufferCache[i] = null
        multisampleFramebufferCache = null
        gaussianPassFramebufferCache.clear()
        gaussianPassFramebufferParity.clear()
        unitSquareBufferCache = null
        rectangleElementsBufferCache = null
        defaultTextureCache = null
        defaultCubeTextureCache = null
        textures.fill(KglTexture.NONE)
        bufferPool.contextLost()
        texturesCache.clear()
        surfaceShapeTiles.clear()
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
     * Forgets a deleted texture in the per-unit bind cache. GL auto-unbinds deleted names, so a
     * recycled name would otherwise cache-hit here and skip the real bind on rarely-rebound units.
     */
    fun textureDeleted(texture: KglTexture) {
        for (i in textures.indices) if (textures[i] == texture) textures[i] = KglTexture.NONE
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

    /** Single-pixel depth from the RGB-packed depth color attachment. See [DepthToColorProgram]. */
    fun readPixelDepth(x: Int, y: Int) = readPixelDepths(x, y, 1, 1)[0]

    /**
     * Row-major 24-bit normalized depths over a screen rectangle, in `[0, 1]`. Reads from the
     * RGB-packed depth color attachment; see [DepthToColorProgram].
     */
    fun readPixelDepths(x: Int, y: Int, width: Int, height: Int): FloatArray {
        val pixelCount = width * height
        val pixelBuffer = readPixelsRgba8(x, y, width, height)
        val result = FloatArray(pixelCount)
        for (i in 0 until pixelCount) {
            val idx = i * 4
            val r = pixelBuffer[idx + 0].toInt() and 0xFF
            val g = pixelBuffer[idx + 1].toInt() and 0xFF
            val b = pixelBuffer[idx + 2].toInt() and 0xFF
            // Depth lives in R (high byte) + G (middle byte) + B (low byte); see
            // DepthToColorProgram.packD24. On WebGL1 / GLES2 the source depth is 16-bit so
            // the low byte is zero, which the same formula handles without a special case.
            result[i] = r / 255f + g / (255f * 255f) + b / (255f * 255f * 255f)
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
    /**
     * Reduced-resolution Gaussian-splat pass target: RGBA color (LINEAR, for the composite
     * upsample) + 24-bit depth texture for the terrain occlusion prepass. Grows monotonically
     * like the pick framebuffer; callers render into the lower-left `width x height` region
     * and sample it back via a texture-scale uniform. Requires sized texture formats — callers
     * gate on [earth.worldwind.util.kgl.Kgl.supportsSizedTextureFormats] and fall back to
     * direct drawing otherwise. Returns null if the framebuffer cannot be completed.
     */
    fun ensureGaussianPassFramebuffer(owner: Any, width: Int, height: Int): Framebuffer? {
        // Ping-pong between two buffers so frame N+1's clear/write never has to wait for the
        // GPU to finish frame N's composite read of the same texture — single-buffered reuse
        // serialized the pipeline into a ~1-GPU-frame bubble on the GL thread. The pair and
        // its parity are keyed per [owner] (one pass per layer per frame): a global per-call
        // parity would alias any even number of passes per frame back onto one slot each,
        // silently restoring that stall. Entries live until [contextLost].
        val slots = gaussianPassFramebufferCache.getOrPut(owner) { arrayOfNulls(2) }
        val parity = (gaussianPassFramebufferParity[owner] ?: 0).also {
            gaussianPassFramebufferParity[owner] = it xor 1
        }
        val existing = slots[parity]
        if (existing != null) {
            val color = existing.getAttachedTexture(GL_COLOR_ATTACHMENT0)
            if (color.width >= width && color.height >= height) return existing
            color.release(this)
            existing.getAttachedTexture(GL_DEPTH_ATTACHMENT).release(this)
            existing.release(this)
            slots[parity] = null
        }
        val created = Framebuffer().apply {
            val color = Texture(width, height, GL_RGBA, GL_UNSIGNED_BYTE, true, GL_RGBA8)
            val depth = Texture(width, height, GL_DEPTH_COMPONENT, GL_UNSIGNED_INT, true, GL_DEPTH_COMPONENT24)
            depth.setTexParameter(GL_TEXTURE_MIN_FILTER, GL_NEAREST)
            depth.setTexParameter(GL_TEXTURE_MAG_FILTER, GL_NEAREST)
            attachTexture(this@DrawContext, color, GL_COLOR_ATTACHMENT0)
            attachTexture(this@DrawContext, depth, GL_DEPTH_ATTACHMENT)
        }
        return if (created.isFramebufferComplete(this)) {
            slots[parity] = created
            created
        } else {
            created.getAttachedTexture(GL_COLOR_ATTACHMENT0).release(this)
            created.getAttachedTexture(GL_DEPTH_ATTACHMENT).release(this)
            created.release(this)
            null
        }
    }

    fun ensurePickFramebuffer(width: Int, height: Int) {
        val existing = pickFramebufferCache
        if (existing != null) {
            val color = existing.getAttachedTexture(GL_COLOR_ATTACHMENT0)
            if (color.width >= width && color.height >= height) return
            existing.release(this)
            pickDepthReadbackFramebufferCache?.release(this)
        }

        // DEPTH24 + 8-bit stencil so applyTileStencilState's GROUND_COVERED_BIT survives the
        // pick pass — without stencil, terrain overdraws 3D-Tile content in the pick FBO.
        val sized = gl.supportsSizedTextureFormats
        val colorIF = if (sized) GL_RGBA8 else GL_RGBA
        val depthIF = if (sized) GL_DEPTH24_STENCIL8 else GL_DEPTH_STENCIL

        pickFramebufferCache = Framebuffer().apply {
            val color = Texture(width, height, GL_RGBA, GL_UNSIGNED_BYTE, true, colorIF)
            val depth = Texture(width, height, GL_DEPTH_STENCIL, GL_UNSIGNED_INT_24_8, true, depthIF)
            depth.setTexParameter(GL_TEXTURE_MIN_FILTER, GL_NEAREST)
            depth.setTexParameter(GL_TEXTURE_MAG_FILTER, GL_NEAREST)
            attachTexture(this@DrawContext, color, GL_COLOR_ATTACHMENT0)
            attachTexture(this@DrawContext, depth, GL_DEPTH_STENCIL_ATTACHMENT)
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