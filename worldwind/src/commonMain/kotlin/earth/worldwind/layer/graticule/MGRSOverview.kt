package earth.worldwind.layer.graticule

import earth.worldwind.geom.Position
import earth.worldwind.geom.Position.Companion.fromDegrees
import earth.worldwind.geom.Sector
import earth.worldwind.geom.Sector.Companion.fromDegrees
import earth.worldwind.layer.graticule.GridElement.Companion.TYPE_LATITUDE_LABEL
import earth.worldwind.layer.graticule.GridElement.Companion.TYPE_LINE
import earth.worldwind.layer.graticule.GridElement.Companion.TYPE_LONGITUDE_LABEL
import earth.worldwind.layer.graticule.MGRSGraticuleLayer.Companion.MGRS_OVERVIEW_RESOLUTION
import earth.worldwind.render.RenderContext
import earth.worldwind.shape.Label
import earth.worldwind.shape.PathType

internal class MGRSOverview(layer: MGRSGraticuleLayer): AbstractGraticuleTile(layer, Sector()) {
    override fun selectRenderables(rc: RenderContext) {
        super.selectRenderables(rc)
        val labelPos = layer.computeLabelOffset(rc)
        for (ge in gridElements!!) {
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
        var lon = -180
        var zoneNumber = 1
        var maxLat: Int
        for (i in 0..59) {
            val longitude = lon.toDouble()
            // Meridian
            positions.clear()
            positions.add(fromDegrees(-80.0, longitude, 10e3))
            positions.add(fromDegrees(-60.0, longitude, 10e3))
            positions.add(fromDegrees(-30.0, longitude, 10e3))
            positions.add(fromDegrees(0.0, longitude, 10e3))
            positions.add(fromDegrees(30.0, longitude, 10e3))
            if (lon < 6 || lon > 36) {
                // 'regular' UTM meridians
                maxLat = 84
                positions.add(fromDegrees(60.0, longitude, 10e3))
            } else {
                // Exceptions: shorter meridians around and north-east of Norway
                if (lon == 6) {
                    maxLat = 56
                } else {
                    maxLat = 72
                    positions.add(fromDegrees(60.0, longitude, 10e3))
                }
            }
            positions.add(fromDegrees(maxLat.toDouble(), longitude, 10e3))
            val polyline = layer.createLineRenderable(positions.toList(), PathType.GREAT_CIRCLE)
            var sector = fromDegrees(-80.0, longitude, maxLat + 80.0, 1E-15)
            gridElements!!.add(GridElement(sector, polyline, TYPE_LINE))

            // Zone label
            val text = layer.createTextRenderable(
                fromDegrees(0.0, longitude + 3.0, 0.0),
                zoneNumber.toString(), 10e6
            )
            sector = fromDegrees(-90.0, longitude + 3.0, 180.0, 1E-15)
            gridElements!!.add(GridElement(sector, text, TYPE_LONGITUDE_LABEL))

            // Increase longitude and zone number
            lon += 6
            zoneNumber++
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
            val sector = fromDegrees(latitude1, longitude, latitude2 - latitude1, 1E-15)
            gridElements!!.add(GridElement(sector, polyline, TYPE_LINE))
        }

        // Generate parallels - no exceptions
        var lat = -80
        for (i in 0..20) {
            val latitude = lat.toDouble()
            for (j in 0..3) {
                // Each parallel is divided into four 90 degrees segments
                positions.clear()
                lon = -180 + j * 90
                val longitude = lon.toDouble()
                positions.add(fromDegrees(latitude, longitude, 10e3))
                positions.add(fromDegrees(latitude, longitude + 30, 10e3))
                positions.add(fromDegrees(latitude, longitude + 60, 10e3))
                positions.add(fromDegrees(latitude, longitude + 90, 10e3))
                val polyline = layer.createLineRenderable(ArrayList(positions), PathType.LINEAR)
                val sector = fromDegrees(latitude, longitude, 1E-15, 90.0)
                gridElements!!.add(GridElement(sector, polyline, TYPE_LINE))
            }
            // Latitude band label
            if (i < 20) {
                val text = layer.createTextRenderable(
                    fromDegrees(latitude + 4, 0.0, 0.0),
                    LAT_BANDS[i].toString(), 10e6
                )
                val sector = fromDegrees(latitude + 4, -180.0, 1E-15, 360.0)
                gridElements!!.add(GridElement(sector, text, TYPE_LATITUDE_LABEL))
            }

            // Increase latitude
            lat += if (lat < 72) 8 else 12
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