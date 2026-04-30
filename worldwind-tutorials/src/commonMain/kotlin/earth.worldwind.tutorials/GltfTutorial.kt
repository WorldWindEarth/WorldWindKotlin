package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.formats.gltf.GltfLoader
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.LookAt
import earth.worldwind.geom.Position
import earth.worldwind.layer.RenderableLayer

class GltfTutorial(engine: WorldWind) : AbstractTutorial(engine), PickIndicatorTutorial {
    private val gltfLayer = RenderableLayer("GLTF Box")
    override val picker = PickResultIndicator()
    var isStarted = false
        private set

    suspend fun setupScene() {
        val position = Position(40.009993372683.degrees, (-105.272774533734).degrees, 1500.0)
        val scene = GltfLoader(position, MR.assets.gltf.box_gltf).parse(engine.renderResourceCache)
        scene.scale = 5000.0
        gltfLayer.clearRenderables()
        gltfLayer.addRenderable(scene)
    }

    override fun start() {
        super.start()
        engine.layers.addLayer(gltfLayer)
        picker.attach(engine)
        engine.cameraFromLookAt(
            LookAt(
                position = Position(40.009993372683.degrees, (-105.272774533734).degrees, 1500.0),
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
        engine.layers.removeLayer(gltfLayer)
        // Don't clear [gltfLayer]'s renderable: [setupScene] runs only once at app startup
        // (`mainScope.launch { tutorial.setupScene() }`), so a second [start] would re-add
        // an empty layer if we dropped the scene here.
        picker.detach(engine)
        isStarted = false
    }
}
