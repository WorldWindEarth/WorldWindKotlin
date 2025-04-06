package earth.worldwind.shape.milstd2525

import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Location
import earth.worldwind.geom.Offset
import earth.worldwind.geom.Position
import earth.worldwind.geom.Sector
import earth.worldwind.render.Font
import earth.worldwind.render.Renderable
import earth.worldwind.shape.*
import earth.worldwind.shape.milstd2525.Font.Companion.getTypeString
import earth.worldwind.util.Logger

actual open class MilStd2525TacticalGraphic actual constructor(
    symbolID: String, locations: List<Location>,
    boundingSector: Sector, modifiers: Map<String, String>?, attributes: Map<String, String>?
) : AbstractMilStd2525TacticalGraphic(symbolID, boundingSector, modifiers, attributes) {
    protected lateinit var controlPoints: Array<Point2D>
    protected lateinit var pointUL: Point2D

    init {
        setAnchorLocations(locations)
    }

    fun setAnchorLocations(locations: List<Location>) {
        controlPoints = Array(locations.size) {
            val location = locations[it]
            Point2D(location.longitude.inDegrees, location.latitude.inDegrees)
        }
        val point0 = controlPoints[0]
        var left = point0.getX().toDouble()
        var top = point0.getY().toDouble()
        var right = point0.getX().toDouble()
        var bottom = point0.getY().toDouble()
        for (i in 1 until controlPoints.size) {
            val pt = controlPoints[i]
            if (pt.getX().toDouble() < left) left = pt.getX().toDouble()
            if (pt.getX().toDouble() > right) right = pt.getX().toDouble()
            if (pt.getY().toDouble() > top) top = pt.getY().toDouble()
            if (pt.getY().toDouble() < bottom) bottom = pt.getY().toDouble()
        }
        if (right - left > 180.0) {
            left = point0.getX().toDouble()
            for (i in 1 until controlPoints.size) {
                val pt = controlPoints[i]
                if (pt.getX().toDouble() > 0.0 && pt.getX().toDouble() < left) left = pt.getX().toDouble()
            }
        }
        if (this::pointUL.isInitialized) pointUL.setLocation(left, top) else pointUL = Point2D(left, top)
        reset()
    }

    actual override fun makeRenderables(scale: Double): List<Renderable> {
        val ipc = PointConverter3D(pointUL.getX(), pointUL.getY(), scale * 96.0 * 39.3700787)

//        // Calculate clipping rectangle
//        val leftTop = ipc.GeoToPixels(Point2D(boundingSector.minLongitude.degrees, boundingSector.maxLatitude.degrees))
//        val rightBottom = ipc.GeoToPixels(Point2D(boundingSector.maxLongitude.degrees, boundingSector.minLatitude.degrees))
//        val width = abs(rightBottom.getX().toDouble() - leftTop.getX().toDouble())
//        val height = abs(rightBottom.getY().toDouble() - leftTop.getY().toDouble())
//        val rect = if (width > 0 && height > 0) Rectangle2D(leftTop.getX(), leftTop.getY(), width, height) else null

        // Create MilStd Symbol and render it
        val mss = MilStdSymbol(symbolID, "", controlPoints, emptyMap())
        modifiers?.forEach { (key, value) ->
            when (val modifierKey = Modifiers.getModifierKey(key) ?: "") {
                Modifiers.AM_DISTANCE, Modifiers.AN_AZIMUTH, Modifiers.X_ALTITUDE_DEPTH -> {
                    val elements = value.split(",")
                    for (j in elements.indices) mss.setModifier(modifierKey, elements[j], j)
                }
                else -> mss.setModifier(modifierKey, value)
            }
        }
        attributes?.run {
            get(MilStdAttributes.AltitudeMode)?.let { mss.setAltitudeMode(it) }
            get(MilStdAttributes.AltitudeUnits)?.let { DistanceUnit.parse(it)?.let { mss.setAltitudeUnit(it) } }
            get(MilStdAttributes.DistanceUnits)?.let { DistanceUnit.parse(it)?.let { mss.setDistanceUnit(it) } }
        }
        clsRenderer.renderWithPolylines(mss, ipc, null as Rectangle? /*rect*/)

        // Create Renderables based on Poly-lines and Modifiers from Renderer
        val shapes = mutableListOf<Renderable>()
        val outlines = mutableListOf<Renderable>()
        for (i in 0 until mss.getSymbolShapes().size) convertShapeToRenderables(mss.getSymbolShapes()[i], mss, ipc, shapes, outlines)
        for (i in 0 until mss.getModifierShapes().size) convertShapeToRenderables(mss.getModifierShapes()[i], mss, ipc, shapes, outlines)
        return outlines + shapes
    }

    protected open fun convertShapeToRenderables(
        si: ShapeInfo, mss: MilStdSymbol, ipc: IPointConversion, shapes: MutableList<Renderable>, outlines: MutableList<Renderable>
    ) {
        when (si.getShapeType()) {
            ShapeInfo.SHAPE_TYPE_POLYLINE, ShapeInfo.SHAPE_TYPE_FILL -> {
                val shapeAttributes = ShapeAttributes().apply {
                    outlineWidth = MilStd2525.graphicsLineWidth
                    (si.getLineColor() ?: si.getFillColor())?.let { outlineColor = convertColor(it) } ?: return
                    (si.getFillColor() ?: si.getLineColor())?.let { interiorColor = convertColor(it) } ?: return
                }
                val hasOutline = MilStd2525.graphicsOutlineWidth != 0f
                val outlineAttributes = if (hasOutline) ShapeAttributes(shapeAttributes).apply {
                    (si.getLineColor() ?: si.getFillColor())?.let {
                        outlineColor = convertColor(RendererUtilities.getIdealOutlineColor(it).apply { setAlpha(0.5f) })
                    }
                    outlineWidth += MilStd2525.graphicsOutlineWidth * 2f
                } else shapeAttributes
                for (idx in 0 until si.getPolylines().size) {
                    val polyline = si.getPolylines()[idx]
                    val positions = mutableListOf<Position>()
                    for (p in 0 until polyline.size) {
                        val geoPoint = ipc.PixelsToGeo(polyline[p])
                        val position = Position.fromDegrees(geoPoint.getY().toDouble(), geoPoint.getX().toDouble(), 0.0)
                        positions.add(position)
                        sector.union(position) // Extend bounding box by real graphics measures
                    }
                    for (i in 0..1) {
                        val shape = if (si.getShapeType() == ShapeInfo.SHAPE_TYPE_FILL) {
                            Polygon(positions, if (i == 0) shapeAttributes else outlineAttributes)
                        } else {
                            Path(positions, if (i == 0) shapeAttributes else outlineAttributes)
                        }
                        applyShapeAttributes(shape)
                        if (i == 0) shapes += shape else outlines += shape
                        if (!hasOutline) break
                    }
                }
            }

            ShapeInfo.SHAPE_TYPE_MODIFIER, ShapeInfo.SHAPE_TYPE_MODIFIER_FILL -> {
                val textAttributes = TextAttributes().apply {
                    val renderSettings = RendererSettings.getInstance()
                    textColor = convertColor(mss.getLineColor())
                    textOffset = Offset.center()
                    font = Font(
                        size = renderSettings.getMPLabelFontSize().toInt(),
                        weight = getTypeString(renderSettings.getMPLabelFontType()),
                        family = renderSettings.getMPLabelFontName()
                    )
                    outlineColor = convertColor(RendererUtilities.getIdealOutlineColor(mss.getLineColor()))
                }
                val point = ipc.PixelsToGeo(si.getModifierStringPosition())
                val position = Position.fromDegrees(point.getY().toDouble(), point.getX().toDouble(), 0.0)
                sector.union(position) // Extend bounding box by real graphics measures
                val label = Label(position, si.getModifierString(), textAttributes)
                applyLabelAttributes(label, si.getModifierStringAngle().toDouble().degrees)
                shapes += label
            }
            else -> Logger.logMessage(Logger.ERROR, "MilStd2525TacticalGraphic", "convertShapeToRenderables", "unknownShapeType")
        }
    }

    protected companion object {
        fun convertColor(color: Color) = earth.worldwind.render.Color(
            color.getRed().toInt(),
            color.getGreen().toInt(),
            color.getBlue().toInt(),
            color.getAlpha().toInt()
        )
    }
}