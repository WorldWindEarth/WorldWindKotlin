package earth.worldwind.geom

/**
 * Un-projects a screen coordinate point to Cartesian coordinates at the near clip plane and the far clip plane.
 * This function assumes [matrix] represents an inverse modelview-projection matrix. The result is undefined if
 * [matrix] is not an inverse modelview-projection matrix.
 * <br>
 * The screen point is understood to be in OpenGL screen coordinates, with the origin in the bottom-left corner and
 * axes that extend up and to the right from the origin.
 *
 * @param matrix     the inverse modelview-projection matrix
 * @param x          the screen point's X component
 * @param y          the screen point's Y component
 * @param viewport   the viewport defining the screen point's coordinate system
 * @param nearResult a pre-allocated [Vec3] in which to return the un-projected near clip plane point
 * @param farResult  a pre-allocated [Vec3] in which to return the un-projected far clip plane point
 *
 * @return true if the transformation is successful, otherwise false
 */
fun unProject(matrix: Matrix4, x: Double, y: Double, viewport: Viewport, nearResult: Vec3, farResult: Vec3): Boolean {
    val m = matrix.m

    // Convert the XY screen coordinates to coordinates in the range [0, 1]. This enables the XY coordinates to
    // be converted to clip coordinates.
    var sx = (x - viewport.x) / viewport.width
    var sy = (y - viewport.y) / viewport.height

    // Convert from coordinates in the range [0, 1] to clip coordinates in the range [-1, 1].
    sx = sx * 2 - 1
    sy = sy * 2 - 1

    // Transform the screen point from clip coordinates to model coordinates. This is a partial transformation that
    // factors out the contribution from the screen point's X and Y components. The contribution from the Z
    // component, which is both -1 and +1, is included next.
    val mx = m[0] * sx + m[1] * sy + m[3]
    val my = m[4] * sx + m[5] * sy + m[7]
    val mz = m[8] * sx + m[9] * sy + m[11]
    val mw = m[12] * sx + m[13] * sy + m[15]

    // Transform the screen point at the near clip plane (z = -1) to model coordinates.
    val nx = mx - m[2]
    val ny = my - m[6]
    val nz = mz - m[10]
    val nw = mw - m[14]

    // Transform the screen point at the far clip plane (z = +1) to model coordinates.
    val fx = mx + m[2]
    val fy = my + m[6]
    val fz = mz + m[10]
    val fw = mw + m[14]
    if (nw == 0.0 || fw == 0.0) return false

    // Complete the conversion from near clip coordinates to model coordinates by dividing by the W component.
    nearResult.x = nx / nw
    nearResult.y = ny / nw
    nearResult.z = nz / nw

    // Complete the conversion from far clip coordinates to model coordinates by dividing by the W component.
    farResult.x = fx / fw
    farResult.y = fy / fw
    farResult.z = fz / fw
    return true
}

/**
 * Un-projects a screen coordinate point with a normalized depth value to Cartesian coordinates.
 * This function assumes [matrix] represents an inverse modelview-projection matrix.
 *
 * @param matrix   the inverse modelview-projection matrix
 * @param x        the screen point's X component in OpenGL coordinates
 * @param y        the screen point's Y component in OpenGL coordinates
 * @param z        the normalized depth value in the range [0, 1]
 * @param viewport the viewport defining the screen point's coordinate system
 * @param result   a pre-allocated [Vec3] in which to return the un-projected point
 *
 * @return true if the transformation is successful, otherwise false
 */
fun unProject(matrix: Matrix4, x: Double, y: Double, z: Double, viewport: Viewport, result: Vec3): Boolean {
    val m = matrix.m
    var sx = (x - viewport.x) / viewport.width
    var sy = (y - viewport.y) / viewport.height
    var sz = z

    sx = sx * 2 - 1
    sy = sy * 2 - 1
    sz = sz * 2 - 1

    val mx = m[0] * sx + m[1] * sy + m[2] * sz + m[3]
    val my = m[4] * sx + m[5] * sy + m[6] * sz + m[7]
    val mz = m[8] * sx + m[9] * sy + m[10] * sz + m[11]
    val mw = m[12] * sx + m[13] * sy + m[14] * sz + m[15]
    if (mw == 0.0) return false

    result.x = mx / mw
    result.y = my / mw
    result.z = mz / mw
    return true
}
