package earth.worldwind.layer.graticule.utm

import earth.worldwind.geom.Position
import earth.worldwind.geom.Vec3
import earth.worldwind.geom.coords.Hemisphere
import earth.worldwind.geom.coords.UPSCoord
import earth.worldwind.geom.coords.UTMCoord
import earth.worldwind.layer.graticule.GridElement
import earth.worldwind.layer.graticule.GridElement.Companion.TYPE_LINE_EAST
import earth.worldwind.layer.graticule.GridElement.Companion.TYPE_LINE_EASTING
import earth.worldwind.layer.graticule.GridElement.Companion.TYPE_LINE_NORTH
import earth.worldwind.layer.graticule.GridElement.Companion.TYPE_LINE_NORTHING
import earth.worldwind.layer.graticule.GridElement.Companion.TYPE_LINE_SOUTH
import earth.worldwind.layer.graticule.GridElement.Companion.TYPE_LINE_WEST
import earth.worldwind.layer.graticule.utm.AbstractUTMGraticuleLayer.Companion.UTM_MAX_LATITUDE
import earth.worldwind.layer.graticule.utm.AbstractUTMGraticuleLayer.Companion.UTM_MIN_LATITUDE
import earth.worldwind.render.RenderContext
import kotlin.math.log10
import kotlin.math.pow

internal class UTMMetricScaleSupport(private val layer: AbstractUTMGraticuleLayer) {
    private class UTMExtremes {
        var minX = 1e6
        var maxX = 0.0
        var minY = 10e6
        var maxY = 0.0
        var minYHemisphere = Hemisphere.N
        var maxYHemisphere = Hemisphere.S
    }

    var scaleModulo = 10000000
    var maxResolution = 1e5
        set(value) {
            field = value
            clear()
        }
    var zone = 0
        private set

    // 5 levels 100km to 10m
    private lateinit var extremes: Array<UTMExtremes>

    fun computeZone(rc: RenderContext) {
        try {
            if (layer.hasLookAtPos(rc)) {
                val latitude = layer.getLookAtLatitude(rc)
                val longitude = layer.getLookAtLongitude(rc)
                zone = if (latitude.inDegrees in UTM_MIN_LATITUDE..UTM_MAX_LATITUDE) {
                    val utm = UTMCoord.fromLatLon(latitude, longitude)
                    utm.zone
                } else 0
            }
        } catch (ex: Exception) {
            zone = 0
        }
    }

    fun clear() {
        val numLevels = log10(maxResolution).toInt()
        extremes = Array(numLevels) { UTMExtremes() }
    }

    fun computeMetricScaleExtremes(UTMZone: Int, hemisphere: Hemisphere, ge: GridElement, size: Double) {
        if (UTMZone != zone) return
        if (size < 1 || size > maxResolution) return
        val levelExtremes = extremes[log10(size).toInt() - 1]
        if (ge.type == TYPE_LINE_EASTING || ge.type == TYPE_LINE_EAST || ge.type == TYPE_LINE_WEST) {
            levelExtremes.minX = ge.value.inDegrees.coerceAtMost(levelExtremes.minX)
            levelExtremes.maxX = ge.value.inDegrees.coerceAtLeast(levelExtremes.maxX)
        } else if (ge.type == TYPE_LINE_NORTHING || ge.type == TYPE_LINE_SOUTH || ge.type == TYPE_LINE_NORTH) {
            if (hemisphere == levelExtremes.minYHemisphere) {
                levelExtremes.minY = ge.value.inDegrees.coerceAtMost(levelExtremes.minY)
            } else if (hemisphere == Hemisphere.S) {
                levelExtremes.minY = ge.value.inDegrees
                levelExtremes.minYHemisphere = hemisphere
            }
            if (hemisphere == levelExtremes.maxYHemisphere) {
                levelExtremes.maxY = ge.value.inDegrees.coerceAtLeast(levelExtremes.maxY)
            } else if (hemisphere == Hemisphere.N) {
                levelExtremes.maxY = ge.value.inDegrees
                levelExtremes.maxYHemisphere = hemisphere
            }
        }
    }

    fun selectRenderables(rc: RenderContext) {
        if (!layer.hasLookAtPos(rc)) return

        // Compute easting and northing label offsets
        val pixelSize = layer.getPixelSize(rc)
        val eastingOffset = rc.viewport.width * pixelSize * OFFSET_FACTOR_X / 2
        val northingOffset = rc.viewport.height * pixelSize * OFFSET_FACTOR_Y / 2
        // Derive labels center pos from the view center
        val labelEasting: Double
        var labelNorthing: Double
        var labelHemisphere: Hemisphere
        if (zone > 0) {
            val utm = UTMCoord.fromLatLon(layer.getLookAtLatitude(rc), layer.getLookAtLongitude(rc))
            labelEasting = utm.easting + eastingOffset
            labelNorthing = utm.northing + northingOffset
            labelHemisphere = utm.hemisphere
            if (labelNorthing < 0) {
                labelNorthing += 10e6
                labelHemisphere = Hemisphere.S
            }
        } else {
            val ups = UPSCoord.fromLatLon(layer.getLookAtLatitude(rc), layer.getLookAtLongitude(rc))
            labelEasting = ups.easting + eastingOffset
            labelNorthing = ups.northing + northingOffset
            labelHemisphere = ups.hemisphere
        }
        val viewFrustum = rc.frustum
        var labelPos: Position?
        for (i in extremes.indices) {
            val levelExtremes = extremes[i]
            val gridStep = 10.0.pow(i.toDouble())
            val gridStepTimesTen = gridStep * 10
            val graticuleType = layer.getTypeFor(gridStep)
            if (levelExtremes.minX <= levelExtremes.maxX) {
                // Process easting scale labels for this level
                var easting = levelExtremes.minX
                while (easting <= levelExtremes.maxX) {
                    // Skip multiples of ten grid steps except for last (higher) level
                    if (i == extremes.size - 1 || easting % gridStepTimesTen != 0.0) {
                        labelPos = layer.computePosition(zone, labelHemisphere, easting, labelNorthing)
                        val lat = labelPos.latitude
                        val lon = labelPos.longitude
                        val surfacePoint = layer.getSurfacePoint(rc, lat, lon)
                        if (viewFrustum.containsPoint(surfacePoint) && isPointInRange(rc, surfacePoint)) {
                            val text = (easting % scaleModulo).toInt().toString()
                            val gt = layer.createTextRenderable(
                                Position(lat, lon, 0.0), text, gridStepTimesTen
                            )
                            layer.addRenderable(gt, graticuleType)
                        }
                    }
                    easting += gridStep
                }
            }
            if (!(levelExtremes.maxYHemisphere == Hemisphere.S && levelExtremes.maxY == 0.0)) {
                // Process northing scale labels for this level
                var currentHemisphere = levelExtremes.minYHemisphere
                var northing = levelExtremes.minY
                while (northing <= levelExtremes.maxY || currentHemisphere != levelExtremes.maxYHemisphere) {
                    // Skip multiples of ten grid steps except for last (higher) level
                    if (i == extremes.size - 1 || northing % gridStepTimesTen != 0.0) {
                        labelPos = layer.computePosition(zone, currentHemisphere, labelEasting, northing)
                        val lat = labelPos.latitude
                        val lon = labelPos.longitude
                        val surfacePoint = layer.getSurfacePoint(rc, lat, lon)
                        if (viewFrustum.containsPoint(surfacePoint) && isPointInRange(rc, surfacePoint)) {
                            val text: String = (northing % scaleModulo).toInt().toString()
                            val gt = layer.createTextRenderable(
                                Position(lat, lon, 0.0), text, gridStepTimesTen
                            )
                            layer.addRenderable(gt, graticuleType)
                        }
                        if (currentHemisphere != levelExtremes.maxYHemisphere && northing >= 10e6 - gridStep) {
                            // Switch hemisphere
                            currentHemisphere = levelExtremes.maxYHemisphere
                            northing = -gridStep
                        }
                    }
                    northing += gridStep
                }
            }
        }
    }

    private fun isPointInRange(rc: RenderContext, point: Vec3): Boolean {
        val altitudeAboveGround = layer.computeAltitudeAboveGround(rc)
        return rc.cameraPoint.distanceTo(point) < altitudeAboveGround * VISIBLE_DISTANCE_FACTOR
    }

    override fun toString(): String {
        val sb = StringBuilder()
        for (i in 0..4) {
            sb.append("level ")
            sb.append(i)
            sb.append(" : ")
            val levelExtremes = extremes[i]
            if (levelExtremes.minX < levelExtremes.maxX ||
                !(levelExtremes.maxYHemisphere == Hemisphere.S && levelExtremes.maxY == 0.0)
            ) {
                sb.append(levelExtremes.minX)
                sb.append(", ")
                sb.append(levelExtremes.maxX)
                sb.append(" - ")
                sb.append(levelExtremes.minY)
                sb.append(levelExtremes.minYHemisphere)
                sb.append(", ")
                sb.append(levelExtremes.maxY)
                sb.append(levelExtremes.maxYHemisphere)
            } else {
                sb.append("empty")
            }
            sb.append("\n")
        }
        return sb.toString()
    }

    companion object {
        private const val OFFSET_FACTOR_X = -.5
        private const val OFFSET_FACTOR_Y = -.5
        private const val VISIBLE_DISTANCE_FACTOR = 10.0
    }
}