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

    override fun doRender(rc: RenderContext) {
        if (sector.isEmpty || !rc.terrain.sector.intersects(sector) || !getExtent(rc).intersectsFrustum(rc.frustum)) return
        val texture = rc.getTexture(imageSource, imageOptions) ?: return // no texture to draw

        // Enqueue a drawable surface texture for processing on the OpenGL thread.
        val program = getShaderProgram(rc)
        val pool = rc.getDrawablePool<DrawableSurfaceTexture>()
        val drawable = DrawableSurfaceTexture.obtain(pool).set(program, sector, texture, texture.coordTransform)
        rc.offerSurfaceDrawable(drawable, 0.0 /*z-order*/)

        // Enqueue a picked object that associates the drawable surface texture with this surface image.
        if (rc.isPickMode) {
            val pickedObjectId = rc.nextPickedObjectId()
            PickedObject.identifierToUniqueColor(pickedObjectId, drawable.color)
            rc.offerPickedObject(PickedObject.fromRenderable(pickedObjectId, this, rc.currentLayer))
        }
    }

    protected open fun getShaderProgram(rc: RenderContext) = rc.getShaderProgram { SurfaceTextureProgram() }
}