package earth.worldwind.shape.milstd2525

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Position
import earth.worldwind.render.Color
import earth.worldwind.render.Font
import org.w3c.dom.HTMLCanvasElement

/**
 * Target-agnostic projection of one mil-sym multipoint `ShapeInfo`, produced by
 * [MilStd2525Renderer.renderTacticalGraphic]. The per-target adapter does all the C5Ren work — building
 * the `MilStdSymbol`, running the renderer, converting pixels→geo, colours, and the ideal-outline /
 * MP-label font — and emits these plain-Kotlin shapes so `MilStd2525TacticalGraphic` can turn them
 * into Renderables in `webMain` without touching any generated type.
 */
sealed interface MilStd2525Shape

/** A poly-line / filled shape: its (geo-converted) poly-lines plus the resolved stroke/fill styling. */
class MilStd2525Polyline(
    val polylines: List<List<Position>>,
    val lineWidth: Float,
    val lineColor: Color?,
    val fillColor: Color?,
    /** `getIdealOutlineColor(lineColor)` at alpha 0.5, pre-computed; null when [lineColor] is null. */
    val idealOutlineColor: Color?,
    /** First dash-array element (stipple factor), or null for a solid line. */
    val dashFactor: Int?,
    val patternFillCanvas: HTMLCanvasElement?,
) : MilStd2525Shape

/** A modifier rendered as a rasterized image (e.g. a fill glyph) anchored at [position]. */
class MilStd2525ModifierImage(
    val position: Position,
    val canvas: HTMLCanvasElement,
    val text: String?,
    val angle: Angle,
) : MilStd2525Shape

/** A modifier rendered as a text label anchored at [position], styled with the MP-label font. */
class MilStd2525ModifierLabel(
    val position: Position,
    val text: String?,
    val angle: Angle,
    val textColor: Color?,
    /** `getIdealOutlineColor(lineColor)`, pre-computed; null when the line colour is null. */
    val idealOutlineColor: Color?,
    val mpLabelFont: Font,
) : MilStd2525Shape

/**
 * A rendered point symbol (from [MilStd2525Renderer.renderSymbol]) — the rasterized canvas plus the
 * renderer's symbol-center offset and symbol-bounds size (already coerced to [Double], free of any
 * generated `number` alias). Not a [MilStd2525Shape]; co-located here as the other renderer-result DTO.
 */
class MilStd2525Symbol(
    val canvas: HTMLCanvasElement,
    val symbolCenterX: Double,
    val symbolCenterY: Double,
    val symbolWidth: Double,
    val symbolHeight: Double,
)
