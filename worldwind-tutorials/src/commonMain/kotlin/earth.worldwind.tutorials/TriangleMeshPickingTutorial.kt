package earth.worldwind.tutorials

import earth.worldwind.PickedRenderablePoint
import earth.worldwind.PickedPointMethod
import earth.worldwind.WorldWind
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Line
import earth.worldwind.geom.Position
import earth.worldwind.geom.Position.Companion.fromDegrees
import earth.worldwind.geom.Vec3
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.render.Color
import earth.worldwind.shape.AbstractMesh
import earth.worldwind.shape.ShapeAttributes
import earth.worldwind.shape.TriangleMesh
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin

class TriangleMeshPickingTutorial(private val engine: WorldWind) : AbstractTutorial() {
    private enum class PickType { Mesh, Earth }

    var isStarted = false
        private set

    private val meshLayer = RenderableLayer("Pickable triangle meshes")
    private val feedbackLayer = RenderableLayer("Mesh picking feedback").apply { isPickEnabled = false }
    private val radialPickLine = RadialPickLineRenderable()
    private val terrainPosition = Position()
    private val terrainPoint = Vec3()
    private val radialRay = Line()
    private val radialDirection = Vec3()
    private val radialSurfacePoint = Vec3()
    private val radialEndPoint = Vec3()
    private var selectedMesh: AbstractMesh? = null
    private var pickMarker: TriangleMesh? = null
    var statusText = defaultStatusText
        private set

    init {
        createMeshes()
    }

    override fun start() {
        super.start()
        engine.layers.addLayer(meshLayer)
        engine.layers.addLayer(feedbackLayer)
        if (feedbackLayer.indexOfRenderable(radialPickLine) < 0) feedbackLayer.addRenderable(radialPickLine)
        engine.camera.set(
            37.0.degrees, (-106.0).degrees, 2400000.0,
            AltitudeMode.ABSOLUTE, heading = 0.0.degrees, tilt = 20.0.degrees, roll = Angle.ZERO
        )
        statusText = defaultStatusText
        isStarted = true
    }

    override fun stop() {
        super.stop()
        clearPickFeedback()
        engine.layers.removeLayer(meshLayer)
        engine.layers.removeLayer(feedbackLayer)
        isStarted = false
    }

    fun handlePick(pickResult: PickedRenderablePoint?, pickedTerrainPosition: Position? = null) {
        val pickedMesh = pickResult?.renderable as? AbstractMesh
        if (pickedMesh != null && meshLayer.indexOfRenderable(pickedMesh) >= 0) {
            applyMeshSelection(pickedMesh, pickResult)
            return
        }

        if (pickedTerrainPosition != null) {
            applyEarthSelection(pickedTerrainPosition)
        } else {
            clearPickFeedback()
        }
    }

    fun pickTerrainPosition(x: Double, y: Double): Position? =
        if (engine.pickTerrainPosition(x, y, terrainPosition)) Position(terrainPosition) else null

    fun clearPickFeedback() {
        selectedMesh?.isHighlighted = false
        selectedMesh = null
        pickMarker?.let { feedbackLayer.removeRenderable(it) }
        pickMarker = null
        radialPickLine.hide()
        statusText = defaultStatusText
    }

    private fun applyMeshSelection(mesh: AbstractMesh, pickResult: PickedRenderablePoint) {
        if (selectedMesh !== mesh) {
            selectedMesh?.isHighlighted = false
            selectedMesh = mesh.also { it.isHighlighted = true }
        }
        updateMarker(pickResult.position)
        updateRadialLine(pickResult.cartesianPoint, meshHitLineColor)
        updateStatus(
            type = PickType.Mesh,
            mesh = mesh,
            position = pickResult.position,
            point = pickResult.cartesianPoint,
            method = formatPickMethod(pickResult.method)
        )
    }

    private fun applyEarthSelection(position: Position) {
        selectedMesh?.isHighlighted = false
        selectedMesh = null
        engine.globe.geographicToCartesian(position.latitude, position.longitude, position.altitude, terrainPoint)
        updateMarker(position)
        updateRadialLine(terrainPoint, earthHitLineColor)
        updateStatus(PickType.Earth, null, position, terrainPoint, terrainPickMethod)
    }

    private fun updateMarker(position: Position) {
        pickMarker?.let { feedbackLayer.removeRenderable(it) }
        pickMarker = createMarkerSphere(position).also { feedbackLayer.addRenderable(it) }
    }

    private fun updateRadialLine(point: Vec3, color: Color) {
        radialDirection.copy(point)
        if (radialDirection.magnitude == 0.0) {
            radialPickLine.hide()
            return
        }

        radialDirection.normalize()
        radialRay.origin.set(0.0, 0.0, 0.0)
        radialRay.direction.copy(radialDirection)
        if (!engine.globe.intersect(radialRay, radialSurfacePoint)) {
            radialPickLine.hide()
            return
        }

        radialEndPoint.copy(radialDirection).multiply(radialSurfacePoint.magnitude + radialLineAltitude)
        radialPickLine.show(radialEndPoint, color)
    }

    private fun createMeshes() {
        meshLayer.addRenderable(createCanopyMesh(
            center = fromDegrees(34.5, -112.0, 140e3),
            radiusDegrees = 4.2,
            baseColor = Color(0.93f, 0.62f, 0.20f, 0.90f),
            highlightColor = Color(1.0f, 0.90f, 0.55f, 0.98f)
        ).apply { displayName = "Amber canopy" })

        meshLayer.addRenderable(createCanopyMesh(
            center = fromDegrees(37.5, -104.5, 115e3),
            radiusDegrees = 3.7,
            baseColor = Color(0.12f, 0.69f, 0.72f, 0.88f),
            highlightColor = Color(0.76f, 0.98f, 1.0f, 0.98f)
        ).apply { displayName = "Teal canopy" })

        meshLayer.addRenderable(createCanopyMesh(
            center = fromDegrees(32.8, -98.0, 165e3),
            radiusDegrees = 4.6,
            baseColor = Color(0.74f, 0.22f, 0.29f, 0.88f),
            highlightColor = Color(1.0f, 0.80f, 0.82f, 0.98f)
        ).apply { displayName = "Crimson canopy" })
    }

    private fun createCanopyMesh(
        center: Position,
        radiusDegrees: Double,
        baseColor: Color,
        highlightColor: Color,
        segments: Int = 28,
    ): TriangleMesh {
        val positions = mutableListOf<Position>()
        val indices = mutableListOf<Int>()
        val outlineIndices = mutableListOf<Int>()

        positions.add(Position(center.latitude, center.longitude, center.altitude + 35e3))
        for (i in 0 until segments) {
            val angle = 2.0 * PI * i / segments
            val latitude = center.latitude.inDegrees + sin(angle) * radiusDegrees
            val longitude = center.longitude.inDegrees + cos(angle) * radiusDegrees
            val altitude = center.altitude + 10e3 + sin(angle * 3.0) * 12e3
            positions.add(fromDegrees(latitude, longitude, altitude))
            outlineIndices.add(i + 1)
        }
        outlineIndices.add(1)

        for (i in 1 until segments) {
            indices.add(0)
            indices.add(i)
            indices.add(i + 1)
        }
        indices.add(0)
        indices.add(segments)
        indices.add(1)

        val attributes = ShapeAttributes().apply {
            interiorColor = baseColor
            outlineColor = Color(baseColor).apply { alpha = 1f }
            outlineWidth = 2f
            isLightingEnabled = true
        }
        val highlightAttributes = ShapeAttributes(attributes).apply {
            interiorColor = highlightColor
            outlineColor = Color(1f, 1f, 1f, 1f)
            outlineWidth = 4f
            isLightingEnabled = false
        }

        return TriangleMesh(positions.toTypedArray(), indices.toIntArray(), attributes).apply {
            altitudeMode = AltitudeMode.ABSOLUTE
            this.outlineIndices = outlineIndices.toIntArray()
            this.highlightAttributes = highlightAttributes
        }
    }

    private fun createMarkerSphere(center: Position, radiusMeters: Double = 18e3): TriangleMesh {
        val globe = engine.globe
        val centerPoint = globe.geographicToCartesian(center.latitude, center.longitude, center.altitude, Vec3())
        val latSegments = 10
        val lonSegments = 18
        val positions = Array((latSegments + 1) * (lonSegments + 1)) { Position() }
        val indices = IntArray(latSegments * lonSegments * 6)

        var vertexIndex = 0
        for (lat in 0..latSegments) {
            val phi = PI * lat / latSegments
            val ringRadius = sin(phi) * radiusMeters
            val z = cos(phi) * radiusMeters
            for (lon in 0..lonSegments) {
                val theta = 2.0 * PI * lon / lonSegments
                val x = cos(theta) * ringRadius
                val y = sin(theta) * ringRadius
                positions[vertexIndex++] = globe.cartesianToGeographic(
                    centerPoint.x + x, centerPoint.y + y, centerPoint.z + z, Position()
                )
            }
        }

        var index = 0
        val rowSize = lonSegments + 1
        for (lat in 0 until latSegments) {
            for (lon in 0 until lonSegments) {
                val topLeft = lat * rowSize + lon
                val bottomLeft = topLeft + rowSize
                indices[index++] = topLeft
                indices[index++] = bottomLeft
                indices[index++] = topLeft + 1
                indices[index++] = topLeft + 1
                indices[index++] = bottomLeft
                indices[index++] = bottomLeft + 1
            }
        }

        return TriangleMesh(
            positions = positions,
            indices = indices,
            attributes = ShapeAttributes().apply {
                interiorColor = Color(1f, 0.84f, 0.18f, 1f)
                outlineColor = Color(0.72f, 0.20f, 0.16f, 1f)
                outlineWidth = 1.5f
                isLightingEnabled = true
            }
        ).apply {
            altitudeMode = AltitudeMode.ABSOLUTE
            isPickEnabled = false
        }
    }

    private fun updateStatus(type: PickType, mesh: AbstractMesh?, position: Position, point: Vec3, method: String) {
        val pickedName = when (type) {
            PickType.Mesh -> mesh?.displayName ?: mesh?.let { it::class.simpleName } ?: "Triangle mesh"
            PickType.Earth -> "Earth surface"
        }
        statusText = buildString {
            append("Picked: ")
            append(pickedName)
            append('\n')
            append("Geo: ")
            append(formatLatitude(position.latitude.inDegrees))
            append(", ")
            append(formatLongitude(position.longitude.inDegrees))
            append(", ")
            append(formatDistance(position.altitude))
            append('\n')
            append("Method: ")
            append(method)
            append('\n')
            append("XYZ: ")
            append(formatCartesian(point.x))
            append(", ")
            append(formatCartesian(point.y))
            append(", ")
            append(formatCartesian(point.z))
        }
    }

    private fun formatPickMethod(method: PickedPointMethod) = when (method) {
        PickedPointMethod.DEPTH_UNPROJECTION -> "Depth read + unproject"
        PickedPointMethod.GEOMETRY_RAY_INTERSECTION -> "Geometry ray-triangle intersection"
    }

    private fun formatLatitude(latitude: Double): String {
        val magnitude = abs(latitude)
        return "${formatNumber(magnitude, 4)}°${if (latitude >= 0.0) "N" else "S"}"
    }

    private fun formatLongitude(longitude: Double): String {
        val magnitude = abs(longitude)
        return "${formatNumber(magnitude, 4)}°${if (longitude >= 0.0) "E" else "W"}"
    }

    private fun formatDistance(distanceMeters: Double): String = if (abs(distanceMeters) >= 1000.0) {
        "${formatNumber(distanceMeters / 1000.0, 2)} km"
    } else "${formatNumber(distanceMeters, 0)} m"

    private fun formatCartesian(value: Double) = "${formatNumber(value / 1000.0, 1)} km"

    private fun formatNumber(value: Double, decimals: Int): String {
        val factor = when (decimals) {
            0 -> 1.0
            1 -> 10.0
            2 -> 100.0
            3 -> 1000.0
            else -> 10000.0
        }
        val rounded = round(value * factor) / factor
        val text = rounded.toString()
        return if (decimals == 0) text.substringBefore('.') else text.padDecimals(decimals)
    }

    private fun String.padDecimals(decimals: Int): String {
        val parts = split('.', limit = 2)
        if (parts.size == 1) return this + "." + "0".repeat(decimals)
        val fraction = parts[1]
        return if (fraction.length >= decimals) this else this + "0".repeat(decimals - fraction.length)
    }

    companion object {
        private const val radialLineAltitude = 3_000_000.0
        private const val defaultStatusText = "Move the pointer or tap to inspect a mesh or the globe."
        private const val terrainPickMethod = "Terrain ray intersection"
        private val meshHitLineColor = Color(0.96f, 0.22f, 0.18f, 1f)
        private val earthHitLineColor = Color(0.22f, 0.86f, 0.34f, 1f)
    }
}
