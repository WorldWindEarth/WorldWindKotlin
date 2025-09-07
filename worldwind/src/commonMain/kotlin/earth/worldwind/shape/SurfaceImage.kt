package earth.worldwind.shape

import earth.worldwind.PickedObject
import earth.worldwind.draw.DrawableSurfaceTexture
import earth.worldwind.geom.Sector
import earth.worldwind.render.AbstractSurfaceRenderable
import earth.worldwind.render.RenderContext
import earth.worldwind.render.image.ImageOptions
import earth.worldwind.render.image.ImageSource
import earth.worldwind.render.program.SurfaceTextureProgram

open class SurfaceImage(sector: Sector, var imageSource: ImageSource): AbstractSurfaceRenderable(sector, "Surface Image") {
    var imageOptions: ImageOptions? = null
    protected val terrainSector = Sector()

    override fun doRender(rc: RenderContext) {
        terrainSector.copy(sector)
        if (terrainSector.isEmpty || !terrainSector.intersect(rc.terrain.sector) || !getExtent(rc).intersectsFrustum(rc.frustum)) return
        val texture = rc.getTexture(imageSource, imageOptions) ?: return // no texture to draw
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