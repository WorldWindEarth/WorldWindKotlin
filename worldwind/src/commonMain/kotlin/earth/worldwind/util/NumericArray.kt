package earth.worldwind.util

sealed class NumericArray {
    abstract val byteCount: Int
    data class Floats(val array: FloatArray, override val byteCount: Int = array.size * Float.SIZE_BYTES) : NumericArray()
    data class Ints(val array: IntArray, override val byteCount: Int = array.size * Int.SIZE_BYTES) : NumericArray()
    data class Shorts(val array: ShortArray, override val byteCount: Int = array.size * Short.SIZE_BYTES) : NumericArray()
}
