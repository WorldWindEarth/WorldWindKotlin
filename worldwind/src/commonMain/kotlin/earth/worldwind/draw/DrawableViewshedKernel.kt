package earth.worldwind.draw

import earth.worldwind.render.Color
import earth.worldwind.render.Framebuffer
import earth.worldwind.render.Texture
import earth.worldwind.render.program.ViewshedKernelShaderProgram
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.logMessage
import earth.worldwind.util.kgl.*

/**
 * Single-pass GPU dispatch for [earth.worldwind.shape.ViewshedSightline]'s visibility kernel.
 * Uploads the elevation grid as `R32F`, runs the per-fragment Amanatides–Woo walk via
 * [ViewshedKernelShaderProgram] into an externally-owned RGBA8 [outputTexture], and signals
 * completion via [callback]. The texture lives on the GPU and is sampled directly by
 * `SurfaceImage` on its own draw — no CPU readback, no PBO, no fence.
 *
 * NOT pool-managed; the renderable holds a single instance and re-enqueues it per dispatch.
 * [recycle] is a deliberate no-op. [width]/[height] are settable per-dispatch — the same
 * drawable instance survives `samplesPerSide` changes; only the [outputTexture] reference is
 * swapped out by the renderable.
 *
 * Bails (no callback fired) when [Kgl.supportsSizedTextureFormats] is false (WebGL1 / GLES2);
 * the kernel needs `R32F` and `texelFetch`.
 */
open class DrawableViewshedKernel : Drawable {
    var width = 0
    var height = 0
    /** Source elevations in row-major south-up order (matching `Globe.getElevationGrid`). */
    var elevations: FloatArray? = null
    /** RGBA8 render target attached to [GL_COLOR_ATTACHMENT0]. Owned by the renderable. */
    var outputTexture: Texture? = null
    var observerCellX = 0
    var observerCellY = 0
    var observerAltitude = 0f
    var earthRadius = 0f
    var metersPerPixelX = 0f
    var metersPerPixelY = 0f
    var verticalExaggeration = 1f
    /** 0 = circular AOI, 1 = rectangular AOI. */
    var areaMode = 0
    var areaHalfPxX = 0f
    var areaHalfPxY = 0f
    val visibleColor = Color()
    val occludedColor = Color()
    var missingValue = 0f
    /** Invoked on the GL thread once the kernel has rendered into [outputTexture]. */
    var callback: (() -> Unit)? = null
    /** Shader program acquired via `RenderContext.getShaderProgram` before enqueue. */
    var program: ViewshedKernelShaderProgram? = null
    /**
     * Textures the renderable wants released on the next [draw]. Used to dispose previous
     * `outputTexture` instances after a `samplesPerSide` change without leaking until engine
     * teardown. Drained at the top of [draw].
     */
    val texturesToRelease: MutableList<Texture> = mutableListOf()
    /**
     * If `true`, [draw] drains [texturesToRelease], calls [release] on this drawable's own
     * GPU resources, and returns without rendering. The renderable sets this from
     * `dispose()`; one final enqueue gets all resources off the GL.
     */
    var disposeOnNextDraw = false

    private var elevTexture = KglTexture.NONE
    private var framebuffer: Framebuffer? = null

    override fun recycle() {
        // Drawable is owned 1:1 by its ViewshedSightline; the framework calls recycle() after
        // each draw but the renderable holds the reference for the next dispatch. Resources
        // outlive any single frame's queue.
    }

    /** Free this drawable's GPU resources. Must run on the GL thread (i.e. from within [draw]). */
    fun release(dc: DrawContext) {
        if (elevTexture.isValid()) {
            dc.gl.deleteTexture(elevTexture)
            elevTexture = KglTexture.NONE
        }
        framebuffer?.release(dc)
        framebuffer = null
    }

    override fun draw(dc: DrawContext) {
        // Drain deferred releases before any other work. The renderable populates
        // [texturesToRelease] when its samplesPerSide changes (the previous outputTexture
        // becomes orphaned the moment we allocate a fresh one) and via [disposeOnNextDraw]
        // to fully shut down.
        if (texturesToRelease.isNotEmpty()) {
            for (t in texturesToRelease) t.release(dc)
            texturesToRelease.clear()
        }
        if (disposeOnNextDraw) {
            release(dc)
            disposeOnNextDraw = false
            return
        }

        val elevations = this.elevations ?: return
        val output = this.outputTexture ?: return
        val callback = this.callback ?: return
        val program = this.program ?: return

        if (!dc.gl.supportsSizedTextureFormats) {
            logMessage(WARN, TAG, "draw", "GLES 3 / WebGL 2 required for R32F sampling - kernel skipped")
            return
        }
        if (!program.useProgram(dc)) return

        val fb = framebuffer ?: Framebuffer().also { framebuffer = it }

        // Upload elevations to the persistent R32F sampler texture. Re-uploads each dispatch;
        // the texture object stays the same, only its data changes between frames. Routing the
        // bind through DrawContext keeps its 2D-texture-binding cache in sync.
        if (!elevTexture.isValid()) elevTexture = dc.gl.createTexture()
        dc.activeTextureUnit(GL_TEXTURE0)
        dc.bindTexture(elevTexture)
        dc.gl.texImage2D(
            GL_TEXTURE_2D, 0, GL_R32F, width, height, 0, GL_RED, GL_FLOAT, elevations,
        )
        dc.gl.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
        dc.gl.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
        dc.gl.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        dc.gl.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)

        // Save state we're about to clobber so the next surface drawable still sees WorldWind
        // defaults: depth-test enabled, blend enabled, depthMask true, attrib 0 enabled,
        // framebuffer = whatever the renderer set, viewport = main window.
        val savedFramebuffer = dc.currentFramebuffer
        val savedViewportX = dc.viewport.x
        val savedViewportY = dc.viewport.y
        val savedViewportW = dc.viewport.width
        val savedViewportH = dc.viewport.height

        if (!fb.attachTexture(dc, output, GL_COLOR_ATTACHMENT0)) return
        if (!fb.bindFramebuffer(dc)) return
        dc.gl.viewport(0, 0, width, height)

        program.loadGridSize(width, height)
        program.loadObserverCell(observerCellX, observerCellY)
        program.loadObserverAltitude(observerAltitude)
        program.loadEarthRadius(earthRadius)
        program.loadMetersPerPixel(metersPerPixelX, metersPerPixelY)
        program.loadVerticalExaggeration(verticalExaggeration)
        program.loadArea(areaMode, areaHalfPxX, areaHalfPxY)
        program.loadVisibleColor(visibleColor)
        program.loadOccludedColor(occludedColor)
        program.loadMissingValue(missingValue)

        dc.gl.disable(GL_DEPTH_TEST)
        dc.gl.disable(GL_BLEND)
        dc.gl.depthMask(false)
        // Vertex shader uses gl_VertexID; disable attrib 0 (WorldWind enables it by default) so
        // strict drivers don't complain about an enabled attribute pointing at no buffer.
        dc.gl.disableVertexAttribArray(0)
        dc.gl.drawArrays(GL_TRIANGLES, 0, 3)
        dc.gl.enableVertexAttribArray(0)

        dc.bindFramebuffer(savedFramebuffer)
        dc.gl.viewport(savedViewportX, savedViewportY, savedViewportW, savedViewportH)
        dc.gl.enable(GL_DEPTH_TEST)
        dc.gl.enable(GL_BLEND)
        dc.gl.depthMask(true)

        callback.invoke()
    }

    companion object {
        val KEY = DrawableViewshedKernel::class
        private const val TAG = "DrawableViewshedKernel"
    }
}
