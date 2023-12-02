package earth.worldwind.layer.graticule.gk

import earth.worldwind.geom.Position
import earth.worldwind.geom.Position.Companion.fromDegrees
import earth.worldwind.geom.Sector
import earth.worldwind.geom.Sector.Companion.fromDegrees
import earth.worldwind.layer.graticule.AbstractGraticuleTile
import earth.worldwind.layer.graticule.GridElement
import earth.worldwind.layer.graticule.GridElement.Companion.TYPE_LATITUDE_LABEL
import earth.worldwind.layer.graticule.GridElement.Companion.TYPE_LINE
import earth.worldwind.layer.graticule.GridElement.Companion.TYPE_LONGITUDE_LABEL
import earth.worldwind.layer.graticule.gk.GKGraticuleLayer.Companion.MILLION_COOL_NAME
import earth.worldwind.render.RenderContext
import earth.worldwind.shape.Label
import earth.worldwind.shape.PathType

internal class GKOverview(layer: GKGraticuleLayer): AbstractGraticuleTile(layer, Sector().setFullSphere()) {
    override fun selectRenderables(rc: RenderContext) {
        super.selectRenderables(rc)
        val labelPos = layer.computeLabelOffset(rc)
        for (ge in gridElements) {
            if (ge.isInView(rc)) {
                if (ge.renderable is Label) {
                    val gt = ge.renderable
                    if (labelPos.latitude.inDegrees < 72 || !"*32*34*36*".contains("*" + gt.text + "*")) {
                        // Adjust label position according to eye position
                        val pos = gt.position
                        if (ge.type == TYPE_LATITUDE_LABEL) gt.position = Position(pos.latitude, labelPos.longitude, pos.altitude)
                        else if (ge.type == TYPE_LONGITUDE_LABEL) gt.position = Position(labelPos.latitude, pos.longitude, pos.altitude)
                    }
                }
                layer.addRenderable(ge.renderable, GKGraticuleLayer.GRATICULE_GK_OVERVIEW)
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
            positions.add(fromDegrees(-88.0, longitude, 0.0))
            positions.add(fromDegrees(-60.0, longitude, 0.0))
            positions.add(fromDegrees(-30.0, longitude, 0.0))
            positions.add(fromDegrees(0.0, longitude, 0.0))
            positions.add(fromDegrees(30.0, longitude, 0.0))
            positions.add(fromDegrees(60.0, longitude, 0.0))
            positions.add(fromDegrees(88.0, longitude, 0.0))
            val polyline = layer.createLineRenderable(positions.toList(), PathType.GREAT_CIRCLE)
            var sector = fromDegrees(-88.0, longitude,  176.0, 30.0)
            gridElements.add(GridElement(sector, polyline, TYPE_LINE))
            // Zone label
            val text = layer.createTextRenderable(
                fromDegrees(0.0, longitude - 3.0, 0.0), zoneNumber.toString(), 10e6
            )
            text.attributes.isOutlineEnabled = false
            sector = fromDegrees(-90.0, longitude - 3.0, 180.0, 0.0)
            gridElements.add(GridElement(sector, text, TYPE_LONGITUDE_LABEL))
        }

        // Generate parallels
        for (i in 0..45) {
            val latitude = -92.0 + i * 4.0
            // don't need parallel for firs and last
            if(i != 0 || i != 46 ) {
                for (j in 0..3) {
                    // Each parallel is divided into four 90 degrees segments
                    positions.clear()
                    val longitude = -180.0 + j * 90.0
                    positions.add(fromDegrees(latitude, longitude, 0.0))
                    positions.add(fromDegrees(latitude, longitude + 30.0, 0.0))
                    positions.add(fromDegrees(latitude, longitude + 60.0, 0.0))
                    if(j == 3 ) positions.add(fromDegrees(latitude, 180.0, 0.0))
                    else positions.add(fromDegrees(latitude, longitude + 90.0, 0.0))

                    val polyline = layer.createLineRenderable(ArrayList(positions), PathType.LINEAR)
                    val sector = fromDegrees(latitude, longitude, 3.0, 90.0)
                    gridElements.add(GridElement(sector, polyline, TYPE_LINE))
                }
            }
            // Latitude band label
            val text = layer.createTextRenderable(
                fromDegrees(latitude + 2, 0.0, 0.0), MILLION_COOL_NAME[i],10e6
            )
            text.attributes.isOutlineEnabled = false
            val sector = fromDegrees(latitude + 2, -180.0, 3.0, 360.0)
            gridElements.add(GridElement(sector, text, TYPE_LATITUDE_LABEL))
        }
    }
}