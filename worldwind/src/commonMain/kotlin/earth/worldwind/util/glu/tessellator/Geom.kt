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

import kotlin.math.abs

@Suppress("NAME_SHADOWING")
internal object Geom {
    /* Given three vertices u,v,w such that VertLeq(u,v) && VertLeq(v,w),
     * evaluates the t-coord of the edge uw at the s-coord of the vertex v.
     * Returns v->t - (uw)(v->s), ie. the signed distance from uw to v.
     * If uw is vertical (and thus passes thru v), the result is zero.
     *
     * The calculation is extremely accurate and stable, even when v
     * is very close to u or w.  In particular if we set v->t = 0 and
     * let r be the negated result (this evaluates (uw)(v->s)), then
     * r is guaranteed to satisfy MIN(u->t,w->t) <= r <= MAX(u->t,w->t).
     */
    fun edgeEval(u: GLUvertex, v: GLUvertex, w: GLUvertex): Double {
        val gapL = v.s - u.s
        val gapR = w.s - v.s
        return if (gapL + gapR > 0) {
            if (gapL < gapR) v.t - u.t + (u.t - w.t) * (gapL / (gapL + gapR))
            else v.t - w.t + (w.t - u.t) * (gapR / (gapL + gapR))
        } else 0.0
        /* vertical line */
    }

    fun edgeSign(u: GLUvertex, v: GLUvertex, w: GLUvertex): Double {
        val gapL = v.s - u.s
        val gapR = w.s - v.s
        return if (gapL + gapR > 0) (v.t - w.t) * gapL + (v.t - u.t) * gapR else 0.0
        /* vertical line */
    }

    /**
     * Define versions of EdgeSign, EdgeEval with s and t transposed.
     */
    fun transEval(u: GLUvertex, v: GLUvertex, w: GLUvertex): Double {
        /* Given three vertices u,v,w such that TransLeq(u,v) && TransLeq(v,w),
         * evaluates the t-coord of the edge uw at the s-coord of the vertex v.
         * Returns v->s - (uw)(v->t), ie. the signed distance from uw to v.
         * If uw is vertical (and thus passes thru v), the result is zero.
         *
         * The calculation is extremely accurate and stable, even when v
         * is very close to u or w.  In particular if we set v->s = 0 and
         * let r be the negated result (this evaluates (uw)(v->t)), then
         * r is guaranteed to satisfy MIN(u->s,w->s) <= r <= MAX(u->s,w->s).
         */
        val gapL = v.t - u.t
        val gapR = w.t - v.t
        return if (gapL + gapR > 0) {
            if (gapL < gapR) v.s - u.s + (u.s - w.s) * (gapL / (gapL + gapR))
            else v.s - w.s + (w.s - u.s) * (gapR / (gapL + gapR))
        } else 0.0
        /* vertical line */
    }

    /**
     *  Returns a number whose sign matches TransEval(u,v,w) but which
     * is cheaper to evaluate.  Returns > 0, == 0 , or < 0
     * as v is above, on, or below the edge uw.
     */
    fun transSign(u: GLUvertex, v: GLUvertex, w: GLUvertex): Double {
        val gapL = v.t - u.t
        val gapR = w.t - v.t
        return if (gapL + gapR > 0) (v.s - w.s) * gapL + (v.s - u.s) * gapR else 0.0
        /* vertical line */
    }

    /**
     *  For almost-degenerate situations, the results are not reliable.
     * Unless the floating-point arithmetic can be performed without
     * rounding errors, *any* implementation will give incorrect results
     * on some degenerate inputs, so the client must have some way to
     * handle this situation.
     */
    fun vertCCW(u: GLUvertex, v: GLUvertex, w: GLUvertex) =
        u.s * (v.t - w.t) + v.s * (w.t - u.t) + w.s * (u.t - v.t) >= 0

    /**
     *  Given parameters a,x,b,y returns the value (b*x+a*y)/(a+b),
     * or (x+y)/2 if a==b==0.  It requires that a,b >= 0, and enforces
     * this in the rare case that one argument is slightly negative.
     * The implementation is extremely stable numerically.
     * In particular it guarantees that the result r satisfies
     * MIN(x,y) <= r <= MAX(x,y), and the results are very accurate
     * even when a and b differ greatly in magnitude.
     */
    fun interpolate(a: Double, x: Double, b: Double, y: Double): Double {
        val a = if (a < 0) 0.0 else a
        val b = if (b < 0) 0.0 else b
        return if (a <= b) {
            if (b == 0.0) (x + y) / 2.0
            else x + (y - x) * (a / (a + b))
        } else y + (x - y) * (b / (a + b))
    }

    /**
     * Given edges (o1,d1) and (o2,d2), compute their point of intersection.
     * The computed point is guaranteed to lie in the intersection of the
     * bounding rectangles defined by each edge.
     */
    fun edgeIntersect(
        o1: GLUvertex, d1: GLUvertex, o2: GLUvertex, d2: GLUvertex, v: GLUvertex
    ) {
        var o1 = o1
        var d1 = d1
        var o2 = o2
        var d2 = d2

        /**
         * This is certainly not the most efficient way to find the intersection
         * of two line segments, but it is very numerically stable.
         *
         * Strategy: find the two middle vertices in the VertLeq ordering,
         * and interpolate the intersection s-value from these.  Then repeat
         * using the TransLeq ordering to find the intersection t-value.
         */
        if (!vertLeq(o1, d1)) {
            val temp = o1
            o1 = d1
            d1 = temp
        }
        if (!vertLeq(o2, d2)) {
            val temp = o2
            o2 = d2
            d2 = temp
        }
        if (!vertLeq(o1, o2)) {
            var temp = o1
            o1 = o2
            o2 = temp
            temp = d1
            d1 = d2
            d2 = temp
        }
        if (!vertLeq(o2, d1)) {
            /* Technically, no intersection -- do our best */
            v.s = (o2.s + d1.s) / 2.0
        } else if (vertLeq(d1, d2)) {
            /* Interpolate between o2 and d1 */
            var z1 = edgeEval(o1, o2, d1)
            var z2 = edgeEval(o2, d1, d2)
            if (z1 + z2 < 0) {
                z1 = -z1
                z2 = -z2
            }
            v.s = interpolate(z1, o2.s, z2, d1.s)
        } else {
            /* Interpolate between o2 and d2 */
            var z1 = edgeSign(o1, o2, d1)
            var z2 = -edgeSign(o1, d2, d1)
            if (z1 + z2 < 0) {
                z1 = -z1
                z2 = -z2
            }
            v.s = interpolate(z1, o2.s, z2, d2.s)
        }

        /* Now repeat the process for t */
        if (!transLeq(o1, d1)) {
            val temp = o1
            o1 = d1
            d1 = temp
        }
        if (!transLeq(o2, d2)) {
            val temp = o2
            o2 = d2
            d2 = temp
        }
        if (!transLeq(o1, o2)) {
            var temp = o2
            o2 = o1
            o1 = temp
            temp = d2
            d2 = d1
            d1 = temp
        }
        if (!transLeq(o2, d1)) {
            /* Technically, no intersection -- do our best */
            v.t = (o2.t + d1.t) / 2.0
        } else if (transLeq(d1, d2)) {
            /* Interpolate between o2 and d1 */
            var z1 = transEval(o1, o2, d1)
            var z2 = transEval(o2, d1, d2)
            if (z1 + z2 < 0) {
                z1 = -z1
                z2 = -z2
            }
            v.t = interpolate(z1, o2.t, z2, d1.t)
        } else {
            /* Interpolate between o2 and d2 */
            var z1 = transSign(o1, o2, d1)
            var z2 = -transSign(o1, d2, d1)
            if (z1 + z2 < 0) {
                z1 = -z1
                z2 = -z2
            }
            v.t = interpolate(z1, o2.t, z2, d2.t)
        }
    }

    fun vertEq(u: GLUvertex, v: GLUvertex) = u.s == v.s && u.t == v.t

    fun vertLeq(u: GLUvertex, v: GLUvertex) = u.s < v.s || u.s == v.s && u.t <= v.t

    /* Versions of VertLeq, EdgeSign, EdgeEval with s and t transposed. */
    fun transLeq(u: GLUvertex, v: GLUvertex) = u.t < v.t || u.t == v.t && u.s <= v.s

    fun edgeGoesLeft(e: GLUhalfEdge) = vertLeq(e.sym?.org!!, e.org!!)

    fun edgeGoesRight(e: GLUhalfEdge) = vertLeq(e.org!!, e.sym?.org!!)

    fun vertL1dist(u: GLUvertex, v: GLUvertex) = abs(u.s - v.s) + abs(u.t - v.t)
}