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

internal class PriorityQHeap : PriorityQ() {
    var nodes = Array(INIT_SIZE + 1) { PQnode() }
    var handles = Array(INIT_SIZE + 1) { PQhandleElem() }
    var size = 0
    var max = INIT_SIZE
    var freeList = 0
    var initialized = false

    init {
        nodes[1].handle = 1
        handles[1].key = null
    }

    /* really glPqHeapDeletePriorityQ */
    override fun pqDeletePriorityQ() {
        handles = emptyArray()
        nodes = emptyArray()
    }

    fun floatDown(curr: Int) {
        var c = curr
        val n = nodes
        val h = handles
        val hCurr = n[c].handle
        while (true) {
            var child = c shl 1
            if (child < size && leq(h[n[child + 1].handle].key!!, h[n[child].handle].key!!)) {
                ++child
            }
            val hChild = n[child].handle
            if (child > size || leq(h[hCurr].key!!, h[hChild].key!!)) {
                n[c].handle = hCurr
                h[hCurr].node = c
                break
            }
            n[c].handle = hChild
            h[hChild].node = c
            c = child
        }
    }

    fun floatUp(curr: Int) {
        var c = curr
        val n = nodes
        val h = handles
        val hCurr = n[c].handle
        while (true) {
            val parent = c shr 1
            val hParent = n[parent].handle
            if (parent == 0 || leq(h[hParent].key!!, h[hCurr].key!!)) {
                n[c].handle = hCurr
                h[hCurr].node = c
                break
            }
            n[c].handle = hParent
            h[hParent].node = c
            c = parent
        }
    }

    /* really glPqHeapInit */
    override fun pqInit(): Boolean {
        /* This method of building a heap is O(n), rather than O(n lg n). */
        var i = size
        while (i >= 1) {
            floatDown(i)
            --i
        }
        initialized = true
        return true
    }

    /* really glPqHeapInsert */
    /* returns LONG_MAX iff out of memory */
    override fun pqInsert(keyNew: Any): Int {
        val free: Int
        val curr = ++size
        if (curr * 2 > max) {
            /* If the heap overflows, double its size. */
            max = max shl 1
            nodes = Array(max + 1) { if (it < nodes.size) nodes[it] else PQnode() }
            handles = Array(max + 1) { if (it < handles.size) handles[it] else PQhandleElem() }
        }
        if (freeList == 0) {
            free = curr
        } else {
            free = freeList
            freeList = handles[free].node
        }
        nodes[curr].handle = free
        handles[free].node = curr
        handles[free].key = keyNew
        if (initialized) {
            floatUp(curr)
        }
        return free
    }

    /* really glPqHeapExtractMin */
    override fun pqExtractMin(): Any? {
        val n = nodes
        val h = handles
        val hMin = n[1].handle
        val min = h[hMin].key
        if (size > 0) {
            n[1].handle = n[size].handle
            h[n[1].handle].node = 1
            h[hMin].key = null
            h[hMin].node = freeList
            freeList = hMin
            if (--size > 0) {
                floatDown(1)
            }
        }
        return min
    }

    /* really glPqHeapDelete */
    override fun pqDelete(hCurr: Int) {
        val n = nodes
        val h = handles
        val curr = h[hCurr].node
        n[curr].handle = n[size].handle
        h[n[curr].handle].node = curr
        if (curr <= --size) {
            if (curr <= 1 || leq(h[n[curr shr 1].handle].key!!, h[n[curr].handle].key!!)) {
                floatDown(curr)
            } else {
                floatUp(curr)
            }
        }
        h[hCurr].key = null
        h[hCurr].node = freeList
        freeList = hCurr
    }

    override fun pqMinimum(): Any? {
        return handles[nodes[1].handle].key
    }

    override fun pqIsEmpty(): Boolean {
        return size == 0
    }
}