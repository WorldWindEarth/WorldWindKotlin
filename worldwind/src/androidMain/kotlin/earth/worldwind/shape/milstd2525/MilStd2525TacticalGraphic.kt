package earth.worldwind.shape.milstd2525

import android.graphics.Typeface
import android.util.SparseArray
import armyc2.c2sd.JavaRendererServer.RenderMultipoints.clsRenderer
import armyc2.c2sd.graphics2d.BasicStroke
import armyc2.c2sd.graphics2d.Point2D
import armyc2.c2sd.renderer.utilities.*
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Location
import earth.worldwind.geom.Position
import earth.worldwind.geom.Sector
import earth.worldwind.render.Color
import earth.worldwind.render.Font
import earth.worldwind.render.Renderable
import earth.worldwind.render.image.ImageSource
import earth.worldwind.shape.*
import earth.worldwind.util.Logger
import kotlin.math.roundToInt

actual open class MilStd2525TacticalGraphic actual constructor(
    sidc: String, locations: List<Location>,
    boundingSector: Sector, modifiers: Map<String, String>?, attributes: Map<String, String>?
) : AbstractMilStd2525TacticalGraphic(sidc, locations, boundingSector, modifiers, attributes) {
    protected lateinit var controlPoints: ArrayList<Point2D>
    protected lateinit var pointUL: Point2D.Double

    constructor(
        locations: List<Location>, boundingSector: Sector, sidc: String,
        modifiers: SparseArray<String>? = null, attributes: SparseArray<String>? = null
    ): this(
        sidc, locations, boundingSector,
        MilStd2525.graphicModifiersFromSparseArray(modifiers), MilStd2525.attributesFromSparseArray(attributes)
    )

    override fun transformLocations(locations: List<Location>) {
        if (this::controlPoints.isInitialized) controlPoints.clear() else controlPoints = ArrayList()
        for (location in locations) controlPoints.add(Point2D.Double(location.longitude.degrees, location.latitude.degrees))
        var left = controlPoints[0].x
        var top = controlPoints[0].y
        var right = controlPoints[0].x
        var bottom = controlPoints[0].y
        var pt: Point2D
        for (i in 1 until controlPoints.size) {
            pt = controlPoints[i]
            if (pt.x < left) left = pt.x
            if (pt.x > right) right = pt.x
            if (pt.y > top) top = pt.y
            if (pt.y < bottom) bottom = pt.y
        }
        if (right - left > 180.0) {
            left = controlPoints[0].x
            for (i in 1 until controlPoints.size) {
                pt = controlPoints[i]
                if (pt.x > 0.0 && pt.x < left) left = pt.x
            }
        }
        if (this::pointUL.isInitialized) pointUL.setLocation(left, top) else pointUL = Point2D.Double(left, top)
    }

    override fun makeRenderables(scale: Double, shapes: MutableList<Renderable>) {
        val ipc = PointConverter3D(pointUL.x, pointUL.y, scale * 96.0 * 39.3700787)

//        // Calculate clipping rectangle
//        val leftTop = ipc.GeoToPixels(Point2D.Double(boundingSector.minLongitude.degrees, boundingSector.maxLatitude.degrees))
//        val rightBottom = ipc.GeoToPixels(Point2D.Double(boundingSector.maxLongitude.degrees, boundingSector.minLatitude.degrees))
//        val width = abs(rightBottom.x - leftTop.x)
//        val height = abs(rightBottom.y - leftTop.y)
//        val rect = if (width > 0 && height > 0) Rectangle2D.Double(leftTop.x, leftTop.y, width, height) else null

        // Create MilStd Symbol and render it
        val mss = MilStdSymbol(sidc, null, controlPoints, null)
        modifiers?.forEach { (key, value) ->
            when (val modifierKey = ModifiersTG.getModifierKey(key)) {
                ModifiersTG.AM_DISTANCE, ModifiersTG.AN_AZIMUTH, ModifiersTG.X_ALTITUDE_DEPTH -> {
                    val elements = value.split(",")
                    for (j in elements.indices) mss.setModifier(modifierKey, elements[j], j)
                }
                else -> mss.setModifier(modifierKey, value)
            }
        }
        attributes?.run {
            mss.altitudeMode = get(MilStdAttributes.AltitudeMode.toString())
            mss.altitudeUnit = DistanceUnit.parse(get(MilStdAttributes.AltitudeUnits.toString()))
            mss.distanceUnit = DistanceUnit.parse(get(MilStdAttributes.DistanceUnits.toString()))
        }
        clsRenderer.renderWithPolylines(mss, ipc, null /*rect*/)

        // Create Renderables based on Poly-lines and Modifiers from Renderer
        for (shape in mss.symbolShapes) convertShapeToRenderables(shape, mss, ipc, shapes)
        for (shape in mss.modifierShapes) convertShapeToRenderables(shape, mss, ipc, shapes)
    }

    protected open fun convertShapeToRenderables(
        shape: ShapeInfo, mss: MilStdSymbol, ipc: IPointConversion, shapes: MutableList<Renderable>
    ) {
        when (shape.shapeType) {
            ShapeInfo.SHAPE_TYPE_POLYLINE -> {
                val shapeAttributes = ShapeAttributes().apply {
                    outlineWidth = MilStd2525.outlineWidth
                    shape.lineColor?.let { outlineColor = Color(it.toARGB()) }
                    shape.fillColor?.let { interiorColor = Color(it.toARGB()) }
                    // Fill dash pattern
                    val stroke = shape.stroke
                    if (stroke is BasicStroke) {
                        val dash = stroke.dashArray
                        if (dash != null && dash.isNotEmpty()) outlineImageSource = ImageSource.fromLineStipple(
                            // TODO How to correctly interpret dash array?
                            factor = dash[0].roundToInt(), pattern = 0xF0F0.toShort()
                        )
                    }
                }
                for (polyline in shape.polylines) {
                    val positions = mutableListOf<Position>()
                    for (point in polyline) {
                        val geoPoint = ipc.PixelsToGeo(point)
                        positions.add(Position.fromDegrees(geoPoint.y, geoPoint.x, 0.0))
                    }
                    val path = Path(positions, shapeAttributes).apply {
                        altitudeMode = AltitudeMode.CLAMP_TO_GROUND
                        isFollowTerrain = true
                        maximumIntermediatePoints = 0 // Do not draw intermediate vertices for tactical graphics
                        highlightAttributes = ShapeAttributes(shapeAttributes).apply {
//                            outlineColor = Color(android.graphics.Color.WHITE)
                            outlineWidth *= HIGHLIGHT_FACTOR
                        }
                    }
                    path.pickDelegate = this
                    shapes.add(path)
                }
            }
            ShapeInfo.SHAPE_TYPE_MODIFIER_FILL -> {
                val rs = RendererSettings.getInstance()
                val textAttributes = TextAttributes().apply {
                    textColor = Color(mss.lineColor.toARGB())
                    font = Font(
                        Typeface.create(rs.mpModifierFontName, rs.mpModifierFontType),
                        rs.mpModifierFontSize.toFloat()
                    )
                }
                val point = ipc.PixelsToGeo(shape.modifierStringPosition ?: shape.glyphPosition)
                val label = Label(
                    Position.fromDegrees(point.y, point.x, 0.0), shape.modifierString, textAttributes
                ).apply {
                    altitudeMode = AltitudeMode.CLAMP_TO_GROUND
                    rotation = shape.modifierStringAngle.degrees
                    rotationMode = OrientationMode.RELATIVE_TO_GLOBE
//                    highlightAttributes = TextAttributes(textAttributes).apply {
//                        textColor = Color(android.graphics.Color.WHITE)
//                    }
                }
                label.pickDelegate = this
                shapes.add(label)
            }
            else -> Logger.logMessage(Logger.ERROR, "MilStd2525TacticalGraphic", "convertShapeToRenderables", "unknownShapeType")
        }
    }
}