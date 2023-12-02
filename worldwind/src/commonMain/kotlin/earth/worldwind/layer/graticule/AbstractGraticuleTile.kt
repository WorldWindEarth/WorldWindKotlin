package earth.worldwind.layer.graticule

import earth.worldwind.geom.Sector
import earth.worldwind.geom.Sector.Companion.fromDegrees
import earth.worldwind.geom.Vec3
import earth.worldwind.render.RenderContext
import earth.worldwind.util.AbstractTile

abstract class AbstractGraticuleTile(open val layer: AbstractGraticuleLayer, sector: Sector) : AbstractTile(sector) {
    val gridElements = mutableListOf<GridElement>()
    /**
     * Flag to avoid recursive renderables creation if tile should not have elements by design
     */
    private var shouldCreateRenderables = true
    private val scratchVector = Vec3()

    open fun isInView(rc: RenderContext) = intersectsSector(rc.terrain.sector) && intersectsFrustum(rc) && isFacingCamera(rc)

    open fun getSizeInPixels(rc: RenderContext): Double {
        val centerPoint = layer.getSurfacePoint(rc, sector.centroidLatitude, sector.centroidLongitude)
        val distance = rc.cameraPoint.distanceTo(centerPoint)
        val tileSizeMeter = sector.deltaLatitude.inRadians * rc.globe.equatorialRadius
        return tileSizeMeter / rc.pixelSizeAtDistance(distance) / rc.densityFactor
    }

    open fun selectRenderables(rc: RenderContext) {
        if (shouldCreateRenderables && gridElements.isEmpty()) createRenderables()
    }

    open fun clearRenderables() {
        gridElements.clear()
        shouldCreateRenderables = true
    }

    open fun createRenderables() {
        shouldCreateRenderables = false
    }

    fun subdivide(div: Int, sector: Sector = this.sector): List<Sector> {
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

    protected open fun isFacingCamera(rc: RenderContext) = if (rc.globe.is2D) true else {
        val viewingVector = nearestPoint(rc).subtract(rc.cameraPoint)
        val normalVector = rc.globe.geographicToCartesianNormal(sector.centroidLatitude, sector.centroidLongitude, scratchVector)
        val dot = viewingVector.dot(normalVector)
        dot / (viewingVector.magnitude * normalVector.magnitude) < 0.0
    }
}