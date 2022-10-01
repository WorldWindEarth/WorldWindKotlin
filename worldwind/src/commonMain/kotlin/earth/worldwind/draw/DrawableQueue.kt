package earth.worldwind.draw

import kotlin.math.max

open class DrawableQueue internal constructor(){
    protected var size = 0
    protected var position = 0
    protected var entries = arrayOfNulls<Entry>(size)
    val count get() = size
    /**
     * Sorts drawables by ascending group ID, then ascending order, then by ascending ordinal.
     */
    protected open val sortComparator = Comparator<Entry?> { lhs, rhs ->
        // Comparator accepts only non-null Entries
        var result = lhs!!.groupId.compareTo(rhs!!.groupId)
        if (result == 0) result = lhs.order.compareTo(rhs.order)
        if (result == 0) result = lhs.ordinal.compareTo(rhs.ordinal)
        result
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

    fun sortDrawables() {
        // Limit sort to non-null Entries only
        entries.sortWith(sortComparator, 0, size)
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
        var groupId = DrawableGroup.BACKGROUND
        var order = 0.0
        var ordinal = 0

        fun set(drawable: Drawable, groupId: DrawableGroup, order: Double, ordinal: Int) {
            this.drawable = drawable
            this.groupId = groupId
            this.order = order
            this.ordinal = ordinal
        }

        fun recycle() {
            drawable?.recycle()
            drawable = null
        }
    }
}