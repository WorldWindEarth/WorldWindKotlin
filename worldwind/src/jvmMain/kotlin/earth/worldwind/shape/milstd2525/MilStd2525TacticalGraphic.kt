package earth.worldwind.shape.milstd2525

import armyc2.c5isr.RenderMultipoints.clsRenderer
import armyc2.c5isr.renderer.utilities.*
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Location
import earth.worldwind.geom.Offset
import earth.worldwind.geom.Position
import earth.worldwind.geom.Sector
import earth.worldwind.render.Color
import earth.worldwind.render.Font
import earth.worldwind.render.RenderContext
import earth.worldwind.render.Renderable
import earth.worldwind.render.image.ImageSource
import earth.worldwind.shape.*
import earth.worldwind.util.Logger
import java.awt.geom.Point2D
import kotlin.math.roundToInt

actual open class MilStd2525TacticalGraphic @JvmOverloads actual constructor(
    symbolID: String, locations: List<Location>,
    boundingSector: Sector, modifiers: Map<String, String>?, attributes: Map<String, String>?
) : AbstractMilStd2525TacticalGraphic(symbolID, boundingSector, modifiers, attributes) {
    protected lateinit var controlPoints: ArrayList<Point2D>
    protected lateinit var pointUL: Point2D

    init {
        setAnchorLocations(locations)
    }

    fun setAnchorLocations(locations: List<Location>) {
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
        reset()
    }

    actual override fun makeRenderables(rc: RenderContext, scale: Double): List<Renderable> {
        val ipc = PointConverter3D(pointUL.x, pointUL.y, scale * 96.0 * rc.densityFactor * 39.3700787)

//        // Calculate clipping rectangle
//        val leftTop = ipc.GeoToPixels(Point2D.Double(boundingSector.minLongitude.degrees, boundingSector.maxLatitude.degrees))
//        val rightBottom = ipc.GeoToPixels(Point2D.Double(boundingSector.maxLongitude.degrees, boundingSector.minLatitude.degrees))
//        val width = abs(rightBottom.x - leftTop.x)
//        val height = abs(rightBottom.y - leftTop.y)
//        val rect = if (width > 0 && height > 0) Rectangle2D.Double(leftTop.x, leftTop.y, width, height) else null

        // Create MilStd Symbol and render it
        val mss = MilStdSymbol(symbolID, null, controlPoints, null)
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
            get(MilStdAttributes.AltitudeMode)?.let { mss.altitudeMode = it }
            DistanceUnit.parse(get(MilStdAttributes.AltitudeUnits))?.let { mss.altitudeUnit = it }
            DistanceUnit.parse(get(MilStdAttributes.DistanceUnits))?.let { mss.distanceUnit = it }
            get(MilStdAttributes.LineWidth)?.toFloatOrNull()?.let { mss.lineWidth = (it * rc.densityFactor).roundToInt() }
        }
        clsRenderer.renderWithPolylines(mss, ipc, null /*rect*/)

        // Create Renderables based on Poly-lines and Modifiers from Renderer
        val shapes = mutableListOf<Renderable>()
        val outlines = mutableListOf<Renderable>()
        for (i in mss.symbolShapes.indices) convertShapeToRenderables(mss.symbolShapes[i], ipc, shapes, outlines)
        for (i in mss.modifierShapes.indices) convertShapeToRenderables(mss.modifierShapes[i], ipc, shapes, outlines)
        return outlines + shapes
    }

    protected open fun convertShapeToRenderables(
        si: ShapeInfo, ipc: IPointConversion, shapes: MutableList<Renderable>, outlines: MutableList<Renderable>
    ) {
        when (si.shapeType) {
            ShapeInfo.SHAPE_TYPE_POLYLINE -> {
                val shapeAttributes = ShapeAttributes().apply {
                    outlineWidth = si.stroke.lineWidth
                    si.lineColor?.let { outlineColor = Color(it.rgb) }
                    si.fillColor?.let { interiorColor = Color(it.rgb) }
                    isPickInterior = false // Allow picking outline only
                    si.patternFillImage?.let { interiorImageSource = ImageSource.fromImage(it) }
                    val dash = si.stroke.dashArray
                    if (dash != null && dash.isNotEmpty()) outlineImageSource = ImageSource.fromLineStipple(
                        // TODO How to correctly interpret dash array?
                        factor = dash[0].toInt(), pattern = 0xF0F0.toShort()
                    )
                }
                val hasOutline = graphicsOutlineWidth != 0f && (isHighlighted || !isOutlineHighlightedOnly)
                val outlineAttributes = if (hasOutline) ShapeAttributes(shapeAttributes).apply {
                    si.lineColor?.let {
                        outlineColor = graphicsOutlineColor
                            ?: Color(RendererUtilities.getIdealOutlineColor(it).rgb).apply { alpha = 0.5f }
                    }
                    outlineWidth += graphicsOutlineWidth * 2f
                } else shapeAttributes
                for (idx in si.polylines.indices) {
                    val polyline = si.polylines[idx]
                    val positions = mutableListOf<Position>()
                    for (p in polyline.indices) {
                        val geoPoint = ipc.PixelsToGeo(polyline[p])
                        val position = Position.fromDegrees(geoPoint.y, geoPoint.x, 0.0)
                        positions.add(position)
                        sector.union(position) // Extend bounding box by real graphics measures
                    }
                    for (i in 0..1) {
                        val shape = if (si.shapeType == ShapeInfo.SHAPE_TYPE_FILL || si.patternFillImage != null) {
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

            ShapeInfo.SHAPE_TYPE_FILL, ShapeInfo.SHAPE_TYPE_MODIFIER -> {} // Skip this types

            ShapeInfo.SHAPE_TYPE_MODIFIER_FILL -> {
                val rs = RendererSettings.getInstance()
                val textAttributes = TextAttributes().apply {
                    si.lineColor?.let {
                        textColor = Color(it.rgb)
                        outlineColor = Color(RendererUtilities.getIdealOutlineColor(it).rgb)
                    }
                    textOffset = Offset.center()
                    font = Font(rs.mpLabelFont)
                }
                val point = ipc.PixelsToGeo(si.modifierPosition ?: si.glyphPosition ?: return)
                val position = Position.fromDegrees(point.y, point.x, 0.0)
                sector.union(position) // Extend bounding box by real graphics measures
                val label = Label(position, si.modifierString, textAttributes)
                applyLabelAttributes(label, si.modifierAngle.degrees)
                shapes += label
            }

            else -> Logger.logMessage(Logger.ERROR, "MilStd2525TacticalGraphic", "convertShapeToRenderables", "unknownShapeType")
        }
    }
}