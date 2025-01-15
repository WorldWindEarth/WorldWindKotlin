package earth.worldwind.util

sealed class NumericArray {
    abstract val byteCount: Int
    class Floats(val array: FloatArray, override val byteCount: Int = array.size * Float.SIZE_BYTES) : NumericArray()
    class Ints(val array: IntArray, override val byteCount: Int = array.size * Int.SIZE_BYTES) : NumericArray()
    class Shorts(val array: ShortArray, override val byteCount: Int = array.size * Short.SIZE_BYTES) : NumericArray()
}
