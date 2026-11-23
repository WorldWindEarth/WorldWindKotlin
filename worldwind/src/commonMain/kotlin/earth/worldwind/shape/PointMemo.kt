package earth.worldwind.shape

import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle
import earth.worldwind.geom.Angle.Companion.ZERO
import earth.worldwind.geom.Position
import earth.worldwind.geom.Sector
import earth.worldwind.geom.Vec3
import earth.worldwind.globe.Globe
import earth.worldwind.render.RenderContext

/**
 * Per-shape memo of the window-independent terms of a geographic to Cartesian conversion, reused
 * across frames by point shapes ([Placemark], [Label]).
 *
 * Cached terms: the altitude-zero base point and the altitude direction (every projection is
 * linear in altitude: cartesian(lat, lon, h) = base + normal * h), the global elevation model
 * height and the geoid offset. Terrain-clamped surface points are NOT memoized - the tessellated
 * terrain differs per window and per LoD and is still resolved each frame.
 *
 * The terms are kept per [Globe.State] in slots that grow up to [MAX_SLOTS], then reuse the least
 * recently used; each slot keeps a base point per globe offset. Windows with the same globe
 * configuration share a slot, as they share one elevation model. No globe is kept alive.
 *
 * Position and globe configuration changes are detected by value, so in-place position edits stay
 * safe. Altitude mode handling mirrors [RenderContext.geographicToCartesian] (without the useEM
 * flag, which point shapes do not use); the equivalence is pinned by PointMemoTest, which compares
 * both paths for every altitude mode.
 */
open class PointMemo {
    /** The terms of one globe configuration. */
    private class Slot(var globeState: Globe.State?) {
        /** False for the first slot until a globe state claims it. */
        var isClaimed = false
        /** [useCount] at last use, for least recently used reuse. */
        var lastUse = 0L
        val normal = Vec3()
        var hasNormal = false
        // Base point per globe offset, allocated on first use
        val basePoints = arrayOfNulls<Vec3>(Globe.Offset.entries.size)
        var baseMask = 0
        var elevation = 0.0
        var elevationTimestamp = 0L
        var hasElevation = false
        var geoidOffset = 0f
        var hasGeoid = false

        fun reset() {
            hasNormal = false
            baseMask = 0
            hasElevation = false
            hasGeoid = false
        }
    }

    private var latitude = ZERO
    private var longitude = ZERO
    private var hasLocation = false
    private val sector = Sector()
    /** Slot of the current call, checked first. */
    private var slot = Slot(null)
    private var slots = arrayOf<Slot?>(slot)
    private var slotCount = 1
    private var useCount = 0L
    /** Base point of the current call's globe offset. */
    private var basePoint = Vec3()

    /**
     * Forgets all cached terms, forcing recomputation on next use.
     */
    fun invalidate() {
        for (slot in slots) slot?.reset()
    }

    /**
     * Converts a geographic position to Cartesian coordinates according to an [altitudeMode],
     * exactly as [RenderContext.geographicToCartesian] does, reusing the memoized terms.
     */
    fun geographicToCartesian(
        rc: RenderContext, position: Position, altitudeMode: AltitudeMode, result: Vec3
    ) = geographicToCartesian(rc, position.latitude, position.longitude, position.altitude, altitudeMode, result)

    /**
     * Converts a geographic location to Cartesian coordinates according to an [altitudeMode],
     * exactly as [RenderContext.geographicToCartesian] does, reusing the memoized terms.
     */
    fun geographicToCartesian(
        rc: RenderContext, latitude: Angle, longitude: Angle, altitude: Double, altitudeMode: AltitudeMode, result: Vec3
    ): Vec3 {
        prepare(rc, latitude, longitude)
        when (altitudeMode) {
            AltitudeMode.ABSOLUTE -> pointAt(altitude, result)
            AltitudeMode.ABOVE_SEA_LEVEL -> pointAt(altitude + geoidOffset(rc), result)
            AltitudeMode.CLAMP_TO_GROUND -> if (!rc.terrain.surfacePoint(latitude, longitude, result)) {
                // Use elevation model height as a fallback
                pointAt(elevation(rc), result)
            }
            AltitudeMode.RELATIVE_TO_GROUND -> if (rc.terrain.surfacePoint(latitude, longitude, result)) {
                // Offset along the normal vector at the terrain surface point.
                if (altitude != 0.0) offsetAlongNormal(altitude * rc.globe.verticalExaggeration, result)
            } else {
                // Use elevation model height as a fallback
                pointAt(altitude + elevation(rc), result)
            }
        }
        return result
    }

    /**
     * Provides the globe's Cartesian surface normal at a geographic location, reusing the memoized value.
     */
    fun geographicToCartesianNormal(rc: RenderContext, latitude: Angle, longitude: Angle, result: Vec3): Vec3 {
        prepare(rc, latitude, longitude)
        return result.copy(slot.normal)
    }

    /**
     * Revalidates the terms against the position and selects the slot of the current globe
     * configuration.
     */
    private fun prepare(rc: RenderContext, latitude: Angle, longitude: Angle) {
        if (!hasLocation || latitude != this.latitude || longitude != this.longitude) {
            this.latitude = latitude
            this.longitude = longitude
            hasLocation = true
            // Small non-degenerate sector around the location for elevation update intersection tests
            sector.setDegrees(
                latitude.inDegrees - SECTOR_RADIUS_DEGREES, longitude.inDegrees - SECTOR_RADIUS_DEGREES,
                2 * SECTOR_RADIUS_DEGREES, 2 * SECTOR_RADIUS_DEGREES
            )
            invalidate()
        }
        val globe = rc.globe
        val state = rc.globeState
        if (!slot.isClaimed || slot.globeState != state) slot = slotOf(state)
        val slot = slot
        slot.lastUse = ++useCount
        if (!slot.hasNormal) {
            globe.geographicToCartesianNormal(latitude, longitude, slot.normal)
            slot.hasNormal = true
        }
        val index = globe.offset.ordinal
        basePoint = slot.basePoints[index] ?: Vec3().also { slot.basePoints[index] = it }
        if (slot.baseMask and (1 shl index) == 0) {
            globe.geographicToCartesian(latitude, longitude, 0.0, basePoint)
            slot.baseMask = slot.baseMask or (1 shl index)
        }
    }

    /** Slot of [state]: existing, unclaimed, new, or the least recently used at [MAX_SLOTS]. */
    private fun slotOf(state: Globe.State?): Slot {
        var lru = 0
        for (i in 0 until slotCount) {
            val slot = slots[i]!!
            if (slot.isClaimed && slot.globeState == state) return slot
            if (slot.lastUse < slots[lru]!!.lastUse) lru = i
        }
        val reused = slots[lru]!!
        if (reused.isClaimed && slotCount < MAX_SLOTS) {
            if (slotCount == slots.size) slots = slots.copyOf(minOf(slots.size * 2, MAX_SLOTS))
            return Slot(state).also { it.isClaimed = true; slots[slotCount++] = it }
        }
        return reused.also {
            it.globeState = state
            it.isClaimed = true
            it.reset()
        }
    }

    private fun pointAt(altitude: Double, result: Vec3): Vec3 {
        val normal = slot.normal
        return result.set(
            basePoint.x + normal.x * altitude,
            basePoint.y + normal.y * altitude,
            basePoint.z + normal.z * altitude
        )
    }

    private fun offsetAlongNormal(distance: Double, point: Vec3): Vec3 {
        val normal = slot.normal
        return point.set(point.x + normal.x * distance, point.y + normal.y * distance, point.z + normal.z * distance)
    }

    /**
     * The global elevation model height incl. geoid offset, matching [Globe.getElevation].
     * Guarded by the sector-scoped elevation update log, so tile loads elsewhere on the globe
     * keep the cached height; the timestamp advances on every check to stay within the bounded
     * update log window.
     */
    private fun elevation(rc: RenderContext): Double {
        val slot = slot
        val timestamp = rc.elevationModelTimestamp
        if (!slot.hasElevation ||
            timestamp != slot.elevationTimestamp && rc.globe.isElevationChangedSince(slot.elevationTimestamp, sector)
        ) {
            slot.elevation = rc.globe.getElevation(latitude, longitude)
            // Includes the geoid offset: cache only once the geoid has loaded
            slot.hasElevation = rc.globe.geoid.isLoaded
        }
        slot.elevationTimestamp = timestamp
        return slot.elevation
    }

    private fun geoidOffset(rc: RenderContext): Float {
        val slot = slot
        if (!slot.hasGeoid) {
            slot.geoidOffset = rc.globe.geoid.getOffset(latitude, longitude)
            slot.hasGeoid = rc.globe.geoid.isLoaded
        }
        return slot.geoidOffset
    }

    companion object {
        /**
         * Half-size of the elevation invalidation sector. Keeps the sector non-degenerate for the
         * strict [Sector.intersects] used by the update log while staying far below any elevation
         * tile size (~11 meters).
         */
        private const val SECTOR_RADIUS_DEGREES = 1.0e-4
        /** Most globe configurations kept before the least recently used slot is reused. */
        internal const val MAX_SLOTS = 32
    }
}
