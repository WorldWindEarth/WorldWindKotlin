package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.formats.gltf.GltfLoader
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.LookAt
import earth.worldwind.geom.Position
import earth.worldwind.layer.RenderableLayer

class GltfTutorial(private val engine: WorldWind) : AbstractTutorial() {
    private val gltfLayer = RenderableLayer("GLTF Box")

    suspend fun setupScene() {
        val position = Position(40.009993372683.degrees, (-105.272774533734).degrees, 1500.0)
        val scene = GltfLoader(position, MR.assets.gltf.box_gltf).parse(engine.renderResourceCache)
        scene.scale = 5000.0
        gltfLayer.clearRenderables()
        gltfLayer.addRenderable(scene)
    }

    override fun start() {
        engine.layers.addLayer(gltfLayer)
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
    }

    override fun stop() {
        engine.layers.removeLayer(gltfLayer)
        gltfLayer.clearRenderables()
    }
}
