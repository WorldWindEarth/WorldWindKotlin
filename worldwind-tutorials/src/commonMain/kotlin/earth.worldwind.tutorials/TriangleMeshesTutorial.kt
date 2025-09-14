package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.geom.*
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.globe.Globe
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.render.Color
import earth.worldwind.render.image.ImageSource
import earth.worldwind.shape.Placemark
import earth.worldwind.shape.PlacemarkAttributes
import earth.worldwind.shape.ShapeAttributes
import earth.worldwind.shape.TriangleMesh
import kotlin.math.*

/**
 * Illustrates how to display shapes built with triangles.
 */
class TriangleMeshesTutorial(private val engine: WorldWind) : AbstractTutorial() {

    var isStarted = false
        private set
    private val meshLayer = RenderableLayer("Triangle Meshes")
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
            40.05.degrees, (-105.15).degrees, 2800000.0,
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
        // Create a mesh that displays a texture image from an image file.
        val altitude = 100e3
        val numRadialPositions = 40
        val meshIndices = mutableListOf<Int>()
        val outlineIndices = mutableListOf<Int>()
        val texCoords = mutableListOf<Vec2>()
        val meshRadius = 5 // degrees
        val meshPositions = mutableListOf<Position>()

        // Create the mesh's positions, which are the center point of a circle followed by points on the circle.
        meshPositions.add(Position.fromDegrees(35.0, -115.0, altitude)) // the mesh center
        texCoords.add(Vec2(0.5, 0.5))

        for (angle in 0 until 360 step 360 / numRadialPositions) {
            val angleRadians = angle * Angle.DEGREES_TO_RADIANS
            val lat = meshPositions[0].latitude.inDegrees + sin(angleRadians) * meshRadius
            val lon = meshPositions[0].longitude.inDegrees + cos(angleRadians) * meshRadius
            val t = 0.5 * (1 + sin(angleRadians))
            val s = 0.5 * (1 + cos(angleRadians))

            meshPositions.add(Position.fromDegrees(lat, lon, altitude))
            texCoords.add(Vec2(s, t))
        }

        // Create the mesh indices.
        for (i in 1 until numRadialPositions) {
            meshIndices.add(0)
            meshIndices.add(i)
            meshIndices.add(i + 1)
        }

        // Close the circle.
        meshIndices.add(0)
        meshIndices.add(numRadialPositions)
        meshIndices.add(1)

        // Create the outline indices.
        for (j in 1..numRadialPositions) outlineIndices.add(j)

        // Close the outline.
        outlineIndices.add(1)

        // Create the mesh's attributes. Light this mesh.
        var meshAttributes = ShapeAttributes().apply {
            outlineColor = Color(0f, 0f, 1f, 1f)
            interiorColor = Color(1f, 1f, 1f, 1f)
            interiorImageSource = ImageSource.fromResource(MR.images.manhole)
            isLightingEnabled = true
        }

        // Create the mesh's highlight attributes.
        var highlightAttributes = ShapeAttributes(meshAttributes).apply {
            outlineColor = Color(1f, 0f, 0f, 1f)
            interiorColor = Color(1f, 1f, 1f, 0.5f)
            isLightingEnabled = false
        }

        // Create the mesh.
        val firstMesh = TriangleMesh(meshPositions.toTypedArray(), meshIndices.toIntArray(), meshAttributes)
        firstMesh.textureCoordinates = texCoords.toTypedArray()
        firstMesh.outlineIndices = outlineIndices.toIntArray()
        firstMesh.highlightAttributes = highlightAttributes
        meshLayer.addRenderable(firstMesh)

        // Create a mesh that displays a custom image created with a 2D canvas.
       val imageFactory = TriangleMeshImageFactory()

        // Create the mesh's positions.
        meshPositions.clear() // Use a new positions array.
        meshPositions.add(Position.fromDegrees(35.0, -95.0, altitude)) // the mesh center

        for (angle in 0 until 360 step 360 / numRadialPositions) {
            val angleRadians = angle * Angle.DEGREES_TO_RADIANS
            val lat = meshPositions[0].latitude.inDegrees + sin(angleRadians) * meshRadius
            val lon = meshPositions[0].longitude.inDegrees + cos(angleRadians) * meshRadius

            meshPositions.add(Position.fromDegrees(lat, lon, altitude))
        }

        // Use the same attributes as before, except for the image source, which is now the custom image.
        meshAttributes = ShapeAttributes(meshAttributes).apply {
            interiorImageSource = ImageSource.fromImageFactory(imageFactory)
        }

        // Use the same highlight attributes as the previous shape. Point the image source to the custom image.
        highlightAttributes = ShapeAttributes(highlightAttributes).apply {
            interiorImageSource = ImageSource.fromImageFactory(imageFactory)
        }

        // Create the mesh.
        val secondMesh = TriangleMesh(meshPositions.toTypedArray(), meshIndices.toIntArray(), meshAttributes)
        secondMesh.textureCoordinates = texCoords.toTypedArray()
        secondMesh.outlineIndices = outlineIndices.toIntArray()
        secondMesh.highlightAttributes = highlightAttributes
        meshLayer.addRenderable(secondMesh)

        // Create a rectangular grid mesh with texture
        val latMin = 45.0
        val latMax = 50.0
        val latStep = 0.5
        val lonMin = -110.0
        val lonMax = -100.0
        val lonStep = 0.5
        val numLat = floor((latMax - latMin) / latStep).toInt() + 1
        val numLon = floor((lonMax - lonMin) / lonStep).toInt() + 1

        // Create flattened position array
        val gridPositions = mutableListOf<Position>()
        val gridIndices = mutableListOf<Int>()
        val gridTexCoords = mutableListOf<Vec2>()

        // Create positions and texture coordinates
        for (iLat in 0 until numLat) {
            for (iLon in 0 until numLon) {
                val lat = latMin + iLat * latStep
                val lon = lonMin + iLon * lonStep

                // Create elevations that follow a sine wave in latitude and a cosine wave in longitude.
                val elevationScale = sin(((lat - 30) / 5) * 2 * PI) * cos(((lon + 120) / 10) * 2 * PI)

                gridPositions.add(Position.fromDegrees(lat, lon, 100e3 * (1 + elevationScale)))

                // Texture coordinates from 0 to 1
                gridTexCoords.add(Vec2(iLon.toDouble() / (numLon - 1), iLat.toDouble() / (numLat - 1)))
            }
        }

        // Create triangle indices
        for (iLat in 0 until numLat - 1) {
            for (iLon in 0 until numLon - 1) {
                val idx = iLat * numLon + iLon

                // First triangle
                gridIndices.add(idx)
                gridIndices.add(idx + numLon)
                gridIndices.add(idx + 1)

                // Second triangle
                gridIndices.add(idx + 1)
                gridIndices.add(idx + numLon)
                gridIndices.add(idx + numLon + 1)
            }
        }

        // Create the mesh attributes
        val gridMeshAttributes = ShapeAttributes().apply {
            outlineColor = Color(0f, 0f, 1f, 1f)
            interiorColor = Color(1f, 1f, 1f, 1f)
            interiorImageSource = ImageSource.fromResource(MR.images.aladdin_carpet)
            isLightingEnabled = true
        }

        // Create the mesh
        val gridMesh = TriangleMesh(gridPositions.toTypedArray(), gridIndices.toIntArray(), gridMeshAttributes)
        gridMesh.textureCoordinates = gridTexCoords.toTypedArray()
        meshLayer.addRenderable(gridMesh);
    }

    fun pickMesh(clickRay: Line, globe: Globe) {
        placemarkLayer.clearRenderables()

        // Check for intersections with each of the meshes
        for (currentMesh in meshLayer) if (currentMesh is TriangleMesh) {
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

                // Set labels for each placemark in every intersection point, showing
                // the intersection number and the distance from the camera.

                // Create a descriptive label showing the intersection number
                val distanceInMeters = intersection.distance.roundToInt()
                placemark.label = "Intersection ${i + 1}: $distanceInMeters m"
                placemarkLayer.addRenderable(placemark)
            }
        }
    }
}