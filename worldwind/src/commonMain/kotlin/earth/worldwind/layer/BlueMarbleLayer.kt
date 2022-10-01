package earth.worldwind.layer

import earth.worldwind.geom.Sector
import earth.worldwind.ogc.WmsLayer
import earth.worldwind.ogc.WmsLayerConfig
import earth.worldwind.render.image.ImageConfig
import earth.worldwind.render.image.ImageOptions
import earth.worldwind.shape.TiledSurfaceImage
import kotlin.jvm.JvmOverloads

/**
 * Displays NASA's Blue Marble next generation imagery at 500m resolution from an OGC Web Map Service (WMS). By default,
 * BlueMarbleLayer is configured to retrieve imagery for May 2004 from the WMS at [&amp;https://worldwind25.arc.nasa.gov/wms](https://worldwind25.arc.nasa.gov/wms?SERVICE=WMS&amp;REQUEST=GetCapabilities).
 * <br>
 * Information on NASA's Blue Marble next generation imagery can be found at http://earthobservatory.nasa.gov/Features/BlueMarble/
 */
class BlueMarbleLayer @JvmOverloads constructor(
    serviceAddress: String = "https://worldwind25.arc.nasa.gov/wms"
): WmsLayer("Blue Marble") {
    init {
        val config = WmsLayerConfig(serviceAddress, "BlueMarble-200405").apply {
            isTransparent = false // the BlueMarble layer is opaque
        }
        setConfiguration(Sector().setFullSphere(), 500.0, config) // 500m resolution on Earth
        (getRenderable(0) as TiledSurfaceImage).apply {
            imageOptions = ImageOptions(ImageConfig.RGB_565) // exploit opaque imagery to reduce memory usage
        }
    }
}