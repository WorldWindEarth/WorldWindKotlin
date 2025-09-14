package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Line
import earth.worldwind.geom.Position
import earth.worldwind.globe.Globe
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.render.Color
import earth.worldwind.render.image.ImageSource
import earth.worldwind.shape.*
import kotlin.math.*

/**
 * Illustrates how to display GeographicMesh shapes.
 */
class GeographicMeshesTutorial(private val engine: WorldWind) : AbstractTutorial() {

    var isStarted = false
        private set
    private val meshLayer = RenderableLayer("Geographic Mesh")
    private val placemarkLayer = RenderableLayer("Intersection Points")
    private val placemarkAttributes = PlacemarkAttributes().apply {
        labelAttributes.scale = 1.0
        labelAttributes.textColor = Color(1f, 1f, 1f, 1f)
        leaderAttributes.outlineColor = Color(1f, 0f, 0f, 1f)
        isDrawLeader = true
    }
    private val closestPlacemarkAttributes = PlacemarkAttributes(placemarkAttributes).apply {
        labelAttributes.textColor = Color(0f, 1f, 0f, 1f)
        leaderAttributes.outlineColor = Color(0f, 1f, 0f, 1f)
    }

    init {
        crearteMeshes()
    }

    override fun start() {
        super.start()
        engine.layers.apply {
            addLayer(meshLayer)
            addLayer(placemarkLayer)
        }
        engine.camera.set(
            32.5.degrees, (-115.0).degrees, 1200000.0,
            AltitudeMode.ABSOLUTE, heading = Angle.ZERO, tilt = Angle.ZERO, roll = Angle.ZERO
        )
        isStarted = true
    }

    override fun stop() {
        super.stop()
        engine.layers.apply {
            removeLayer(meshLayer)
            removeLayer(placemarkLayer)
        }
        isStarted = false
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
        var mesh = GeographicMesh(meshPositions.toTypedArray())
        meshLayer.addRenderable(mesh)

        // Create and assign the mesh's attributes. Light this mesh.
        mesh.attributes = ShapeAttributes().apply {
            outlineColor = Color(0f, 0f, 1f, 1f)
            interiorColor = Color(1f, 1f, 1f, 1f)
            interiorImageSource = ImageSource.fromResource(MR.images.aladdin_carpet)
            isLightingEnabled = true
        }

        // Create and assign the mesh's highlight attributes.
        mesh.highlightAttributes = ShapeAttributes(mesh.attributes).apply {
            outlineColor = Color(1f, 0f, 0f, 1f)
            interiorColor = Color(1f, 1f, 1f, 1f)
            isLightingEnabled = false
        }

        // Create a mesh that displays a custom image.
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
        meshLayer.addRenderable(mesh)

        // Create and assign the mesh's attributes.
        mesh.attributes = ShapeAttributes().apply {
            outlineColor = Color(0f, 0f, 1f, 1f)
            interiorColor = Color(1f, 1f, 1f, 0.7f)
            interiorImageSource = ImageSource.fromImageFactory(imageFactory)
            isLightingEnabled = false
        }

        // Create and assign the mesh's highlight attributes.
        mesh.highlightAttributes = ShapeAttributes(mesh.attributes).apply {
            outlineColor = Color(1f, 1f, 1f, 1f)
        }
    }

    fun pickMesh(clickRay: Line, globe: Globe) {
        placemarkLayer.clearRenderables()

        // Get all the meshes in the mesh layer
        for (currentMesh in meshLayer) if (currentMesh is GeographicMesh) {
            // Find all intersections
            val intersections = currentMesh.rayIntersections(clickRay, globe)

            // Add a placemark for each intersection
            for (i in intersections.indices) {
                val intersection = intersections[i]
                val placemark = Placemark(intersection.position)
                placemark.isEyeDistanceScaling = true
                placemark.altitudeMode = AltitudeMode.ABSOLUTE
                placemark.isAlwaysOnTop = true

                // Use different attributes for the closest intersection
                if (i == 0) placemark.attributes = closestPlacemarkAttributes
                else placemark.attributes = placemarkAttributes

                // Create a descriptive label showing the intersection number
                val distanceInMeters = intersection.distance.roundToInt()
                placemark.label = "Intersection ${i + 1}: $distanceInMeters m"
                placemarkLayer.addRenderable(placemark);
            }
        }
    }
}