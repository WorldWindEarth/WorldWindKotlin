package earth.worldwind.layer.graticule.utm

import earth.worldwind.geom.Position
import earth.worldwind.geom.Position.Companion.fromDegrees
import earth.worldwind.geom.Sector
import earth.worldwind.geom.Sector.Companion.fromDegrees
import earth.worldwind.layer.graticule.AbstractGraticuleTile
import earth.worldwind.layer.graticule.GridElement
import earth.worldwind.layer.graticule.GridElement.Companion.TYPE_LATITUDE_LABEL
import earth.worldwind.layer.graticule.GridElement.Companion.TYPE_LINE
import earth.worldwind.layer.graticule.GridElement.Companion.TYPE_LONGITUDE_LABEL
import earth.worldwind.layer.graticule.utm.MGRSGraticuleLayer.Companion.MGRS_OVERVIEW_RESOLUTION
import earth.worldwind.render.RenderContext
import earth.worldwind.shape.Label
import earth.worldwind.shape.PathType

internal class MGRSOverview(layer: MGRSGraticuleLayer): AbstractGraticuleTile(layer, Sector()) {
    override fun selectRenderables(rc: RenderContext) {
        super.selectRenderables(rc)
        val labelPos = layer.computeLabelOffset(rc)
        for (ge in gridElements) {
            if (ge.isInView(rc)) {
                if (ge.renderable is Label) {
                    val gt = ge.renderable
                    if (labelPos.latitude.inDegrees < 72 || !"*32*34*36*".contains("*" + gt.text + "*")) {
                        // Adjust label position according to eye position
                        var pos = gt.position
                        if (ge.type == TYPE_LATITUDE_LABEL) pos = Position(pos.latitude, labelPos.longitude, pos.altitude)
                        else if (ge.type == TYPE_LONGITUDE_LABEL) pos = Position(labelPos.latitude, pos.longitude, pos.altitude)
                        gt.position = pos
                    }
                }
                layer.addRenderable(ge.renderable, layer.getTypeFor(MGRS_OVERVIEW_RESOLUTION))
            }
        }
    }

    override fun createRenderables() {
        super.createRenderables()
        val positions = mutableListOf<Position>()

        // Generate meridians and zone labels
        for (zoneNumber in 1 .. 60) {
            val longitude = -180.0 + zoneNumber * 6.0
            // Meridian
            positions.clear()
            positions.add(fromDegrees(-80.0, longitude, 10e3))
            positions.add(fromDegrees(-60.0, longitude, 10e3))
            positions.add(fromDegrees(-30.0, longitude, 10e3))
            positions.add(fromDegrees(0.0, longitude, 10e3))
            positions.add(fromDegrees(30.0, longitude, 10e3))
            val maxLat = if (longitude < 6.0 || longitude > 36.0) {
                // 'regular' UTM meridians
                positions.add(fromDegrees(60.0, longitude, 10e3))
                84.0
            } else {
                // Exceptions: shorter meridians around and north-east of Norway
                if (longitude == 6.0) {
                    56.0
                } else {
                    positions.add(fromDegrees(60.0, longitude, 10e3))
                    72.0
                }
            }
            positions.add(fromDegrees(maxLat, longitude, 10e3))
            val polyline = layer.createLineRenderable(positions.toList(), PathType.GREAT_CIRCLE)
            var sector = fromDegrees(-80.0, longitude, maxLat + 80.0, 0.0)
            gridElements.add(GridElement(sector, polyline, TYPE_LINE))

            // Zone label
            val text = layer.createTextRenderable(
                fromDegrees(0.0, longitude - 3.0, 0.0),
                zoneNumber.toString(), 10e6
            )
            sector = fromDegrees(-90.0, longitude - 3.0, 180.0, 0.0)
            gridElements.add(GridElement(sector, text, TYPE_LONGITUDE_LABEL))
        }

        // Generate special meridian segments for exceptions around and north-east of Norway
        for (i in 0..4) {
            positions.clear()
            val longitude = SPECIAL_MERIDIANS[i][0].toDouble()
            val latitude1 = SPECIAL_MERIDIANS[i][1].toDouble()
            val latitude2 = SPECIAL_MERIDIANS[i][2].toDouble()
            positions.add(fromDegrees(latitude1, longitude, 10e3))
            positions.add(fromDegrees(latitude2, longitude, 10e3))
            val polyline = layer.createLineRenderable(ArrayList(positions), PathType.GREAT_CIRCLE)
            val sector = fromDegrees(latitude1, longitude, latitude2 - latitude1, 0.0)
            gridElements.add(GridElement(sector, polyline, TYPE_LINE))
        }

        // Generate parallels - no exceptions
        var latitude = -80.0
        for (i in 0..20) {
            for (j in 0..3) {
                // Each parallel is divided into four 90 degrees segments
                positions.clear()
                val longitude = -180.0 + j * 90.0
                positions.add(fromDegrees(latitude, longitude, 10e3))
                positions.add(fromDegrees(latitude, longitude + 30.0, 10e3))
                positions.add(fromDegrees(latitude, longitude + 60.0, 10e3))
                positions.add(fromDegrees(latitude, longitude + 90.0, 10e3))
                val polyline = layer.createLineRenderable(ArrayList(positions), PathType.LINEAR)
                val sector = fromDegrees(latitude, longitude, 0.0, 90.0)
                gridElements.add(GridElement(sector, polyline, TYPE_LINE))
            }
            // Latitude band label
            if (i < 20) {
                val text = layer.createTextRenderable(
                    fromDegrees(latitude + 4.0, 0.0, 0.0),
                    LAT_BANDS[i].toString(), 10e6
                )
                val sector = fromDegrees(latitude + 4.0, -180.0, 0.0, 360.0)
                gridElements.add(GridElement(sector, text, TYPE_LATITUDE_LABEL))
            }

            // Increase latitude
            latitude += if (latitude < 72.0) 8.0 else 12.0
        }
    }

    companion object {
        // Exceptions for some meridians. Values: longitude, min latitude, max latitude
        private val SPECIAL_MERIDIANS = arrayOf(
            intArrayOf(3, 56, 64), intArrayOf(6, 64, 72), intArrayOf(9, 72, 84), intArrayOf(21, 72, 84), intArrayOf(33, 72, 84)
        )

        // Latitude bands letters - from south to north
        private const val LAT_BANDS = "CDEFGHJKLMNPQRSTUVWX"
    }
}