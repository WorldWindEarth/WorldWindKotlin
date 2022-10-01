package earth.worldwind.layer

import earth.worldwind.geom.Sector
import earth.worldwind.ogc.WmsLayer
import earth.worldwind.ogc.WmsLayerConfig
import kotlin.jvm.JvmOverloads

/**
 * Displays Landsat imagery at 15m resolution from an OGC Web Map Service (WMS). By default, LandsatLayer is configured
 * to retrieve imagery from the WMS at [&amp;https://worldwind25.arc.nasa.gov/wms](https://worldwind25.arc.nasa.gov/wms?SERVICE=WMS&amp;REQUEST=GetCapabilities).
 */
class LandsatLayer @JvmOverloads constructor(serviceAddress: String = "https://worldwind25.arc.nasa.gov/wms") : WmsLayer("Landsat") {
    init {
        val config = WmsLayerConfig(serviceAddress, "esat")
        setConfiguration(Sector().setFullSphere(), 15.0, config) // 15m resolution on Earth
    }
}