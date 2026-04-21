package earth.worldwind.util

import earth.worldwind.geom.Location
import earth.worldwind.geom.Position
import kotlin.math.abs
import kotlin.math.sign

enum class Pole { NONE, NORTH, SOUTH }

class Intersection(
    var visited: Boolean = false,
    var forPole: Boolean = false,
    var index: Int = -1,
    var linkTo: Int = -1
)

internal class IntersectionPair(
    var indexEnd: Int,
    var indexStart: Int,
    val latitude: Double
)

class ContourInfo(
    val polygons: List<List<Location>>,
    val pole: Pole,
    val poleIndex: Int,
    val iMaps: List<Map<Int, Intersection>>
)

/**
 * Splits polygons that cross the antimeridian and/or contain a pole.
 * Ported from WorldWindTS PolygonSplitter.
 */
class PolygonSplitter {
    private var addedIndex = -1
    private var poleIndexOffset = -1

    companion object {
        /**
         * Splits an array of contours that may cross the antimeridian or contain a pole.
         * @return Pair of (doesCross, list of ContourInfo per input contour)
         */
        fun splitContours(contours: List<List<Location>>): Pair<Boolean, List<ContourInfo>> {
            var doesCross = false
            val results = mutableListOf<ContourInfo>()
            val splitter = PolygonSplitter()
            for (contour in contours) {
                val info = splitter.splitContour(contour)
                if (info.polygons.size > 1) doesCross = true
                results.add(info)
            }
            return Pair(doesCross, results)
        }

        /**
         * Linearly interpolates the latitude where the segment between p1 and p2 crosses the given meridian.
         */
        fun meridianIntersection(p1: Location, p2: Location, meridian: Double): Double? {
            val lon1 = p1.longitude.inDegrees
            var lon2 = p2.longitude.inDegrees
            // Normalize lon2 so linear interpolation goes the correct short way across the antimeridian
            if (lon1 > 0 && lon2 < 0) lon2 += 360.0
            else if (lon1 < 0 && lon2 > 0) lon2 -= 360.0
            if (lon1 == lon2) return null
            val slope = (p2.latitude.inDegrees - p1.latitude.inDegrees) / (lon2 - lon1)
            val effectiveMeridian = if (lon1 > 0) meridian else -meridian
            return p1.latitude.inDegrees + slope * (effectiveMeridian - lon1)
        }
    }

    fun splitContour(points: List<Location>): ContourInfo {
        val iMap = mutableMapOf<Int, Intersection>()
        val newPoints = mutableListOf<Location>()
        val intersections = mutableListOf<IntersectionPair>()
        val polygons = mutableListOf<List<Location>>()
        val iMaps = mutableListOf<Map<Int, Intersection>>()
        var poleIndex = -1

        val pole = findIntersectionAndPole(points, newPoints, intersections, iMap)

        if (intersections.isEmpty()) {
            polygons.add(newPoints.toList())
            iMaps.add(iMap.toMap())
            return ContourInfo(polygons, pole, poleIndex, iMaps)
        }

        if (intersections.size > 2) intersections.sortByDescending { it.latitude }

        var workPoints: MutableList<Location> = newPoints
        var workIMap: MutableMap<Int, Intersection> = iMap

        if (pole != Pole.NONE) {
            // For pole polygons, consume every antimeridian-intersection pair via handleOnePole
            // (inserting pole vertices at each crossing) so the result is always a single sub-polygon.
            // Without this, small rigid rotations that change the intersection count between 1 and 3
            // cause the splitter to flip between one sub-polygon and two, producing visible flicker
            // in the rendered fill topology.
            while (intersections.isNotEmpty()) {
                val handled = handleOnePole(workPoints, intersections, workIMap, pole)
                workPoints = handled.toMutableList()
                workIMap = reindexIntersections(intersections, workIMap, poleIndexOffset)
            }
            polygons.add(workPoints.toList())
            iMaps.add(workIMap.toMap())
            poleIndex = 0
            return ContourInfo(polygons, pole, poleIndex, iMaps)
        }

        linkIntersections(intersections, workIMap)
        poleIndex = makePolygons(workPoints, intersections, workIMap, polygons, iMaps)

        return ContourInfo(polygons, pole, poleIndex, iMaps)
    }

    private fun findIntersectionAndPole(
        points: List<Location>,
        newPoints: MutableList<Location>,
        intersections: MutableList<IntersectionPair>,
        iMap: MutableMap<Int, Intersection>
    ): Pole {
        // Pole containment via winding number: sum the (shortest) longitudinal change around the
        // polygon; |Σ dλ| ≈ 360° when the polygon encircles a pole. This is stable against the
        // floating-point jitter that makes sign-flip / odd-crossing tests flicker near ±180°.
        var windingDegrees = 0.0
        var minLatitude = 90.0
        var maxLatitude = -90.0
        addedIndex = -1
        val len = points.size

        for (i in 0 until len) {
            val pt1 = points[i]
            val pt2 = points[(i + 1) % len]

            minLatitude = minOf(minLatitude, pt1.latitude.inDegrees)
            maxLatitude = maxOf(maxLatitude, pt1.latitude.inDegrees)

            var dLon = pt2.longitude.inDegrees - pt1.longitude.inDegrees
            if (dLon > 180.0) dLon -= 360.0 else if (dLon < -180.0) dLon += 360.0
            windingDegrees += dLon

            if (Location.locationsCrossAntimeridian(listOf(pt1, pt2))) {
                val iLatitude = meridianIntersection(pt1, pt2, 180.0)
                    ?: (pt1.latitude.inDegrees + pt2.latitude.inDegrees) / 2.0
                val iLongitude = (if (sign(pt1.longitude.inDegrees) != 0.0) sign(pt1.longitude.inDegrees) else 1.0) * 180.0

                val pt1Alt = if (pt1 is Position) pt1.altitude else 0.0
                val pt2Alt = if (pt2 is Position) pt2.altitude else 0.0

                safeAdd(newPoints, pt1, i, len)

                val index = newPoints.size
                iMap[index] = Intersection(index = index)
                iMap[index + 1] = Intersection(index = index + 1)
                intersections.add(IntersectionPair(index, index + 1, iLatitude))

                newPoints.add(createPoint(iLatitude, iLongitude, pt1Alt))
                newPoints.add(createPoint(iLatitude, -iLongitude, pt2Alt))

                safeAdd(newPoints, pt2, i + 1, len)
            } else {
                safeAdd(newPoints, pt1, i, len)
                safeAdd(newPoints, pt2, i + 1, len)
            }
        }

        return if (abs(windingDegrees) > 180.0) determinePole(minLatitude, maxLatitude) else Pole.NONE
    }

    private fun determinePole(minLatitude: Double, maxLatitude: Double) = when {
        minLatitude > 0 -> Pole.NORTH
        maxLatitude < 0 -> Pole.SOUTH
        abs(maxLatitude) >= abs(minLatitude) -> Pole.NORTH
        else -> Pole.SOUTH
    }

    private fun createPoint(latitude: Double, longitude: Double, altitude: Double): Location =
        if (altitude != 0.0) Position.fromDegrees(latitude, longitude, altitude)
        else Location.fromDegrees(latitude, longitude)

    private fun handleOnePole(
        points: List<Location>,
        intersections: MutableList<IntersectionPair>,
        iMap: MutableMap<Int, Intersection>,
        pole: Pole
    ): List<Location> {
        val intersection = if (pole == Pole.NORTH) intersections.removeAt(0) else intersections.removeAt(intersections.size - 1)
        val poleLat = if (pole == Pole.NORTH) 90.0 else -90.0

        val iEnd = iMap[intersection.indexEnd]!!
        val iStart = iMap[intersection.indexStart]!!
        iEnd.forPole = true
        iStart.forPole = true

        poleIndexOffset = intersection.indexStart

        val endAlt = if (points[iEnd.index] is Position) (points[iEnd.index] as Position).altitude else 0.0
        val startAlt = if (points[iStart.index] is Position) (points[iStart.index] as Position).altitude else 0.0

        val result = mutableListOf<Location>()
        result.addAll(points.subList(0, intersection.indexEnd + 1))
        result.add(createPoint(poleLat, points[iEnd.index].longitude.inDegrees, endAlt))
        result.add(createPoint(poleLat, points[iStart.index].longitude.inDegrees, startAlt))
        result.addAll(points.subList(poleIndexOffset, points.size))
        return result
    }

    private fun reindexIntersections(
        intersections: MutableList<IntersectionPair>,
        iMap: MutableMap<Int, Intersection>,
        indexOffset: Int
    ): MutableMap<Int, Intersection> {
        val newMap = mutableMapOf<Int, Intersection>()
        for ((index, entry) in iMap) {
            val newIndex = if (index >= indexOffset) index + 2 else index
            entry.index = newIndex
            newMap[newIndex] = entry
        }
        for (pair in intersections) {
            if (pair.indexEnd >= indexOffset) pair.indexEnd += 2
            if (pair.indexStart >= indexOffset) pair.indexStart += 2
        }
        return newMap
    }

    private fun linkIntersections(intersections: List<IntersectionPair>, iMap: MutableMap<Int, Intersection>) {
        var i = 0
        while (i < intersections.size - 1) {
            val i0 = intersections[i]
            val i1 = intersections[i + 1]
            iMap[i0.indexEnd]!!.linkTo = i1.indexStart
            iMap[i0.indexStart]!!.linkTo = i1.indexEnd
            iMap[i1.indexEnd]!!.linkTo = i0.indexStart
            iMap[i1.indexStart]!!.linkTo = i0.indexEnd
            i += 2
        }
    }

    private fun makePolygons(
        points: List<Location>,
        intersections: List<IntersectionPair>,
        iMap: Map<Int, Intersection>,
        polygons: MutableList<List<Location>>,
        iMaps: MutableList<Map<Int, Intersection>>
    ): Int {
        var poleIndex = -1
        var i = 0
        while (i < intersections.size - 1) {
            val i0 = intersections[i]
            val i1 = intersections[i + 1]

            val poly1 = mutableListOf<Location>()
            val map1 = mutableMapOf<Int, Intersection>()
            if (makePolygon(i0.indexStart, i1.indexEnd, points, iMap, poly1, map1)) poleIndex = polygons.size
            if (poly1.isNotEmpty()) { polygons.add(poly1); iMaps.add(map1) }

            val poly2 = mutableListOf<Location>()
            val map2 = mutableMapOf<Int, Intersection>()
            if (makePolygon(i1.indexStart, i0.indexEnd, points, iMap, poly2, map2)) poleIndex = polygons.size
            if (poly2.isNotEmpty()) { polygons.add(poly2); iMaps.add(map2) }

            i += 2
        }
        return poleIndex
    }

    private fun makePolygon(
        start: Int, end: Int,
        points: List<Location>, iMap: Map<Int, Intersection>,
        result: MutableList<Location>, resultMap: MutableMap<Int, Intersection>
    ): Boolean {
        var pass = false
        var containsPole = false
        val len = points.size
        val effectiveEnd = if (end < start) end + len else end
        var i = start
        while (i <= effectiveEnd) {
            val idx = i % len
            val intersection = iMap[idx]
            if (intersection != null) {
                if (intersection.visited) break
                result.add(points[idx])
                resultMap[result.size - 1] = intersection
                if (intersection.forPole) {
                    containsPole = true
                } else {
                    if (pass) {
                        i = intersection.linkTo - 1
                        if (i + 1 == start) break
                    }
                    pass = !pass
                    intersection.visited = true
                }
            } else {
                result.add(points[idx])
            }
            i++
        }
        return containsPole
    }

    private fun safeAdd(points: MutableList<Location>, point: Location, index: Int, len: Int) {
        if (addedIndex < index && addedIndex < len - 1) {
            points.add(point)
            addedIndex = index
        }
    }
}
