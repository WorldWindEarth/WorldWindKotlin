package earth.worldwind.layer.graticule

import earth.worldwind.render.Color
import earth.worldwind.render.Font

class GraticuleRenderingParams: MutableMap<String, Any?> by HashMap() {
    var isDrawLines: Boolean
        get() = get(KEY_DRAW_LINES) as? Boolean ?: false
        set(drawLines) { put(KEY_DRAW_LINES, drawLines) }
    var lineColor: Color?
        get() = get(KEY_LINE_COLOR) as? Color
        set(color) { put(KEY_LINE_COLOR, color) }
    var lineWidth: Double
        get() = get(KEY_LINE_WIDTH) as? Double ?: 0.0
        set(lineWidth) { put(KEY_LINE_WIDTH, lineWidth) }
//    var lineStyle: String?
//        get() = get(KEY_LINE_STYLE) as? String
//        set(lineStyle) { put(KEY_LINE_STYLE, lineStyle) }
    var isDrawLabels: Boolean
        get() = get(KEY_DRAW_LABELS) as? Boolean ?: false
        set(drawLabels) { put(KEY_DRAW_LABELS, drawLabels) }
    var labelColor: Color?
        get() = get(KEY_LABEL_COLOR) as? Color
        set(color) { put(KEY_LABEL_COLOR, color) }
    var labelFont: Font?
        get() = get(KEY_LABEL_FONT) as? Font
        set(font) { put(KEY_LABEL_FONT, font) }

    fun getStringValue(key: String) = this[key]?.toString()

    fun getFloatValue(key: String): Float? {
        val o = get(key) ?: return null
        if (o is Float) return o
        val v = getStringValue(key)
        return v?.toFloat()
    }

    companion object {
        const val KEY_DRAW_LINES = "DrawGraticule"
        const val KEY_LINE_COLOR = "GraticuleLineColor"
        const val KEY_LINE_WIDTH = "GraticuleLineWidth"
//        const val KEY_LINE_STYLE = "GraticuleLineStyle";
//        const val KEY_LINE_CONFORMANCE = "GraticuleLineConformance";
        const val KEY_DRAW_LABELS = "DrawLabels"
        const val KEY_LABEL_COLOR = "LabelColor"
        const val KEY_LABEL_FONT = "LabelFont"
//        const val VALUE_LINE_STYLE_SOLID = "LineStyleSolid";
//        const val VALUE_LINE_STYLE_DASHED = "LineStyleDashed";
//        const val VALUE_LINE_STYLE_DOTTED = "LineStyleDotted";
    }
}