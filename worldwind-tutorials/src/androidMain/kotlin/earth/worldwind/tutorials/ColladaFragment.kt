package earth.worldwind.tutorials

import android.view.GestureDetector
import android.view.MotionEvent
import androidx.lifecycle.lifecycleScope
import earth.worldwind.BasicWorldWindowController
import earth.worldwind.WorldWindow
import earth.worldwind.geom.Line
import kotlinx.coroutines.launch

class ColladaFragment : BasicGlobeFragment() {
    private lateinit var tutorial: ColladaTutorial

    override fun createWorldWindow(): WorldWindow {
        val wwd = super.createWorldWindow()
        tutorial = ColladaTutorial(wwd.engine)
        tutorial.start()

        lifecycleScope.launch {
            tutorial.setupScene()
            wwd.requestRedraw()
        }

        wwd.controller = RayPickController(wwd)
        return wwd
    }

    private inner class RayPickController(wwd: WorldWindow) : BasicWorldWindowController(wwd) {
        private val gestureDetector = GestureDetector(
            requireContext().applicationContext,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    val ray = Line()
                    if (wwd.engine.rayThroughScreenPoint(e.x.toDouble(), e.y.toDouble(), ray)) {
                        tutorial.pickScene(ray, wwd.engine.globe)
                        wwd.requestRedraw()
                    }
                    return true
                }
            }
        )

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val consumed = gestureDetector.onTouchEvent(event)
            return if (!consumed) super.onTouchEvent(event) else true
        }
    }
}
