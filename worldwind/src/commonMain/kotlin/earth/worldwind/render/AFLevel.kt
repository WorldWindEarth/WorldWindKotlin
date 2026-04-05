package earth.worldwind.render

/**
 * Anisotropic filtering level
 */
enum class AFLevel(val level: Int) { OFF(0), AF2X(2), AF4X(4), AF8X(8), AF16X(16) }