package earth.worldwind

/**
 * Rendering mode for a frame or context.
 */
enum class PickMode {
    NONE,
    OBJECT,
    DEPTH,
    ;

    val isPicking get() = this != NONE
    val isDepthPicking get() = this == DEPTH
}
