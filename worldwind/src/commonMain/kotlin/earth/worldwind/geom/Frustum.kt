package earth.worldwind.geom

/**
 * Represents a six-sided view frustum in Cartesian coordinates with a corresponding viewport in screen coordinates.
 */
open class Frustum {
    internal val left = Plane(1.0, 0.0, 0.0, 1.0)
    internal val right = Plane(-1.0, 0.0, 0.0, 1.0)
    internal val bottom = Plane(0.0, 1.0, 0.0, 1.0)
    internal val top = Plane(0.0, -1.0, 0.0, 1.0)
    internal val near = Plane(0.0, 0.0, -1.0, 1.0)
    internal val far = Plane(0.0, 0.0, 1.0, 1.0)
    internal val viewport = Viewport(0, 0, 1, 1)
    internal val planes = arrayOf(near, far, left, right, top, bottom)
    private val scratchMatrix = Matrix4()

    /**
     * Constructs a new unit frustum with each of its planes 1 meter from the center and a viewport with width and
     * height both 1.
     */
    constructor()

    /**
     * Constructs a frustum from planes.
     *
     * @param left     the frustum's left plane
     * @param right    the frustum's right plane
     * @param bottom   the frustum's bottom plane
     * @param top      the frustum's top plane
     * @param near     the frustum's near plane
     * @param far      the frustum's far plane
     * @param viewport the frustum's viewport
     */
    constructor(left: Plane, right: Plane, bottom: Plane, top: Plane, near: Plane, far: Plane, viewport: Viewport): this() {
        this.left.copy(left)
        this.right.copy(right)
        this.bottom.copy(bottom)
        this.top.copy(top)
        this.near.copy(near)
        this.far.copy(far)
        this.viewport.copy(viewport)
    }

    /**
     * Sets this frustum to a unit frustum with each of its planes 1 meter from the center a viewport with width and
     * height both 1.
     *
     * @return this frustum, set to a unit frustum
     */
    fun setToUnitFrustum() = apply {
        left.set(1.0, 0.0, 0.0, 1.0)
        right.set(-1.0, 0.0, 0.0, 1.0)
        bottom.set(0.0, 1.0, 0.0, 1.0)
        top.set(0.0, -1.0, 0.0, 1.0)
        near.set(0.0, 0.0, -1.0, 1.0)
        far.set(0.0, 0.0, 1.0, 1.0)
        viewport.set(0, 0, 1, 1)
    }

    /**
     * Sets this frustum to one appropriate for a modelview-projection matrix. A modelview-projection matrix's view
     * frustum is a Cartesian volume that contains everything visible in a scene displayed using that
     * modelview-projection matrix.
     * <br>
     * This method assumes that the specified matrices represents a projection matrix and a modelview matrix
     * respectively. If this is not the case the results are undefined.
     *
     * @param projection the projection matrix to extract the frustum from
     * @param modelview  the modelview matrix defining the frustum's position and orientation in Cartesian coordinates
     * @param viewport   the screen coordinate viewport corresponding to the projection matrix
     *
     * @return this frustum, with its planes set to the modelview-projection matrix's view frustum, in Cartesian
     * coordinates
     */
    fun setToModelviewProjection(projection: Matrix4, modelview: Matrix4, viewport: Viewport) = apply {
        // Compute the transpose of the modelview matrix.
        scratchMatrix.transposeMatrix(modelview)

        // Get the components of the projection matrix.
        val m = projection.m

        // Left Plane = row 4 + row 1:
        var x = m[12] + m[0]
        var y = m[13] + m[1]
        var z = m[14] + m[2]
        var w = m[15] + m[3]
        left.set(x, y, z, w) // normalizes the plane's coordinates
        left.transformByMatrix(scratchMatrix)

        // Right Plane = row 4 - row 1:
        x = m[12] - m[0]
        y = m[13] - m[1]
        z = m[14] - m[2]
        w = m[15] - m[3]
        right.set(x, y, z, w) // normalizes the plane's coordinates
        right.transformByMatrix(scratchMatrix)

        // Bottom Plane = row 4 + row 2:
        x = m[12] + m[4]
        y = m[13] + m[5]
        z = m[14] + m[6]
        w = m[15] + m[7]
        bottom.set(x, y, z, w) // normalizes the plane's coordinates
        bottom.transformByMatrix(scratchMatrix)

        // Top Plane = row 4 - row 2:
        x = m[12] - m[4]
        y = m[13] - m[5]
        z = m[14] - m[6]
        w = m[15] - m[7]
        top.set(x, y, z, w) // normalizes the plane's coordinates
        top.transformByMatrix(scratchMatrix)

        // Near Plane = row 4 + row 3:
        x = m[12] + m[8]
        y = m[13] + m[9]
        z = m[14] + m[10]
        w = m[15] + m[11]
        near.set(x, y, z, w) // normalizes the plane's coordinates
        near.transformByMatrix(scratchMatrix)

        // Far Plane = row 4 - row 3:
        x = m[12] - m[8]
        y = m[13] - m[9]
        z = m[14] - m[10]
        w = m[15] - m[11]
        far.set(x, y, z, w) // normalizes the plane's coordinates
        far.transformByMatrix(scratchMatrix)

        // Copy the specified viewport.
        this.viewport.copy(viewport)
    }

    /**
     * Sets this frustum to one appropriate for a subset of a modelview-projection matrix. A modelview-projection
     * matrix's view frustum is a Cartesian volume that contains everything visible in a scene displayed using that
     * modelview-projection matrix. The subset is defined by the region within the original viewport that the frustum
     * contains.
     * <br>
     * This method assumes that the specified matrices represents a projection matrix and a modelview matrix
     * respectively. If this is not the case the results are undefined.
     *
     * @param projection  the projection matrix to extract the frustum from
     * @param modelview   the modelview matrix defining the frustum's position and orientation in Cartesian coordinates
     * @param viewport    the screen coordinate viewport corresponding to the projection matrix
     * @param subViewport the screen coordinate region the frustum should contain
     *
     * @return this frustum, with its planes set to the modelview-projection matrix's view frustum, in Cartesian
     * coordinates
     */
    fun setToModelviewProjection(projection: Matrix4, modelview: Matrix4, viewport: Viewport, subViewport: Viewport) = apply {
        // Compute the sub-viewport's four edges in screen coordinates.
        val left = subViewport.x.toDouble()
        val right = (subViewport.x + subViewport.width).toDouble()
        val bottom = subViewport.y.toDouble()
        val top = (subViewport.y + subViewport.height).toDouble()

        // Transform the sub-viewport's four edges from screen coordinates to Cartesian coordinates.
        var bln: Vec3
        var blf: Vec3
        var brn: Vec3
        var brf: Vec3
        var tln: Vec3
        var tlf: Vec3
        var trn: Vec3
        var trf: Vec3
        val mvpInv = scratchMatrix.setToMultiply(projection, modelview).invert()
        mvpInv.unProject(left, bottom, viewport, Vec3().also { bln = it }, Vec3().also { blf = it })
        mvpInv.unProject(right, bottom, viewport, Vec3().also { brn = it }, Vec3().also { brf = it })
        mvpInv.unProject(left, top, viewport, Vec3().also { tln = it }, Vec3().also { tlf = it })
        mvpInv.unProject(right, top, viewport, Vec3().also { trn = it }, Vec3().also { trf = it })

        val va = Vec3(tlf.x - bln.x, tlf.y - bln.y, tlf.z - bln.z)
        val vb = Vec3(tln.x - blf.x, tln.y - blf.y, tln.z - blf.z)

        val nl = va.cross(vb)
        this.left.set(nl.x, nl.y, nl.z, -nl.dot(bln))
        va.set(trn.x - brf.x, trn.y - brf.y, trn.z - brf.z)
        vb.set(trf.x - brn.x, trf.y - brn.y, trf.z - brn.z)

        val nr = va.cross(vb)
        this.right.set(nr.x, nr.y, nr.z, -nr.dot(brn))
        va.set(brf.x - bln.x, brf.y - bln.y, brf.z - bln.z)
        vb.set(blf.x - brn.x, blf.y - brn.y, blf.z - brn.z)

        val nb = va.cross(vb)
        this.bottom.set(nb.x, nb.y, nb.z, -nb.dot(brn))
        va.set(tlf.x - trn.x, tlf.y - trn.y, tlf.z - trn.z)
        vb.set(trf.x - tln.x, trf.y - tln.y, trf.z - tln.z)

        val nt = va.cross(vb)
        this.top.set(nt.x, nt.y, nt.z, -nt.dot(tln))
        va.set(tln.x - brn.x, tln.y - brn.y, tln.z - brn.z)
        vb.set(trn.x - bln.x, trn.y - bln.y, trn.z - bln.z)

        val nn = va.cross(vb)
        this.near.set(nn.x, nn.y, nn.z, -nn.dot(bln))
        va.set(trf.x - blf.x, trf.y - blf.y, trf.z - blf.z)
        vb.set(tlf.x - brf.x, tlf.y - brf.y, tlf.z - brf.z)

        val nf = va.cross(vb)
        this.far.set(nf.x, nf.y, nf.z, -nf.dot(blf))

        // Copy the specified sub-viewport.
        this.viewport.copy(subViewport)
    }

    /**
     * See if the point is entirely within the frustum. The dot product of the point with each plane's vector
     * provides a distance to each plane. If this distance is less than 0, the point is clipped by that plane and
     * neither intersects nor is contained by the space enclosed by this Frustum.
     *
     * @param point Vector to check
     *
     * @return true if point contains in frustum
     */
    fun containsPoint(point: Vec3) = far.dot(point) > 0 && left.dot(point) > 0 && right.dot(point) > 0
            && top.dot(point) > 0 && bottom.dot(point) > 0 && near.dot(point) > 0

    /**
     * Determines whether a line segment intersects this frustum.
     *
     * @param pointA the first line segment endpoint
     * @param pointB the second line segment endpoint
     *
     * @return true if the segment intersects or is contained in this frustum, otherwise false
     */
    fun intersectsSegment(pointA: Vec3, pointB: Vec3): Boolean {
        // First do a trivial accept test.
        if (containsPoint(pointA) || containsPoint(pointB)) return true
        if (pointA == pointB) return false
        for (i in planes.indices) {
            val plane = planes[i]
            // See if both points are behind the plane and therefore not in the frustum.
            if (plane.onSameSide(pointA, pointB) < 0) return false
            // See if the segment intersects the plane.
            if (plane.clip(pointA, pointB) != null) return true
        }
        return false // segment does not intersect frustum
    }

    /**
     * Determines whether a screen coordinate viewport intersects this frustum.
     *
     * @param viewport the viewport to test
     *
     * @return true if the viewport intersects or is contained in this frustum, otherwise false
     */
    fun intersectsViewport(viewport: Viewport) = this.viewport.intersects(viewport)
}