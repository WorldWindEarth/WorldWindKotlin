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
        for (location in locations) controlPoints.add(Point2D.Double(location.longitude.inDegrees, location.latitude.inDegrees))
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
        for (i in mss.symbolShapes.indices) convertShapeToRenderables(mss.symbolShapes[i], mss, ipc, shapes)
        for (i in mss.modifierShapes.indices) convertShapeToRenderables(mss.modifierShapes[i], mss, ipc, shapes)
        invalidateExtent() // Regenerate extent in next frame due to sector may be extended by real shape measures
    }

    protected open fun convertShapeToRenderables(
        shape: ShapeInfo, mss: MilStdSymbol, ipc: IPointConversion, shapes: MutableList<Renderable>
    ) {
        when (shape.shapeType) {
            ShapeInfo.SHAPE_TYPE_POLYLINE -> {
                val shapeAttributes = ShapeAttributes().apply {
                    outlineWidth = MilStd2525.graphicsLineWidth
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
                val hasOutline = MilStd2525.graphicsOutlineWidth != 0f
                val outlineAttributes = if (hasOutline) ShapeAttributes(shapeAttributes).apply {
                    outlineColor = Color(RendererUtilities.getIdealOutlineColor(shape.lineColor).toARGB()).apply { alpha = 0.5f }
                    outlineWidth += MilStd2525.graphicsOutlineWidth * 2f
                } else shapeAttributes
                val lines = mutableListOf<Renderable>()
                val outlines = mutableListOf<Renderable>()
                for (idx in shape.polylines.indices) {
                    val polyline = shape.polylines[idx]
                    val positions = mutableListOf<Position>()
                    for (p in polyline.indices) {
                        val geoPoint = ipc.PixelsToGeo(polyline[p])
                        val position = Position.fromDegrees(geoPoint.y, geoPoint.x, 0.0)
                        positions.add(position)
                        sector.union(position) // Extend bounding box by real graphics measures
                    }
                    for (i in 0..1) {
                        val path = Path(positions, if (i == 0) shapeAttributes else outlineAttributes).apply {
                            altitudeMode = AltitudeMode.CLAMP_TO_GROUND
                            isFollowTerrain = true
                            maximumIntermediatePoints = 0 // Do not draw intermediate vertices for tactical graphics
                            highlightAttributes = ShapeAttributes(attributes).apply {
                                outlineWidth *= HIGHLIGHT_FACTOR
                            }
                            pickDelegate = this@MilStd2525TacticalGraphic
                        }
                        if (i == 0) lines += path else outlines += path
                        if (!hasOutline) break
                    }
                }
                shapes += outlines
                shapes += lines
            }
            ShapeInfo.SHAPE_TYPE_MODIFIER_FILL -> {
                val rs = RendererSettings.getInstance()
                val textAttributes = TextAttributes().apply {
                    textColor = Color(mss.lineColor.toARGB())
                    font = Font(
                        Typeface.create(rs.mpModifierFontName, rs.mpModifierFontType),
                        rs.mpModifierFontSize.toFloat()
                    )
                    outlineWidth = MilStd2525.graphicsOutlineWidth
                }
                val point = ipc.PixelsToGeo(shape.modifierStringPosition ?: shape.glyphPosition)
                val position = Position.fromDegrees(point.y, point.x, 0.0)
                sector.union(position) // Extend bounding box by real graphics measures
                val label = Label(position, shape.modifierString, textAttributes).apply {
                    altitudeMode = AltitudeMode.CLAMP_TO_GROUND
                    rotation = shape.modifierStringAngle.degrees
                    rotationMode = OrientationMode.RELATIVE_TO_GLOBE
                    pickDelegate = this@MilStd2525TacticalGraphic
                }
                shapes += label
            }
            else -> Logger.logMessage(Logger.ERROR, "MilStd2525TacticalGraphic", "convertShapeToRenderables", "unknownShapeType")
        }
    }
}