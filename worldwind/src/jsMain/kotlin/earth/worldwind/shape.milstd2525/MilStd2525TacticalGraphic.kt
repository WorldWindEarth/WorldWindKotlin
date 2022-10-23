package earth.worldwind.shape.milstd2525

import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Location
import earth.worldwind.geom.Position
import earth.worldwind.geom.Sector
import earth.worldwind.render.Font
import earth.worldwind.render.Renderable
import earth.worldwind.shape.*
import earth.worldwind.util.Logger

actual open class MilStd2525TacticalGraphic actual constructor(
    sidc: String, locations: List<Location>,
    boundingSector: Sector, modifiers: Map<String, String>?, attributes: Map<String, String>?
) : AbstractMilStd2525TacticalGraphic(sidc, locations, boundingSector, modifiers, attributes) {
    protected lateinit var controlPoints: java.util.ArrayList<armyc2.c2sd.graphics2d.Point2D>
    protected lateinit var pointUL: armyc2.c2sd.graphics2d.Point2D

    protected companion object {
        fun convertColor(color: Color) = earth.worldwind.render.Color(
            color.getRed().toInt(),
            color.getGreen().toInt(),
            color.getBlue().toInt(),
            color.getAlpha().toInt()
        )
    }

    override fun transformLocations(locations: List<Location>) {
        if (this::controlPoints.isInitialized) controlPoints.clear() else controlPoints = java.util.ArrayList()
        for (location in locations) controlPoints.add(armyc2.c2sd.graphics2d.Point2D(location.longitude.inDegrees, location.latitude.inDegrees))
        val point0 = controlPoints.get(0) ?: return
        var left = point0.getX().toDouble()
        var top = point0.getY().toDouble()
        var right = point0.getX().toDouble()
        var bottom = point0.getY().toDouble()
        for (i in 1 until controlPoints.size()) {
            val pt = controlPoints.get(i) ?: continue
            if (pt.getX().toDouble() < left) left = pt.getX().toDouble()
            if (pt.getX().toDouble() > right) right = pt.getX().toDouble()
            if (pt.getY().toDouble() > top) top = pt.getY().toDouble()
            if (pt.getY().toDouble() < bottom) bottom = pt.getY().toDouble()
        }
        if (right - left > 180.0) {
            left = point0.getX().toDouble()
            for (i in 1 until controlPoints.size()) {
                val pt = controlPoints.get(i)!!
                if (pt.getX().toDouble() > 0.0 && pt.getX().toDouble() < left) left = pt.getX().toDouble()
            }
        }
        if (this::pointUL.isInitialized) pointUL.setLocation(left, top) else pointUL = armyc2.c2sd.graphics2d.Point2D(left, top)
        pointUL.setLocation(left, top)
    }

    override fun makeRenderables(scale: Double, shapes: MutableList<Renderable>) {
        val ipc = PointConverter3D(pointUL.getX(), pointUL.getY(), scale * 96.0 * 39.3700787)

//        // Calculate clipping rectangle
//        val leftTop = ipc.GeoToPixels(armyc2.c2sd.graphics2d.Point2D(boundingSector.minLongitude.degrees, boundingSector.maxLatitude.degrees))
//        val rightBottom = ipc.GeoToPixels(armyc2.c2sd.graphics2d.Point2D(boundingSector.maxLongitude.degrees, boundingSector.minLatitude.degrees))
//        val width = abs(rightBottom.getX().toDouble() - leftTop.getX().toDouble())
//        val height = abs(rightBottom.getY().toDouble() - leftTop.getY().toDouble())
//        val rect = if (width > 0 && height > 0) armyc2.c2sd.graphics2d.Rectangle2D(leftTop.getX(), leftTop.getY(), width, height) else null

        // Create MilStd Symbol and render it
        val mss = MilStdSymbol(sidc, null, controlPoints, null)
        modifiers?.forEach { (key, value) ->
            when (key) {
                ModifiersTG.AM_DISTANCE, ModifiersTG.AN_AZIMUTH, ModifiersTG.X_ALTITUDE_DEPTH -> {
                    val elements = value.split(",")
                    for (j in elements.indices) mss.setModifier(key, elements[j], j)
                }
                else -> mss.setModifier(key, value)
            }
        }
        attributes?.run {
            mss.setAltitudeMode(get(MilStdAttributes.AltitudeMode))
            // mss.setAltitudeUnit(DistanceUnit.parse(get(MilStdAttributes.AltitudeUnits)))
            // mss.setDistanceUnit(DistanceUnit.parse(get(MilStdAttributes.DistanceUnits)))
        }
        armyc2.c2sd.JavaRendererServer.RenderMultipoints.clsRenderer.renderWithPolylines(mss, ipc, null /*rect*/)

        // Create Renderables based on Poly-lines and Modifiers from Renderer
        for (shape in mss.getSymbolShapes().getArray()) convertShapeToRenderables(shape, mss, ipc, shapes)
        for (shape in mss.getModifierShapes().getArray()) convertShapeToRenderables(shape, mss, ipc, shapes)
    }

    protected open fun convertShapeToRenderables(
        shape: ShapeInfo, mss: MilStdSymbol, ipc: IPointConversion, shapes: MutableList<Renderable>
    ) {
        when (shape.getShapeType()) {
            ShapeInfo.SHAPE_TYPE_POLYLINE -> {
                val shapeAttributes = ShapeAttributes().apply {
                    outlineWidth = MilStd2525.GRAPHICS_OUTLINE_WIDTH
                    shape.getLineColor()?.let { outlineColor = convertColor(it) }
                    shape.getFillColor()?.let { interiorColor = convertColor(it) }
                    // TODO Fill dash pattern
//                    val stroke = shape.getStroke()
//                    if (stroke is armyc2.c2sd.graphics2d.BasicStroke) {
//                        val dash = stroke.getDashArray()
//                        if (!dash.isNullOrEmpty()) outlineImageSource = ImageSource.fromLineStipple(
//                            // TODO How to correctly interpret dash array?
//                            factor = dash[0].roundToInt(), pattern = 0xF0F0.toShort()
//                        )
//                    }
                }

                for (polyline in shape.getPolylines().toArray()) {
                    val positions = mutableListOf<Position>()
                    for (point in polyline.toArray()) {
                        val geoPoint = ipc.PixelsToGeo(point)
                        positions.add(Position.fromDegrees(geoPoint.getY().toDouble(), geoPoint.getX().toDouble(), 0.0))
                    }
                    val path = Path(positions, shapeAttributes).apply {
                        altitudeMode = AltitudeMode.CLAMP_TO_GROUND
                        isFollowTerrain = true
                        maximumIntermediatePoints = 0 // Do not draw intermediate vertices for tactical graphics
                        highlightAttributes = ShapeAttributes(shapeAttributes).apply {
//                            outlineColor = Color(1f, 1f, 1f, 1f)
                            outlineWidth *= HIGHLIGHT_FACTOR
                        }
                    }
                    path.pickDelegate = this
                    shapes.add(path)
                }
            }

            ShapeInfo.SHAPE_TYPE_MODIFIER_FILL -> {
                val textAttributes = TextAttributes().apply {
                    val fontInfo = RendererSettings.getMPFontInfo()
                    textColor = convertColor(mss.getLineColor())
                    font = Font(
                        size = fontInfo.size as Int,
                        weight = fontInfo.style as String,
                        family = fontInfo.name as String
                    )
                }
                val point = ipc.PixelsToGeo(shape.getModifierStringPosition() ?: shape.getGlyphPosition())
                val label = Label(
                    Position.fromDegrees(point.getY().toDouble(), point.getX().toDouble(), 0.0),
                    shape.getModifierString(), textAttributes
                ).apply {
                    altitudeMode = AltitudeMode.CLAMP_TO_GROUND
                    rotation = shape.getModifierStringAngle().toDouble().degrees
                    rotationMode = OrientationMode.RELATIVE_TO_GLOBE
//                    highlightAttributes = TextAttributes(textAttributes).apply {
//                        textColor = Color(1f, 1f, 1f, 1f)
//                    }
                }
                label.pickDelegate = this
                shapes.add(label)
            }
            else -> Logger.logMessage(Logger.ERROR, "MilStd2525TacticalGraphic", "convertShapeToRenderables", "unknownShapeType")
        }
    }
}