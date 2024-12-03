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
import earth.worldwind.util.glu.GLUtessellator
import earth.worldwind.util.glu.GLUtessellatorCallback
import earth.worldwind.util.glu.GLUtessellatorCallbackAdapter

class GLUtessellatorImpl private constructor() : GLUtessellator {
    /**
     * what begin/end calls have we seen?
     */
    private var state = TessState.T_DORMANT
    /**
     * lastEdge->Org is the most recent vertex
     */
    private var lastEdge: GLUhalfEdge? = null
    /**
     *  stores the input contours, and eventually the tessellation itself
     */
    var mesh: GLUmesh? = null

    /*** state needed for projecting onto the sweep plane  */
    /**
     * user-specified normal (if provided)
     */
    val normal = DoubleArray(3)
    /**
     * unit vector in s-direction (debugging)
     */
    val sUnit = DoubleArray(3)
    /**
     * unit vector in t-direction (debugging)
     */
    val tUnit = DoubleArray(3)

    /*** state needed for the line sweep  */
    /**
     * tolerance for merging features
     */
    private var relTolerance = GLU_TESS_DEFAULT_TOLERANCE
    /**
     * rule for determining polygon interior
     */
    var windingRule = GLU.GLU_TESS_WINDING_ODD
    /**
     * fatal error: needed combine callback
     */
    var fatalError = false
    /**
     * edge dictionary for sweep line
     */
    var dict: Dict? = null
    /**
     * priority queue of vertex events
     */
    var pq: PriorityQ? = null
    /**
     * current sweep event being processed
     */
    var event: GLUvertex? = null

    /*** state needed for rendering callbacks (see render.c)  */
    /**
     * mark boundary edges (use EdgeFlag)
     */
    var flagBoundary = false
    /**
     * Extract contours, not triangles
     */
    var boundaryOnly = false
    /**
     * list of triangles which could not be rendered as strips or fans
     */
    var lonelyTriList: GLUface? = null
    /*** state needed to cache single-contour polygons for renderCache()  */
    /**
     * empty cache on next vertex() call
     */
    private var flushCacheOnNextVertex = false
    /**
     * number of cached vertices
     */
    var cacheCount = 0
    /**
     * the vertex data
     */
    val cache = Array(TESS_MAX_CACHE) { CachedVertex() }

    /*** rendering callbacks that also pass polygon data   */
    /**
     * client data for current polygon
     */
    private var polygonData: Any? = null
    private var callBegin: GLUtessellatorCallback = NULL_CB
    private var callEdgeFlag: GLUtessellatorCallback = NULL_CB
    private var callVertex: GLUtessellatorCallback = NULL_CB
    private var callEnd: GLUtessellatorCallback = NULL_CB

    //private var callMesh: GLUtessellatorCallback = NULL_CB
    private var callError: GLUtessellatorCallback = NULL_CB
    private var callCombine: GLUtessellatorCallback = NULL_CB
    private var callBeginData: GLUtessellatorCallback = NULL_CB
    private var callEdgeFlagData: GLUtessellatorCallback = NULL_CB
    private var callVertexData: GLUtessellatorCallback = NULL_CB
    private var callEndData: GLUtessellatorCallback = NULL_CB

    //private GLUtessellatorCallback callMeshData;
    private var callErrorData: GLUtessellatorCallback = NULL_CB
    private var callCombineData: GLUtessellatorCallback = NULL_CB

    private fun makeDormant() {
        /**
         * Return the tessellator to its original dormant state.
         */
        mesh?.let { Mesh.glMeshDeleteMesh(it) }
        state = TessState.T_DORMANT
        lastEdge = null
        mesh = null
    }

    private fun requireState(newState: Int) {
        if (state != newState) gotoState(newState)
    }

    private fun gotoState(newState: Int) {
        while (state != newState) {
            /**
             * We change the current state one level at a time, to get to the desired state.
             */
            if (state < newState) {
                if (state == TessState.T_DORMANT) {
                    callErrorOrErrorData(GLU.GLU_TESS_MISSING_BEGIN_POLYGON)
                    gluTessBeginPolygon(null)
                } else if (state == TessState.T_IN_POLYGON) {
                    callErrorOrErrorData(GLU.GLU_TESS_MISSING_BEGIN_CONTOUR)
                    gluTessBeginContour()
                }
            } else {
                if (state == TessState.T_IN_CONTOUR) {
                    callErrorOrErrorData(GLU.GLU_TESS_MISSING_END_CONTOUR)
                    gluTessEndContour()
                } else if (state == TessState.T_IN_POLYGON) {
                    callErrorOrErrorData(GLU.GLU_TESS_MISSING_END_POLYGON)
                    /* gluTessEndPolygon( tess ) is too much work! */
                    makeDormant()
                }
            }
        }
    }

    fun gluDeleteTess() {
        requireState(TessState.T_DORMANT)
    }

    fun gluTessProperty(which: Int, value: Double) {
        when (which) {
            GLU.GLU_TESS_TOLERANCE -> {
                if (value < 0.0 || value > 1.0) callErrorOrErrorData(GLU.GLU_INVALID_VALUE)
                else relTolerance = value
            }
            GLU.GLU_TESS_WINDING_RULE -> {
                val windingRule = value.toInt()
                if (windingRule.toDouble() != value) {
                    /* not an integer */
                    callErrorOrErrorData(GLU.GLU_INVALID_VALUE)
                } else {
                    when (windingRule) {
                        GLU.GLU_TESS_WINDING_ODD,
                        GLU.GLU_TESS_WINDING_NONZERO,
                        GLU.GLU_TESS_WINDING_POSITIVE,
                        GLU.GLU_TESS_WINDING_NEGATIVE,
                        GLU.GLU_TESS_WINDING_ABS_GEQ_TWO -> this.windingRule = windingRule
                        else -> callErrorOrErrorData(GLU.GLU_INVALID_VALUE)
                    }
                }
            }
            GLU.GLU_TESS_BOUNDARY_ONLY -> boundaryOnly = value != 0.0
            else -> callErrorOrErrorData(GLU.GLU_INVALID_ENUM)
        }
    }

    /**
     * Returns tessellator property
     */
    fun gluGetTessProperty(which: Int, value: DoubleArray, value_offset: Int) {
        when (which) {
            GLU.GLU_TESS_TOLERANCE -> value[value_offset] = relTolerance
            GLU.GLU_TESS_WINDING_RULE -> value[value_offset] = windingRule.toDouble()
            GLU.GLU_TESS_BOUNDARY_ONLY -> value[value_offset] = if (boundaryOnly) 1.0 else 0.0
            else -> {
                value[value_offset] = 0.0
                callErrorOrErrorData(GLU.GLU_INVALID_ENUM)
            }
        }
    }

    fun gluTessNormal(x: Double, y: Double, z: Double) {
        normal[0] = x
        normal[1] = y
        normal[2] = z
    }

    fun gluTessCallback(which: Int, aCallback: GLUtessellatorCallback?) {
        when (which) {
            GLU.GLU_TESS_BEGIN -> callBegin = aCallback ?: NULL_CB
            GLU.GLU_TESS_BEGIN_DATA -> callBeginData = aCallback ?: NULL_CB
            GLU.GLU_TESS_EDGE_FLAG -> {
                callEdgeFlag = aCallback ?: NULL_CB
                /**
                 * If the client wants boundary edges to be flagged,
                 * we render everything as separate triangles (no strips or fans).
                 */
                flagBoundary = aCallback != null
            }
            GLU.GLU_TESS_EDGE_FLAG_DATA -> {
                run {
                    callBegin = aCallback ?: NULL_CB
                    callEdgeFlagData = callBegin
                }
                /**
                 * If the client wants boundary edges to be flagged,
                 * we render everything as separate triangles (no strips or fans).
                 */
                flagBoundary = aCallback != null
            }
            GLU.GLU_TESS_VERTEX -> callVertex = aCallback ?: NULL_CB
            GLU.GLU_TESS_VERTEX_DATA -> callVertexData = aCallback ?: NULL_CB
            GLU.GLU_TESS_END -> callEnd = aCallback ?: NULL_CB
            GLU.GLU_TESS_END_DATA -> callEndData = aCallback ?: NULL_CB
            GLU.GLU_TESS_ERROR -> callError = aCallback ?: NULL_CB
            GLU.GLU_TESS_ERROR_DATA -> callErrorData = aCallback ?: NULL_CB
            GLU.GLU_TESS_COMBINE -> callCombine = aCallback ?: NULL_CB
            GLU.GLU_TESS_COMBINE_DATA -> callCombineData = aCallback ?: NULL_CB
            else -> callErrorOrErrorData(GLU.GLU_INVALID_ENUM)
        }
    }

    private fun addVertex(coords: DoubleArray, vertexData: Any?): Boolean {
        var e = lastEdge
        if (e == null) {
            /* Make a self-loop (one vertex, one edge). */
            e = Mesh.glMeshMakeEdge(mesh!!)
            if (!Mesh.glMeshSplice(e, e.sym!!)) return false
        } else {
            /**
             * Create a new vertex and edge which immediately follow e
             * in the ordering around the left face.
             */
            Mesh.glMeshSplitEdge(e)
            e = e.lNext!!
        }

        /* The new vertex is now e.Org. */
        e.org?.data = vertexData
        e.org!!.coords[0] = coords[0]
        e.org!!.coords[1] = coords[1]
        e.org!!.coords[2] = coords[2]

        /**
         * The winding of an edge says how the winding number changes as we
         * cross from the edge''s right face to its left face.  We add the
         * vertices in such an order that a CCW contour will add +1 to
         * the winding number of the region inside the contour.
         */
        e.winding = 1
        e.sym?.winding = -1
        lastEdge = e
        return true
    }

    private fun cacheVertex(coords: DoubleArray, vertexData: Any?) {
        val v = cache[cacheCount]
        v.data = vertexData
        v.coords[0] = coords[0]
        v.coords[1] = coords[1]
        v.coords[2] = coords[2]
        ++cacheCount
    }

    private fun flushCache(): Boolean {
        mesh = Mesh.glMeshNewMesh()
        for (i in 0 until cacheCount) {
            val vertex = cache[i]
            if (!addVertex(vertex.coords, vertex.data)) return false
        }
        cacheCount = 0
        flushCacheOnNextVertex = false
        return true
    }

    fun gluTessVertex(coords: DoubleArray, coords_offset: Int, vertexData: Any?) {
        var tooLarge = false
        val clamped = DoubleArray(3)
        requireState(TessState.T_IN_CONTOUR)
        if (flushCacheOnNextVertex) {
            if (!flushCache()) {
                callErrorOrErrorData(GLU.GLU_OUT_OF_MEMORY)
                return
            }
            lastEdge = null
        }
        var i = 0
        while (i < 3) {
            var x = coords[i + coords_offset]
            if (x < -GLU.GLU_TESS_MAX_COORD) {
                x = -GLU.GLU_TESS_MAX_COORD
                tooLarge = true
            }
            if (x > GLU.GLU_TESS_MAX_COORD) {
                x = GLU.GLU_TESS_MAX_COORD
                tooLarge = true
            }
            clamped[i] = x
            ++i
        }
        if (tooLarge) {
            callErrorOrErrorData(GLU.GLU_TESS_COORD_TOO_LARGE)
        }
        if (mesh == null) {
            if (cacheCount < TESS_MAX_CACHE) {
                cacheVertex(clamped, vertexData)
                return
            }
            if (!flushCache()) {
                callErrorOrErrorData(GLU.GLU_OUT_OF_MEMORY)
                return
            }
        }
        if (!addVertex(clamped, vertexData)) {
            callErrorOrErrorData(GLU.GLU_OUT_OF_MEMORY)
        }
    }

    fun gluTessBeginPolygon(data: Any?) {
        requireState(TessState.T_DORMANT)
        state = TessState.T_IN_POLYGON
        cacheCount = 0
        flushCacheOnNextVertex = false
        mesh = null
        polygonData = data
    }

    fun gluTessBeginContour() {
        requireState(TessState.T_IN_POLYGON)
        state = TessState.T_IN_CONTOUR
        lastEdge = null
        if (cacheCount > 0) {
            /**
             * Just set a flag so we don't get confused by empty contours
             * -- these can be generated accidentally with the obsolete
             * NextContour() interface.
             */
            flushCacheOnNextVertex = true
        }
    }

    fun gluTessEndContour() {
        requireState(TessState.T_IN_CONTOUR)
        state = TessState.T_IN_POLYGON
    }

    fun gluTessEndPolygon() {
        try {
            requireState(TessState.T_IN_POLYGON)
            state = TessState.T_DORMANT
            if (this.mesh == null) {
                if (!flagBoundary /*&& callMesh == NULL_CB*/) {
                    /**
                     * Try some special code to make the easy cases go quickly
                     * (eg. convex polygons).  This code does NOT handle multiple contours,
                     * intersections, edge flags, and of course it does not generate
                     * an explicit mesh either.
                     */
                    if (Render.glRenderCache(this)) {
                        polygonData = null
                        return
                    }
                }
                if (!flushCache()) throw RuntimeException() /* could've used a label*/
            }

            /**
             * Determine the polygon normal and project vertices onto the plane
             * of the polygon.
             */
            Normal.glProjectPolygon(this)

            /**
             * __gl_computeInterior( tess ) computes the planar arrangement specified
             * by the given contours, and further subdivides this arrangement
             * into regions.  Each region is marked "inside" if it belongs
             * to the polygon, according to the rule given by windingRule.
             * Each interior region is guaranteed be monotone.
             */
            if (!Sweep.glComputeInterior(this)) {
                throw RuntimeException() /* could've used a label */
            }
            val mesh = this.mesh!!
            if (!fatalError) {
                /**
                 * If the user wants only the boundary contours, we throw away all edges
                 * except those which separate the interior from the exterior.
                 * Otherwise we tessellate all the regions marked "inside".
                 */
                val rc = if (boundaryOnly) {
                    TessMono.glMeshSetWindingNumber(mesh, 1, true)
                } else {
                    TessMono.glMeshTessellateInterior(mesh)
                }
                if (!rc) throw RuntimeException() /* could've used a label */
                Mesh.glMeshCheckMesh(mesh)
                if (callBegin !== NULL_CB || callEnd !== NULL_CB || callVertex !== NULL_CB || callEdgeFlag !== NULL_CB || callBeginData !== NULL_CB || callEndData !== NULL_CB || callVertexData !== NULL_CB || callEdgeFlagData !== NULL_CB) {
                    if (boundaryOnly) {
                        Render.glRenderBoundary(this, mesh) /* output boundary contours */
                    } else {
                        Render.glRenderMesh(this, mesh) /* output strips and fans */
                    }
                }
                //                if (callMesh != NULL_CB) {
//                    /**
//                     * Throw away the exterior faces, so that all faces are interior.
//                     * This way the user doesn't have to check the "inside" flag,
//                     * and we don't need to even reveal its existence.  It also leaves
//                     * the freedom for an implementation to not generate the exterior
//                     * faces in the first place.
//                     */
//                    TessMono.__gl_meshDiscardExterior(mesh);
//                    callMesh.mesh(mesh);		/* user wants the mesh itself */
//                    mesh = null;
//                    polygonData = null;
//                    return;
//                }
            }
            Mesh.glMeshDeleteMesh(mesh)
            polygonData = null
        } catch (e: Exception) {
            e.printStackTrace()
            callErrorOrErrorData(GLU.GLU_OUT_OF_MEMORY)
        }
    }

    fun callBeginOrBeginData(a: Int) {
        if (callBeginData !== NULL_CB) callBeginData.beginData(a, polygonData!!) else callBegin.begin(a)
    }

    fun callVertexOrVertexData(a: Any) {
        if (callVertexData !== NULL_CB) callVertexData.vertexData(a, polygonData!!) else callVertex.vertex(a)
    }

    fun callEdgeFlagOrEdgeFlagData(a: Boolean) {
        if (callEdgeFlagData !== NULL_CB) callEdgeFlagData.edgeFlagData(a, polygonData!!) else callEdgeFlag.edgeFlag(a)
    }

    fun callEndOrEndData() {
        if (callEndData !== NULL_CB) callEndData.endData(polygonData!!) else callEnd.end()
    }

    fun callCombineOrCombineData(
        coords: DoubleArray,
        vertexData: Array<Any?>,
        weights: FloatArray,
        outData: Array<Any?>
    ) {
        if (callCombineData !== NULL_CB) callCombineData.combineData(coords, vertexData, weights, outData, polygonData!!)
        else callCombine.combine(coords, vertexData, weights, outData)
    }

    fun callErrorOrErrorData(a: Int) {
        if (callErrorData !== NULL_CB) callErrorData.errorData(a, polygonData!!) else callError.error(a)
    }

    companion object {
        const val TESS_MAX_CACHE = 100
        private const val GLU_TESS_DEFAULT_TOLERANCE = 0.0

        //private static final int GLU_TESS_MESH = 100112;	/* void (*)(GLUmesh *mesh)	    */
        private val NULL_CB = GLUtessellatorCallbackAdapter()

        fun gluNewTess(): GLUtessellator = GLUtessellatorImpl()
    }
}