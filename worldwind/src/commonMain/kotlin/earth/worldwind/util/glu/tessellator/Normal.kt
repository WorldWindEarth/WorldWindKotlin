/*
* Portions Copyright (C) 2003-2006 Sun Microsystems, Inc.
* All rights reserved.
*/
/*
** License Applicability. Except to the extent portions of this file are
** made subject to an alternative license as permitted in the SGI Free
** Software License B, Version 1.1 (the "License"), the contents of this
** file are subject only to the provisions of the License. You may not use
** this file except in compliance with the License. You may obtain a copy
** of the License at Silicon Graphics, Inc., attn: Legal Services, 1600
** Amphitheatre Parkway, Mountain View, CA 94043-1351, or at:
**
** http://oss.sgi.com/projects/FreeB
**
** Note that, as provided in the License, the Software is distributed on an
** "AS IS" basis, with ALL EXPRESS AND IMPLIED WARRANTIES AND CONDITIONS
** DISCLAIMED, INCLUDING, WITHOUT LIMITATION, ANY IMPLIED WARRANTIES AND
** CONDITIONS OF MERCHANTABILITY, SATISFACTORY QUALITY, FITNESS FOR A
** PARTICULAR PURPOSE, AND NON-INFRINGEMENT.
**
** NOTE:  The Original Code (as defined below) has been licensed to Sun
** Microsystems, Inc. ("Sun") under the SGI Free Software License B
** (Version 1.1), shown above ("SGI License").   Pursuant to Section
** 3.2(3) of the SGI License, Sun is distributing the Covered Code to
** you under an alternative license ("Alternative License").  This
** Alternative License includes all of the provisions of the SGI License
** except that Section 2.2 and 11 are omitted.  Any differences between
** the Alternative License and the SGI License are offered solely by Sun
** and not by SGI.
**
** Original Code. The Original Code is: OpenGL Sample Implementation,
** Version 1.2.1, released January 26, 2000, developed by Silicon Graphics,
** Inc. The Original Code is Copyright (c) 1991-2000 Silicon Graphics, Inc.
** Copyright in any portions created by third parties is as indicated
** elsewhere herein. All Rights Reserved.
**
** Additional Notice Provisions: The application programming interfaces
** established by SGI in conjunction with the Original Code are The
** OpenGL(R) Graphics System: A Specification (Version 1.2.1), released
** April 1, 1999; The OpenGL(R) Graphics System Utility Library (Version
** 1.3), released November 4, 1998; and OpenGL(R) Graphics with the X
** Window System(R) (Version 1.3), released October 19, 1998. This software
** was created using the OpenGL(R) version 1.2.1 Sample Implementation
** published by SGI, but has not been independently verified as being
** compliant with the OpenGL(R) version 1.2.1 Specification.
**
** Author: Eric Veach, July 1994
** Java Port: Pepijn Van Eeckhoudt, July 2003
** Java Port: Nathan Parker Burg, August 2003
** Kotlin Port: Eugene Maksymenko, April 2022
*/
package earth.worldwind.util.glu.tessellator

import earth.worldwind.util.glu.GLU
import kotlin.math.abs
import kotlin.math.sqrt

internal object Normal {
    private const val SLANTED_SWEEP = false
    private var S_UNIT_X = 0.0 /* Pre-normalized */
    private var S_UNIT_Y = 0.0
    private const val TRUE_PROJECT = false

    init {
        if (SLANTED_SWEEP) {
            /**
             * The "feature merging" is not intended to be complete.  There are
             * special cases where edges are nearly parallel to the sweep line
             * which are not implemented.  The algorithm should still behave
             * robustly (ie. produce a reasonable tesselation) in the presence
             * of such edges, however it may miss features which could have been
             * merged.  We could minimize this effect by choosing the sweep line
             * direction to be something unusual (ie. not parallel to one of the
             * coordinate axes).
             */
            S_UNIT_X = 0.5094153956495538 /* Pre-normalized */
            S_UNIT_Y = 0.8605207462201063
        } else {
            S_UNIT_X = 1.0
            S_UNIT_Y = 0.0
        }
    }

    private fun dot(u: DoubleArray, v: DoubleArray): Double {
        return u[0] * v[0] + u[1] * v[1] + u[2] * v[2]
    }

    fun normalize(v: DoubleArray) {
        var len = v[0] * v[0] + v[1] * v[1] + v[2] * v[2]
        len = sqrt(len)
        v[0] /= len
        v[1] /= len
        v[2] /= len
    }

    fun longAxis(v: DoubleArray): Int {
        var i = 0
        if (abs(v[1]) > abs(v[0])) {
            i = 1
        }
        if (abs(v[2]) > abs(v[i])) {
            i = 2
        }
        return i
    }

    fun computeNormal(tess: GLUtessellatorImpl, norm: DoubleArray) {
        val vHead = tess.mesh.vHead
        val maxVal = DoubleArray(3)
        val minVal = DoubleArray(3)
        val minVert = arrayOfNulls<GLUvertex>(3)
        val maxVert = arrayOfNulls<GLUvertex>(3)
        val d1 = DoubleArray(3)
        val d2 = DoubleArray(3)
        val tNorm = DoubleArray(3)
        maxVal[2] = -2 * GLU.GLU_TESS_MAX_COORD
        maxVal[1] = maxVal[2]
        maxVal[0] = maxVal[1]
        minVal[2] = 2 * GLU.GLU_TESS_MAX_COORD
        minVal[1] = minVal[2]
        minVal[0] = minVal[1]
        var v = vHead.next
        while (v !== vHead) {
            var i = 0
            while (i < 3) {
                val c = v.coords[i]
                if (c < minVal[i]) {
                    minVal[i] = c
                    minVert[i] = v
                }
                if (c > maxVal[i]) {
                    maxVal[i] = c
                    maxVert[i] = v
                }
                ++i
            }
            v = v.next
        }

        /**
         * Find two vertices separated by at least 1/sqrt(3) of the maximum
         * distance between any two vertices
         */
        var i = 0
        if (maxVal[1] - minVal[1] > maxVal[0] - minVal[0]) {
            i = 1
        }
        if (maxVal[2] - minVal[2] > maxVal[i] - minVal[i]) {
            i = 2
        }
        if (minVal[i] >= maxVal[i]) {
            /* All vertices are the same -- normal doesn't matter */
            norm[0] = 0.0
            norm[1] = 0.0
            norm[2] = 1.0
            return
        }

        /**
         * Look for a third vertex which forms the triangle with maximum area
         * (Length of normal == twice the triangle area)
         */
        var maxLen2 = 0.0
        val v1 = minVert[i] ?: error("This should never happen")
        val v2 = maxVert[i] ?: error("This should never happen")
        d1[0] = v1.coords[0] - v2.coords[0]
        d1[1] = v1.coords[1] - v2.coords[1]
        d1[2] = v1.coords[2] - v2.coords[2]
        v = vHead.next
        while (v !== vHead) {
            d2[0] = v.coords[0] - v2.coords[0]
            d2[1] = v.coords[1] - v2.coords[1]
            d2[2] = v.coords[2] - v2.coords[2]
            tNorm[0] = d1[1] * d2[2] - d1[2] * d2[1]
            tNorm[1] = d1[2] * d2[0] - d1[0] * d2[2]
            tNorm[2] = d1[0] * d2[1] - d1[1] * d2[0]
            val tLen2 = tNorm[0] * tNorm[0] + tNorm[1] * tNorm[1] + tNorm[2] * tNorm[2]
            if (tLen2 > maxLen2) {
                maxLen2 = tLen2
                norm[0] = tNorm[0]
                norm[1] = tNorm[1]
                norm[2] = tNorm[2]
            }
            v = v.next
        }
        if (maxLen2 <= 0) {
            /* All points lie on a single line -- any decent normal will do */
            norm[2] = 0.0
            norm[1] = norm[2]
            norm[0] = norm[1]
            norm[longAxis(d1)] = 1.0
        }
    }

    fun checkOrientation(tess: GLUtessellatorImpl) {
        val fHead = tess.mesh.fHead
        val vHead = tess.mesh.vHead

        /**
         *  When we compute the normal automatically, we choose the orientation
         * so that the the sum of the signed areas of all contours is non-negative.
         */
        var area = 0.0
        var f = fHead.next
        while (f !== fHead) {
            var e = f.anEdge
            if (e.winding <= 0) {
                f = f.next
                continue
            }
            do {
                area += (e.org.s - e.sym.org.s) * (e.org.t + e.sym.org.t)
                e = e.lNext
            } while (e !== f.anEdge)
            f = f.next
        }
        if (area < 0) {
            /* Reverse the orientation by flipping all the t-coordinates */
            var v = vHead.next
            while (v !== vHead) {
                v.t = -v.t
                v = v.next
            }
            tess.tUnit[0] = -tess.tUnit[0]
            tess.tUnit[1] = -tess.tUnit[1]
            tess.tUnit[2] = -tess.tUnit[2]
        }
    }

    /**
     * Determine the polygon normal and project vertices onto the plane
     * of the polygon.
     */
    fun glProjectPolygon(tess: GLUtessellatorImpl) {
        val vHead = tess.mesh.vHead
        val norm = DoubleArray(3)
        var computedNormal = false
        norm[0] = tess.normal[0]
        norm[1] = tess.normal[1]
        norm[2] = tess.normal[2]
        if (norm[0] == 0.0 && norm[1] == 0.0 && norm[2] == 0.0) {
            computeNormal(tess, norm)
            computedNormal = true
        }
        val sUnit = tess.sUnit
        val tUnit = tess.tUnit
        val i = longAxis(norm)
        if (TRUE_PROJECT) {
            /**
             * Choose the initial sUnit vector to be approximately perpendicular
             * to the normal.
             */
            normalize(norm)
            sUnit[i] = 0.0
            sUnit[(i + 1) % 3] = S_UNIT_X
            sUnit[(i + 2) % 3] = S_UNIT_Y

            /* Now make it exactly perpendicular */
            val w = dot(sUnit, norm)
            sUnit[0] -= w * norm[0]
            sUnit[1] -= w * norm[1]
            sUnit[2] -= w * norm[2]
            normalize(sUnit)

            /* Choose tUnit so that (sUnit,tUnit,norm) form a right-handed frame */
            tUnit[0] = norm[1] * sUnit[2] - norm[2] * sUnit[1]
            tUnit[1] = norm[2] * sUnit[0] - norm[0] * sUnit[2]
            tUnit[2] = norm[0] * sUnit[1] - norm[1] * sUnit[0]
            normalize(tUnit)
        } else {
            /* Project perpendicular to a coordinate axis -- better numerically */
            sUnit[i] = 0.0
            sUnit[(i + 1) % 3] = S_UNIT_X
            sUnit[(i + 2) % 3] = S_UNIT_Y
            tUnit[i] = 0.0
            tUnit[(i + 1) % 3] = if (norm[i] > 0) -S_UNIT_Y else S_UNIT_Y
            tUnit[(i + 2) % 3] = if (norm[i] > 0) S_UNIT_X else -S_UNIT_X
        }

        /* Project the vertices onto the sweep plane */
        var v = vHead.next
        while (v !== vHead) {
            v.s = dot(v.coords, sUnit)
            v.t = dot(v.coords, tUnit)
            v = v.next
        }
        if (computedNormal) {
            checkOrientation(tess)
        }
    }
}