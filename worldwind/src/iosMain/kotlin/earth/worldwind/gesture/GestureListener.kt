package earth.worldwind.gesture

interface GestureListener {
    fun gestureStateChanged(event: TouchEvent, recognizer: GestureRecognizer)
}
