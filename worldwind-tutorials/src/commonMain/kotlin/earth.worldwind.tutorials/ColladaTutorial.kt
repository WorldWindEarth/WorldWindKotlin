package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.formats.collada.ColladaLoader
import earth.worldwind.formats.collada.ColladaScene
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Line
import earth.worldwind.geom.LookAt
import earth.worldwind.geom.Position
import earth.worldwind.globe.Globe
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.render.Color
import earth.worldwind.shape.Placemark

class ColladaTutorial(engine: WorldWind) : AbstractTutorial(engine) {
    var isStarted = false
        private set

    val colladaLayer = RenderableLayer("COLLADA Duck")
    val pickLayer = RenderableLayer("Ray Intersections")
    private var scene: ColladaScene? = null

    suspend fun setupScene(): ColladaScene {
        val position = Position(40.009993372683.degrees, (-105.272774533734).degrees, 3500.0)
        return ColladaLoader(position, MR.assets.collada.duck_dae).parse(MR.assets, engine.renderResourceCache).also {
            it.scale = 1000.0
            scene = it
            colladaLayer.clearRenderables()
            colladaLayer.addRenderable(it)
        }
    }

    fun pickScene(ray: Line, globe: Globe) {
        val intersections = scene?.rayIntersections(ray, globe)?.sortedBy { it.distance } ?: return
        pickLayer.clearRenderables()
        for ((index, intersection) in intersections.withIndex()) {
            val color = if (index == 0) Color(0f, 1f, 0f, 1f) else Color(1f, 0f, 0f, 1f)
            pickLayer.addRenderable(
                Placemark.createWithColorAndSize(intersection.position, color, 10).apply {
                    altitudeMode = AltitudeMode.ABSOLUTE
                    attributes.isDrawLeader = true
                    attributes.leaderAttributes.outlineColor.copy(color)
                }
            )
        }
    }

    override fun start() {
        super.start()
        engine.layers.addLayer(colladaLayer)
        engine.layers.addLayer(pickLayer)
        engine.cameraFromLookAt(
            LookAt(
                position = Position(40.028.degrees, (-105.27284091410579).degrees, 0.0),
                altitudeMode = AltitudeMode.ABSOLUTE,
                range = 21000.0,
                heading = 0.0.degrees,
                tilt = 0.0.degrees,
                roll = 0.0.degrees
            )
        )
        isStarted = true
    }

    override fun stop() {
        super.stop()
        engine.layers.removeLayer(colladaLayer)
        engine.layers.removeLayer(pickLayer)
        // Don't clear [colladaLayer]'s renderable or null out [scene]: [setupScene] is only
        // invoked once at app start (`mainScope.launch { tutorial.setupScene() }`), so a
        // second [start] re-adds an empty layer otherwise. The pick layer is transient picks
        // accumulated by [pickScene] - safe to drop here.
        pickLayer.clearRenderables()
        isStarted = false
    }
}
