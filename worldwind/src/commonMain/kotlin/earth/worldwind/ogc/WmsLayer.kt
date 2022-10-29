package earth.worldwind.ogc

import earth.worldwind.geom.Ellipsoid
import earth.worldwind.geom.Sector
import earth.worldwind.globe.Globe
import earth.worldwind.layer.TiledImageLayer
import earth.worldwind.shape.TiledSurfaceImage
import earth.worldwind.util.LevelSet
import earth.worldwind.util.LevelSetConfig
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import kotlin.jvm.JvmOverloads

/**
 * Displays imagery from OGC Web Map Service (WMS) layers.
 * <br>
 * WMSLayer's configuration may be specified at construction or by calling `setConfiguration`, and must
 * include the following values: service address, WMS protocol version, layer names, coordinate reference system, sector
 * and resolution. All other WMS configuration values may be unspecified, in which case a default value is used.
 * <br>
 * WmsLayer defaults to retrieving imagery in the PNG format. This may be configured by calling
 * `setImageFormat`.
 */
open class WmsLayer @JvmOverloads constructor(displayName: String = "WMS Layer") : TiledImageLayer(displayName) {
    /**
     * Constructs a Web Map Service (WMS) layer with specified WMS layer configuration values. The configuration must
     * specify the following values: service address, WMS protocol version, layer names, coordinate reference system,
     * sector and resolution. All other WMS configuration values may be unspecified, in which case a default value is
     * used.
     *
     * @param sector         the geographic region in which to display the WMS layer
     * @param metersPerPixel the desired resolution in meters on Earth
     * @param config         the WMS layer configuration values
     * @param globe          the Globe to take equatorial radius
     *
     * @throws IllegalArgumentException If the resolution is not positive, or if any
     * configuration value is invalid
     */
    @JvmOverloads
    constructor(sector: Sector, metersPerPixel: Double, config: WmsLayerConfig, globe: Globe? = null) : this() {
        setConfiguration(sector, metersPerPixel, config, globe)
    }

    /**
     * Specifies this Web Map Service (WMS) layer's configuration. The configuration must specify the following values:
     * service address, WMS protocol version, layer names, coordinate reference system, sector and resolution. All other
     * WMS configuration values may be unspecified, in which case a default value is used.
     *
     * @param sector         the geographic region in which to display the WMS layer
     * @param metersPerPixel the desired resolution in meters on the specified globe
     * @param config         the WMS layer configuration values
     * @param globe          the Globe to take equatorial radius
     *
     * @throws IllegalArgumentException If the resolution is not positive, or if any
     * configuration value is invalid
     */
    @JvmOverloads
    fun setConfiguration(
        sector: Sector, metersPerPixel: Double, config: WmsLayerConfig, globe: Globe? = null
    ) {
        require(metersPerPixel > 0) {
            logMessage(ERROR, "WmsLayer", "setConfiguration", "invalidResolution")
        }
        val radiansPerPixel = metersPerPixel / (globe?.equatorialRadius ?: Ellipsoid.WGS84.semiMajorAxis)
        val levelsConfig = LevelSetConfig().apply {
            this.sector.copy(sector)
            numLevels = numLevelsForResolution(radiansPerPixel)
        }
        tiledSurfaceImage = TiledSurfaceImage(WmsTileFactory(config), LevelSet(levelsConfig))
    }
}