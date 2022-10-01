package earth.worldwind.shape

/**
 * Interface to controlling a shape's attributes. Shapes implementing this interface use the [ShapeAttributes]
 * bundle for specifying the normal attributes and the highlight attributes.
 */
interface Attributable {
    /**
     * Indicates the shape's normal (non-highlight) attributes.
     */
    var attributes: ShapeAttributes
    /**
     * Indicates the shape's highlight attributes.
     */
    var highlightAttributes: ShapeAttributes?
}