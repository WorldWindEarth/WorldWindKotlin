package earth.worldwind.shape

import earth.worldwind.PickedObject
import earth.worldwind.draw.DrawableSurfaceTexture
import earth.worldwind.geom.Sector
import earth.worldwind.render.AbstractSurfaceRenderable
import earth.worldwind.render.RenderContext
import earth.worldwind.render.Texture
import earth.worldwind.render.image.ImageOptions
import earth.worldwind.render.image.ImageSource
import earth.worldwind.render.program.SurfaceTextureProgram

/**
 * Decal that projects an image (CPU-loaded) or a [Texture] (GPU-resident) onto the terrain over
 * [sector]. Use the [imageSource] constructor for the typical bitmap/URL path (the texture is
 * resolved via `RenderContext.getTexture` and cached in `RenderResourceCache`); use the
 * [texture] constructor when the data already lives on the GPU — e.g. the output of a
 * render-to-texture pass — to skip the upload round-trip entirely.
 *
 * When both [imageSource] and [texture] are set, [texture] takes precedence.
 */
open class SurfaceImage : AbstractSurfaceRenderable {

    /** Image-source path: image is uploaded by `RenderResourceCache` on first use and cached. */
    var imageSource: ImageSource? = null
    /**
     * GPU-direct path: texture is sampled in place by the surface drawable, no upload.
     * Caller owns the lifecycle. Setting this overrides any [imageSource] resolution.
     */
    var texture: Texture? = null
    var imageOptions: ImageOptions? = null
    protected val terrainSector = Sector()

    constructor(sector: Sector, imageSource: ImageSource) : super(sector, "Surface Image") {
        this.imageSource = imageSource
    }

    constructor(sector: Sector, texture: Texture) : super(sector, "Surface Image") {
        this.texture = texture
    }

    override fun doRender(rc: RenderContext) {
        terrainSector.copy(sector)
        if (terrainSector.isEmpty || !terrainSector.intersect(rc.terrain.sector) || !getExtent(rc).intersectsFrustum(rc.frustum)) return
        val texture = this.texture ?: imageSource?.let { rc.getTexture(it, imageOptions) } ?: return
        val opacity = if (rc.isPickMode) 1f else rc.currentLayer.opacity

        // Enqueue a drawable surface texture for processing on the OpenGL thread.
        val program = getShaderProgram(rc)
        val pool = rc.getDrawablePool(DrawableSurfaceTexture.KEY)
        val drawable = DrawableSurfaceTexture.obtain(pool).set(
            program, sector, opacity, texture, texture.coordTransform, rc.globe.offset, terrainSector
        )
        rc.offerSurfaceDrawable(drawable, zOrder)

        // Enqueue a picked object that associates the drawable surface texture with this surface image.
        if (rc.isPickMode) {
            val pickedObjectId = rc.nextPickedObjectId()
            PickedObject.identifierToUniqueColor(pickedObjectId, drawable.color)
            rc.offerPickedObject(PickedObject.fromRenderable(pickedObjectId, this, rc.currentLayer))
        }
    }

    protected open fun getShaderProgram(rc: RenderContext) =
        rc.getShaderProgram(SurfaceTextureProgram.KEY) { SurfaceTextureProgram() }
}
