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

import kotlin.math.abs

internal class PriorityQSort: PriorityQ() {
    val heap = PriorityQHeap()
    var keys: Array<Any?>? = arrayOfNulls(INIT_SIZE)

    // JAVA: 'order' contains indices into the keys array.
    // This simulates the indirect pointers used in the original C code
    // (from Frank Suykens, Luciad.com).
    var order: IntArray? = null
    var size = 0
    var max = INIT_SIZE
    var initialized = false

    /* really __gl_pqSortDeletePriorityQ */
    override fun pqDeletePriorityQ() {
        heap.pqDeletePriorityQ()
        order = null
        keys = null
    }

    private class Stack {
        var p = 0
        var r = 0
    }

    /* really __gl_pqSortInit */
    override fun pqInit(): Boolean {
        val stack = arrayOfNulls<Stack?>(50)
        for (k in stack.indices) {
            stack[k] = Stack()
        }
        var top = 0
        var seed = 2016473283

        /**
         * Create an array of indirect pointers to the keys, so that we
         * the handles we have returned are still valid.
         */
        order = IntArray(size + 1)
        /* the previous line is a patch to compensate for the fact that IBM */
        /* machines return a null on a malloc of zero bytes (unlike SGI),   */
        /* so we have to put in this defense to guard against a memory      */
        /* fault four lines down. from fossum@austin.ibm.com.               */
        var p = 0
        var r = size - 1
        var piv = 0
        var i = p
        while (i <= r) {
            // indirect pointers: keep an index into the keys array, not a direct pointer to its contents
            order!![i] = piv
            ++piv
            ++i
        }

        /**
         * Sort the indirect pointers in descending order,
         * using randomized Quicksort
         */
        stack[top]?.p = p
        stack[top]?.r = r
        ++top
        while (--top >= 0) {
            p = stack[top]!!.p
            r = stack[top]!!.r
            while (r > p + 10) {
                seed = abs(seed * 1539415821 + 1)
                i = p + seed % (r - p + 1)
                piv = order!![i]
                order!![i] = order!![p]
                order!![p] = piv
                i = p - 1
                var j = r + 1
                do {
                    do {
                        ++i
                    } while (gt(keys!![order!![i]]!!, keys!![piv]!!))
                    do {
                        --j
                    } while (lt(keys!![order!![j]]!!, keys!![piv]!!))
                    swap(order!!, i, j)
                } while (i < j)
                swap(order!!, i, j) /* Undo last swap */
                if (i - p < r - j) {
                    stack[top]?.p = j + 1
                    stack[top]?.r = r
                    ++top
                    r = i - 1
                } else {
                    stack[top]?.p = p
                    stack[top]?.r = i - 1
                    ++top
                    p = j + 1
                }
            }
            /* Insertion sort small lists */
            i = p + 1
            while (i <= r) {
                piv = order!![i]
                var j = i
                while (j > p && lt(keys!![order!![j - 1]]!!, keys!![piv]!!)) {
                    order!![j] = order!![j - 1]
                    --j
                }
                order!![j] = piv
                ++i
            }
        }
        max = size
        initialized = true
        heap.pqInit() /* always succeeds */

/*      #ifndef NDEBUG
        p = order;
        r = p + size - 1;
        for (i = p; i < r; ++i) {
            Assertion.doAssert(LEQ(     * * (i + 1), **i ));
        }
        #endif*/
        return true
    }

    /* really __gl_pqSortInsert */
    /* returns LONG_MAX iff out of memory */
    override fun pqInsert(keyNew: Any?): Int {
        if (initialized) {
            return heap.pqInsert(keyNew)
        }
        val curr = size
        if (++size >= max) {
            /* If the heap overflows, double its size. */
            max = max shl 1
            //            pq->keys = (PQHeapKey *)memRealloc( pq->keys,(size_t)(pq->max * sizeof( pq->keys[0] )));
            val pqKeys = arrayOfNulls<Any>(max)
            keys?.copyInto(pqKeys)
            keys = pqKeys
        }
        keys!![curr] = keyNew

        /* Negative handles index the sorted array. */
        return -(curr + 1)
    }

    /* really __gl_pqSortExtractMin */
    override fun pqExtractMin(): Any? {
        if (size == 0) {
            return heap.pqExtractMin()
        }
        val sortMin = keys!![order!![size - 1]]!!
        if (!heap.pqIsEmpty()) {
            val heapMin = heap.pqMinimum()!!
            if (leq(heapMin, sortMin)) {
                return heap.pqExtractMin()
            }
        }
        do {
            --size
        } while (size > 0 && keys!![order!![size - 1]] == null)
        return sortMin
    }

    /* really __gl_pqSortMinimum */
    override fun pqMinimum(): Any? {
        if (size == 0) {
            return heap.pqMinimum()
        }
        val sortMin = keys!![order!![size - 1]]!!
        if (!heap.pqIsEmpty()) {
            val heapMin = heap.pqMinimum()!!
            if (leq(heapMin, sortMin)) {
                return heapMin
            }
        }
        return sortMin
    }

    /* really __gl_pqSortIsEmpty */
    override fun pqIsEmpty(): Boolean {
        return size == 0 && heap.pqIsEmpty()
    }

    /* really __gl_pqSortDelete */
    override fun pqDelete(hCurr: Int) {
        var curr = hCurr
        if (curr >= 0) {
            heap.pqDelete(curr)
            return
        }
        curr = -(curr + 1)
        keys!![curr] = null
        while (size > 0 && keys!![order!![size - 1]] == null) {
            --size
        }
    }

    companion object {
        private fun lt(x: Any, y: Any): Boolean {
            return !leq(y, x)
        }

        private fun gt(x: Any, y: Any): Boolean {
            return !leq(x, y)
        }

        private fun swap(array: IntArray, a: Int, b: Int) {
            val tmp = array[a]
            array[a] = array[b]
            array[b] = tmp
        }
    }
}