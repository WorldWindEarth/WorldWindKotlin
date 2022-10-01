package earth.worldwind.layer.graticule

import earth.worldwind.render.Color
import earth.worldwind.render.Font

class GraticuleRenderingParams: MutableMap<String, Any?> by HashMap() {
    var isDrawLines: Boolean
        get() {
            val value = get(KEY_DRAW_LINES)
            return if (value is Boolean) value else false
        }
        set(drawLines) { put(KEY_DRAW_LINES, drawLines) }
    var lineColor: Color?
        get() {
            val value = get(KEY_LINE_COLOR)
            return if (value is Color) value else null
        }
        set(color) { put(KEY_LINE_COLOR, color) }
    var lineWidth: Double
        get() {
            val value = get(KEY_LINE_WIDTH)
            return if (value is Double) value else 0.0
        }
        set(lineWidth) { put(KEY_LINE_WIDTH, lineWidth) }
//    var lineStyle: String?
//        get() {
//            val value = get(KEY_LINE_STYLE)
//            return if (value is String) value else null
//        }
//        set(lineStyle) { put(KEY_LINE_STYLE, lineStyle) }
    var isDrawLabels: Boolean
        get() {
            val value = get(KEY_DRAW_LABELS)
            return if (value is Boolean) value else false
        }
        set(drawLabels) { put(KEY_DRAW_LABELS, drawLabels) }
    var labelColor: Color?
        get() {
            val value = get(KEY_LABEL_COLOR)
            return if (value is Color) value else null
        }
        set(color) { put(KEY_LABEL_COLOR, color) }
    var labelFont: Font?
        get() {
            val value = get(KEY_LABEL_FONT)
            return if (value is Font) value else null
        }
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