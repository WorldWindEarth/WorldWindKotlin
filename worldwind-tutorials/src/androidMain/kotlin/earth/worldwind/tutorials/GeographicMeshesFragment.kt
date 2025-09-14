package earth.worldwind.tutorials

import android.annotation.SuppressLint
import earth.worldwind.geom.Line

class GeographicMeshesFragment : BasicGlobeFragment() {
    private val clickRay = Line()

    /**
     * Creates a new WorldWindow (GLSurfaceView) object with a set of Geographic Mesh shapes
     *
     * @return The WorldWindow object containing the globe.
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun createWorldWindow() = super.createWorldWindow().also {
        val tutorial = GeographicMeshesTutorial(it.engine)

        // Add click handler to detect intersections
        wwd.setOnTouchListener { _, e ->
            if (wwd.engine.rayThroughScreenPoint(e.x.toDouble(), e.y.toDouble(), clickRay)) {
                tutorial.pickMesh(clickRay, wwd.engine.globe)
                wwd.requestRedraw()
            }
            false
        }

        tutorial.start()
    }
}