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

import earth.worldwind.util.glu.GLU
import earth.worldwind.util.glu.tessellator.Dict.DictLeq

@Suppress("NAME_SHADOWING")
internal object Sweep {
    //    #ifdef FOR_TRITE_TEST_PROGRAM
    //    extern void DebugEvent( GLUtessellator *tess );
    //    #else
    @Suppress("UNUSED_PARAMETER")
    private fun debugEvent(tess: GLUtessellatorImpl) {}

    //    #endif
    /*
     * Invariants for the Edge Dictionary.
     * - each pair of adjacent edges e2=Succ(e1) satisfies EdgeLeq(e1,e2)
     *   at any valid location of the sweep event
     * - if EdgeLeq(e2,e1) as well (at any valid sweep event), then e1 and e2
     *   share a common endpoint
     * - for each e, e.Dst has been processed, but not e.Org
     * - each edge e satisfies VertLeq(e.Dst,event) && VertLeq(event,e.Org)
     *   where "event" is the current sweep line event.
     * - no edge e has zero length
     *
     * Invariants for the Mesh (the processed portion).
     * - the portion of the mesh left of the sweep line is a planar graph,
     *   ie. there is *some* way to embed it in the plane
     * - no processed edge has zero length
     * - no two processed vertices have identical coordinates
     * - each "inside" region is monotone, ie. can be broken into two chains
     *   of monotonically increasing vertices according to VertLeq(v1,v2)
     *   - a non-invariant: these chains may intersect (very slightly)
     *
     * Invariants for the Sweep.
     * - if none of the edges incident to the event vertex have an activeRegion
     *   (ie. none of these edges are in the edge dictionary), then the vertex
     *   has only right-going edges.
     * - if an edge is marked "fixUpperEdge" (it is a temporary edge introduced
     *   by ConnectRightVertex), then it is the only right-going edge from
     *   its associated vertex.  (This says that these edges exist only
     *   when it is necessary.)
     */
    /**
     * When we merge two edges into one, we need to compute the combined
     * winding of the new edge.
     */
    private fun addWinding(eDst: GLUhalfEdge, eSrc: GLUhalfEdge) {
        eDst.winding += eSrc.winding
        eDst.sym.winding += eSrc.sym.winding
    }

    private fun regionBelow(r: ActiveRegion): ActiveRegion {
        return Dict.dictKey(Dict.dictPred(r.nodeUp)) as ActiveRegion
    }

    private fun regionAbove(r: ActiveRegion): ActiveRegion {
        return Dict.dictKey(Dict.dictSucc(r.nodeUp)) as ActiveRegion
    }

    private fun regionAboveOrNull(r: ActiveRegion): ActiveRegion? {
        return Dict.dictKey(Dict.dictSucc(r.nodeUp)) as ActiveRegion?
    }

    /**
     * Both edges must be directed from right to left (this is the canonical
     * direction for the upper edge of each region).
     *
     * The strategy is to evaluate a "t" value for each edge at the
     * current sweep line position, given by tess.event.  The calculations
     * are designed to be very stable, but of course they are not perfect.
     *
     * Special case: if both edge destinations are at the sweep event,
     * we sort the edges by slope (they would otherwise compare equally).
     */
    fun edgeLeq(tess: GLUtessellatorImpl, reg1: ActiveRegion, reg2: ActiveRegion): Boolean {
        val event = tess.event
        val e1 = reg1.eUp
        val e2 = reg2.eUp
        if (e1.sym.org === event) {
            return if (e2.sym.org === event) {
                /**
                 * Two edges right of the sweep line which meet at the sweep event.
                 * Sort them by slope.
                 */
                if (Geom.vertLeq(e1.org, e2.org)) {
                    Geom.edgeSign(e2.sym.org, e1.org, e2.org) <= 0
                } else Geom.edgeSign(e1.sym.org, e2.org, e1.org) >= 0
            } else Geom.edgeSign(e2.sym.org, event, e2.org) <= 0
        }
        if (e2.sym.org === event) return Geom.edgeSign(e1.sym.org, event, e1.org) >= 0

        /* General case - compute signed distance *from* e1, e2 to event */
        val t1 = Geom.edgeEval(e1.sym.org, event, e1.org)
        val t2 = Geom.edgeEval(e2.sym.org, event, e2.org)
        return t1 >= t2
    }

    fun deleteRegion(reg: ActiveRegion) {
        reg.eUp.activeRegion = null
        Dict.dictDelete(reg.nodeUp) /* glDictListDelete */
    }

    /**
     * Replace an upper edge which needs fixing (see ConnectRightVertex).
     */
    fun fixUpperEdge(reg: ActiveRegion, newEdge: GLUhalfEdge): Boolean  {
        if (!Mesh.glMeshDelete(reg.eUp)) return false
        reg.fixUpperEdge = false
        reg.eUp = newEdge
        newEdge.activeRegion = reg
        return true
    }

    fun topLeftRegion(reg: ActiveRegion): ActiveRegion {
        var reg = reg
        val org = reg.eUp.org

        /* Find the region above the uppermost edge with the same origin */
        do {
            reg = regionAbove(reg)
        } while (reg.eUp.org === org)

        /**
         * If the edge above was a temporary edge introduced by ConnectRightVertex,
         * now is the time to fix it.
         */
        if (reg.fixUpperEdge) {
            val e = Mesh.glMeshConnect(regionBelow(reg).eUp.sym, reg.eUp.lNext)
            if (!fixUpperEdge(reg, e)) error("This should never happen") //return null
            reg = regionAbove(reg)
        }
        return reg
    }

    fun topRightRegion(reg: ActiveRegion): ActiveRegion {
        var reg = reg
        val dst = reg.eUp.sym.org

        /* Find the region above the uppermost edge with the same destination */
        do {
            reg = regionAbove(reg)
        } while (reg.eUp.sym.org === dst)
        return reg
    }

    /**
     * Add a new active region to the sweep line, *somewhere* below "regAbove"
     * (according to where the new edge belongs in the sweep-line dictionary).
     * The upper edge of the new region will be "eNewUp".
     * Winding number and "inside" flag are not updated.
     */
    fun addRegionBelow(
        tess: GLUtessellatorImpl,
        regAbove: ActiveRegion,
        eNewUp: GLUhalfEdge
    ): ActiveRegion {
        val regNew = ActiveRegion(eNewUp)
        /* glDictListInsertBefore */
        regNew.nodeUp = Dict.dictInsertBefore(tess.dict, regAbove.nodeUp, regNew)
        regNew.fixUpperEdge = false
        regNew.sentinel = false
        regNew.dirty = false
        eNewUp.activeRegion = regNew
        return regNew
    }

    fun isWindingInside(tess: GLUtessellatorImpl, n: Int): Boolean {
        when (tess.windingRule) {
            GLU.GLU_TESS_WINDING_ODD -> return n and 1 != 0
            GLU.GLU_TESS_WINDING_NONZERO -> return n != 0
            GLU.GLU_TESS_WINDING_POSITIVE -> return n > 0
            GLU.GLU_TESS_WINDING_NEGATIVE -> return n < 0
            GLU.GLU_TESS_WINDING_ABS_GEQ_TWO -> return n >= 2 || n <= -2
        }
        throw RuntimeException()
        /*NOTREACHED*/
    }

    fun computeWinding(tess: GLUtessellatorImpl, reg: ActiveRegion) {
        reg.windingNumber = regionAbove(reg).windingNumber + reg.eUp.winding
        reg.inside = isWindingInside(tess, reg.windingNumber)
    }

    /**
     * Delete a region from the sweep line.  This happens when the upper
     * and lower chains of a region meet (at a vertex on the sweep line).
     * The "inside" flag is copied to the appropriate mesh face (we could
     * not do this before -- since the structure of the mesh is always
     * changing, this face may not have even existed until now).
     */
    fun finishRegion(reg: ActiveRegion)  {
        val e = reg.eUp
        val f = e.lFace
        f.inside = reg.inside
        f.anEdge = e /* optimization for glMeshTessellateMonoRegion() */
        deleteRegion(reg)
    }

    /**
     * We are given a vertex with one or more left-going edges.  All affected
     * edges should be in the edge dictionary.  Starting at regFirst.eUp,
     * we walk down deleting all regions where both edges have the same
     * origin vOrg.  At the same time we copy the "inside" flag from the
     * active region to the face, since at this point each face will belong
     * to at most one region (this was not necessarily true until this point
     * in the sweep).  The walk stops at the region above regLast; if regLast
     * is null we walk as far as possible.  At the same time we relink the
     * mesh if necessary, so that the ordering of edges around vOrg is the
     * same as in the dictionary.
     */
    fun finishLeftRegions(regFirst: ActiveRegion, regLast: ActiveRegion?): GLUhalfEdge  {
        var regPrev = regFirst
        var ePrev = regFirst.eUp
        while (regPrev !== regLast) {
            regPrev.fixUpperEdge = false /* placement was OK */
            val reg = regionBelow(regPrev)
            var e = reg.eUp
            if (e.org !== ePrev.org) {
                if (!reg.fixUpperEdge) {
                    /**
                     * Remove the last left-going edge.  Even though there are no further
                     * edges in the dictionary with this origin, there may be further
                     * such edges in the mesh (if we are adding left edges to a vertex
                     * that has already been processed).  Thus it is important to call
                     * FinishRegion rather than just DeleteRegion.
                     */
                    finishRegion(regPrev)
                    break
                }
                /**
                 * If the edge below was a temporary edge introduced by
                 * ConnectRightVertex, now is the time to fix it.
                 */
                e = Mesh.glMeshConnect(ePrev.oNext.sym, e.sym)
                if (!fixUpperEdge(reg, e)) throw RuntimeException()
            }

            /* Relink edges so that ePrev.Onext == e */
            if (ePrev.oNext !== e) {
                if (!Mesh.glMeshSplice(e.sym.lNext, e)) throw RuntimeException()
                if (!Mesh.glMeshSplice(ePrev, e)) throw RuntimeException()
            }
            finishRegion(regPrev) /* may change reg.eUp */
            ePrev = reg.eUp
            regPrev = reg
        }
        return ePrev
    }

    /**
     * Purpose: insert right-going edges into the edge dictionary, and update
     * winding numbers and mesh connectivity appropriately.  All right-going
     * edges share a common origin vOrg.  Edges are inserted CCW starting at
     * eFirst; the last edge inserted is eLast.Sym.Lnext.  If vOrg has any
     * left-going edges already processed, then eTopLeft must be the edge
     * such that an imaginary upward vertical segment from vOrg would be
     * contained between eTopLeft.Sym.Lnext and eTopLeft; otherwise eTopLeft
     * should be null.
     */
    fun addRightEdges(
        tess: GLUtessellatorImpl, regUp: ActiveRegion,
        eFirst: GLUhalfEdge, eLast: GLUhalfEdge?, eTopLeft: GLUhalfEdge?,
        cleanUp: Boolean
    ) {
        var firstTime = true

        /* Insert the new right-going edges in the dictionary */
        var e = eFirst
        do {
            addRegionBelow(tess, regUp, e.sym)
            e = e.oNext
        } while (e !== eLast)

        /**
         * Walk *all* right-going edges from e.Org, in the dictionary order,
         * updating the winding numbers of each region, and re-linking the mesh
         * edges to match the dictionary ordering (if necessary).
         */
        var regPrev = regUp
        var ePrev = eTopLeft ?: regionBelow(regUp).eUp.sym.oNext
        var reg: ActiveRegion
        while (true) {
            reg = regionBelow(regPrev)
            e = reg.eUp.sym
            if (e.org !== ePrev.org) break
            if (e.oNext !== ePrev) {
                /* Unlink e from its current position, and relink below ePrev */
                if (!Mesh.glMeshSplice(e.sym.lNext, e)) throw RuntimeException()
                if (!Mesh.glMeshSplice(ePrev.sym.lNext, e)) throw RuntimeException()
            }
            /* Compute the winding number and "inside" flag for the new regions */
            reg.windingNumber = regPrev.windingNumber - e.winding
            reg.inside = isWindingInside(tess, reg.windingNumber)

            /**
             * Check for two outgoing edges with same slope -- process these
             * before any intersection tests (see example in glComputeInterior).
             */
            regPrev.dirty = true
            if (!firstTime && checkForRightSplice(tess, regPrev)) {
                addWinding(e, ePrev)
                deleteRegion(regPrev)
                if (!Mesh.glMeshDelete(ePrev)) throw RuntimeException()
            }
            firstTime = false
            regPrev = reg
            ePrev = e
        }
        regPrev.dirty = true
        if (cleanUp) {
            /* Check for intersections between newly adjacent edges. */
            walkDirtyRegions(tess, regPrev)
        }
    }

    fun callCombine(
        tess: GLUtessellatorImpl, isect: GLUvertex,
        data: Array<Any?>, weights: FloatArray, needed: Boolean
    ) {
        val coords = DoubleArray(3)

        /* Copy coord data in case the callback changes it. */
        coords[0] = isect.coords[0]
        coords[1] = isect.coords[1]
        coords[2] = isect.coords[2]
        val outData = arrayOfNulls<Any>(1)
        tess.callCombineOrCombineData(coords, data, weights, outData)
        isect.data = outData[0]
        if (isect.data == null) {
            if (!needed) {
                isect.data = data[0]
            } else if (!tess.fatalError) {
                /**
                 * The only way fatal error is when two edges are found to intersect,
                 * but the user has not provided the callback necessary to handle
                 * generated intersection points.
                 */
                tess.callErrorOrErrorData(GLU.GLU_TESS_NEED_COMBINE_CALLBACK)
                tess.fatalError = true
            }
        }
    }

    /**
     * Two vertices with idential coordinates are combined into one.
     * e1.Org is kept, while e2.Org is discarded.
     */
    fun spliceMergeVertices(tess: GLUtessellatorImpl, e1: GLUhalfEdge, e2: GLUhalfEdge) {
        val data = arrayOfNulls<Any>(4)
        val weights = floatArrayOf(0.5f, 0.5f, 0.0f, 0.0f)
        data[0] = e1.org.data
        data[1] = e2.org.data
        callCombine(tess, e1.org, data, weights, false)
        if (!Mesh.glMeshSplice(e1, e2)) throw RuntimeException()
    }

    /**
     * Find some weights which describe how the intersection vertex is
     * a linear combination of "org" and "dest".  Each of the two edges
     * which generated "isect" is allocated 50% of the weight; each edge
     * splits the weight between its org and dst according to the
     * relative distance to "isect".
     */
    fun vertexWeights(
        isect: GLUvertex, org: GLUvertex, dst: GLUvertex,
        weights: FloatArray
    ) {
        val t1 = Geom.vertL1dist(org, isect)
        val t2 = Geom.vertL1dist(dst, isect)
        weights[0] = (0.5 * t2 / (t1 + t2)).toFloat()
        weights[1] = (0.5 * t1 / (t1 + t2)).toFloat()
        isect.coords[0] += weights[0] * org.coords[0] + weights[1] * dst.coords[0]
        isect.coords[1] += weights[0] * org.coords[1] + weights[1] * dst.coords[1]
        isect.coords[2] += weights[0] * org.coords[2] + weights[1] * dst.coords[2]
    }

    /**
     * We've computed a new intersection point, now we need a "data" pointer
     * from the user so that we can refer to this new vertex in the
     * rendering callbacks.
     */
    fun getIntersectData(
        tess: GLUtessellatorImpl, isect: GLUvertex,
        orgUp: GLUvertex, dstUp: GLUvertex,
        orgLo: GLUvertex, dstLo: GLUvertex
    ) {
        val data = arrayOfNulls<Any>(4)
        val weights = FloatArray(4)
        val weights1 = FloatArray(2)
        val weights2 = FloatArray(2)
        data[0] = orgUp.data
        data[1] = dstUp.data
        data[2] = orgLo.data
        data[3] = dstLo.data
        isect.coords[2] = 0.0
        isect.coords[1] = isect.coords[2]
        isect.coords[0] = isect.coords[1]
        vertexWeights(isect, orgUp, dstUp, weights1)
        vertexWeights(isect, orgLo, dstLo, weights2)
        weights1.copyInto(weights, 0)
        weights2.copyInto(weights, 2)
        callCombine(tess, isect, data, weights, true)
    }

    /**
     * Check the upper and lower edge of "regUp", to make sure that the
     * eUp.Org is above eLo, or eLo.Org is below eUp (depending on which
     * origin is leftmost).
     *
     * The main purpose is to splice right-going edges with the same
     * dest vertex and nearly identical slopes (ie. we can't distinguish
     * the slopes numerically).  However the splicing can also help us
     * to recover from numerical errors.  For example, suppose at one
     * point we checked eUp and eLo, and decided that eUp.Org is barely
     * above eLo.  Then later, we split eLo into two edges (eg. from
     * a splice operation like this one).  This can change the result of
     * our test so that now eUp.Org is incident to eLo, or barely below it.
     * We must correct this condition to maintain the dictionary invariants.
     *
     * One possibility is to check these edges for intersection again
     * (ie. CheckForIntersect).  This is what we do if possible.  However
     * CheckForIntersect requires that tess.event lies between eUp and eLo,
     * so that it has something to fall back on when the intersection
     * calculation gives us an unusable answer.  So, for those cases where
     * we can't check for intersection, this routine fixes the problem
     * by just splicing the offending vertex into the other edge.
     * This is a guaranteed solution, no matter how degenerate things get.
     * Basically this is a combinatorial solution to a numerical problem.
     */
    fun checkForRightSplice(tess: GLUtessellatorImpl, regUp: ActiveRegion): Boolean {
        val regLo = regionBelow(regUp)
        val eUp = regUp.eUp
        val eLo = regLo.eUp
        if (Geom.vertLeq(eUp.org, eLo.org)) {
            if (Geom.edgeSign(eLo.sym.org, eUp.org, eLo.org) > 0) return false

            /* eUp.Org appears to be below eLo */
            if (!Geom.vertEq(eUp.org, eLo.org)) {
                /* Splice eUp.Org into eLo */
                Mesh.glMeshSplitEdge(eLo.sym)
                if (!Mesh.glMeshSplice(eUp, eLo.sym.lNext)) throw RuntimeException()
                regLo.dirty = true
                regUp.dirty = regLo.dirty
            } else if (eUp.org !== eLo.org) {
                /* merge the two vertices, discarding eUp.Org */
                tess.pq.pqDelete(eUp.org.pqHandle) /* glPqSortDelete */
                spliceMergeVertices(tess, eLo.sym.lNext, eUp)
            }
        } else {
            if (Geom.edgeSign(eUp.sym.org, eLo.org, eUp.org) < 0) return false

            /* eLo.Org appears to be above eUp, so splice eLo.Org into eUp */
            regUp.dirty = true
            regionAbove(regUp).dirty = regUp.dirty
            Mesh.glMeshSplitEdge(eUp.sym)
            if (!Mesh.glMeshSplice(eLo.sym.lNext, eUp)) throw RuntimeException()
        }
        return true
    }

    /**
     * Check the upper and lower edge of "regUp", to make sure that the
     * eUp.Sym.Org is above eLo, or eLo.Sym.Org is below eUp (depending on which
     * destination is rightmost).
     *
     * Theoretically, this should always be true.  However, splitting an edge
     * into two pieces can change the results of previous tests.  For example,
     * suppose at one point we checked eUp and eLo, and decided that eUp.Sym.Org
     * is barely above eLo.  Then later, we split eLo into two edges (eg. from
     * a splice operation like this one).  This can change the result of
     * the test so that now eUp.Sym.Org is incident to eLo, or barely below it.
     * We must correct this condition to maintain the dictionary invariants
     * (otherwise new edges might get inserted in the wrong place in the
     * dictionary, and bad stuff will happen).
     *
     * We fix the problem by just splicing the offending vertex into the
     * other edge.
     */
    fun checkForLeftSplice(regUp: ActiveRegion): Boolean {
        val regLo = regionBelow(regUp)
        val eUp = regUp.eUp
        val eLo = regLo.eUp
        if (Geom.vertLeq(eUp.sym.org, eLo.sym.org)) {
            if (Geom.edgeSign(eUp.sym.org, eLo.sym.org, eUp.org) < 0) return false

            /* eLo.Sym.Org is above eUp, so splice eLo.Sym.Org into eUp */
            regUp.dirty = true
            regionAbove(regUp).dirty = regUp.dirty
            val e = Mesh.glMeshSplitEdge(eUp)
            if (!Mesh.glMeshSplice(eLo.sym, e)) throw RuntimeException()
            e.lFace.inside = regUp.inside
        } else {
            if (Geom.edgeSign(eLo.sym.org, eUp.sym.org, eLo.org) > 0) return false

            /* eUp.Sym.Org is below eLo, so splice eUp.Sym.Org into eLo */
            regLo.dirty = true
            regUp.dirty = regLo.dirty
            val e = Mesh.glMeshSplitEdge(eLo)
            if (!Mesh.glMeshSplice(eUp.lNext, eLo.sym)) throw RuntimeException()
            e.sym.lFace.inside = regUp.inside
        }
        return true
    }

    /**
     * Check the upper and lower edges of the given region to see if
     * they intersect.  If so, create the intersection and add it
     * to the data structures.
     *
     * Returns true if adding the new intersection resulted in a recursive
     * call to AddRightEdges(); in this case all "dirty" regions have been
     * checked for intersections, and possibly regUp has been deleted.
     */
    fun checkForIntersect(tess: GLUtessellatorImpl, regUp: ActiveRegion): Boolean {
        var regUp = regUp
        var regLo = regionBelow(regUp)
        var eUp = regUp.eUp
        var eLo = regLo.eUp
        val orgUp = eUp.org
        val orgLo = eLo.org
        val dstUp = eUp.sym.org
        val dstLo = eLo.sym.org
        val isect = GLUvertex()
        if (orgUp === orgLo) return false /* right endpoints are the same */
        val tMinUp = orgUp.t.coerceAtMost(dstUp.t)
        val tMaxLo = orgLo.t.coerceAtLeast(dstLo.t)
        if (tMinUp > tMaxLo) return false /* t ranges do not overlap */
        if (Geom.vertLeq(orgUp, orgLo)) {
            if (Geom.edgeSign(dstLo, orgUp, orgLo) > 0) return false
        } else {
            if (Geom.edgeSign(dstUp, orgLo, orgUp) < 0) return false
        }

        /* At this point the edges intersect, at least marginally */
        debugEvent(tess)
        Geom.edgeIntersect(dstUp, orgUp, dstLo, orgLo, isect)
        if (Geom.vertLeq(isect, tess.event)) {
            /**
             * The intersection point lies slightly to the left of the sweep line,
             * so move it until it''s slightly to the right of the sweep line.
             * (If we had perfect numerical precision, this would never happen
             * in the first place).  The easiest and safest thing to do is
             * replace the intersection by tess.event.
             */
            isect.s = tess.event.s
            isect.t = tess.event.t
        }
        /**
         * Similarly, if the computed intersection lies to the right of the
         * rightmost origin (which should rarely happen), it can cause
         * unbelievable inefficiency on sufficiently degenerate inputs.
         * (If you have the test program, try running test54.d with the
         * "X zoom" option turned on).
         */
        val orgMin = if (Geom.vertLeq(orgUp, orgLo)) orgUp else orgLo
        if (Geom.vertLeq(orgMin, isect)) {
            isect.s = orgMin.s
            isect.t = orgMin.t
        }
        if (Geom.vertEq(isect, orgUp) || Geom.vertEq(isect, orgLo)) {
            /* Easy case -- intersection at one of the right endpoints */
            checkForRightSplice(tess, regUp)
            return false
        }
        if ((!Geom.vertEq(dstUp, tess.event)
                    && Geom.edgeSign(dstUp, tess.event, isect) >= 0)
            || (!Geom.vertEq(dstLo, tess.event)
                    && Geom.edgeSign(dstLo, tess.event, isect) <= 0)
        ) {
            /**
             * Very unusual -- the new upper or lower edge would pass on the
             * wrong side of the sweep event, or through it.  This can happen
             * due to very small numerical errors in the intersection calculation.
             */
            if (dstLo === tess.event) {
                /* Splice dstLo into eUp, and process the new region(s) */
                Mesh.glMeshSplitEdge(eUp.sym)
                if (!Mesh.glMeshSplice(eLo.sym, eUp)) throw RuntimeException()
                regUp = topLeftRegion(regUp)
                eUp = regionBelow(regUp).eUp
                finishLeftRegions(regionBelow(regUp), regLo)
                addRightEdges(tess, regUp, eUp.sym.lNext, eUp, eUp, true)
                return true
            }
            if (dstUp === tess.event) {
                /* Splice dstUp into eLo, and process the new region(s) */
                Mesh.glMeshSplitEdge(eLo.sym)
                if (!Mesh.glMeshSplice(eUp.lNext, eLo.sym.lNext)) throw RuntimeException()
                regLo = regUp
                regUp = topRightRegion(regUp)
                val e = regionBelow(regUp).eUp.sym.oNext
                regLo.eUp = eLo.sym.lNext
                eLo = finishLeftRegions(regLo, null)
                addRightEdges(tess, regUp, eLo.oNext, eUp.sym.oNext, e, true)
                return true
            }
            /**
             * Special case: called from ConnectRightVertex.  If either
             * edge passes on the wrong side of tess.event, split it
             * (and wait for ConnectRightVertex to splice it appropriately).
             */
            if (Geom.edgeSign(dstUp, tess.event, isect) >= 0) {
                regUp.dirty = true
                regionAbove(regUp).dirty = regUp.dirty
                Mesh.glMeshSplitEdge(eUp.sym)
                eUp.org.s = tess.event.s
                eUp.org.t = tess.event.t
            }
            if (Geom.edgeSign(dstLo, tess.event, isect) <= 0) {
                regLo.dirty = true
                regUp.dirty = regLo.dirty
                Mesh.glMeshSplitEdge(eLo.sym)
                eLo.org.s = tess.event.s
                eLo.org.t = tess.event.t
            }
            /* leave the rest for ConnectRightVertex */
            return false
        }

        /**
         * General case -- split both edges, splice into new vertex.
         * When we do the splice operation, the order of the arguments is
         * arbitrary as far as correctness goes.  However, when the operation
         * creates a new face, the work done is proportional to the size of
         * the new face.  We expect the faces in the processed part of
         * the mesh (ie. eUp.Lface) to be smaller than the faces in the
         * unprocessed original contours (which will be eLo.Sym.Lnext.Lface).
         */
        Mesh.glMeshSplitEdge(eUp.sym)
        Mesh.glMeshSplitEdge(eLo.sym)
        if (!Mesh.glMeshSplice(eLo.sym.lNext, eUp)) throw RuntimeException()
        eUp.org.s = isect.s
        eUp.org.t = isect.t
        eUp.org.pqHandle = tess.pq.pqInsert(eUp.org) /* glPqSortInsert */
        getIntersectData(tess, eUp.org, orgUp, dstUp, orgLo, dstLo)
        regLo.dirty = true
        regUp.dirty = regLo.dirty
        regionAbove(regUp).dirty = regUp.dirty
        return false
    }

    /**
     * When the upper or lower edge of any region changes, the region is
     * marked "dirty".  This routine walks through all the dirty regions
     * and makes sure that the dictionary invariants are satisfied
     * (see the comments at the beginning of this file).  Of course
     * new dirty regions can be created as we make changes to restore
     * the invariants.
     */
    fun walkDirtyRegions(tess: GLUtessellatorImpl, regUp: ActiveRegion) {
        var regUp = regUp
        var regLo = regionBelow(regUp)
        while (true) {

            /* Find the lowest dirty region (we walk from the bottom up). */
            while (regLo.dirty) {
                regUp = regLo
                regLo = regionBelow(regLo)
            }
            if (!regUp.dirty) {
                regLo = regUp
                regUp = regionAboveOrNull(regUp) ?: return
                if (!regUp.dirty) return /* We've walked all the dirty regions */
            }
            regUp.dirty = false
            var eUp = regUp.eUp
            var eLo = regLo.eUp
            if (eUp.sym.org !== eLo.sym.org) {
                /* Check that the edge ordering is obeyed at the Dst vertices. */
                if (checkForLeftSplice(regUp)) {

                    /**
                     * If the upper or lower edge was marked fixUpperEdge, then
                     * we no longer need it (since these edges are needed only for
                     * vertices which otherwise have no right-going edges).
                     */
                    if (regLo.fixUpperEdge) {
                        deleteRegion(regLo)
                        if (!Mesh.glMeshDelete(eLo)) throw RuntimeException()
                        regLo = regionBelow(regUp)
                        eLo = regLo.eUp
                    } else if (regUp.fixUpperEdge) {
                        deleteRegion(regUp)
                        if (!Mesh.glMeshDelete(eUp)) throw RuntimeException()
                        regUp = regionAbove(regLo)
                        eUp = regUp.eUp
                    }
                }
            }
            if (eUp.org !== eLo.org) {
                if (eUp.sym.org !== eLo.sym.org && !regUp.fixUpperEdge && !regLo.fixUpperEdge
                    && (eUp.sym.org === tess.event || eLo.sym.org === tess.event)
                ) {
                    /**
                     * When all else fails in CheckForIntersect(), it uses tess.event
                     * as the intersection location.  To make this possible, it requires
                     * that tess.event lie between the upper and lower edges, and also
                     * that neither of these is marked fixUpperEdge (since in the worst
                     * case it might splice one of these edges into tess.event, and
                     * violate the invariant that fixable edges are the only right-going
                     * edge from their associated vertex).
                     */
                    if (checkForIntersect(tess, regUp)) {
                        /* WalkDirtyRegions() was called recursively; we're done */
                        return
                    }
                } else {
                    /**
                     * Even though we can't use CheckForIntersect(), the Org vertices
                     * may violate the dictionary edge ordering.  Check and correct this.
                     */
                    checkForRightSplice(tess, regUp)
                }
            }
            if (eUp.org === eLo.org && eUp.sym.org === eLo.sym.org) {
                /* A degenerate loop consisting of only two edges -- delete it. */
                addWinding(eLo, eUp)
                deleteRegion(regUp)
                if (!Mesh.glMeshDelete(eUp)) throw RuntimeException()
                regUp = regionAbove(regLo)
            }
        }
    }

    /**
     * Purpose: connect a "right" vertex vEvent (one where all edges go left)
     * to the unprocessed portion of the mesh.  Since there are no right-going
     * edges, two regions (one above vEvent and one below) are being merged
     * into one.  "regUp" is the upper of these two regions.
     *
     * There are two reasons for doing this (adding a right-going edge):
     *  - if the two regions being merged are "inside", we must add an edge
     *    to keep them separated (the combined region would not be monotone).
     *  - in any case, we must leave some record of vEvent in the dictionary,
     *    so that we can merge vEvent with features that we have not seen yet.
     *    For example, maybe there is a vertical edge which passes just to
     *    the right of vEvent; we would like to splice vEvent into this edge.
     *
     * However, we don't want to connect vEvent to just any vertex.  We don''t
     * want the new edge to cross any other edges; otherwise we will create
     * intersection vertices even when the input data had no self-intersections.
     * (This is a bad thing; if the user's input data has no intersections,
     * we don't want to generate any false intersections ourselves.)
     *
     * Our eventual goal is to connect vEvent to the leftmost unprocessed
     * vertex of the combined region (the union of regUp and regLo).
     * But because of unseen vertices with all right-going edges, and also
     * new vertices which may be created by edge intersections, we don''t
     * know where that leftmost unprocessed vertex is.  In the meantime, we
     * connect vEvent to the closest vertex of either chain, and mark the region
     * as "fixUpperEdge".  This flag says to delete and reconnect this edge
     * to the next processed vertex on the boundary of the combined region.
     * Quite possibly the vertex we connected to will turn out to be the
     * closest one, in which case we won''t need to make any changes.
     */
    fun connectRightVertex(
        tess: GLUtessellatorImpl, regUp: ActiveRegion,
        eBottomLeft: GLUhalfEdge
    ) {
        var regUp = regUp
        var eBottomLeft = eBottomLeft
        var eTopLeft = eBottomLeft.oNext
        val regLo = regionBelow(regUp)
        val eUp = regUp.eUp
        val eLo = regLo.eUp
        var degenerate = false
        if (eUp.sym.org !== eLo.sym.org) {
            checkForIntersect(tess, regUp)
        }

        /**
         * Possible new degeneracies: upper or lower edge of regUp may pass
         * through vEvent, or may coincide with new intersection vertex
         */
        if (Geom.vertEq(eUp.org, tess.event)) {
            if (!Mesh.glMeshSplice(eTopLeft.sym.lNext, eUp)) throw RuntimeException()
            regUp = topLeftRegion(regUp)
            eTopLeft = regionBelow(regUp).eUp
            finishLeftRegions(regionBelow(regUp), regLo)
            degenerate = true
        }
        if (Geom.vertEq(eLo.org, tess.event)) {
            if (!Mesh.glMeshSplice(eBottomLeft, eLo.sym.lNext)) throw RuntimeException()
            eBottomLeft = finishLeftRegions(regLo, null)
            degenerate = true
        }
        if (degenerate) {
            addRightEdges(tess, regUp, eBottomLeft.oNext, eTopLeft, eTopLeft, true)
            return
        }

        /**
         * Non-degenerate situation -- need to add a temporary, fixable edge.
         * Connect to the closer of eLo.Org, eUp.Org.
         */
        var eNew = if (Geom.vertLeq(eLo.org, eUp.org)) eLo.sym.lNext else eUp
        eNew = Mesh.glMeshConnect(eBottomLeft.oNext.sym, eNew)

        /**
         * Prevent cleanup, otherwise eNew might disappear before we've even
         * had a chance to mark it as a temporary edge.
         */
        addRightEdges(tess, regUp, eNew, eNew.oNext, eNew.oNext, false)
        eNew.sym.activeRegion?.fixUpperEdge = true
        walkDirtyRegions(tess, regUp)
    }

    /**
     * Because vertices at exactly the same location are merged together
     * before we process the sweep event, some degenerate cases can't occur.
     * However if someone eventually makes the modifications required to
     * merge features which are close together, the cases below marked
     * TOLERANCE_NONZERO will be useful.  They were debugged before the
     * code to merge identical vertices in the main loop was added.
     */
    private const val TOLERANCE_NONZERO = false
    /**
     * The event vertex lies exacty on an already-processed edge or vertex.
     * Adding the new vertex involves splicing it into the already-processed
     * part of the mesh.
     */
    fun connectLeftDegenerate(
        tess: GLUtessellatorImpl,
        regUp: ActiveRegion, vEvent: GLUvertex
    ) {
        var regUp = regUp
        val e = regUp.eUp
        if (Geom.vertEq(e.org, vEvent)) {
            /**
             * e.Org is an unprocessed vertex - just combine them, and wait
             * for e.Org to be pulled from the queue
             */
            spliceMergeVertices(tess, e, vEvent.anEdge)
            return
        }
        if (!Geom.vertEq(e.sym.org, vEvent)) {
            /* General case -- splice vEvent into edge e which passes through it */
            Mesh.glMeshSplitEdge(e.sym)
            if (regUp.fixUpperEdge) {
                /* This edge was fixable -- delete unused portion of original edge */
                if (!Mesh.glMeshDelete(e.oNext)) throw RuntimeException()
                regUp.fixUpperEdge = false
            }
            if (!Mesh.glMeshSplice(vEvent.anEdge, e)) throw RuntimeException()
            sweepEvent(tess, vEvent) /* recurse */
            return
        }
        regUp = topRightRegion(regUp)
        val reg = regionBelow(regUp)
        var eTopRight = reg.eUp.sym
        val eLast = eTopRight.oNext
        if (reg.fixUpperEdge) {
            /**
             * Here e.Sym.Org has only a single fixable edge going right.
             * We can delete it since now we have some real right-going edges.
             */
            deleteRegion(reg)
            if (!Mesh.glMeshDelete(eTopRight)) throw RuntimeException()
            eTopRight = eLast.sym.lNext
        }
        if (!Mesh.glMeshSplice(vEvent.anEdge, eTopRight)) throw RuntimeException()
        /* e.Sym.Org had no left-going edges -- indicate this to AddRightEdges() */
        val eTopLeft = if (Geom.edgeGoesLeft(eLast)) eLast else null
        addRightEdges(tess, regUp, eTopRight.oNext, eLast, eTopLeft, true)
    }

    /**
     * Purpose: connect a "left" vertex (one where both edges go right)
     * to the processed portion of the mesh.  Let R be the active region
     * containing vEvent, and let U and L be the upper and lower edge
     * chains of R.  There are two possibilities:
     *
     * - the normal case: split R into two regions, by connecting vEvent to
     *   the rightmost vertex of U or L lying to the left of the sweep line
     *
     * - the degenerate case: if vEvent is close enough to U or L, we
     *   merge vEvent into that edge chain.  The subcases are:
     *	- merging with the rightmost vertex of U or L
     *	- merging with the active edge of U or L
     *	- merging with an already-processed portion of U or L
     */
    fun connectLeftVertex(tess: GLUtessellatorImpl, vEvent: GLUvertex) {
        /* Get a pointer to the active region containing vEvent */
        val tmp = ActiveRegion(vEvent.anEdge.sym)
        val regUp = Dict.dictKey(Dict.dictSearch(tess.dict, tmp)) as ActiveRegion
        val regLo = regionBelow(regUp)
        val eUp = regUp.eUp
        val eLo = regLo.eUp

        /* Try merging with U or L first */
        if (Geom.edgeSign(eUp.sym.org, vEvent, eUp.org) == 0.0) {
            connectLeftDegenerate(tess, regUp, vEvent)
            return
        }

        /**
         * Connect vEvent to rightmost processed vertex of either chain.
         * e.Sym.Org is the vertex that we will connect to vEvent.
         */
        val reg = if (Geom.vertLeq(eLo.sym.org, eUp.sym.org)) regUp else regLo
        if (regUp.inside || reg.fixUpperEdge) {
            val eNew = if (reg === regUp) {
                Mesh.glMeshConnect(vEvent.anEdge.sym, eUp.lNext)
            } else {
                val tempHalfEdge = Mesh.glMeshConnect(eLo.sym.oNext.sym, vEvent.anEdge)
                tempHalfEdge.sym
            }
            if (reg.fixUpperEdge) {
                if (!fixUpperEdge(reg, eNew)) throw RuntimeException()
            } else {
                computeWinding(tess, addRegionBelow(tess, regUp, eNew))
            }
            sweepEvent(tess, vEvent)
        } else {
            /**
             * The new vertex is in a region which does not belong to the polygon.
             * We don''t need to connect this vertex to the rest of the mesh.
             */
            addRightEdges(tess, regUp, vEvent.anEdge, vEvent.anEdge, null, true)
        }
    }

    /**
     * Does everything necessary when the sweep line crosses a vertex.
     * Updates the mesh and the edge dictionary.
     */
    fun sweepEvent(tess: GLUtessellatorImpl, vEvent: GLUvertex) {
        tess.event = vEvent /* for access in EdgeLeq() */
        debugEvent(tess)

        /**
         * Check if this vertex is the right endpoint of an edge that is
         * already in the dictionary.  In this case we don't need to waste
         * time searching for the location to insert new edges.
         */
        var e = vEvent.anEdge
        var activeRegion = e.activeRegion
        while (activeRegion == null) {
            e = e.oNext
            if (e === vEvent.anEdge) {
                /* All edges go right -- not incident to any processed edges */
                connectLeftVertex(tess, vEvent)
                return
            }
            activeRegion = e.activeRegion
        }

        /**
         * Processing consists of two phases: first we "finish" all the
         * active regions where both the upper and lower edges terminate
         * at vEvent (ie. vEvent is closing off these regions).
         * We mark these faces "inside" or "outside" the polygon according
         * to their winding number, and delete the edges from the dictionary.
         * This takes care of all the left-going edges from vEvent.
         */
        val regUp = topLeftRegion(activeRegion)
        val reg = regionBelow(regUp)
        val eTopLeft = reg.eUp
        val eBottomLeft = finishLeftRegions(reg, null)

        /**
         * Next we process all the right-going edges from vEvent.  This
         * involves adding the edges to the dictionary, and creating the
         * associated "active regions" which record information about the
         * regions between adjacent dictionary edges.
         */
        if (eBottomLeft.oNext === eTopLeft) {
            /* No right-going edges -- add a temporary "fixable" edge */
            connectRightVertex(tess, regUp, eBottomLeft)
        } else {
            addRightEdges(tess, regUp, eBottomLeft.oNext, eTopLeft, eTopLeft, true)
        }
    }

    /**
     * Make the sentinel coordinates big enough that they will never be
     * merged with real input features.  (Even with the largest possible
     * input contour and the maximum tolerance of 1.0, no merging will be
     * done with coordinates larger than 3 * GLU_TESS_MAX_COORD).
     */
    private const val SENTINEL_COORD = 4.0 * GLU.GLU_TESS_MAX_COORD
    /**
     * We add two sentinel edges above and below all other edges,
     * to avoid special cases at the top and bottom.
     */
    fun addSentinel(tess: GLUtessellatorImpl, t: Double) {
        val e = Mesh.glMeshMakeEdge(tess.mesh)
        e.org.s = SENTINEL_COORD
        e.org.t = t
        e.sym.org.s = -SENTINEL_COORD
        e.sym.org.t = t
        tess.event = e.sym.org /* initialize it */
        val reg = ActiveRegion(e)
        reg.windingNumber = 0
        reg.inside = false
        reg.fixUpperEdge = false
        reg.sentinel = true
        reg.dirty = false
        reg.nodeUp = Dict.dictInsert(tess.dict, reg) /* glDictListInsertBefore */
    }

    /**
     * We maintain an ordering of edge intersections with the sweep line.
     * This order is maintained in a dynamic dictionary.
     */
    fun initEdgeDict(tess: GLUtessellatorImpl) {
        /* glDictListNewDict */
        tess.dict = Dict.dictNewDict(
            tess,
            object : DictLeq {
                override fun leq(frame: Any, key1: Any, key2: Any): Boolean {
                    return edgeLeq(
                        tess,
                        key1 as ActiveRegion,
                        key2 as ActiveRegion
                    )
                }
            }
        )
        addSentinel(tess, -SENTINEL_COORD)
        addSentinel(tess, SENTINEL_COORD)
    }

    fun doneEdgeDict(tess: GLUtessellatorImpl) {
        while (true) {
            val reg = Dict.dictKey(Dict.dictMin(tess.dict)) as ActiveRegion? ?: break
            /**
             * At the end of all processing, the dictionary should contain
             * only the two sentinel edges, plus at most one "fixable" edge
             * created by ConnectRightVertex().
             */
            deleteRegion(reg)
            /*    glMeshDelete( reg.eUp )*/
        }
        Dict.dictDeleteDict(tess.dict) /* glDictListDeleteDict */
    }

    /**
     * Remove zero-length edges, and contours with fewer than 3 vertices.
     */
    fun removeDegenerateEdges(tess: GLUtessellatorImpl) {
        val eHead = tess.mesh.eHead

        /*LINTED*/
        var e = eHead.next
        while (e !== eHead) {
            var eNext = e.next
            var eLnext = e.lNext
            if (Geom.vertEq(e.org, e.sym.org) && e.lNext.lNext !== e) {
                /* Zero-length edge, contour has at least 3 edges */
                spliceMergeVertices(tess, eLnext, e) /* deletes e.Org */
                if (!Mesh.glMeshDelete(e)) throw RuntimeException() /* e is a self-loop */
                e = eLnext
                eLnext = e.lNext
            }
            if (eLnext.lNext === e) {
                /* Degenerate contour (one or two edges) */
                if (eLnext !== e) {
                    if (eLnext === eNext || eLnext === eNext.sym) {
                        eNext = eNext.next
                    }
                    if (!Mesh.glMeshDelete(eLnext)) throw RuntimeException()
                }
                if (e === eNext || e === eNext.sym) {
                    eNext = eNext.next
                }
                if (!Mesh.glMeshDelete(e)) throw RuntimeException()
            }
            e = eNext
        }
    }

    /**
     * Insert all vertices into the priority queue which determines the
     * order in which vertices cross the sweep line.
     */
    fun initPriorityQ(tess: GLUtessellatorImpl): Boolean {
        /* glPqSortNewPriorityQ */
        tess.pq = PriorityQ.pqNewPriorityQ()
        val vHead = tess.mesh.vHead
        var v = vHead.next
        while (v !== vHead) {
            v.pqHandle = tess.pq.pqInsert(v) /* glPqSortInsert */
            v = v.next
        }
        if (!tess.pq.pqInit()) { /* glPqSortInit */
            tess.pq.pqDeletePriorityQ() /* glPqSortDeletePriorityQ */
            //tess.pq = null
            return false
        }
        return true
    }

    fun donePriorityQ(tess: GLUtessellatorImpl) {
        tess.pq.pqDeletePriorityQ() /* glPqSortDeletePriorityQ */
    }

    /**
     * Delete any degenerate faces with only two edges.  WalkDirtyRegions()
     * will catch almost all of these, but it won't catch degenerate faces
     * produced by splice operations on already-processed edges.
     * The two places this can happen are in FinishLeftRegions(), when
     * we splice in a "temporary" edge produced by ConnectRightVertex(),
     * and in CheckForLeftSplice(), where we splice already-processed
     * edges to ensure that our dictionary invariants are not violated
     * by numerical errors.
     *
     * In both these cases it is *very* dangerous to delete the offending
     * edge at the time, since one of the routines further up the stack
     * will sometimes be keeping a pointer to that edge.
     */
    fun removeDegenerateFaces(mesh: GLUmesh): Boolean {
        var f = mesh.fHead.next
        while (f !== mesh.fHead) {
            val fNext = f.next
            val e = f.anEdge
            if (e.lNext.lNext === e) {
                /* A face with only two edges */
                addWinding(e.oNext, e)
                if (!Mesh.glMeshDelete(e)) return false
            }
            f = fNext
        }
        return true
    }

    /**
     * glComputeInterior( tess ) computes the planar arrangement specified
     * by the given contours, and further subdivides this arrangement
     * into regions.  Each region is marked "inside" if it belongs
     * to the polygon, according to the rule given by tess.windingRule.
     * Each interior region is guaranteed be monotone.
     */
    fun glComputeInterior(tess: GLUtessellatorImpl): Boolean {
        tess.fatalError = false

        /**
         * Each vertex defines an event for our sweep line.  Start by inserting
         * all the vertices in a priority queue.  Events are processed in
         * lexicographic order, ie.
         *
         *	e1 < e2  iff  e1.x < e2.x || (e1.x == e2.x && e1.y < e2.y)
         */
        removeDegenerateEdges(tess)
        if (!initPriorityQ(tess)) return false /* if error */
        initEdgeDict(tess)

        /* glPqSortExtractMin */
        while (true) {
            val v = tess.pq.pqExtractMin() as GLUvertex? ?: break
            while (true) {
                var vNext = tess.pq.pqMinimum() as GLUvertex? /* glPqSortMinimum */
                if (vNext == null || !Geom.vertEq(vNext, v)) break

                /**
                 * Merge together all vertices at exactly the same location.
                 * This is more efficient than processing them one at a time,
                 * simplifies the code (see ConnectLeftDegenerate), and is also
                 * important for correct handling of certain degenerate cases.
                 * For example, suppose there are two identical edges A and B
                 * that belong to different contours (so without this code they would
                 * be processed by separate sweep events).  Suppose another edge C
                 * crosses A and B from above.  When A is processed, we split it
                 * at its intersection point with C.  However this also splits C,
                 * so when we insert B we may compute a slightly different
                 * intersection point.  This might leave two edges with a small
                 * gap between them.  This kind of error is especially obvious
                 * when using boundary extraction (GLU_TESS_BOUNDARY_ONLY).
                 */
                vNext = tess.pq.pqExtractMin() as GLUvertex /* glPqSortExtractMin*/
                spliceMergeVertices(tess, v.anEdge, vNext.anEdge)
            }
            sweepEvent(tess, v)
        }

        /* Set tess.event for debugging purposes */
        /* GL_DICTLISTKEY */
        /* GL_DICTLISTMIN */
        tess.event = (Dict.dictKey(Dict.dictMin(tess.dict)) as ActiveRegion).eUp.org
        debugEvent(tess)
        doneEdgeDict(tess)
        donePriorityQ(tess)
        if (!removeDegenerateFaces(tess.mesh)) return false
        Mesh.glMeshCheckMesh(tess.mesh)
        return true
    }
}