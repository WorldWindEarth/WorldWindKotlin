package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Location
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.render.Color
import earth.worldwind.render.image.ImageSource
import earth.worldwind.shape.ShapeAttributes
import earth.worldwind.shape.ProjectedMediaSurface

/**
 * Single-photo orthorectification tutorial. Drapes a still drone photo onto the actual
 * terrain via the engine's [ProjectedMediaSurface] surface drawable, driven by four hard-coded ground
 * corners. The shape lives in the surface-rendering pipeline rather than as a 3D mesh, so
 * the texture follows the actual terrain elevation without ever intersecting hills.
 *
 * Same patch over Pripyat that [ProjectedMediaSurfaceTutorial] uses; this is the static counterpart
 * of [VideoOnTerrainTutorial], which drives the same [ProjectedMediaSurface] from a live KLV stream.
 */
class PhotoOnTerrainTutorial(engine: WorldWind) : AbstractTutorial(engine) {

    private val layer = RenderableLayer("Photo on terrain")

    init {
        layer.addRenderable(
            ProjectedMediaSurface(
                bottomLeft  = Location(51.26891660167462.degrees,  30.0096671570868.degrees),
                bottomRight = Location(51.27062637593827.degrees,  30.01240117718469.degrees),
                topRight    = Location(51.271804026840556.degrees, 30.01029779181104.degrees),
                topLeft     = Location(51.270125460836965.degrees, 30.00779959572076.degrees),
                attributes = ShapeAttributes().apply {
                    interiorImageSource = ImageSource.fromResource(MR.images.korogode_image)
                    isDrawOutline = true
                    outlineWidth = 2.0f
                    outlineColor = Color(1.0f, 0.0f, 0.0f, 1.0f)
                },
            )
        )
    }

    override fun start() {
        super.start()
        engine.layers.addLayer(layer)
        engine.camera.set(
            51.270125460836965.degrees, 30.00989959572076.degrees, 900.0,
            AltitudeMode.ABSOLUTE, heading = Angle.ZERO, tilt = Angle.ZERO, roll = Angle.ZERO,
        )
    }

    override fun stop() {
        super.stop()
        engine.layers.removeLayer(layer)
    }
}
