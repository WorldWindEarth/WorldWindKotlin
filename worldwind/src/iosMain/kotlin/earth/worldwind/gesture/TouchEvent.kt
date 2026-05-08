package earth.worldwind.gesture

/** iOS analog of Android's `MotionEvent` — normalized snapshot of one touch phase. */
class TouchEvent {
    var action: Int = ACTION_DOWN
    var actionIndex: Int = 0
    private val pointerIds = mutableListOf<Int>()
    private val xs = mutableListOf<Float>()
    private val ys = mutableListOf<Float>()

    val actionMasked: Int get() = action and ACTION_MASK

    val pointerCount: Int get() = pointerIds.size

    fun getPointerId(index: Int): Int = pointerIds[index]

    fun getX(index: Int): Float = xs[index]

    fun getY(index: Int): Float = ys[index]

    fun findPointerIndex(pointerId: Int): Int = pointerIds.indexOf(pointerId)

    fun set(action: Int, actionIndex: Int, ids: IntArray, xs: FloatArray, ys: FloatArray) {
        this.action = action
        this.actionIndex = actionIndex
        this.pointerIds.clear()
        this.xs.clear()
        this.ys.clear()
        for (i in ids.indices) {
            this.pointerIds.add(ids[i])
            this.xs.add(xs[i])
            this.ys.add(ys[i])
        }
    }

    fun toCancelCopy(): TouchEvent {
        val copy = TouchEvent()
        copy.action = ACTION_CANCEL
        copy.actionIndex = actionIndex
        copy.pointerIds.addAll(pointerIds)
        copy.xs.addAll(xs)
        copy.ys.addAll(ys)
        return copy
    }

    companion object {
        const val ACTION_MASK = 0xFF
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2
        const val ACTION_CANCEL = 3
        const val ACTION_POINTER_DOWN = 5
        const val ACTION_POINTER_UP = 6
    }
}
