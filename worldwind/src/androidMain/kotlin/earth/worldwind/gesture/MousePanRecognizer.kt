package earth.worldwind.gesture

import android.view.MotionEvent

open class MousePanRecognizer : PanRecognizer {
    var buttonState = MotionEvent.BUTTON_PRIMARY

    constructor()
    constructor(listener: GestureListener): super(listener)

    override fun shouldRecognize(event: MotionEvent) = super.shouldRecognize(event) && event.buttonState == buttonState
}