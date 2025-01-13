package earth.worldwind.util

sealed class NumericArray {
    data class Floats(val array: FloatArray) : NumericArray()
    data class Ints(val array: IntArray) : NumericArray()
    data class Shorts(val array: ShortArray) : NumericArray()
}
