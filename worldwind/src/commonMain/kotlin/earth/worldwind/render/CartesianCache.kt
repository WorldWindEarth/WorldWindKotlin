package earth.worldwind.render

import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle
import earth.worldwind.geom.Angle.Companion.ZERO
import earth.worldwind.geom.Vec3
import earth.worldwind.globe.Globe
import earth.worldwind.globe.terrain.Terrain
import earth.worldwind.globe.terrain.TerrainTile

/**
 * Memo of a single geographic to Cartesian conversion, reused across frames by point shapes. Pass an instance to
 * [RenderContext.geographicToCartesian] or [RenderContext.geographicToCartesianNormal] to skip recomputation on frames
 * where the result could not have changed. Position, globe state, globe offset and vertical exaggeration changes are
 * detected by value comparison. Terrain-dependent results additionally remain valid only while the terrain tile which
 * produced them is rendered with unchanged geometry, which keeps clamped points in sync with terrain LoD selection.
 */
class CartesianCache {
    private val point = Vec3()
    private var valid = false
    private var latitude = ZERO
    private var longitude = ZERO
    private var altitude = 0.0
    // Null altitude mode marks a cached surface normal entry
    private var altitudeMode: AltitudeMode? = null
    private var globeState: Globe.State? = null
    private var globeOffset: Globe.Offset? = null
    private var verticalExaggeration = 0.0
    private var terrainVersion = Terrain.UNKNOWN_VERSION
    private var tileKey: String? = null
    private var tileVersion = 0
    private var elevationTimestamp = 0L

    /**
     * Forgets the cached result, forcing recomputation on next use.
     */
    fun invalidate() { valid = false }

    internal fun lookup(
        rc: RenderContext, latitude: Angle, longitude: Angle, altitude: Double, altitudeMode: AltitudeMode?, result: Vec3
    ): Boolean {
        if (!valid || altitudeMode != this.altitudeMode || latitude != this.latitude || longitude != this.longitude
            || rc.globeState != globeState || rc.globe.offset != globeOffset) return false
        val isNormal = altitudeMode == null
        if (!isNormal && (altitude != this.altitude || rc.globe.verticalExaggeration != verticalExaggeration)) return false
        if (altitudeMode == AltitudeMode.CLAMP_TO_GROUND || altitudeMode == AltitudeMode.RELATIVE_TO_GROUND) {
            val version = rc.terrain.version
            if (version == Terrain.UNKNOWN_VERSION) return false
            if (version != terrainVersion) {
                // Keep the point if its covering tile is intact and only other parts of the terrain changed
                val key = tileKey
                if (key == null || !rc.terrain.containsTile(key, tileVersion)) return false
                terrainVersion = version
            } else if (tileKey == null && rc.elevationModelTimestamp != elevationTimestamp) return false
        }
        result.copy(point)
        return true
    }

    internal fun store(
        rc: RenderContext, latitude: Angle, longitude: Angle, altitude: Double, altitudeMode: AltitudeMode?,
        tile: TerrainTile?, computed: Vec3
    ) {
        val terrainDependent = altitudeMode == AltitudeMode.CLAMP_TO_GROUND || altitudeMode == AltitudeMode.RELATIVE_TO_GROUND
        point.copy(computed)
        this.latitude = latitude
        this.longitude = longitude
        this.altitude = altitude
        this.altitudeMode = altitudeMode
        globeState = rc.globeState
        globeOffset = rc.globe.offset
        verticalExaggeration = rc.globe.verticalExaggeration
        terrainVersion = if (terrainDependent) rc.terrain.version else Terrain.UNKNOWN_VERSION
        tileKey = tile?.tileKey
        tileVersion = tile?.pointBufferVersion ?: 0
        elevationTimestamp = rc.elevationModelTimestamp
        valid = true
    }
}
