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

/**
 * The **GLUtessellatorCallbackAdapter** provides a default implementation of
 * [GLUtessellatorCallback]
 * with empty callback methods.  This class can be extended to provide user
 * defined callback methods.
 *
 * @author Eric Veach, July 1994
 * @author Java Port: Pepijn Van Eechhoudt, July 2003
 * @author Java Port: Nathan Parker Burg, August 2003
 * @author Kotlin Port: Eugene Maksymenko, April 2022
 */
open class GLUtessellatorCallbackAdapter : GLUtessellatorCallback {
	override fun begin(type: Int) {}
	override fun edgeFlag(boundaryEdge: Boolean) {}
	override fun vertex(vertexData: Any) {}
	override fun end() {}
	override fun error(errnum: Int) {}
	override fun combine(coords: DoubleArray, data: Array<Any?>, weight: FloatArray, outData: Array<Any?>) {}

	override fun beginData(type: Int, polygonData: Any) {}
	override fun edgeFlagData(boundaryEdge: Boolean, polygonData: Any) {}

	override fun vertexData(vertexData: Any, polygonData: Any) {}
	override fun endData(polygonData: Any) {}
	override fun errorData(errnum: Int, polygonData: Any) {}
	override fun combineData(coords: DoubleArray, data: Array<Any?>, weight: FloatArray, outData: Array<Any?>, polygonData: Any) {}
}