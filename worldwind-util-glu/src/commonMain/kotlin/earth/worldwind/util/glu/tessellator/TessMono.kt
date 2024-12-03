/*
 * Portions Copyright (C) 2003-2006 Sun Microsystems, Inc.
 * All rights reserved.
 */
/*
 * License Applicability. Except to the extent portions of this file are
 * made subject to an alternative license as permitted in the SGI Free
 * Software License B, Version 1.1 (the "License"), the contents of this
 * file are subject only to the provisions of the License. You may not use
 * this file except in compliance with the License. You may obtain a copy
 * of the License at Silicon Graphics, Inc., attn: Legal Services, 1600
 * Amphitheatre Parkway, Mountain View, CA 94043-1351, or at:
 *
 * http://oss.sgi.com/projects/FreeB
 *
 * Note that, as provided in the License, the Software is distributed on an
 * "AS IS" basis, with ALL EXPRESS AND IMPLIED WARRANTIES AND CONDITIONS
 * DISCLAIMED, INCLUDING, WITHOUT LIMITATION, ANY IMPLIED WARRANTIES AND
 * CONDITIONS OF MERCHANTABILITY, SATISFACTORY QUALITY, FITNESS FOR A
 * PARTICULAR PURPOSE, AND NON-INFRINGEMENT.
 *
 * NOTE:  The Original Code (as defined below) has been licensed to Sun
 * Microsystems, Inc. ("Sun") under the SGI Free Software License B
 * (Version 1.1), shown above ("SGI License").   Pursuant to Section
 * 3.2(3) of the SGI License, Sun is distributing the Covered Code to
 * you under an alternative license ("Alternative License").  This
 * Alternative License includes all of the provisions of the SGI License
 * except that Section 2.2 and 11 are omitted.  Any differences between
 * the Alternative License and the SGI License are offered solely by Sun
 * and not by SGI.
 *
 * Original Code. The Original Code is: OpenGL Sample Implementation,
 * Version 1.2.1, released January 26, 2000, developed by Silicon Graphics,
 * Inc. The Original Code is Copyright (c) 1991-2000 Silicon Graphics, Inc.
 * Copyright in any portions created by third parties is as indicated
 * elsewhere herein. All Rights Reserved.
 *
 * Additional Notice Provisions: The application programming interfaces
 * established by SGI in conjunction with the Original Code are The
 * OpenGL(R) Graphics System: A Specification (Version 1.2.1), released
 * April 1, 1999; The OpenGL(R) Graphics System Utility Library (Version
 * 1.3), released November 4, 1998; and OpenGL(R) Graphics with the X
 * Window System(R) (Version 1.3), released October 19, 1998. This software
 * was created using the OpenGL(R) version 1.2.1 Sample Implementation
 * published by SGI, but has not been independently verified as being
 * compliant with the OpenGL(R) version 1.2.1 Specification.
 *
 * Author: Eric Veach, July 1994
 * Java Port: Pepijn Van Eeckhoudt, July 2003
 * Java Port: Nathan Parker Burg, August 2003
 * Kotlin Port: Eugene Maksymenko, April 2022
 */
package earth.worldwind.util.glu.tessellator

internal object TessMono {
    /**
     * __gl_meshTessellateMonoRegion( face ) tessellates a monotone region
     * (what else would it do??)  The region must consist of a single
     * loop of half-edges (see mesh.h) oriented CCW.  "Monotone" in this
     * case means that any vertical line intersects the interior of the
     * region in a single interval.
     *
     * Tessellation consists of adding interior edges (actually pairs of
     * half-edges), to split the region into non-overlapping triangles.
     *
     * The basic idea is explained in Preparata and Shamos (which I don''t
     * have handy right now), although their implementation is more
     * complicated than this one.  The are two edge chains, an upper chain
     * and a lower chain.  We process all vertices from both chains in order,
     * from right to left.
     *
     * The algorithm ensures that the following invariant holds after each
     * vertex is processed: the untessellated region consists of two
     * chains, where one chain (say the upper) is a single edge, and
     * the other chain is concave.  The left vertex of the single edge
     * is always to the left of all vertices in the concave chain.
     *
     * Each step consists of adding the rightmost unprocessed vertex to one
     * of the two chains, and forming a fan of triangles from the rightmost
     * of two chain endpoints.  Determining whether we can add each triangle
     * to the fan is a simple orientation test.  By making the fan as large
     * as possible, we restore the invariant (check it yourself).
     */
    fun glMeshTessellateMonoRegion(face: GLUface) {
        /**
         * All edges are oriented CCW around the boundary of the region.
         * First, find the half-edge whose origin vertex is rightmost.
         * Since the sweep goes from left to right, face->anEdge should
         * be close to the edge we want.
         */
        var up = face.anEdge!!
        while (Geom.vertLeq(up.sym?.org!!, up.org!!)) {
            up = up.oNext?.sym!!
        }
        while (Geom.vertLeq(up.org!!, up.sym?.org!!)) {
            up = up.lNext!!
        }
        var lo = up.oNext?.sym!!
        while (up.lNext !== lo) {
            if (Geom.vertLeq(up.sym?.org!!, lo.org!!)) {
                /**
                 * up.Sym.Org is on the left.  It is safe to form triangles from lo.Org.
                 * The EdgeGoesLeft test guarantees progress even when some triangles
                 * are CW, given that the upper and lower chains are truly monotone.
                 */
                while (lo.lNext !== up && (Geom.edgeGoesLeft(lo.lNext!!)
                            || Geom.edgeSign(lo.org!!, lo.sym?.org!!, lo.lNext?.sym?.org!!) <= 0)
                ) {
                    val tempHalfEdge = Mesh.glMeshConnect(lo.lNext!!, lo)
                    lo = tempHalfEdge.sym!!
                }
                lo = lo.oNext?.sym!!
            } else {
                /* lo.Org is on the left.  We can make CCW triangles from up.Sym.Org. */
                while (lo.lNext !== up && (Geom.edgeGoesRight(up.oNext?.sym!!)
                            || Geom.edgeSign(up.sym?.org!!, up.org!!, up.oNext?.sym?.org!!) >= 0)
                ) {
                    val tempHalfEdge = Mesh.glMeshConnect(up, up.oNext?.sym!!)
                    up = tempHalfEdge.sym!!
                }
                up = up.lNext!!
            }
        }
        while (lo.lNext?.lNext !== up) {
            val tempHalfEdge = Mesh.glMeshConnect(lo.lNext!!, lo)
            lo = tempHalfEdge.sym!!
        }
    }

    /**
     * __gl_meshTessellateInterior( mesh ) tessellates each region of
     * the mesh which is marked "inside" the polygon.  Each such region
     * must be monotone.
     */
    fun glMeshTessellateInterior(mesh: GLUmesh): Boolean {
        var f = mesh.fHead.next!!
        while (f !== mesh.fHead) {
            /* Make sure we don''t try to tessellate the new triangles. */
            val next = f.next!!
            if (f.inside) {
                glMeshTessellateMonoRegion(f)
            }
            f = next
        }
        return true
    }

    /**
     * __gl_meshDiscardExterior( mesh ) zaps (ie. sets to NULL) all faces
     * which are not marked "inside" the polygon.  Since further mesh operations
     * on NULL faces are not allowed, the main purpose is to clean up the
     * mesh so that exterior loops are not represented in the data structure.
     */
    fun glMeshDiscardExterior(mesh: GLUmesh) {
        var f = mesh.fHead.next!!
        while (f !== mesh.fHead) {
            /* Since f will be destroyed, save its next pointer. */
            val next = f.next!!
            if (!f.inside) {
                Mesh.glMeshZapFace(f)
            }
            f = next
        }
    }

    /**
     * __gl_meshSetWindingNumber( mesh, value, keepOnlyBoundary ) resets the
     * winding numbers on all edges so that regions marked "inside" the
     * polygon have a winding number of "value", and regions outside
     * have a winding number of 0.
     *
     * If keepOnlyBoundary is TRUE, it also deletes all edges which do not
     * separate an interior region from an exterior one.
     */
    fun glMeshSetWindingNumber(mesh: GLUmesh, value: Int, keepOnlyBoundary: Boolean): Boolean {
        var e = mesh.eHead.next!!
        while (e !== mesh.eHead) {
            val eNext = e.next!!
            if (e.sym?.lFace?.inside != e.lFace?.inside) {

                /* This is a boundary edge (one side is interior, one is exterior). */
                e.winding = if (e.lFace!!.inside) value else -value
            } else {

                /* Both regions are interior, or both are exterior. */
                if (!keepOnlyBoundary) {
                    e.winding = 0
                } else {
                    if (!Mesh.glMeshDelete(e)) return false
                }
            }
            e = eNext
        }
        return true
    }
}