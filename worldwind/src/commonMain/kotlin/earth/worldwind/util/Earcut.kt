package earth.worldwind.util

import kotlin.math.abs

/**
 * Ear-clipping polygon triangulator — a Kotlin port of mapbox/earcut (ISC licence). It is the
 * triangulator MVT renderers (Mapbox GL, MapLibre, deck.gl) use for polygon fills: far cheaper than
 * a GLU-style sweep-line tessellator because it builds only a small doubly-linked node list (no
 * half-edge mesh), and it stays robust on real-world rings (holes, near-collinear / self-touching
 * edges) via its hole-bridging and `cureLocalIntersections` / `splitEarcut` fallbacks.
 *
 * **Struct-of-arrays arena.** The reference port allocates one `Node` object per polygon vertex (plus
 * two per hole bridge / split) — millions of short-lived objects across a pan session, the dominant
 * GC source. This implementation stores node state in parallel primitive arrays indexed by an Int
 * node id (`NULL` = -1 for the absent link), reused across [triangulate] calls on the same instance.
 * The algorithm and the emitted triangles are identical to the object port — only the memory layout
 * differs. Hold one instance per assembler (it is not thread-safe; the arena is shared mutable state)
 * and reuse it across all of a tile's features; per-tile allocation drops from O(vertices) to O(1).
 */
class Earcut {

    // SoA node arena, indexed by node id [0, count). Grown geometrically, reused across calls.
    private var cap = 0
    private var count = 0
    private var nI = IntArray(0)        // vertex ordinal (index into the caller's data, the emitted id)
    private var nX = DoubleArray(0)
    private var nY = DoubleArray(0)
    private var nPrev = IntArray(0)      // circular ring links (never NULL)
    private var nNext = IntArray(0)
    private var nZ = IntArray(0)         // z-order value (0 = not yet computed)
    private var nPrevZ = IntArray(0)     // z-order chain links (NULL when unset)
    private var nNextZ = IntArray(0)
    private var nSteiner = BooleanArray(0)

    // Reused hole leftmost-node queue, sorted by x. Few entries per tile; cleared per call.
    private val holeQueue = ArrayList<Int>()

    // Operation budget for splitEarcut's O(n³) self-intersection search, decremented per intersectsPolygon
    // step and checked in splitEarcut. Node count is a poor bail proxy — a clean ring never reaches
    // splitEarcut at all (the ear-clip + hash fast path handles it), so a complex coastline that does need
    // one split should be allowed to finish; only a pathological ring that genuinely churns must bail.
    // Bailing leaves the residue untriangulated (missing fill), never garbage.
    private var splitOps = 0

    private fun ensureCap(n: Int) {
        if (n <= cap) return
        val c = maxOf(n, cap * 2, 64)
        nI = nI.copyOf(c); nX = nX.copyOf(c); nY = nY.copyOf(c)
        nPrev = nPrev.copyOf(c); nNext = nNext.copyOf(c); nZ = nZ.copyOf(c)
        nPrevZ = nPrevZ.copyOf(c); nNextZ = nNextZ.copyOf(c); nSteiner = nSteiner.copyOf(c)
        cap = c
    }

    private fun newNode(i: Int, x: Double, y: Double): Int {
        if (count == cap) ensureCap(count + 1)
        val id = count++
        nI[id] = i; nX[id] = x; nY[id] = y
        nPrev[id] = id; nNext[id] = id; nZ[id] = 0; nPrevZ[id] = NULL; nNextZ[id] = NULL; nSteiner[id] = false
        return id
    }

    /** Free the arena's backing arrays after a batch of triangulations, keeping the instance reusable.
     *  Next [triangulate] regrows on demand — call once an assembler is done to avoid retaining the
     *  largest feature's node arrays for the tile's whole lifetime. */
    fun release() {
        cap = 0; count = 0
        nI = IntArray(0); nX = DoubleArray(0); nY = DoubleArray(0)
        nPrev = IntArray(0); nNext = IntArray(0); nZ = IntArray(0)
        nPrevZ = IntArray(0); nNextZ = IntArray(0); nSteiner = BooleanArray(0)
        holeQueue.clear(); holeQueue.trimToSize()
    }

    /**
     * Triangulate a polygon. [data] holds flat vertex coordinates, [dim] components per vertex
     * (`data[i*dim]`, `data[i*dim+1]`, …). [holeIndices] gives the **vertex** index where each hole
     * ring starts; the outer ring is the vertices before the first hole. Triangle **vertex** indices
     * are appended to [out] in groups of three (each index is a vertex ordinal `i`, i.e. into the
     * per-vertex `data[i*dim]` layout). Rings need not be a particular winding — earcut normalises the
     * outer ring CCW and holes CW. [out] is cleared first and returned.
     */
    fun triangulate(data: DoubleArray, holeIndices: IntArray? = null, dim: Int = 2, out: IntList = IntList()): IntList {
        out.clear() // reusable across calls — callers triangulating many polygons pass one scratch
        count = 0   // reset the arena; node ids restart from 0 for this polygon
        splitOps = MAX_SPLIT_OPS // refill the split-search budget for this polygon
        val hasHoles = holeIndices != null && holeIndices.isNotEmpty()
        val outerLen = if (hasHoles) holeIndices[0] * dim else data.size
        var outerNode = linkedList(data, 0, outerLen, dim, true)
        if (outerNode == NULL || nNext[outerNode] == nPrev[outerNode]) return out

        var minX = 0.0
        var minY = 0.0
        var invSize = 0.0

        if (hasHoles) outerNode = eliminateHoles(data, holeIndices, outerNode, dim)

        // z-order curve hashing speeds up ear-checking on big polygons.
        if (data.size > 80 * dim) {
            minX = data[0]; minY = data[1]
            var maxX = minX; var maxY = minY
            // Bbox over EVERY vertex (outer + holes), not just the outer ring. Globe-scale quantization
            // and tile clipping can leave a hole vertex fractionally outside the outer ring's bbox; were
            // it, its z-order would go negative / past 15 bits, the bit-interleave would garble, the hash
            // chain would mis-sort, and isEarHashed would accept a non-ear → one triangle fans across the
            // map. Holes lie inside the outer for well-formed input, so this is a no-op there.
            var i = dim
            while (i < data.size) {
                val x = data[i]; val y = data[i + 1]
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
                i += dim
            }
            invSize = maxOf(maxX - minX, maxY - minY)
            invSize = if (invSize != 0.0) 32767.0 / invSize else 0.0
        }

        earcutLinked(outerNode, out, dim, minX, minY, invSize, 0)
        return out
    }

    /** Create a circular doubly-linked list from a ring of the polygon, in the given winding. */
    private fun linkedList(data: DoubleArray, start: Int, end: Int, dim: Int, clockwise: Boolean): Int {
        var last = NULL
        if (clockwise == (signedArea(data, start, end, dim) > 0)) {
            var i = start
            while (i < end) { last = insertNode(i / dim, data[i], data[i + 1], last); i += dim }
        } else {
            var i = end - dim
            while (i >= start) { last = insertNode(i / dim, data[i], data[i + 1], last); i -= dim }
        }
        if (last != NULL && equalsNode(last, nNext[last])) { removeNode(last); last = nNext[last] }
        return last
    }

    /** Eliminate collinear or duplicate points. */
    private fun filterPoints(start: Int, endIn: Int): Int {
        if (start == NULL) return start
        var end = if (endIn != NULL) endIn else start
        var p = start
        while (true) {
            var again = false
            if (!nSteiner[p] && (equalsNode(p, nNext[p]) || area(nPrev[p], p, nNext[p]) == 0.0)) {
                removeNode(p)
                p = nPrev[p]
                end = p
                if (p == nNext[p]) break
                again = true
            } else {
                p = nNext[p]
            }
            if (!again && p == end) break
        }
        return end
    }

    /** Main ear-slicing loop which triangulates a polygon (given as a linked list). */
    private fun earcutLinked(earIn: Int, triangles: IntList, dim: Int, minX: Double, minY: Double, invSize: Double, pass: Int) {
        if (earIn == NULL) return
        var ear = earIn
        if (pass == 0 && invSize != 0.0) indexCurve(ear, minX, minY, invSize)

        var stop = ear
        while (nPrev[ear] != nNext[ear]) {
            val prev = nPrev[ear]
            val next = nNext[ear]
            val isEar = if (invSize != 0.0) isEarHashed(ear, minX, minY, invSize) else isEar(ear)
            if (isEar) {
                triangles.add(nI[prev]); triangles.add(nI[ear]); triangles.add(nI[next])
                removeNode(ear)
                ear = nNext[next]
                stop = nNext[next]
                continue
            }
            ear = next
            if (ear == stop) {
                when (pass) {
                    0 -> earcutLinked(filterPoints(ear, NULL), triangles, dim, minX, minY, invSize, 1)
                    1 -> {
                        val cured = cureLocalIntersections(filterPoints(ear, NULL), triangles)
                        earcutLinked(cured, triangles, dim, minX, minY, invSize, 2)
                    }
                    2 -> splitEarcut(ear, triangles, dim, minX, minY, invSize)
                }
                break
            }
        }
    }

    /** Check whether a polygon node forms a valid ear with adjacent nodes. */
    private fun isEar(ear: Int): Boolean {
        val a = nPrev[ear]; val b = ear; val c = nNext[ear]
        if (area(a, b, c) >= 0) return false // reflex, can't be an ear
        val ax = nX[a]; val bx = nX[b]; val cx = nX[c]; val ay = nY[a]; val by = nY[b]; val cy = nY[c]
        val x0 = min3(ax, bx, cx); val y0 = min3(ay, by, cy); val x1 = max3(ax, bx, cx); val y1 = max3(ay, by, cy)
        var p = nNext[c]
        while (p != a) {
            if (nX[p] >= x0 && nX[p] <= x1 && nY[p] >= y0 && nY[p] <= y1 &&
                pointInTriangle(ax, ay, bx, by, cx, cy, nX[p], nY[p]) && area(nPrev[p], p, nNext[p]) >= 0) return false
            p = nNext[p]
        }
        return true
    }

    private fun isEarHashed(ear: Int, minX: Double, minY: Double, invSize: Double): Boolean {
        val a = nPrev[ear]; val b = ear; val c = nNext[ear]
        if (area(a, b, c) >= 0) return false
        val ax = nX[a]; val bx = nX[b]; val cx = nX[c]; val ay = nY[a]; val by = nY[b]; val cy = nY[c]
        val x0 = min3(ax, bx, cx); val y0 = min3(ay, by, cy); val x1 = max3(ax, bx, cx); val y1 = max3(ay, by, cy)
        val minZ = zOrder(x0, y0, minX, minY, invSize)
        val maxZ = zOrder(x1, y1, minX, minY, invSize)

        var p = nPrevZ[ear]
        var n = nNextZ[ear]
        while (p != NULL && nZ[p] >= minZ && n != NULL && nZ[n] <= maxZ) {
            if (nX[p] >= x0 && nX[p] <= x1 && nY[p] >= y0 && nY[p] <= y1 && p != a && p != c &&
                pointInTriangle(ax, ay, bx, by, cx, cy, nX[p], nY[p]) && area(nPrev[p], p, nNext[p]) >= 0) return false
            p = nPrevZ[p]
            if (nX[n] >= x0 && nX[n] <= x1 && nY[n] >= y0 && nY[n] <= y1 && n != a && n != c &&
                pointInTriangle(ax, ay, bx, by, cx, cy, nX[n], nY[n]) && area(nPrev[n], n, nNext[n]) >= 0) return false
            n = nNextZ[n]
        }
        while (p != NULL && nZ[p] >= minZ) {
            if (nX[p] >= x0 && nX[p] <= x1 && nY[p] >= y0 && nY[p] <= y1 && p != a && p != c &&
                pointInTriangle(ax, ay, bx, by, cx, cy, nX[p], nY[p]) && area(nPrev[p], p, nNext[p]) >= 0) return false
            p = nPrevZ[p]
        }
        while (n != NULL && nZ[n] <= maxZ) {
            if (nX[n] >= x0 && nX[n] <= x1 && nY[n] >= y0 && nY[n] <= y1 && n != a && n != c &&
                pointInTriangle(ax, ay, bx, by, cx, cy, nX[n], nY[n]) && area(nPrev[n], n, nNext[n]) >= 0) return false
            n = nNextZ[n]
        }
        return true
    }

    /** Go through all polygon nodes and cure small local self-intersections. */
    private fun cureLocalIntersections(startIn: Int, triangles: IntList): Int {
        var start = startIn
        var p = start
        do {
            val a = nPrev[p]; val b = nNext[nNext[p]]
            if (!equalsNode(a, b) && intersects(a, p, nNext[p], b) && locallyInside(a, b) && locallyInside(b, a)) {
                triangles.add(nI[a]); triangles.add(nI[p]); triangles.add(nI[b])
                removeNode(p)
                removeNode(nNext[p])
                p = b; start = b
            }
            p = nNext[p]
        } while (p != start)
        return filterPoints(p, NULL)
    }

    /** Try splitting polygon into two and triangulate them independently. */
    private fun splitEarcut(start: Int, triangles: IntList, dim: Int, minX: Double, minY: Double, invSize: Double) {
        // Guard the O(n²) diagonal search × O(n) [intersectsPolygon] (= O(n³)): a pathological self-
        // intersecting ring (MVT clipping/quantization artifact) would otherwise churn for seconds and
        // hang loading. The [splitOps] budget (metered in intersectsPolygon) bails only when the search
        // genuinely runs away — a complex coastline that finds a valid diagonal quickly still finishes,
        // unlike the old node-count cap which gave up on big-but-resolvable rings. Bailing leaves the
        // residue untriangulated (missing fill), never a garbage spanning triangle.
        var a = start
        do {
            var b = nNext[nNext[a]]
            while (b != nPrev[a]) {
                if (splitOps < 0) return
                if (nI[a] != nI[b] && isValidDiagonal(a, b)) {
                    var c = splitPolygon(a, b)
                    val a2 = filterPoints(a, nNext[a])
                    c = filterPoints(c, nNext[c])
                    earcutLinked(a2, triangles, dim, minX, minY, invSize, 0)
                    earcutLinked(c, triangles, dim, minX, minY, invSize, 0)
                    return
                }
                b = nNext[b]
            }
            a = nNext[a]
        } while (a != start)
    }

    /** Link every hole into the outer loop, producing a single-ring polygon without holes. */
    private fun eliminateHoles(data: DoubleArray, holeIndices: IntArray, outerNodeIn: Int, dim: Int): Int {
        var outerNode = outerNodeIn
        // Guard [findHoleBridge]'s O(outerN)-per-hole cost: a huge outer ring with many holes (pathological
        // MVT geometry) would stall tile assembly for seconds. Above this outer size, skip hole subtraction
        // — the holes fill in (a minor error on a rare bad polygon) rather than hanging loading.
        var on = 0
        var op = outerNode
        do { if (++on > MAX_HOLE_OUTER_NODES) return outerNode; op = nNext[op] } while (op != outerNode)
        holeQueue.clear()
        val len = holeIndices.size
        for (i in 0 until len) {
            val start = holeIndices[i] * dim
            val end = if (i < len - 1) holeIndices[i + 1] * dim else data.size
            val list = linkedList(data, start, end, dim, false)
            if (list == NULL) continue
            if (list == nNext[list]) nSteiner[list] = true
            holeQueue.add(getLeftmost(list))
        }
        holeQueue.sortWith { i1, i2 -> nX[i1].compareTo(nX[i2]) }
        for (q in holeQueue) outerNode = eliminateHole(q, outerNode)
        return outerNode
    }

    private fun eliminateHole(hole: Int, outerNode: Int): Int {
        val bridge = findHoleBridge(hole, outerNode)
        if (bridge == NULL) return outerNode
        val bridgeReverse = splitPolygon(bridge, hole)
        filterPoints(bridgeReverse, nNext[bridgeReverse])
        val r = filterPoints(bridge, nNext[bridge])
        return if (r == NULL) outerNode else r
    }

    /** David Eberly's algorithm for finding a bridge between hole and outer polygon. */
    private fun findHoleBridge(hole: Int, outerNode: Int): Int {
        var p = outerNode
        val hx = nX[hole]; val hy = nY[hole]
        var qx = Double.NEGATIVE_INFINITY
        var m = NULL

        do {
            val pn = nNext[p]
            if (hy <= nY[p] && hy >= nY[pn] && nY[pn] != nY[p]) {
                val x = nX[p] + (hy - nY[p]) * (nX[pn] - nX[p]) / (nY[pn] - nY[p])
                if (x <= hx && x > qx) {
                    qx = x
                    m = if (nX[p] < nX[pn]) p else pn
                    if (x == hx) return m // hole touches outer segment; pick leftmost endpoint
                }
            }
            p = nNext[p]
        } while (p != outerNode)

        if (m == NULL) return NULL

        // Look for points strictly inside the triangle of hole point, segment intersection, and
        // endpoint; if none, the connection is valid. Otherwise pick the point of min angle to the ray.
        val stop = m
        val mx = nX[m]; val my = nY[m]
        var tanMin = Double.POSITIVE_INFINITY
        p = m
        do {
            if (hx >= nX[p] && nX[p] >= mx && hx != nX[p] &&
                pointInTriangle(if (hy < my) hx else qx, hy, mx, my, if (hy < my) qx else hx, hy, nX[p], nY[p])) {
                val tan = abs(hy - nY[p]) / (hx - nX[p])
                if (locallyInside(p, hole) && (tan < tanMin || (tan == tanMin && nX[p] > nX[m]))) {
                    m = p; tanMin = tan
                }
            }
            p = nNext[p]
        } while (p != stop)

        return m
    }

    /** Interlink polygon nodes in z-order. */
    private fun indexCurve(start: Int, minX: Double, minY: Double, invSize: Double) {
        var p = start
        do {
            if (nZ[p] == 0) nZ[p] = zOrder(nX[p], nY[p], minX, minY, invSize)
            nPrevZ[p] = nPrev[p]
            nNextZ[p] = nNext[p]
            p = nNext[p]
        } while (p != start)

        nNextZ[nPrevZ[p]] = NULL
        nPrevZ[p] = NULL
        sortLinked(p)
    }

    /** Simon Tatham's linked-list merge sort, on the z-order chain. */
    private fun sortLinked(listIn: Int) {
        var list = listIn
        var inSize = 1
        var numMerges: Int
        do {
            var p = list
            list = NULL
            var tail = NULL
            numMerges = 0
            while (p != NULL) {
                numMerges++
                var q = p
                var pSize = 0
                var i = 0
                while (i < inSize && q != NULL) { pSize++; q = nNextZ[q]; i++ }
                var qSize = inSize
                while (pSize > 0 || (qSize > 0 && q != NULL)) {
                    val e: Int
                    if (pSize != 0 && (qSize == 0 || q == NULL || nZ[p] <= nZ[q])) {
                        e = p; p = nNextZ[p]; pSize--
                    } else {
                        e = q; q = nNextZ[q]; qSize--
                    }
                    if (tail != NULL) nNextZ[tail] = e else list = e
                    nPrevZ[e] = tail
                    tail = e
                }
                p = q
            }
            nNextZ[tail] = NULL
            inSize *= 2
        } while (numMerges > 1)
    }

    /** Find the leftmost node of a polygon ring. */
    private fun getLeftmost(start: Int): Int {
        var p = start
        var leftmost = start
        do {
            if (nX[p] < nX[leftmost] || (nX[p] == nX[leftmost] && nY[p] < nY[leftmost])) leftmost = p
            p = nNext[p]
        } while (p != start)
        return leftmost
    }

    /** Whether a diagonal between two polygon nodes is valid (lies in the polygon interior). */
    private fun isValidDiagonal(a: Int, b: Int): Boolean =
        nI[nNext[a]] != nI[b] && nI[nPrev[a]] != nI[b] && !intersectsPolygon(a, b) &&
            (locallyInside(a, b) && locallyInside(b, a) && middleInside(a, b) &&
                (area(nPrev[a], a, nPrev[b]) != 0.0 || area(a, nPrev[b], b) != 0.0) ||
                equalsNode(a, b) && area(nPrev[a], a, nNext[a]) > 0 && area(nPrev[b], b, nNext[b]) > 0)

    /** Signed area of a triangle (p,q,r). */
    private fun area(p: Int, q: Int, r: Int): Double = (nY[q] - nY[p]) * (nX[r] - nX[q]) - (nX[q] - nX[p]) * (nY[r] - nY[q])

    private fun equalsNode(p1: Int, p2: Int): Boolean = nX[p1] == nX[p2] && nY[p1] == nY[p2]

    /** Whether two segments (p1q1) and (p2q2) intersect. */
    private fun intersects(p1: Int, q1: Int, p2: Int, q2: Int): Boolean {
        val o1 = sign(area(p1, q1, p2))
        val o2 = sign(area(p1, q1, q2))
        val o3 = sign(area(p2, q2, p1))
        val o4 = sign(area(p2, q2, q1))
        if (o1 != o2 && o3 != o4) return true
        if (o1 == 0 && onSegment(p1, p2, q1)) return true
        if (o2 == 0 && onSegment(p1, q2, q1)) return true
        if (o3 == 0 && onSegment(p2, p1, q2)) return true
        if (o4 == 0 && onSegment(p2, q1, q2)) return true
        return false
    }

    /** For collinear points p,q,r, whether q lies on segment pr. */
    private fun onSegment(p: Int, q: Int, r: Int): Boolean =
        nX[q] <= maxOf(nX[p], nX[r]) && nX[q] >= minOf(nX[p], nX[r]) && nY[q] <= maxOf(nY[p], nY[r]) && nY[q] >= minOf(nY[p], nY[r])

    /** Whether a polygon diagonal (a,b) is intersected by any polygon edge. */
    private fun intersectsPolygon(a: Int, b: Int): Boolean {
        var p = a
        do {
            splitOps-- // meter the O(n) edge scan so splitEarcut can bail on a pathological O(n³) search
            if (nI[p] != nI[a] && nI[nNext[p]] != nI[a] && nI[p] != nI[b] && nI[nNext[p]] != nI[b] &&
                intersects(p, nNext[p], a, b)) return true
            p = nNext[p]
        } while (p != a)
        return false
    }

    /** Whether a diagonal from a to b lies within the polygon near a. */
    private fun locallyInside(a: Int, b: Int): Boolean =
        if (area(nPrev[a], a, nNext[a]) < 0) area(a, b, nNext[a]) >= 0 && area(a, nPrev[a], b) >= 0
        else area(a, b, nPrev[a]) < 0 || area(a, nNext[a], b) < 0

    /** Whether the midpoint of diagonal (a,b) is inside the polygon. */
    private fun middleInside(a: Int, b: Int): Boolean {
        var p = a
        var inside = false
        val px = (nX[a] + nX[b]) / 2.0
        val py = (nY[a] + nY[b]) / 2.0
        do {
            val pn = nNext[p]
            if (((nY[p] > py) != (nY[pn] > py)) && nY[pn] != nY[p] &&
                (px < (nX[pn] - nX[p]) * (py - nY[p]) / (nY[pn] - nY[p]) + nX[p])) inside = !inside
            p = nNext[p]
        } while (p != a)
        return inside
    }

    /**
     * Link two polygon vertices with a bridge; if they belong to the same ring it splits it into two,
     * if to two separate rings it merges them. The two new vertices share their endpoints' positions.
     */
    private fun splitPolygon(a: Int, b: Int): Int {
        val a2 = newNode(nI[a], nX[a], nY[a])
        val b2 = newNode(nI[b], nX[b], nY[b])
        val an = nNext[a]
        val bp = nPrev[b]
        nNext[a] = b; nPrev[b] = a
        nNext[a2] = an; nPrev[an] = a2
        nNext[b2] = a2; nPrev[a2] = b2
        nNext[bp] = b2; nPrev[b2] = bp
        return b2
    }

    private fun insertNode(i: Int, x: Double, y: Double, last: Int): Int {
        val p = newNode(i, x, y)
        if (last == NULL) {
            nPrev[p] = p; nNext[p] = p
        } else {
            nNext[p] = nNext[last]; nPrev[p] = last; nPrev[nNext[last]] = p; nNext[last] = p
        }
        return p
    }

    private fun removeNode(p: Int) {
        nPrev[nNext[p]] = nPrev[p]
        nNext[nPrev[p]] = nNext[p]
        if (nPrevZ[p] != NULL) nNextZ[nPrevZ[p]] = nNextZ[p]
        if (nNextZ[p] != NULL) nPrevZ[nNextZ[p]] = nPrevZ[p]
    }

    private companion object {
        /** Absent node id (the SoA equivalent of a null `Node`). */
        const val NULL = -1
        // intersectsPolygon-step budget for the O(n³) [splitEarcut] self-intersection repair. ~20M steps
        // is tens of ms even in a debug build, enough for any real coastline's single split to resolve,
        // and bounds a pathological ring instead of letting it hang. Metered, not a node-count cap.
        const val MAX_SPLIT_OPS = 20_000_000
        // Max outer-ring size for hole subtraction (findHoleBridge is O(outerN) per hole). The coarse-zoom
        // OCEAN ring is huge (~10-19k verts) with every continent as a hole; 6000 used to bail and leave
        // them filled — the ocean then painted solid over the continents. Raised to cover real ocean
        // tiles; only an absurd malformed ring bails now. (The caller drops sub-pixel island holes first,
        // so the surviving hole count — hence the O(outerN·holes) cost — stays modest.)
        const val MAX_HOLE_OUTER_NODES = 60000
    }
}

private fun signedArea(data: DoubleArray, start: Int, end: Int, dim: Int): Double {
    var sum = 0.0
    var j = end - dim
    var i = start
    while (i < end) {
        sum += (data[j] - data[i]) * (data[i + 1] + data[j + 1])
        j = i
        i += dim
    }
    return sum
}

/** Z-order of a point given coords and inverse of the longer side of the bbox. The coerce keeps the
 *  value in the 15-bit range the bit-interleave assumes even if a coord falls outside [min, min+size]
 *  (a fractional out-of-bbox vertex from quantization would otherwise produce a garbled, mis-sorted
 *  z-order that makes isEarHashed accept a non-ear → a triangle spanning the map). */
private fun zOrder(xIn: Double, yIn: Double, minX: Double, minY: Double, invSize: Double): Int {
    var x = ((xIn - minX) * invSize).toInt().coerceIn(0, 0x7FFF)
    var y = ((yIn - minY) * invSize).toInt().coerceIn(0, 0x7FFF)
    x = (x or (x shl 8)) and 0x00FF00FF
    x = (x or (x shl 4)) and 0x0F0F0F0F
    x = (x or (x shl 2)) and 0x33333333
    x = (x or (x shl 1)) and 0x55555555
    y = (y or (y shl 8)) and 0x00FF00FF
    y = (y or (y shl 4)) and 0x0F0F0F0F
    y = (y or (y shl 2)) and 0x33333333
    y = (y or (y shl 1)) and 0x55555555
    return x or (y shl 1)
}

private fun pointInTriangle(
    ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double, px: Double, py: Double,
): Boolean = (cx - px) * (ay - py) >= (ax - px) * (cy - py) &&
    (ax - px) * (by - py) >= (bx - px) * (ay - py) &&
    (bx - px) * (cy - py) >= (cx - px) * (by - py)

private fun sign(num: Double): Int = if (num > 0.0) 1 else if (num < 0.0) -1 else 0

private fun min3(a: Double, b: Double, c: Double) = if (a < b) (if (a < c) a else c) else (if (b < c) b else c)
private fun max3(a: Double, b: Double, c: Double) = if (a > b) (if (a > c) a else c) else (if (b > c) b else c)
