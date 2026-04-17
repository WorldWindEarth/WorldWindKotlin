package earth.worldwind.draw

import kotlin.math.max

open class DrawableQueue internal constructor(){
    protected var size = 0
    protected var position = 0
    protected var entries = arrayOfNulls<Entry>(size)
    val count get() = size
    /**
     * Sorts drawables by ascending group ID, then ascending order, then by ascending ordinal.
     * Uses a pre-computed Long sort key per entry to reduce per-comparison work to a single
     * field access and Long comparison.
     */
    protected open val sortComparator = Comparator<Entry> { lhs, rhs ->
        lhs.sortKey.toULong().compareTo(rhs.sortKey.toULong())
    }

    companion object {
        protected const val MIN_CAPACITY_INCREMENT = 12
    }

    fun offerDrawable(drawable: Drawable, groupId: DrawableGroup, depth: Double) {
        val capacity = entries.size
        if (capacity == size) {
            val increment = max(capacity shr 1, MIN_CAPACITY_INCREMENT)
            val newEntries = arrayOfNulls<Entry>(capacity + increment)
            entries.copyInto(newEntries)
            entries = newEntries
        }
        val entry = entries[size] ?: Entry().also { entries[size] = it }
        entry.set(drawable, groupId, depth, size++)
    }

    fun getDrawable(index: Int) = if (index < size) entries[index]?.drawable else null

    fun peekDrawable() = getDrawable(position)

    fun pollDrawable() = getDrawable(position++)

    fun rewindDrawables() { position = 0 }

    @Suppress("UNCHECKED_CAST")
    fun sortDrawables() {
        // Entries at indices 0..size-1 are always non-null; cast is safe.
        (entries as Array<Entry>).sortWith(sortComparator, 0, size)
        position = 0
    }

    fun clearDrawables() {
        for (idx in 0 until size) {
            entries[idx]?.recycle()
        }
        size = 0
        position = 0
    }

    protected open class Entry {
        var drawable: Drawable? = null
        /**
         * Pre-computed sort key encoding groupId (bits 63-62), order top 30 bits (bits 61-32),
         * ordinal low 30 bits (bits 29-0). The order field is converted via the IEEE 754
         * total-order transform so negative depths (used by 3D shapes to prioritize closer
         * objects) sort correctly. Bit 63 encodes groupId MSB — use unsigned comparison.
         */
        var sortKey = 0L

        fun set(drawable: Drawable, groupId: DrawableGroup, order: Double, ordinal: Int) {
            this.drawable = drawable
            val orderBits = order.toRawBits()
            // IEEE 754 total-order transform: negative doubles → invert all bits (restores
            // ascending magnitude order); non-negative doubles → flip sign bit only.
            val sortableOrderBits = if (orderBits < 0) orderBits.inv() else orderBits xor Long.MIN_VALUE
            sortKey = (groupId.ordinal.toLong() shl 62) or
                    ((sortableOrderBits ushr 2) and 0x3FFFFFFF00000000L) or
                    (ordinal.toLong() and 0x3FFFFFFFL)
        }

        fun recycle() {
            drawable?.recycle()
            drawable = null
        }
    }
}