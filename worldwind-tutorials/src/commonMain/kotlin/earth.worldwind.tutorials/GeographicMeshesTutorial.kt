package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Position
import earth.worldwind.geom.Vec2
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.render.Color
import earth.worldwind.render.image.ImageSource
import earth.worldwind.shape.*
import kotlin.math.*

/**
 * Illustrates how to display GeographicMesh shapes.
 */
class GeographicMeshesTutorial(private val engine: WorldWind) : AbstractTutorial() {

    private val meshLayer = RenderableLayer("Geographic Mesh")
    private val placemarkLayer = RenderableLayer("Intersection Points")

    override fun start() {
        super.start()
        crearteMeshes()
        engine.layers.apply {
            addLayer(meshLayer)
            addLayer(placemarkLayer)
        }
        engine.camera.set(
            32.5.degrees, (-115.0).degrees, 1200000.0,
            AltitudeMode.ABSOLUTE, heading = Angle.ZERO, tilt = Angle.ZERO, roll = Angle.ZERO
        )
    }

    override fun stop() {
        super.stop()
        engine.layers.apply {
            removeLayer(meshLayer)
            removeLayer(placemarkLayer)
        }
    }

    private fun crearteMeshes() {
        // Create the mesh's positions.
        val meshPositions = mutableListOf<Array<Position>>()
        var lat = 30.0
        while (lat <= 35.0) {
            val row = mutableListOf<Position>()
            var lon = -120.0
            while (lon <= -110.0) {
                // Create elevations that follow a sine wave in latitude and a cosine wave in longitude.
                val elevationScale = sin(((lat - 30) / 5) * 2 * PI) * cos(((lon + 120) / 10) * 2 * PI)
                row.add(Position.fromDegrees(lat, lon, 100e3 * (1 + elevationScale)))
                lon += 0.5
            }
            meshPositions.add(row.toTypedArray())
            lat += 0.5
        }

        // Create a mesh with a texture image.

        // Create the mesh.
        var mesh = GeographicMesh(meshPositions.toTypedArray())

        // Create and assign the mesh's attributes. Light this mesh.
        var meshAttributes = ShapeAttributes()
        meshAttributes.outlineColor = Color(0f, 0f, 1f, 1f)
        meshAttributes.interiorColor = Color(1f, 1f, 1f, 1f)
        meshAttributes.interiorImageSource = ImageSource.fromResource(MR.images.splash_nww)
        meshAttributes.isLightingEnabled = true
        mesh.attributes = meshAttributes

        // Create and assign the mesh's highlight attributes.
        var highlightAttributes = ShapeAttributes(meshAttributes)
        highlightAttributes.outlineColor = Color(1f, 0f, 0f, 1f)
        highlightAttributes.interiorColor = Color(1f, 1f, 1f, 1f)
        highlightAttributes.isLightingEnabled = false
        mesh.highlightAttributes = highlightAttributes
        meshLayer.addRenderable(mesh)

        // Create a mesh that displays a custom image.

        // Create custom image with a 2D canvas.
        val imageFactory = TriangleMeshImageFactory()

        // Create the mesh's positions.
        meshPositions.clear()
        lat = 30.0
        while (lat <= 35.0) {
            val row = mutableListOf<Position>()
            var lon = -100.0
            while (lon <= -90.0) {
                row.add(Position.fromDegrees(lat, lon, 100e3))
                lon += 0.5
            }
            meshPositions.add(row.toTypedArray())
            lat += 0.5
        }

        // Create the mesh.
        mesh = GeographicMesh(meshPositions.toTypedArray())

        // Create and assign the mesh's attributes.
        meshAttributes = ShapeAttributes()
        meshAttributes.outlineColor = Color(0f, 0f, 1f, 1f)
        meshAttributes.interiorColor = Color(1f, 1f, 1f, 0.7f)
        meshAttributes.interiorImageSource = ImageSource.fromImageFactory(imageFactory)
        meshAttributes.isLightingEnabled = false
        mesh.attributes = meshAttributes

        // Create and assign the mesh's highlight attributes.
        highlightAttributes = ShapeAttributes(meshAttributes)
        highlightAttributes.outlineColor = Color(1f, 1f, 1f, 1f)
        mesh.highlightAttributes = highlightAttributes


        // Add the shape to the layer.
        meshLayer.addRenderable(mesh);

        // Create placemark attributes
        val placemarkAttributes = PlacemarkAttributes()
        placemarkAttributes.labelAttributes.scale = 1.0
        placemarkAttributes.labelAttributes.textColor = Color(1f, 1f, 1f, 1f)
        placemarkAttributes.leaderAttributes.outlineColor = Color(1f, 0f, 0f, 1f)
        placemarkAttributes.isDrawLeader = true

        val closestPlacemarkAttributes = PlacemarkAttributes(placemarkAttributes)
        closestPlacemarkAttributes.labelAttributes.textColor = Color(0f, 1f, 0f, 1f)
        closestPlacemarkAttributes.leaderAttributes.outlineColor = Color(0f, 1f, 0f, 1f)

//        // Add click handler to detect intersections
//        wwd.addEventListener("click", function(e) {
//            if (!(e instanceof PointerEvent)) return
//
//            placemarkLayer.clearRenderables()
//
//            val clickPoint = wwd.canvasCoordinates(e.clientX, e.clientY)
//            val clickRay = wwd.rayThroughScreenPoint(clickPoint)
//
//            // Get all the meshes in the mesh layer
//            val meshes = meshLayer.getRenderables()
//
//            for (renderable in meshes) {
//                if (renderable instanceof GeographicMesh) {
//                    val currentMesh = renderable as GeographicMesh
//                    val intersections = currentMesh.rayIntersections(clickRay, wwd.drawContext)
//
//                    // Add a placemark for each intersection
//                    for (i in intersections.indices) {
//                        val intersection = intersections[i]
//                        val placemark = Placemark(intersection.position)
//                        placemark.isEyeDistanceScaling = true
//                        placemark.altitudeMode = AltitudeMode.ABSOLUTE
//                        placemark.isAlwaysOnTop = true
//
//                        // Use different attributes for the closest intersection
//                        if (i == 0) {
//                            placemark.attributes = closestPlacemarkAttributes
//                        } else {
//                            placemark.attributes = placemarkAttributes
//                        }
//
//                        // Create a descriptive label showing the intersection number
//                        val distanceInMeters = intersection.distance.roundToImt()
//                        val labelText = "Intersection ${i + 1}\nDistance from camera: $distanceInMeters m"
//
//                        // Set the label on the placemark
//                        placemark.label = labelText
//                        placemarkLayer.addRenderable(placemark);
//                }
//            }
//        }
    }
}