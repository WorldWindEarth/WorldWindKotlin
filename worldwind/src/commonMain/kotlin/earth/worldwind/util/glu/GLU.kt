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
package earth.worldwind.util.glu

import earth.worldwind.util.glu.error.Error
import earth.worldwind.util.glu.tessellator.GLUtessellatorImpl

/**
 * Provides access to the OpenGL Utility Library (GLU). This library
 * provides standard methods for setting up view volumes, building
 * mipmaps and performing other common operations.  The GLU NURBS
 * routines are not currently exposed.
 * <BR></BR>
 * Notes from the Reference Implementation for this class:
 * Thanks to the contributions of many individuals, this class is a
 * pure Java port of SGI's original C sources. All of the projection,
 * mipmap, scaling, and tessellation routines that are exposed are
 * compatible with the GLU 1.3 specification. The GLU NURBS routines
 * are not currently exposed.
 */
object GLU {
    fun gluErrorString(errorCode: Int) = Error.gluErrorString(errorCode)
    /**
     * **gluNewTess** creates and returns a new tessellation object.  This
     * object must be referred to when calling tesselation methods.  A return
     * value of null means that there was not enough memeory to allocate the
     * object.
     *
     * @return A new tessellation object.
     *
     * @see .gluTessBeginPolygon gluTessBeginPolygon
     *
     * @see .gluDeleteTess       gluDeleteTess
     *
     * @see .gluTessCallback     gluTessCallback
     */
    fun gluNewTess() = GLUtessellatorImpl.gluNewTess()

    /**
     * **gluDeleteTess** destroys the indicated tessellation object (which was
     * created with [gluNewTess][.gluNewTess]).
     *
     * @param tessellator
     * Specifies the tessellation object to destroy.
     *
     * @see .gluNewTess      gluNewTess
     *
     * @see .gluTessCallback gluTessCallback
     */
    fun gluDeleteTess(tessellator: GLUtessellator) = tessellator.gluDeleteTess()

    /**
     * **gluTessProperty** is used to control properites stored in a
     * tessellation object.  These properties affect the way that the polygons are
     * interpreted and rendered.  The legal value for *which* are as
     * follows:
     * <UL>
     * <LI>**GLU_TESS_WINDING_RULE**
     * <BR></BR>
     * Determines which parts of the polygon are on the "interior".
     * *value* may be set to one of
     * <BR></BR>**GLU_TESS_WINDING_ODD**,
     * <BR></BR>**GLU_TESS_WINDING_NONZERO**,
     * <BR></BR>**GLU_TESS_WINDING_POSITIVE**, or
     * <BR></BR>**GLU_TESS_WINDING_NEGATIVE**, or
     * <BR></BR>**GLU_TESS_WINDING_ABS_GEQ_TWO**.
     * <BR></BR>
     * To understand how the winding rule works, consider that the input
     * contours partition the plane into regions.  The winding rule determines
     * which of these regions are inside the polygon.
     * <BR></BR>
     * For a single contour C, the winding number of a point x is simply the
     * signed number of revolutions we make around x as we travel once around C
     * (where CCW is positive).  When there are several contours, the individual
     * winding numbers are summed.  This procedure associates a signed integer
     * value with each point x in the plane.  Note that the winding number is
     * the same for all points in a single region.
     * <BR></BR>
     * The winding rule classifies a region as "inside" if its winding number
     * belongs to the chosen category (odd, nonzero, positive, negative, or
     * absolute value of at least two).  The previous GLU tessellator (prior to
     * GLU 1.2) used the "odd" rule.  The "nonzero" rule is another common way
     * to define the interior.  The other three rules are useful for polygon CSG
     * operations.
    </LI> *
     * <LI>**GLU_TESS_BOUNDARY_ONLY**
     * <BR></BR>
     * Is a boolean value ("value" should be set to GL_TRUE or GL_FALSE). When
     * set to GL_TRUE, a set of closed contours separating the polygon interior
     * and exterior are returned instead of a tessellation.  Exterior contours
     * are oriented CCW with respect to the normal; interior contours are
     * oriented CW. The **GLU_TESS_BEGIN** and **GLU_TESS_BEGIN_DATA**
     * callbacks use the type GL_LINE_LOOP for each contour.
    </LI> *
     * <LI>**GLU_TESS_TOLERANCE**
     * <BR></BR>
     * Specifies a tolerance for merging features to reduce the size of the
     * output. For example, two vertices that are very close to each other
     * might be replaced by a single vertex.  The tolerance is multiplied by the
     * largest coordinate magnitude of any input vertex; this specifies the
     * maximum distance that any feature can move as the result of a single
     * merge operation.  If a single feature takes part in several merge
     * operations, the toal distance moved could be larger.
     * <BR></BR>
     * Feature merging is completely optional; the tolerance is only a hint.
     * The implementation is free to merge in some cases and not in others, or
     * to never merge features at all.  The initial tolerance is 0.
     * <BR></BR>
     * The current implementation merges vertices only if they are exactly
     * coincident, regardless of the current tolerance.  A vertex is spliced
     * into an edge only if the implementation is unable to distinguish which
     * side of the edge the vertex lies on.  Two edges are merged only when both
     * endpoints are identical.
    </LI> *
    </UL> *
     *
     * @param tessellator
     * Specifies the tessellation object created with
     * [gluNewTess][.gluNewTess]
     * @param which
     * Specifies the property to be set.  Valid values are
     * **GLU_TESS_WINDING_RULE**, **GLU_TESS_BOUNDARDY_ONLY**,
     * **GLU_TESS_TOLERANCE**.
     * @param value
     * Specifices the value of the indicated property.
     *
     * @see .gluGetTessProperty gluGetTessProperty
     *
     * @see .gluNewTess         gluNewTess
     */
    fun gluTessProperty(tessellator: GLUtessellator, which: Int, value: Double) = tessellator.gluTessProperty(which, value)

    /**
     * **gluGetTessProperty** retrieves properties stored in a tessellation
     * object.  These properties affect the way that tessellation objects are
     * interpreted and rendered.  See the
     * [gluTessProperty][.gluTessProperty] reference
     * page for information about the properties and what they do.
     *
     * @param tessellator
     * Specifies the tessellation object (created with
     * [gluNewTess][.gluNewTess]).
     * @param which
     * Specifies the property whose value is to be fetched. Valid values
     * are **GLU_TESS_WINDING_RULE**, **GLU_TESS_BOUNDARY_ONLY**,
     * and **GLU_TESS_TOLERANCES**.
     * @param value
     * Specifices an array into which the value of the named property is
     * written.
     *
     * @see .gluNewTess      gluNewTess
     *
     * @see .gluTessProperty gluTessProperty
     */
    fun gluGetTessProperty(
        tessellator: GLUtessellator, which: Int, value: DoubleArray, value_offset: Int
    ) = tessellator.gluGetTessProperty(which, value, value_offset)

    /**
     * **gluTessNormal** describes a normal for a polygon that the program is
     * defining. All input data will be projected onto a plane perpendicular to
     * the one of the three coordinate axes before tessellation and all output
     * triangles will be oriented CCW with repsect to the normal (CW orientation
     * can be obtained by reversing the sign of the supplied normal).  For
     * example, if you know that all polygons lie in the x-y plane, call
     * **gluTessNormal**(tess, 0.0, 0.0, 0.0) before rendering any polygons.
     * <BR></BR>
     * If the supplied normal is (0.0, 0.0, 0.0)(the initial value), the normal
     * is determined as follows.  The direction of the normal, up to its sign, is
     * found by fitting a plane to the vertices, without regard to how the
     * vertices are connected.  It is expected that the input data lies
     * approximately in the plane; otherwise, projection perpendicular to one of
     * the three coordinate axes may substantially change the geometry.  The sign
     * of the normal is chosen so that the sum of the signed areas of all input
     * contours is nonnegative (where a CCW contour has positive area).
     * <BR></BR>
     * The supplied normal persists until it is changed by another call to
     * **gluTessNormal**.
     *
     * @param tessellator
     * Specifies the tessellation object (created by
     * [gluNewTess][.gluNewTess]).
     * @param x
     * Specifies the first component of the normal.
     * @param y
     * Specifies the second component of the normal.
     * @param z
     * Specifies the third component of the normal.
     *
     * @see .gluTessBeginPolygon gluTessBeginPolygon
     *
     * @see .gluTessEndPolygon   gluTessEndPolygon
     */
    fun gluTessNormal(tessellator: GLUtessellator, x: Double, y: Double, z: Double) = tessellator.gluTessNormal(x, y, z)

    /**
     * **gluTessCallback** is used to indicate a callback to be used by a
     * tessellation object. If the specified callback is already defined, then it
     * is replaced. If *aCallback* is null, then the existing callback
     * becomes undefined.
     * <BR></BR>
     * These callbacks are used by the tessellation object to describe how a
     * polygon specified by the user is broken into triangles. Note that there are
     * two versions of each callback: one with user-specified polygon data and one
     * without. If both versions of a particular callback are specified, then the
     * callback with user-specified polygon data will be used. Note that the
     * polygonData parameter used by some of the methods is a copy of the
     * reference that was specified when
     * [gluTessBeginPolygon][.gluTessBeginPolygon]
     * was called. The legal callbacks are as follows:
     * <UL>
     * <LI>**GLU_TESS_BEGIN**
     * <BR></BR>
     * The begin callback is invoked like
     * glBegin to indicate the start of a (triangle) primitive. The method
     * takes a single argument of type int. If the
     * **GLU_TESS_BOUNDARY_ONLY** property is set to **GL_FALSE**, then
     * the argument is set to either **GL_TRIANGLE_FAN**,
     * **GL_TRIANGLE_STRIP**, or **GL_TRIANGLES**. If the
     * **GLU_TESS_BOUNDARY_ONLY** property is set to **GL_TRUE**, then the
     * argument will be set to **GL_LINE_LOOP**. The method prototype for
     * this callback is:
     * <PRE>void begin(int type);</PRE>
    </LI> *
     * <LI>**GLU_TESS_BEGIN_DATA**
     * <BR></BR>
     * The same as the **GLU_TESS_BEGIN** callback except
     * that it takes an additional reference argument. This reference is
     * identical to the opaque reference provided when
     * [gluTessBeginPolygon][.gluTessBeginPolygon]
     * was called. The method prototype for this callback is:
     * <PRE>void beginData(int type, Object polygonData);</PRE>
    </LI> *
     * <LI>**GLU_TESS_EDGE_FLAG**
     * <BR></BR>
     * The edge flag callback is similar to
     * glEdgeFlag. The method takes
     * a single boolean boundaryEdge that indicates which edges lie on the
     * polygon boundary. If the boundaryEdge is **GL_TRUE**, then each vertex
     * that follows begins an edge that lies on the polygon boundary, that is,
     * an edge that separates an interior region from an exterior one. If the
     * boundaryEdge is **GL_FALSE**, then each vertex that follows begins an
     * edge that lies in the polygon interior. The edge flag callback (if
     * defined) is invoked before the first vertex callback.
     * <BR></BR>
     * Since triangle fans and triangle strips do not support edge flags, the
     * begin callback is not called with **GL_TRIANGLE_FAN** or
     * **GL_TRIANGLE_STRIP** if a non-null edge flag callback is provided.
     * (If the callback is initialized to null, there is no impact on
     * performance). Instead, the fans and strips are converted to independent
     * triangles. The method prototype for this callback is:
     * <PRE>void edgeFlag(boolean boundaryEdge);</PRE>
    </LI> *
     * <LI>**GLU_TESS_EDGE_FLAG_DATA**
     * <BR></BR>
     * The same as the **GLU_TESS_EDGE_FLAG** callback except that it takes
     * an additional reference argument. This reference is identical to the
     * opaque reference provided when
     * [gluTessBeginPolygon][.gluTessBeginPolygon]
     * was called. The method prototype for this callback is:
     * <PRE>void edgeFlagData(boolean boundaryEdge, Object polygonData);</PRE>
    </LI> *
     * <LI>**GLU_TESS_VERTEX**
     * <BR></BR>
     * The vertex callback is invoked between the begin and end callbacks. It is
     * similar to glVertex3f, and it
     * defines the vertices of the triangles created by the tessellation
     * process. The method takes a reference as its only argument. This
     * reference is identical to the opaque reference provided by the user when
     * the vertex was described (see
     * [gluTessVertex][.gluTessVertex]). The method
     * prototype for this callback is:
     * <PRE>void vertex(Object vertexData);</PRE>
    </LI> *
     * <LI>**GLU_TESS_VERTEX_DATA**
     * <BR></BR>
     * The same as the **GLU_TESS_VERTEX** callback except that it takes an
     * additional reference argument. This reference is identical to the opaque
     * reference provided when
     * [gluTessBeginPolygon][.gluTessBeginPolygon]
     * was called. The method prototype for this callback is:
     * <PRE>void vertexData(Object vertexData, Object polygonData);</PRE>
    </LI> *
     * <LI>**GLU_TESS_END**
     * <BR></BR>
     * The end callback serves the same purpose as
     * glEnd. It indicates the end of a
     * primitive and it takes no arguments. The method prototype for this
     * callback is:
     * <PRE>void end();</PRE>
    </LI> *
     * <LI>**GLU_TESS_END_DATA**
     * <BR></BR>
     * The same as the **GLU_TESS_END** callback except that it takes an
     * additional reference argument. This reference is identical to the opaque
     * reference provided when
     * [gluTessBeginPolygon][.gluTessBeginPolygon]
     * was called. The method prototype for this callback is:
     * <PRE>void endData(Object polygonData);</PRE>
    </LI> *
     * <LI>**GLU_TESS_COMBINE**
     * <BR></BR>
     * The combine callback is called to create a new vertex when the
     * tessellation detects an intersection, or wishes to merge features. The
     * method takes four arguments: an array of three elements each of type
     * double, an array of four references, an array of four elements each of
     * type float, and a reference to a reference. The prototype is:
     * <PRE>void combine(double[] coords, Object[] data,
     * float[] weight, Object[] outData);</PRE>
     * The vertex is defined as a linear combination of up to four existing
     * vertices, stored in *data*. The coefficients of the linear
     * combination are given by *weight*; these weights always add up to 1.
     * All vertex pointers are valid even when some of the weights are 0.
     * *coords* gives the location of the new vertex.
     * <BR></BR>
     * The user must allocate another vertex, interpolate parameters using
     * *data* and *weight*, and return the new vertex pointer
     * in *outData*. This handle is supplied during rendering callbacks.
     * The user is responsible for freeing the memory some time after
     * [gluTessEndPolygon][.gluTessEndPolygon] is
     * called.
     * <BR></BR>
     * For example, if the polygon lies in an arbitrary plane in 3-space, and a
     * color is associated with each vertex, the **GLU_TESS_COMBINE**
     * callback might look like this:
     * <PRE>
     * void myCombine(double[] coords, Object[] data,
     * float[] weight, Object[] outData)
     * {
     * MyVertex newVertex = new MyVertex();
     *
     * newVertex.x = coords[0];
     * newVertex.y = coords[1];
     * newVertex.z = coords[2];
     * newVertex.r = weight[0]*data[0].r +
     * weight[1]*data[1].r +
     * weight[2]*data[2].r +
     * weight[3]*data[3].r;
     * newVertex.g = weight[0]*data[0].g +
     * weight[1]*data[1].g +
     * weight[2]*data[2].g +
     * weight[3]*data[3].g;
     * newVertex.b = weight[0]*data[0].b +
     * weight[1]*data[1].b +
     * weight[2]*data[2].b +
     * weight[3]*data[3].b;
     * newVertex.a = weight[0]*data[0].a +
     * weight[1]*data[1].a +
     * weight[2]*data[2].a +
     * weight[3]*data[3].a;
     * outData = newVertex;
     * }</PRE>
     * If the tessellation detects an intersection, then the
     * **GLU_TESS_COMBINE** or **GLU_TESS_COMBINE_DATA** callback (see
     * below) must be defined, and it must write a non-null reference into
     * *outData*. Otherwise the **GLU_TESS_NEED_COMBINE_CALLBACK** error
     * occurs, and no output is generated.
    </LI> *
     * <LI>**GLU_TESS_COMBINE_DATA**
     * <BR></BR>
     * The same as the **GLU_TESS_COMBINE** callback except that it takes an
     * additional reference argument. This reference is identical to the opaque
     * reference provided when
     * [gluTessBeginPolygon][.gluTessBeginPolygon]
     * was called. The method prototype for this callback is:
     * <PRE>
     * void combineData(double[] coords, Object[] data,
     * float[] weight, Object[] outData,
     * Object polygonData);</PRE>
     *
    </LI> *
     * <LI>**GLU_TESS_ERROR**
     * <BR></BR>
     * The error callback is called when an error is encountered. The one
     * argument is of type int; it indicates the specific error that occurred
     * and will be set to one of **GLU_TESS_MISSING_BEGIN_POLYGON**,
     * **GLU_TESS_MISSING_END_POLYGON**,
     * **GLU_TESS_MISSING_BEGIN_CONTOUR**,
     * **GLU_TESS_MISSING_END_CONTOUR**, **GLU_TESS_COORD_TOO_LARGE**,
     * **GLU_TESS_NEED_COMBINE_CALLBACK** or **GLU_OUT_OF_MEMORY**.
     * Character strings describing these errors can be retrieved with the
     * [gluErrorString][.gluErrorString] call. The
     * method prototype for this callback is:
     * <PRE>
     * void error(int errnum);</PRE>
     * The GLU library will recover from the first four errors by inserting the
     * missing call(s). **GLU_TESS_COORD_TOO_LARGE** indicates that some
     * vertex coordinate exceeded the predefined constant
     * **GLU_TESS_MAX_COORD** in absolute value, and that the value has been
     * clamped. (Coordinate values must be small enough so that two can be
     * multiplied together without overflow.)
     * **GLU_TESS_NEED_COMBINE_CALLBACK** indicates that the tessellation
     * detected an intersection between two edges in the input data, and the
     * **GLU_TESS_COMBINE** or **GLU_TESS_COMBINE_DATA** callback was not
     * provided. No output is generated. **GLU_OUT_OF_MEMORY** indicates that
     * there is not enough memory so no output is generated.
    </LI> *
     * <LI>**GLU_TESS_ERROR_DATA**
     * <BR></BR>
     * The same as the GLU_TESS_ERROR callback except that it takes an
     * additional reference argument. This reference is identical to the opaque
     * reference provided when
     * [gluTessBeginPolygon][.gluTessBeginPolygon]
     * was called. The method prototype for this callback is:
     * <PRE>
     * void errorData(int errnum, Object polygonData);</PRE>
    </LI> *
    </UL> *
     *
     * @param tessellator
     * Specifies the tessellation object (created with
     * [gluNewTess][.gluNewTess]).
     * @param which
     * Specifies the callback being defined. The following values are
     * valid: **GLU_TESS_BEGIN**, **GLU_TESS_BEGIN_DATA**,
     * **GLU_TESS_EDGE_FLAG**, **GLU_TESS_EDGE_FLAG_DATA**,
     * **GLU_TESS_VERTEX**, **GLU_TESS_VERTEX_DATA**,
     * **GLU_TESS_END**, **GLU_TESS_END_DATA**,
     * **GLU_TESS_COMBINE**,  **GLU_TESS_COMBINE_DATA**,
     * **GLU_TESS_ERROR**, and **GLU_TESS_ERROR_DATA**.
     * @param aCallback
     * Specifies the callback object to be called.
     *
     * @see .gluNewTess          gluNewTess
     *
     * @see .gluErrorString      gluErrorString
     *
     * @see .gluTessVertex       gluTessVertex
     *
     * @see .gluTessBeginPolygon gluTessBeginPolygon
     *
     * @see .gluTessBeginContour gluTessBeginContour
     *
     * @see .gluTessProperty     gluTessProperty
     *
     * @see .gluTessNormal       gluTessNormal
     */
    fun gluTessCallback(
        tessellator: GLUtessellator, which: Int, aCallback: GLUtessellatorCallback?
    ) = tessellator.gluTessCallback(which, aCallback)

    /**
     * **gluTessVertex** describes a vertex on a polygon that the program
     * defines. Successive **gluTessVertex** calls describe a closed contour.
     * For example, to describe a quadrilateral **gluTessVertex** should be
     * called four times. **gluTessVertex** can only be called between
     * [gluTessBeginContour][.gluTessBeginContour] and
     * [gluTessEndContour][.gluTessBeginContour].
     * <BR></BR>
     * **data** normally references to a structure containing the vertex
     * location, as well as other per-vertex attributes such as color and normal.
     * This reference is passed back to the user through the
     * **GLU_TESS_VERTEX** or **GLU_TESS_VERTEX_DATA** callback after
     * tessellation (see the [ gluTessCallback][.gluTessCallback] reference page).
     *
     * @param tessellator
     * Specifies the tessellation object (created with
     * [gluNewTess][.gluNewTess]).
     * @param coords
     * Specifies the coordinates of the vertex.
     * @param data
     * Specifies an opaque reference passed back to the program with the
     * vertex callback (as specified by
     * [gluTessCallback][.gluTessCallback]).
     *
     * @see .gluTessBeginPolygon gluTessBeginPolygon
     *
     * @see .gluNewTess          gluNewTess
     *
     * @see .gluTessBeginContour gluTessBeginContour
     *
     * @see .gluTessCallback     gluTessCallback
     *
     * @see .gluTessProperty     gluTessProperty
     *
     * @see .gluTessNormal       gluTessNormal
     *
     * @see .gluTessEndPolygon   gluTessEndPolygon
     */
    fun gluTessVertex(
        tessellator: GLUtessellator, coords: DoubleArray, coords_offset: Int, data: Any
    ) = tessellator.gluTessVertex(coords, coords_offset, data)

    /**
     * **gluTessBeginPolygon** and
     * [gluTessEndPolygon][.gluTessEndPolygon] delimit
     * the definition of a convex, concave or self-intersecting polygon. Within
     * each **gluTessBeginPolygon**
     * [gluTessEndPolygon][.gluTessEndPolygon] pair,
     * there must be one or more calls to
     * [gluTessBeginContour][.gluTessBeginContour]/
     * [gluTessEndContour][.gluTessEndContour]. Within
     * each contour, there are zero or more calls to
     * [gluTessVertex][.gluTessVertex]. The vertices
     * specify a closed contour (the last vertex of each contour is automatically
     * linked to the first). See the [ gluTessVertex][.gluTessVertex], [ gluTessBeginContour][.gluTessBeginContour], and [ gluTessEndContour][.gluTessEndContour] reference pages for more details.
     * <BR></BR>
     * **data ** is a reference to a user-defined data structure. If the
     * appropriate callback(s) are specified (see
     * [gluTessCallback][.gluTessCallback]), then this
     * reference is returned to the callback method(s). Thus, it is a convenient
     * way to store per-polygon information.
     * <BR></BR>
     * Once [gluTessEndPolygon][.gluTessEndPolygon] is
     * called, the polygon is tessellated, and the resulting triangles are
     * described through callbacks. See
     * [gluTessCallback][.gluTessCallback] for
     * descriptions of the callback methods.
     *
     * @param tessellator
     * Specifies the tessellation
     object(
         created with
         * [gluNewTess][.gluNewTess]).
     * @param data
     * Specifies a reference to user polygon data .
     *
     * @see.gluNewTess gluNewTess
     *
     * @see.gluTessBeginContour gluTessBeginContour
     *
     * @see.gluTessVertex gluTessVertex
     *
     * @see.gluTessCallback gluTessCallback
     *
     * @see.gluTessProperty gluTessProperty
     *
     * @see.gluTessNormal gluTessNormal
     *
     * @see.gluTessEndPolygon gluTessEndPolygon
     */
    fun gluTessBeginPolygon(tessellator: GLUtessellator, data: Any) = tessellator.gluTessBeginPolygon(data)

    /**
     * **gluTessBeginContour** and
     * [gluTessEndContour][.gluTessEndContour] delimit
     * the definition of a polygon contour. Within each
     * **gluTessBeginContour**
     * [gluTessEndContour][.gluTessEndContour] pair,
     * there can be zero or more calls to
     * [gluTessVertex][.gluTessVertex]. The vertices
     * specify a closed contour (the last vertex of each contour is automatically
     * linked to the first). See the [ gluTessVertex][.gluTessVertex] reference page for more details. **gluTessBeginContour**
     * can only be called between
     * [gluTessBeginPolygon][.gluTessBeginPolygon] and
     * [gluTessEndPolygon][.gluTessEndPolygon].
     *
     * @param tessellator
     * Specifies the tessellation
     object(
         created with
         * [gluNewTess][.gluNewTess]).
     *
     * @see.gluNewTess gluNewTess
     *
     * @see.gluTessBeginPolygon gluTessBeginPolygon
     *
     * @see.gluTessVertex gluTessVertex
     *
     * @see.gluTessCallback gluTessCallback
     *
     * @see.gluTessProperty gluTessProperty
     *
     * @see.gluTessNormal gluTessNormal
     *
     * @see.gluTessEndPolygon gluTessEndPolygon
     */
    fun gluTessBeginContour(tessellator: GLUtessellator) = tessellator.gluTessBeginContour()

    /**
     * **gluTessEndContour** and
     * [gluTessBeginContour][gluTessBeginContour]
     * delimit the definition of a polygon contour. Within each
     * [gluTessBeginContour][gluTessBeginContour]/
     * **gluTessEndContour** pair, there can be zero or more calls to
     * [gluTessVertex][gluTessVertex]. The vertices
     * specify a closed contour (the last vertex of each contour is automatically
     * linked to the first). See the [ gluTessVertex][gluTessVertex] reference page for more details.
     * [gluTessBeginContour][gluTessBeginContour] can
     * only be called between [ gluTessBeginPolygon][gluTessBeginPolygon] and
     * [gluTessEndPolygon][gluTessEndPolygon].
     *
     * @param tessellator
     * Specifies the tessellation object (created with
     * [gluNewTess][gluNewTess]).
     *
     * @see .gluNewTess          gluNewTess
     *
     * @see .gluTessBeginPolygon gluTessBeginPolygon
     *
     * @see .gluTessVertex       gluTessVertex
     *
     * @see .gluTessCallback     gluTessCallback
     *
     * @see .gluTessProperty     gluTessProperty
     *
     * @see .gluTessNormal       gluTessNormal
     *
     * @see .gluTessEndPolygon   gluTessEndPolygon
     */
    fun gluTessEndContour(tessellator: GLUtessellator) = tessellator.gluTessEndContour()

    /**
     * **gluTessEndPolygon** and
     * [gluTessBeginPolygon][gluTessBeginPolygon]
     * delimit the definition of a convex, concave or self-intersecting polygon.
     * Within each [ gluTessBeginPolygon][gluTessBeginPolygon]/ **gluTessEndPolygon** pair, there must be one or
     * more calls to [ gluTessBeginContour][gluTessBeginContour]/[ gluTessEndContour][gluTessEndContour]. Within each contour, there are zero or more calls to
     * [gluTessVertex][gluTessVertex]. The vertices
     * specify a closed contour (the last vertex of each contour is automatically
     * linked to the first). See the [ gluTessVertex][gluTessVertex], [ gluTessBeginContour][gluTessBeginContour] and [ gluTessEndContour][gluTessEndContour] reference pages for more details.
     * <BR></BR>
     * Once **gluTessEndPolygon** is called, the polygon is tessellated, and
     * the resulting triangles are described through callbacks. See
     * [gluTessCallback][gluTessCallback] for
     * descriptions of the callback functions.
     *
     * @param tessellator
     * Specifies the tessellation object (created with
     * [gluNewTess][gluNewTess]).
     *
     * @see .gluNewTess          gluNewTess
     *
     * @see .gluTessBeginContour gluTessBeginContour
     *
     * @see .gluTessVertex       gluTessVertex
     *
     * @see .gluTessCallback     gluTessCallback
     *
     * @see .gluTessProperty     gluTessProperty
     *
     * @see .gluTessNormal       gluTessNormal
     *
     * @see .gluTessBeginPolygon gluTessBeginPolygon
     */
    fun gluTessEndPolygon(tessellator: GLUtessellator) = tessellator.gluTessEndPolygon()

    //----------------------------------------------------------------------
    // GLU constants
    // Boolean
    const val GLU_FALSE = 0
    const val GLU_TRUE = 1

    // String Name
    const val GLU_VERSION = 100800
    const val GLU_EXTENSIONS = 100801

    // Extensions
    const val versionString = "1.3"
    const val extensionString = "GLU_EXT_object_space_tess "

    // ErrorCode
    const val GLU_INVALID_ENUM = 100900
    const val GLU_INVALID_VALUE = 100901
    const val GLU_OUT_OF_MEMORY = 100902
    const val GLU_INVALID_OPERATION = 100904

    // TessCallback
    const val GLU_TESS_BEGIN = 100100
    const val GLU_TESS_VERTEX = 100101
    const val GLU_TESS_END = 100102
    const val GLU_TESS_ERROR = 100103
    const val GLU_TESS_EDGE_FLAG = 100104
    const val GLU_TESS_COMBINE = 100105
    const val GLU_TESS_BEGIN_DATA = 100106
    const val GLU_TESS_VERTEX_DATA = 100107
    const val GLU_TESS_END_DATA = 100108
    const val GLU_TESS_ERROR_DATA = 100109
    const val GLU_TESS_EDGE_FLAG_DATA = 100110
    const val GLU_TESS_COMBINE_DATA = 100111

    // TessContour
    const val GLU_CW = 100120
    const val GLU_CCW = 100121
    const val GLU_INTERIOR = 100122
    const val GLU_EXTERIOR = 100123
    const val GLU_UNKNOWN = 100124

    // TessProperty
    const val GLU_TESS_WINDING_RULE = 100140
    const val GLU_TESS_BOUNDARY_ONLY = 100141
    const val GLU_TESS_TOLERANCE = 100142

    // TessError
    const val GLU_TESS_ERROR1 = 100151
    const val GLU_TESS_ERROR2 = 100152
    const val GLU_TESS_ERROR3 = 100153
    const val GLU_TESS_ERROR4 = 100154
    const val GLU_TESS_ERROR5 = 100155
    const val GLU_TESS_ERROR6 = 100156
    const val GLU_TESS_ERROR7 = 100157
    const val GLU_TESS_ERROR8 = 100158
    const val GLU_TESS_MISSING_BEGIN_POLYGON = 100151
    const val GLU_TESS_MISSING_BEGIN_CONTOUR = 100152
    const val GLU_TESS_MISSING_END_POLYGON = 100153
    const val GLU_TESS_MISSING_END_CONTOUR = 100154
    const val GLU_TESS_COORD_TOO_LARGE = 100155
    const val GLU_TESS_NEED_COMBINE_CALLBACK = 100156

    // TessWinding
    const val GLU_TESS_WINDING_ODD = 100130
    const val GLU_TESS_WINDING_NONZERO = 100131
    const val GLU_TESS_WINDING_POSITIVE = 100132
    const val GLU_TESS_WINDING_NEGATIVE = 100133
    const val GLU_TESS_WINDING_ABS_GEQ_TWO = 100134
    const val GLU_TESS_MAX_COORD = 1.0e150
}
