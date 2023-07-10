package earth.worldwind.layer.graticule

import earth.worldwind.geom.Sector
import earth.worldwind.geom.Sector.Companion.fromDegrees
import earth.worldwind.render.RenderContext
import earth.worldwind.util.AbstractTile

abstract class AbstractGraticuleTile(open val layer: AbstractGraticuleLayer, sector: Sector) : AbstractTile(sector) {
    var gridElements: MutableList<GridElement>? = null
        private set

    open fun isInView(rc: RenderContext) = intersectsSector(rc.terrain.sector) && intersectsFrustum(rc)

    open fun getSizeInPixels(rc: RenderContext): Double {
        val centerPoint = layer.getSurfacePoint(rc, sector.centroidLatitude, sector.centroidLongitude)
        val distance = rc.cameraPoint.distanceTo(centerPoint)
        val tileSizeMeter = sector.deltaLatitude.inRadians * rc.globe.equatorialRadius
        return tileSizeMeter / rc.pixelSizeAtDistance(distance) / rc.densityFactor
    }

    open fun selectRenderables(rc: RenderContext) {
        gridElements ?: createRenderables()
    }

    open fun clearRenderables() {
        gridElements?.clear()
        gridElements = null
    }

    open fun createRenderables() {
        gridElements = mutableListOf()
    }

    fun subdivide(div: Int): List<Sector> {
        val dLat = sector.deltaLatitude.inDegrees / div
        val dLon = sector.deltaLongitude.inDegrees / div
        val sectors = mutableListOf<Sector>()
        for (row in 0 until div) {
            for (col in 0 until div) {
                sectors += fromDegrees(
                    sector.minLatitude.inDegrees + dLat * row,
                    sector.minLongitude.inDegrees + dLon * col, dLat, dLon
                )
            }
        }
        return sectors
    }
}