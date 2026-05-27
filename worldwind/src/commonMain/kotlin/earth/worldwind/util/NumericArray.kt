package earth.worldwind.util

sealed class NumericArray {
    abstract val byteCount: Int
    class Floats(val array: FloatArray, override val byteCount: Int = array.size * Float.SIZE_BYTES) : NumericArray()
    class Ints(val array: IntArray, override val byteCount: Int = array.size * Int.SIZE_BYTES) : NumericArray()
    class Shorts(val array: ShortArray, override val byteCount: Int = array.size * Short.SIZE_BYTES) : NumericArray()

    /**
     * Zero-copy upload from an [IntList]: the backing array may be larger than `list.size`, so
     * [byteCount] (and the GL backend, given a correct `size` argument) bounds the upload.
     * Avoids the [IntList.toIntArray] copy the eager [Ints] variant would force.
     */
    class IntsFromList(val list: IntList) : NumericArray() {
        override val byteCount: Int = list.size * Int.SIZE_BYTES
    }
}
