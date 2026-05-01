package earth.worldwind.layer.shadow

/**
 * Per-shape opt-in for cascaded sun-shadow participation. One enum value selects whether
 * a renderable casts, receives, both, or neither. Decoupled from
 * `ShapeAttributes.isLightingEnabled` (which controls Lambertian shading only) so cast
 * and receive can be configured independently of shading.
 *
 * The [ENABLED] default means the shape participates in both passes when
 * [earth.worldwind.layer.shadow.ShadowLayer] is in the layer list. Apps that need to opt
 * out per-shape pick one of the other modes.
 */
enum class ShadowMode {
    /** Neither casts nor receives. Sun-independent overlay-style rendering. */
    DISABLED,
    /** Casts onto other receivers and receives shadows on its own surface. */
    ENABLED,
    /** Casts but ignores incoming shadows on its own surface. */
    CAST_ONLY,
    /** Receives shadows but doesn't project onto the ground. Useful for unlit-style 3D models. */
    RECEIVE_ONLY,
    ;

    /** Whether the shape's geometry should be rasterised into the cascade depth maps. */
    val castsShadows: Boolean get() = this == ENABLED || this == CAST_ONLY
    /** Whether the shape's fragments should sample the cascade moments and be darkened. */
    val receivesShadows: Boolean get() = this == ENABLED || this == RECEIVE_ONLY
}
