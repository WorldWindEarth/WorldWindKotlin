package earth.worldwind.layer.ogc3d.tileset

/**
 * 3D Tiles tile refinement strategy. REPLACE: children substitute the parent at higher LOD.
 * ADD: children draw alongside the parent (additive detail). The field is required at the
 * root; non-root tiles inherit from the parent when omitted.
 */
enum class Refinement {
    REPLACE,
    ADD;

    companion object {
        /** Strict decode for the inherit-from-parent path; null when absent or unrecognised. */
        fun fromStringOrNull(value: String?): Refinement? = when (value?.uppercase()) {
            "ADD" -> ADD
            "REPLACE" -> REPLACE
            else -> null
        }

        /** Lenient decode: missing values fall through to REPLACE (the spec default at the root). */
        fun fromString(value: String?): Refinement = fromStringOrNull(value) ?: REPLACE
    }
}
