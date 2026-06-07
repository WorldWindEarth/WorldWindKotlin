package earth.worldwind.shape.milstd2525

import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Location
import earth.worldwind.geom.Position
import earth.worldwind.render.Font
import earth.worldwind.shape.TextAttributes
import earth.worldwind.shape.milstd2525.Font.Companion.getTypeString
import earth.worldwind.util.Logger
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import kotlin.js.get
import kotlin.js.toInt
import kotlin.js.toJsArray
import kotlin.js.toList
import kotlin.math.roundToInt

internal actual fun createMilStd2525Renderer(): MilStd2525Renderer = WasmJsMilStd2525Renderer

/**
 * wasmJs adapter for [MilStd2525Renderer]. Delegates to the per-target mil-sym-ts (`C5Ren`) + canvg
 * bindings, absorbing the JS-Map construction and `Number`/`Double` coercion so the interface stays
 * in plain Kotlin types. On wasm the `int`/`number` aliases already resolve to `Double`.
 */
internal object WasmJsMilStd2525Renderer : MilStd2525Renderer {
    init {
        val rendererSettings = RendererSettings.getInstance()
        rendererSettings.setDefaultPixelSize(36 * window.devicePixelRatio)
        rendererSettings.setDeviceDPI(96 * window.devicePixelRatio)
        rendererSettings.setOutlineSPControlMeasures(false)
        rendererSettings.setActionPointDefaultFill(false)
        rendererSettings.setLabelFont("Arial", "normal", 8 * window.devicePixelRatio)
        rendererSettings.setMPLabelFont("Arial", "normal", 12.0)
        rendererSettings.setTextBackgroundMethod(RendererSettings.TextBackgroundMethod_OUTLINE)
        rendererSettings.setTextBackgroundAutoColorThreshold(180.0)
    }

    override fun renderSymbol(
        symbolCode: String, modifiers: Map<String, String>?, attributes: Map<String, String>?,
    ): MilStd2525Symbol? {
        val info = MilStdIconRenderer.getInstance().RenderSVG(
            symbolCode,
            modifiers?.mapKeys { Modifiers.getModifierKey(it.key) ?: "" }?.toJsMap() ?: newJsMap(),
            attributes?.toJsMap() ?: newJsMap(),
        ) ?: return null
        val symbolBounds = info.getSymbolBounds()
        return MilStd2525Symbol(
            canvas = createMilStdCanvas(info),
            symbolCenterX = info.getSymbolCenterX(),
            symbolCenterY = info.getSymbolCenterY(),
            symbolWidth = symbolBounds.getWidth(),
            symbolHeight = symbolBounds.getHeight(),
        )
    }

    override fun renderTacticalGraphic(
        symbolID: String, controlPoints: List<Location>, pointULLon: Double, pointULLat: Double,
        scale: Double, densityFactor: Float, modifiers: Map<String, String>?, attributes: Map<String, String>?,
    ): List<MilStd2525Shape> {
        val ipc = PointConverter3D(pointULLon, pointULLat, scale * 96.0 * densityFactor * 39.3700787)
        val points = controlPoints.map { Point2D(it.longitude.inDegrees, it.latitude.inDegrees) }.toTypedArray()
        val mss = MilStdSymbol(symbolID, null, points.toJsArray(), null)
        modifiers?.forEach { (key, value) ->
            when (val modifierKey = Modifiers.getModifierKey(key) ?: "") {
                Modifiers.AM_DISTANCE, Modifiers.AN_AZIMUTH, Modifiers.X_ALTITUDE_DEPTH -> {
                    val elements = value.split(",")
                    for (j in elements.indices) mss.setModifier(modifierKey, elements[j], j.toDouble())
                }
                else -> mss.setModifier(modifierKey, value)
            }
        }
        attributes?.get(MilStdAttributes.AltitudeMode)?.let { mss.setAltitudeMode(it) }
        DistanceUnit.parse(attributes?.get(MilStdAttributes.AltitudeUnits))?.let { mss.setAltitudeUnit(it) }
        DistanceUnit.parse(attributes?.get(MilStdAttributes.DistanceUnits))?.let { mss.setDistanceUnit(it) }
        attributes?.get(MilStdAttributes.LineWidth)?.toFloatOrNull()?.let {
            mss.setLineWidth((it * densityFactor).roundToInt().toDouble())
        }
        clsRenderer.renderWithPolylines(mss, ipc, null as Rectangle?)

        val result = mutableListOf<MilStd2525Shape>()
        val symbolShapes = mss.getSymbolShapes()
        for (i in 0 until symbolShapes.length) symbolShapes[i]?.let { convertShape(it, ipc)?.let(result::add) }
        val modifierShapes = mss.getModifierShapes()
        for (i in 0 until modifierShapes.length) modifierShapes[i]?.let { convertShape(it, ipc)?.let(result::add) }
        return result
    }

    private fun convertShape(si: ShapeInfo, ipc: IPointConversion): MilStd2525Shape? = when (si.getShapeType()) {
        ShapeInfo.SHAPE_TYPE_POLYLINE -> {
            val lineColor = si.getLineColor()
            val dash = si.getStroke().getDashArray()?.toList()
            val polylines = mutableListOf<List<Position>>()
            val raw = si.getPolylines()
            for (idx in 0 until raw.length) {
                val polyline = raw[idx] ?: continue
                val positions = mutableListOf<Position>()
                for (p in 0 until polyline.length) {
                    val point = polyline[p] ?: continue
                    val geo = ipc.PixelsToGeo(point)
                    positions.add(Position.fromDegrees(geo.getY(), geo.getX(), 0.0))
                }
                polylines.add(positions)
            }
            MilStd2525Polyline(
                polylines = polylines,
                lineWidth = si.getStroke().getLineWidth().toFloat(),
                lineColor = lineColor?.let { convertColor(it) },
                fillColor = si.getFillColor()?.let { convertColor(it) },
                idealOutlineColor = lineColor?.let {
                    convertColor(RendererUtilities.getIdealOutlineColor(it).apply { setAlpha(0.5) })
                },
                dashFactor = if (!dash.isNullOrEmpty()) dash[0].toInt() else null,
                patternFillCanvas = si.getPatternFillImageInfo()?.let { createMilStdCanvas(it) },
            )
        }

        ShapeInfo.SHAPE_TYPE_MODIFIER_FILL -> {
            val image = si.getModifierImageInfo()
            if (image != null) {
                val geo = ipc.PixelsToGeo(si.getModifierPosition() ?: return null)
                MilStd2525ModifierImage(
                    position = Position.fromDegrees(geo.getY(), geo.getX(), 0.0),
                    canvas = createMilStdCanvas(image),
                    text = si.getModifierString(),
                    angle = si.getModifierAngle().degrees,
                )
            } else {
                val geo = ipc.PixelsToGeo(si.getModifierPosition() ?: si.getGlyphPosition() ?: return null)
                val lineColor = si.getLineColor()
                val rs = RendererSettings.getInstance()
                MilStd2525ModifierLabel(
                    position = Position.fromDegrees(geo.getY(), geo.getX(), 0.0),
                    text = si.getModifierString(),
                    angle = si.getModifierAngle().degrees,
                    textColor = lineColor?.let { convertColor(it) },
                    idealOutlineColor = lineColor?.let { convertColor(RendererUtilities.getIdealOutlineColor(it)) },
                    mpLabelFont = Font(
                        size = rs.getMPLabelFontSize().toInt(),
                        weight = getTypeString(rs.getMPLabelFontType()),
                        family = rs.getMPLabelFontName(),
                    ),
                )
            }
        }

        ShapeInfo.SHAPE_TYPE_FILL, ShapeInfo.SHAPE_TYPE_MODIFIER -> null

        else -> {
            Logger.logMessage(Logger.ERROR, "MilStd2525Renderer", "renderTacticalGraphic", "unknownShapeType")
            null
        }
    }

    private fun convertColor(color: Color) = earth.worldwind.render.Color(
        color.getRed().toInt(), color.getGreen().toInt(), color.getBlue().toInt(), color.getAlpha().toInt()
    )

    override fun applyTextAttributes(textAttributes: TextAttributes) {
        val rendererSettings = RendererSettings.getInstance()
        textAttributes.font = Font(
            size = rendererSettings.getLabelFontSize().toInt(),
            family = rendererSettings.getLabelFontName(),
            weight = getTypeString(rendererSettings.getLabelFontType()),
        )
    }

    override fun isTacticalGraphic(symbolId: String) = SymbolUtilities.isTacticalGraphic(symbolId)

    override fun getUnfilledAttributes(symbolId: String): Map<String, String> =
        (if (isTacticalGraphic(symbolId)) {
            SymbolUtilities.getLineColorOfAffiliation(symbolId)
        } else {
            SymbolUtilities.getFillColorOfAffiliation(symbolId)
        })?.toHexString(true)?.let {
            mapOf(
                MilStdAttributes.FillColor to "00000000",
                MilStdAttributes.LineColor to it,
                MilStdAttributes.IconColor to it,
            )
        } ?: emptyMap()
}

/** Rasterize a mil-sym [SVGSymbolInfo] to a canvas via canvg. Shared by the renderer and the
 *  per-target [MilStd2525TacticalGraphic] (which builds its SVGSymbolInfo via the multipoint API). */
internal fun createMilStdCanvas(info: SVGSymbolInfo): HTMLCanvasElement {
    val canvas = document.createElement("canvas") as HTMLCanvasElement
    val imageBounds = info.getImageBounds()
    canvas.width = imageBounds.getWidth().toInt()
    canvas.height = imageBounds.getHeight().toInt()
    val ctx = canvas.getContext("2d") as CanvasRenderingContext2D
    Canvg.fromString(ctx, info.getSVG()).start()
    return canvas
}

// Build a real JS Map for the mil-sym bindings (file-private; Kotlin/Wasm has no kotlin.js.collections view).
private fun newJsMap(): JsMap = js("new Map()")
@Suppress("UNUSED_PARAMETER") private fun jsMapSet(map: JsMap, key: String, value: String): JsAny? = js("map.set(key, value)")
private fun Map<String, String>.toJsMap(): JsMap {
    val map = newJsMap()
    forEach { (k, v) -> jsMapSet(map, k, v) }
    return map
}
