package earth.worldwind.layer.graticule

import earth.worldwind.layer.graticule.GraticuleRenderingParams.Companion.KEY_DRAW_LABELS
import earth.worldwind.layer.graticule.GraticuleRenderingParams.Companion.KEY_DRAW_LINES
import earth.worldwind.layer.graticule.GraticuleRenderingParams.Companion.KEY_LABEL_COLOR
import earth.worldwind.layer.graticule.GraticuleRenderingParams.Companion.KEY_LABEL_FONT
import earth.worldwind.layer.graticule.GraticuleRenderingParams.Companion.KEY_LINE_COLOR
import earth.worldwind.layer.graticule.GraticuleRenderingParams.Companion.KEY_LINE_WIDTH
import earth.worldwind.render.*
import earth.worldwind.shape.Label
import earth.worldwind.shape.Path
import earth.worldwind.shape.ShapeAttributes
import kotlin.jvm.JvmOverloads

internal class GraticuleSupport {
    private val renderables = mutableMapOf<Renderable, String>()
    private val namedParams = mutableMapOf<String, GraticuleRenderingParams>()
    private val namedShapeAttributes = mutableMapOf<String, ShapeAttributes>()
    var defaultParams: GraticuleRenderingParams? = null

    fun addRenderable(renderable: Renderable, paramsKey: String) { renderables[renderable] = paramsKey }

    fun removeAllRenderables() { renderables.clear() }

    @JvmOverloads
    fun render(rc: RenderContext, opacity: Float = 1f) {
        namedShapeAttributes.clear()

        // Render lines and collect text labels
        for ((renderable, paramsKey) in renderables) {
            val renderingParams = namedParams[paramsKey]
            if (renderable is Path) {
                if (renderingParams?.isDrawLines != false) {
                    applyRenderingParams(paramsKey, renderingParams, renderable, opacity)
                    renderable.render(rc)
                }
            } else if (renderable is Label) {
                if (renderingParams?.isDrawLabels != false) {
                    applyRenderingParams(renderingParams, renderable, opacity)
                    renderable.render(rc)
                }
            }
        }
    }

    fun getRenderingParams(key: String): GraticuleRenderingParams {
        return namedParams[key] ?: GraticuleRenderingParams().also { params ->
            initRenderingParams(params)
            defaultParams?.let{ params.putAll(it) }
            namedParams[key] = params
        }
    }

    fun setRenderingParams(key: String, renderingParams: GraticuleRenderingParams) {
        initRenderingParams(renderingParams)
        namedParams[key] = renderingParams
    }

    private fun initRenderingParams(params: GraticuleRenderingParams) {
        if (params[KEY_DRAW_LINES] == null) params[KEY_DRAW_LINES] = true
        if (params[KEY_LINE_COLOR] == null) params[KEY_LINE_COLOR] = Color(255, 255, 255) // White
        if (params[KEY_LINE_WIDTH] == null) params[KEY_LINE_WIDTH] = .5f
//        if (params[KEY_LINE_STYLE] == null) params[KEY_LINE_STYLE] = GraticuleRenderingParams.VALUE_LINE_STYLE_SOLID
        if (params[KEY_DRAW_LABELS] == null) params[KEY_DRAW_LABELS] = true
        if (params[KEY_LABEL_COLOR] == null) params[KEY_LABEL_COLOR] = Color(255, 255, 255) // White
        if (params[KEY_LABEL_FONT] == null) params[KEY_LABEL_FONT] = Font("arial", FontWeight.BOLD, 12)
    }

    private fun applyRenderingParams(params: GraticuleRenderingParams?, text: Label, opacity: Float) {
        if (params != null) {
            // Apply "label" properties to the Label.
            var o = params[KEY_LABEL_COLOR]
            if (o is Color) {
                val color = applyOpacity(o, opacity)
                val compArray = FloatArray(3)
                color.toHSV(compArray)
                val colorValue = if (compArray[2] < .5f) 1f else 0f
                text.attributes.textColor = color
                text.attributes.outlineColor = Color(colorValue, colorValue, colorValue, color.alpha)
            }
            o = params[KEY_LABEL_FONT]
            if (o is Font) text.attributes.font = o
        }
    }

    private fun applyRenderingParams(key: String, params: GraticuleRenderingParams?, path: Path, opacity: Float) {
        if (params != null) {
            path.attributes = getLineShapeAttributes(key, params, opacity)
        }
    }

    private fun getLineShapeAttributes(key: String, params: GraticuleRenderingParams, opacity: Float) =
        namedShapeAttributes[key] ?: createLineShapeAttributes(params, opacity).also { namedShapeAttributes[key] = it }

    private fun createLineShapeAttributes(params: GraticuleRenderingParams, opacity: Float): ShapeAttributes {
        val attrs = ShapeAttributes()
        attrs.isDrawInterior = false
        attrs.isDrawOutline = true

        // Apply "line" properties.
        val o = params[KEY_LINE_COLOR]
        if (o is Color) attrs.outlineColor = applyOpacity(o, opacity)
        val lineWidth = params.getFloatValue(KEY_LINE_WIDTH)
        if (lineWidth != null) attrs.outlineWidth = lineWidth
//        val s = params.getStringValue(KEY_LINE_STYLE)
//        when {
//            VALUE_LINE_STYLE_SOLID.equals(s, true) -> {
//                attrs.outlineStipplePattern = 0xAAAA.toShort()
//                attrs.outlineStippleFactor = 0
//            }
//            VALUE_LINE_STYLE_DASHED.equals(s, true) -> {
//                val baseFactor = lineWidth?.roundToInt() ?: 1
//                attrs.outlineStipplePattern = 0xAAAA.toShort()
//                attrs.outlineStippleFactor = 3 * baseFactor
//            }
//            VALUE_LINE_STYLE_DOTTED.equals(s, true) -> {
//                val baseFactor = lineWidth?.roundToInt() ?: 1
//                attrs.outlineStipplePattern =0xAAAA.toShort()
//                attrs.outlineStippleFactor = baseFactor
//            }
//        }
        return attrs
    }

    private fun applyOpacity(color: Color, opacity: Float) =
        if (opacity >= 1) color else Color(color.red, color.green, color.blue, color.alpha * opacity)
}