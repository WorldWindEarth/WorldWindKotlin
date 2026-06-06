package earth.worldwind.formats.collada

/** COLLADA `<asset><up_axis>` value. Determines the rotation that maps the model's up onto WorldWind's local +Z. */
enum class ColladaUpAxis {
    X_UP, Y_UP, Z_UP;

    companion object {
        /** Parses a COLLADA up_axis token; defaults to [Y_UP] per the COLLADA spec. */
        fun fromString(value: String?) = when (value?.trim()?.uppercase()) {
            "Z_UP" -> Z_UP
            "X_UP" -> X_UP
            else -> Y_UP
        }
    }
}
