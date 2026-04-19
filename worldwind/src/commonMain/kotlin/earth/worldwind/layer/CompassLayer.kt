package earth.worldwind.layer

import earth.worldwind.MR
import earth.worldwind.geom.Offset
import earth.worldwind.geom.OffsetMode
import earth.worldwind.render.RenderContext
import earth.worldwind.render.image.ImageSource
import earth.worldwind.shape.ScreenImage

class CompassLayer(
    private val imageSource: ImageSource = ImageSource.fromResource(MR.images.compass_notched)
) : AbstractLayer("Compass") {
    override var isPickEnabled = false

    // compass diameter in density-independent pixels (dp)
    var compassSizeDp = 100.0

    var marginDp = 10.0

    private val compass = ScreenImage(imageSource = imageSource)

    override fun doRender(rc: RenderContext) {
        val texture = rc.getTexture(imageSource) ?: return
        val margin = marginDp * rc.densityFactor
        compass.screenOffset = Offset(OffsetMode.INSET_PIXELS, margin, OffsetMode.INSET_PIXELS, margin)
        compass.imageOffset = Offset(OffsetMode.INSET_PIXELS, 0.0, OffsetMode.INSET_PIXELS, 0.0)
        compass.imageScale = compassSizeDp * rc.densityFactor / texture.width
        compass.imageRotation = rc.camera.heading
        compass.render(rc)
    }
}
