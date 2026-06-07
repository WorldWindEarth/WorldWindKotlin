package earth.worldwind.shape.milstd2525

import earth.worldwind.geom.Location
import earth.worldwind.geom.Offset
import earth.worldwind.geom.Sector
import earth.worldwind.render.RenderContext
import earth.worldwind.render.Renderable
import earth.worldwind.render.image.ImageSource
import earth.worldwind.shape.ShapeAttributes
import earth.worldwind.shape.TextAttributes

actual open class MilStd2525TacticalGraphic actual constructor(
    symbolID: String, locations: List<Location>,
    boundingSector: Sector, modifiers: Map<String, String>?, attributes: Map<String, String>?
) : AbstractMilStd2525TacticalGraphic(symbolID, boundingSector, modifiers, attributes) {
    protected var controlPoints: List<Location> = emptyList()
    protected var pointULLon = 0.0
    protected var pointULLat = 0.0

    init {
        setAnchorLocations(locations)
    }

    fun setAnchorLocations(locations: List<Location>) {
        controlPoints = locations
        val point0 = locations[0]
        var left = point0.longitude.inDegrees
        var top = point0.latitude.inDegrees
        var right = left
        for (i in 1 until locations.size) {
            val lon = locations[i].longitude.inDegrees
            val lat = locations[i].latitude.inDegrees
            if (lon < left) left = lon
            if (lon > right) right = lon
            if (lat > top) top = lat
        }
        // Handle anti-meridian spanning: pick the least positive longitude as the left edge.
        if (right - left > 180.0) {
            left = point0.longitude.inDegrees
            for (i in 1 until locations.size) {
                val lon = locations[i].longitude.inDegrees
                if (lon > 0.0 && lon < left) left = lon
            }
        }
        pointULLon = left
        pointULLat = top
        reset()
    }

    actual override fun makeRenderables(rc: RenderContext, scale: Double): List<Renderable> {
        val shapes = mutableListOf<Renderable>()
        val outlines = mutableListOf<Renderable>()
        // The per-target renderer builds & rasterizes the MilStdSymbol and hands back target-agnostic
        // shapes (geo-converted); here we only turn those into WorldWind Renderables.
        val rendered = MilStd2525.renderTacticalGraphic(
            symbolID, controlPoints, pointULLon, pointULLat, scale, rc.densityFactor,
            modifiers, attributes
        )
        for (shape in rendered) when (shape) {
            is MilStd2525Polyline -> {
                val shapeAttributes = ShapeAttributes().apply {
                    outlineWidth = shape.lineWidth
                    shape.lineColor?.let { outlineColor = it }
                    shape.fillColor?.let { interiorColor = it }
                    isPickInterior = false // Allow picking outline only
                    shape.patternFillCanvas?.let { interiorImageSource = ImageSource.fromImage(it) }
                    shape.dashFactor?.let {
                        // TODO How to correctly interpret dash array?
                        outlineImageSource = ImageSource.fromLineStipple(factor = it, pattern = 0xF0F0.toShort())
                    }
                }
                val enclosed = shape.patternFillCanvas != null
                val hasOutline = graphicsOutlineWidth != 0f && (isHighlighted || !isOutlineHighlightedOnly)
                val outlineAttributes = if (hasOutline) ShapeAttributes(shapeAttributes).apply {
                    shape.lineColor?.let { outlineColor = graphicsOutlineColor ?: shape.idealOutlineColor ?: it }
                    outlineWidth += graphicsOutlineWidth * 2f
                } else shapeAttributes
                for (polyline in shape.polylines) {
                    for (position in polyline) sector.union(position) // Extend bounding box by real graphics measures
                    for (i in 0..1) {
                        if (i == 0) shapes += createShape(polyline, shapeAttributes, enclosed)
                        else outlines += createShape(polyline, outlineAttributes, enclosed)
                        if (!hasOutline) break
                    }
                }
            }

            is MilStd2525ModifierImage -> {
                sector.union(shape.position) // Extend bounding box by real graphics measures
                shapes += createPlacemark(shape.position, ImageSource.fromImage(shape.canvas), shape.text, shape.angle)
            }

            is MilStd2525ModifierLabel -> {
                sector.union(shape.position) // Extend bounding box by real graphics measures
                val textAttributes = TextAttributes().apply {
                    shape.textColor?.let { textColor = it }
                    shape.idealOutlineColor?.let { outlineColor = it }
                    textOffset = Offset.center()
                    font = shape.mpLabelFont
                }
                shapes += createLabel(shape.position, textAttributes, shape.text, shape.angle)
            }
        }
        return outlines + shapes
    }
}
