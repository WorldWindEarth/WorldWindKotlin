package earth.worldwind.layer.graticule.gk

import earth.worldwind.geom.Position
import earth.worldwind.geom.Vec3
import earth.worldwind.geom.coords.GKCoord
import earth.worldwind.render.RenderContext
import earth.worldwind.shape.Label

class GKMetricLabels(private val layer: GKGraticuleLayer) {
    private val xLabels = mutableMapOf<String, Label>()
    private val yLabels = mutableMapOf<String, Label>()
    private val removeLabel = mutableListOf<String>()

    fun selectRenderables(rc: RenderContext, scale: Int) {
        if (scale == 0 || xLabels.isEmpty() && yLabels.isEmpty()) return

        val (x, y) = labelLineIntersectionPoint(rc)

        for (label in xLabels) {
            if (shouldClean(rc, label.value ,scale)) removeLabel.add(label.key)
            else renderLabel(LABEL_TYPE_X_VALUE, label.value, x, y)
        }
        removeLabels(LABEL_TYPE_X_VALUE)

        for (label in yLabels) {
            if (shouldClean(rc, label.value ,scale)) removeLabel.add(label.key)
            else renderLabel(LABEL_TYPE_Y_VALUE, label.value, x, y)
        }
        removeLabels(LABEL_TYPE_Y_VALUE)
    }

    fun addLabel(label: Label) {
        val labelType = getLabelType(label) ?: return
        val id = getLabelId(label, labelType) ?: return

        if (labelType == LABEL_TYPE_X_VALUE) {
            if (!xLabels.contains(id)) xLabels[id] = label
        } else if (labelType == LABEL_TYPE_Y_VALUE) {
            if (!yLabels.contains(id)) yLabels[id] = label
        }
    }

    private fun getLabelType(label: Label) = label.getUserProperty<String>(LABEL_TYPE_KEY)

    private fun removeLabels(labelType: String) {
        if (labelType == LABEL_TYPE_X_VALUE) removeLabel.forEach { labelId -> xLabels.remove(labelId) }
        if (labelType == LABEL_TYPE_Y_VALUE) removeLabel.forEach { labelId -> yLabels.remove(labelId) }
        removeLabel.clear()
    }

    private fun getLabelId(label: Label, labelType:String): String? {
        val scale = label.getUserProperty<Int>(LABEL_SCALE_TYPE) ?: return null
        val coord = when (labelType) {
            LABEL_TYPE_X_VALUE -> label.getUserProperty<Double>(LABEL_X_KEY)
            LABEL_TYPE_Y_VALUE -> label.getUserProperty<Double>(LABEL_Y_KEY)
            else -> null
        } ?: return null
        val ew = if (label.position.longitude.inDegrees >= 0.0) "E" else "W"
        val ns = if (label.position.latitude.inDegrees >= 0.0) "N" else "S"
        return "$coord$ns$ew$scale"
    }

    private fun renderLabel(labelType: String, label: Label, x: Double, y: Double) {
        val scale = label.getUserProperty<Int>(LABEL_SCALE_TYPE) ?: return
        if (labelType == LABEL_TYPE_Y_VALUE) {
            val yLabel = label.getUserProperty<Double>(LABEL_Y_KEY) ?: return
            val point = GKCoord.fromXY(x,yLabel)
            if (checkIfOutOfZone(point, yLabel)) return
            label.position = layer.transformToWGS(Position(point.latitude, point.longitude, 0.0), label.position)
        } else if (labelType == LABEL_TYPE_X_VALUE) {
            val xLabel = label.getUserProperty<Double>(LABEL_X_KEY) ?: return
            val point = GKCoord.fromXY(xLabel,y)
            label.position = layer.transformToWGS(Position(point.latitude, point.longitude, 0.0), label.position)
        }
        layer.addRenderable(label, getTypeBy(scale))
    }

    private fun checkIfOutOfZone(point: GKCoord, yLabel: Double) =
        (yLabel / 1E6).toInt() != GKLayerHelper.getZone(point.longitude)

    private fun labelLineIntersectionPoint(rc: RenderContext): Pair<Double, Double> {
        val eastingOffset = rc.viewport.width * rc.pixelSize / 4
        val northOffset = rc.viewport.height * rc.pixelSize / 4
        val lookAtPosition = rc.lookAtPosition ?: rc.camera.position
        val centerGK = layer.transformFromWGS(
            Position(lookAtPosition.latitude, lookAtPosition.longitude, 0.0)
        )
        val centerXY = GKCoord.fromLatLon(centerGK.latitude, centerGK.longitude)
        return Pair(centerXY.x - northOffset, centerXY.y - eastingOffset)
    }

    private fun shouldClean(rc: RenderContext, label: Label, scale: Int) =
        isNotInScale(label, scale) || tooFar(rc, label.position, scale)

    private fun isNotInScale(label: Label, scale: Int) =
        scale == 0 || scale > (label.getUserProperty(LABEL_SCALE_TYPE) ?: 0)

    private fun tooFar(rc: RenderContext, position: Position, scale: Int) = rc.cameraPoint.distanceTo(
        rc.globe.geographicToCartesian(position.latitude, position.longitude, 0.0, Vec3())
    ) > getDistanceBy(scale)

    private fun getTypeBy(scale: Int) = if (scale == 1000) TYPE_LABEL_1000 else TYPE_LABEL_2000

    private fun getDistanceBy(scale: Int) = if (scale == 1000) layer.thresholdFor1kLabels else layer.thresholdFor2kLabels

    companion object {
        const val LABEL_TYPE_KEY = "label.name"
        const val LABEL_TYPE_X_VALUE = "X"
        const val LABEL_TYPE_Y_VALUE = "Y"
        const val LABEL_X_KEY = "label.x"
        const val LABEL_Y_KEY = "label.y"
        const val LABEL_SCALE_TYPE = "label.scale.type"
        const val TYPE_LABEL_1000 = "label.1000"
        const val TYPE_LABEL_2000 = "label.2000"
    }
}