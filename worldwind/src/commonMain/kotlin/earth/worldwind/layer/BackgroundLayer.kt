package earth.worldwind.layer

import earth.worldwind.MR
import earth.worldwind.geom.Sector
import earth.worldwind.render.image.ImageConfig
import earth.worldwind.render.image.ImageOptions
import earth.worldwind.render.image.ImageSource
import earth.worldwind.shape.SurfaceImage
import kotlin.jvm.JvmOverloads

/**
 * Displays a single image spanning the globe. By default, BackgroundLayer is configured to display NASA's Blue Marble
 * next generation image at 40km resolution from the built-in WorldWind library resource
 * res/drawable/worldwind_worldtopobathy2004053.
 */
class BackgroundLayer @JvmOverloads constructor(
    imageSource: ImageSource = ImageSource.fromResource(MR.images.worldwind_worldtopobathy2004053),
    imageOptions: ImageOptions = ImageOptions(ImageConfig.RGB_565)
) : RenderableLayer("Background") {
    // Disable picking for the layer because it covers the full sphere and will override a terrain pick.
    override var isPickEnabled = false

    init {
        // Delegate display to the SurfaceImage shape.
        val surfaceImage = SurfaceImage(Sector().setFullSphere(), imageSource)
        surfaceImage.imageOptions = imageOptions
        addRenderable(surfaceImage)
    }
}