package earth.worldwind.layer.ogc3d.tileset

import earth.worldwind.geom.Angle.Companion.radians
import earth.worldwind.geom.BoundingSphere
import earth.worldwind.geom.Matrix4
import earth.worldwind.geom.Vec3
import earth.worldwind.globe.Globe
import kotlin.math.max
import kotlin.math.sqrt

/**
 * One of the three bounding-volume shapes defined in the 3D Tiles 1.0 spec. Each variant
 * carries the raw spec numbers; runtime traversal works against the [worldBoundingSphere]
 * conservatively wrapping the volume.
 *
 * Region is **world-aligned** in the spec: the tile's `transform` matrix does NOT apply to
 * region bounds (they're geographic). Box and Sphere are in tile-local coordinates and the
 * world transform applies.
 */
sealed interface BoundingVolume {
    /** Conservative world-space sphere used for frustum culling + nearest-point distance. */
    fun worldBoundingSphere(globe: Globe, tileToWorld: Matrix4, result: BoundingSphere): BoundingSphere

    /**
     * Geographic region: west, south, east, north (radians) + minHeight, maxHeight (meters).
     */
    data class Region(
        val west: Double,
        val south: Double,
        val east: Double,
        val north: Double,
        val minHeight: Double,
        val maxHeight: Double,
    ) : BoundingVolume {
        override fun worldBoundingSphere(globe: Globe, tileToWorld: Matrix4, result: BoundingSphere): BoundingSphere {
            if (!isFinite(west) || !isFinite(south) || !isFinite(east) || !isFinite(north) ||
                !isFinite(minHeight) || !isFinite(maxHeight)
            ) return result.set(Vec3(0.0, 0.0, 0.0), 0.0)
            val center = Vec3()
            val corner = Vec3()
            var maxDistSq = 0.0
            val midLat = (south + north) * 0.5
            val midLon = (west + east) * 0.5
            val midH = (minHeight + maxHeight) * 0.5
            globe.geographicToCartesian(midLat.radians, midLon.radians, midH, center)
            for (lat in doubleArrayOf(south, north)) {
                for (lon in doubleArrayOf(west, east)) {
                    for (h in doubleArrayOf(minHeight, maxHeight)) {
                        globe.geographicToCartesian(lat.radians, lon.radians, h, corner)
                        val dx = corner.x - center.x
                        val dy = corner.y - center.y
                        val dz = corner.z - center.z
                        val d2 = dx * dx + dy * dy + dz * dz
                        if (d2 > maxDistSq) maxDistSq = d2
                    }
                }
            }
            return result.set(center, sqrt(maxDistSq))
        }
    }

    /**
     * Oriented box: ECEF center + 3 half-axis vectors. Layout per spec is
     * `[cx, cy, cz, hX.x, hX.y, hX.z, hY.x, hY.y, hY.z, hZ.x, hZ.y, hZ.z]`. Coordinates are in
     * tile-local frame; the tile-to-world transform maps them into ECEF.
     */
    data class Box(
        val centerX: Double, val centerY: Double, val centerZ: Double,
        val halfXx: Double, val halfXy: Double, val halfXz: Double,
        val halfYx: Double, val halfYy: Double, val halfYz: Double,
        val halfZx: Double, val halfZy: Double, val halfZz: Double,
    ) : BoundingVolume {
        override fun worldBoundingSphere(globe: Globe, tileToWorld: Matrix4, result: BoundingSphere): BoundingSphere {
            // Degenerate-bounds guard: 3D BAG and other publishers use NaN/Infinity sentinels
            // for tiles with no real geometry. Sanitize before the math so we don't propagate
            // non-finite ECEF coordinates into the frustum cull (which then false-passes on
            // every test). A zero-radius sphere at the origin is the correct degenerate result:
            // it always fails the frustum-intersect test and the tile silently culls.
            if (!isFinite(centerX) || !isFinite(centerY) || !isFinite(centerZ) ||
                !isFinite(halfXx) || !isFinite(halfXy) || !isFinite(halfXz) ||
                !isFinite(halfYx) || !isFinite(halfYy) || !isFinite(halfYz) ||
                !isFinite(halfZx) || !isFinite(halfZy) || !isFinite(halfZz)
            ) return result.set(Vec3(0.0, 0.0, 0.0), 0.0)
            val center = Vec3(centerX, centerY, centerZ).multiplyByMatrix(tileToWorld)
            // Sum of half-axis magnitudes is an exact upper bound on any OBB corner offset.
            val halfX = sqrt(halfXx * halfXx + halfXy * halfXy + halfXz * halfXz)
            val halfY = sqrt(halfYx * halfYx + halfYy * halfYy + halfYz * halfYz)
            val halfZ = sqrt(halfZx * halfZx + halfZy * halfZy + halfZz * halfZz)
            // Scale induced by tileToWorld's linear part. Conservative: max row scale.
            val scale = tileToWorldUniformScale(tileToWorld)
            val radius = scale * sqrt(halfX * halfX + halfY * halfY + halfZ * halfZ)
            return result.set(center, radius)
        }
    }

    /**
     * Sphere: center + radius in tile-local frame. Tile-to-world transform applies.
     */
    data class Sphere(
        val centerX: Double, val centerY: Double, val centerZ: Double,
        val radius: Double,
    ) : BoundingVolume {
        override fun worldBoundingSphere(globe: Globe, tileToWorld: Matrix4, result: BoundingSphere): BoundingSphere {
            if (!isFinite(centerX) || !isFinite(centerY) || !isFinite(centerZ) || !isFinite(radius)) {
                return result.set(Vec3(0.0, 0.0, 0.0), 0.0)
            }
            val center = Vec3(centerX, centerY, centerZ).multiplyByMatrix(tileToWorld)
            val scale = tileToWorldUniformScale(tileToWorld)
            return result.set(center, scale * radius)
        }
    }

    companion object {
        /** True when [v] is a real finite double (rejects NaN and ±Infinity). Inlined locally
         *  to keep the BoundingVolume file self-contained — there's no equivalent in Kotlin
         *  stdlib's commonMain (`Double.isFinite()` lives only on the JVM in older versions). */
        internal fun isFinite(v: Double): Boolean = !v.isNaN() && !v.isInfinite()

        /**
         * Upper bound on the linear scale factor a tile transform applies to a local-space
         * length. Picks the largest of the three column magnitudes; for orthonormal-with-uniform-scale
         * transforms (the common case) all three are equal. For pathologically non-uniform
         * scales this stays conservative — bounding sphere may be slightly larger than tight.
         */
        internal fun tileToWorldUniformScale(m: Matrix4): Double {
            val a = m.m // row-major 4x4
            val sx = sqrt(a[0] * a[0] + a[4] * a[4] + a[8] * a[8])
            val sy = sqrt(a[1] * a[1] + a[5] * a[5] + a[9] * a[9])
            val sz = sqrt(a[2] * a[2] + a[6] * a[6] + a[10] * a[10])
            return max(sx, max(sy, sz))
        }
    }
}
