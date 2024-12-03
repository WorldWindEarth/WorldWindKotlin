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

internal object Mesh {
    /************************ Utility Routines  */
    /**
     * MakeEdge creates a new pair of half-edges which form their own loop.
     * No vertex or face structures are allocated, but these must be assigned
     * before the current edge operation is completed.
     */
    fun makeEdge(eNext: GLUhalfEdge): GLUhalfEdge {
        var next = eNext
        val e = GLUhalfEdge(true)
        val eSym = GLUhalfEdge(false)

        /* Make sure eNext points to the first edge of the edge pair */
        if (!next.first) {
            next = next.sym!!
        }

        /**
         * Insert in circular doubly-linked list before eNext.
         * Note that the prev pointer is stored in Sym->next.
         */
        val ePrev = next.sym?.next!!
        eSym.next = ePrev
        ePrev.sym?.next = e
        e.next = next
        next.sym?.next = eSym
        e.sym = eSym
        e.oNext = e
        e.lNext = eSym
        e.org = null
        e.lFace = null
        e.winding = 0
        e.activeRegion = null
        eSym.sym = e
        eSym.oNext = eSym
        eSym.lNext = e
        eSym.org = null
        eSym.lFace = null
        eSym.winding = 0
        eSym.activeRegion = null
        return e
    }

    /**
     * Splice( a, b ) is best described by the Guibas/Stolfi paper or the
     * CS348a notes (see mesh.h).  Basically it modifies the mesh so that
     * a->Onext and b->Onext are exchanged.  This can have various effects
     * depending on whether a and b belong to different face or vertex rings.
     * For more explanation see __gl_meshSplice() below.
     */
    fun splice(a: GLUhalfEdge, b: GLUhalfEdge) {
        val aONext = a.oNext!!
        val bONext = b.oNext!!
        aONext.sym?.lNext = b
        bONext.sym?.lNext = a
        a.oNext = bONext
        b.oNext = aONext
    }

    /**
     * MakeVertex( newVertex, eOrig, vNext ) attaches a new vertex and makes it the
     * origin of all edges in the vertex loop to which eOrig belongs. "vNext" gives
     * a place to insert the new vertex in the global vertex list.  We insert
     * the new vertex *before* vNext so that algorithms which walk the vertex
     * list will not see the newly created vertices.
     */
    fun makeVertex(newVertex: GLUvertex, eOrig: GLUhalfEdge, vNext: GLUvertex) {
        /* insert in circular doubly-linked list before vNext */
        val vPrev = vNext.prev!!
        newVertex.prev = vPrev
        vPrev.next = newVertex
        newVertex.next = vNext
        vNext.prev = newVertex
        newVertex.anEdge = eOrig
        newVertex.data = null
        /* leave coords, s, t undefined */

        /* fix other edges on this vertex loop */
        var e = eOrig
        do {
            e.org = newVertex
            e = e.oNext!!
        } while (e !== eOrig)
    }

    /**
     * MakeFace( newFace, eOrig, fNext ) attaches a new face and makes it the left
     * face of all edges in the face loop to which eOrig belongs.  "fNext" gives
     * a place to insert the new face in the global face list.  We insert
     * the new face *before* fNext so that algorithms which walk the face
     * list will not see the newly created faces.
     */
    fun makeFace(newFace: GLUface, eOrig: GLUhalfEdge, fNext: GLUface) {
        /* insert in circular doubly-linked list before fNext */
        val fPrev = fNext.prev!!
        newFace.prev = fPrev
        fPrev.next = newFace
        newFace.next = fNext
        fNext.prev = newFace
        newFace.anEdge = eOrig
        newFace.data = null
        newFace.trail = null
        newFace.marked = false

        /**
         * The new face is marked "inside" if the old one was.  This is a
         * convenience for the common case where a face has been split in two.
         */
        newFace.inside = fNext.inside

        /* fix other edges on this face loop */
        var e = eOrig
        do {
            e.lFace = newFace
            e = e.lNext!!
        } while (e !== eOrig)
    }

    /**
     * KillEdge( eDel ) destroys an edge (the half-edges eDel and eDel->Sym),
     * and removes from the global edge list.
     */
    fun killEdge(eDel: GLUhalfEdge) {
        var del = eDel

        /* Half-edges are allocated in pairs, see EdgePair above */
        if (!del.first) {
            del = del.sym!!
        }

        /* delete from circular doubly-linked list */
        val eNext = del.next!!
        val ePrev = del.sym?.next!!
        eNext.sym?.next = ePrev
        ePrev.sym?.next = eNext
    }

    /**
     * KillVertex( vDel ) destroys a vertex and removes it from the global
     * vertex list.  It updates the vertex loop to point to a given new vertex.
     */
    fun killVertex(vDel: GLUvertex, newOrg: GLUvertex?) {
        val eStart = vDel.anEdge!!

        /* change the origin of all affected edges */
        var e = eStart
        do {
            e.org = newOrg
            e = e.oNext!!
        } while (e !== eStart)

        /* delete from circular doubly-linked list */
        val vPrev = vDel.prev!!
        val vNext = vDel.next!!
        vNext.prev = vPrev
        vPrev.next = vNext
    }

    /**
     * KillFace( fDel ) destroys a face and removes it from the global face
     * list.  It updates the face loop to point to a given new face.
     */
    fun killFace(fDel: GLUface, newLface: GLUface?) {
        val eStart = fDel.anEdge!!

        /* change the left face of all affected edges */
        var e = eStart
        do {
            e.lFace = newLface
            e = e.lNext!!
        } while (e !== eStart)

        /* delete from circular doubly-linked list */
        val fPrev = fDel.prev!!
        val fNext = fDel.next!!
        fNext.prev = fPrev
        fPrev.next = fNext
    }

    /****************** Basic Edge Operations  */
    /**
     * __gl_meshMakeEdge creates one edge, two vertices, and a loop (face).
     * The loop consists of the two new half-edges.
     */
    fun glMeshMakeEdge(mesh: GLUmesh): GLUhalfEdge {
        val newVertex1 = GLUvertex()
        val newVertex2 = GLUvertex()
        val newFace = GLUface()
        val e = makeEdge(mesh.eHead)
        makeVertex(newVertex1, e, mesh.vHead)
        makeVertex(newVertex2, e.sym!!, mesh.vHead)
        makeFace(newFace, e, mesh.fHead)
        return e
    }

    /**
     * __gl_meshSplice( eOrg, eDst ) is the basic operation for changing the
     * mesh connectivity and topology.  It changes the mesh so that
     *	eOrg->Onext <- OLD( eDst->Onext )
     *	eDst->Onext <- OLD( eOrg->Onext )
     * where OLD(...) means the value before the meshSplice operation.
     *
     * This can have two effects on the vertex structure:
     *  - if eOrg->Org != eDst->Org, the two vertices are merged together
     *  - if eOrg->Org == eDst->Org, the origin is split into two vertices
     * In both cases, eDst->Org is changed and eOrg->Org is untouched.
     *
     * Similarly (and independently) for the face structure,
     *  - if eOrg->Lface == eDst->Lface, one loop is split into two
     *  - if eOrg->Lface != eDst->Lface, two distinct loops are joined into one
     * In both cases, eDst->Lface is changed and eOrg->Lface is unaffected.
     *
     * Some special cases:
     * If eDst == eOrg, the operation has no effect.
     * If eDst == eOrg->Lnext, the new face will have a single edge.
     * If eDst == eOrg->Lprev, the old face will have a single edge.
     * If eDst == eOrg->Onext, the new vertex will have a single edge.
     * If eDst == eOrg->Oprev, the old vertex will have a single edge.
     */
    fun glMeshSplice(eOrg: GLUhalfEdge, eDst: GLUhalfEdge): Boolean {
        var joiningLoops = false
        var joiningVertices = false
        if (eOrg === eDst) return true
        if (eDst.org !== eOrg.org) {
            /* We are merging two disjoint vertices -- destroy eDst->Org */
            joiningVertices = true
            killVertex(eDst.org!!, eOrg.org)
        }
        if (eDst.lFace !== eOrg.lFace) {
            /* We are connecting two disjoint loops -- destroy eDst.Lface */
            joiningLoops = true
            killFace(eDst.lFace!!, eOrg.lFace)
        }

        /* Change the edge structure */
        splice(eDst, eOrg)
        if (!joiningVertices) {
            val newVertex = GLUvertex()

            /**
             * We split one vertex into two -- the new vertex is eDst.Org.
             * Make sure the old vertex points to a valid half-edge.
             */
            makeVertex(newVertex, eDst, eOrg.org!!)
            eOrg.org?.anEdge = eOrg
        }
        if (!joiningLoops) {
            val newFace = GLUface()

            /**
             * We split one loop into two -- the new loop is eDst.Lface.
             * Make sure the old face points to a valid half-edge.
             */
            makeFace(newFace, eDst, eOrg.lFace!!)
            eOrg.lFace?.anEdge = eOrg
        }
        return true
    }

    /**
     * __gl_meshDelete( eDel ) removes the edge eDel.  There are several cases:
     * if (eDel.Lface != eDel.Rface), we join two loops into one; the loop
     * eDel.Lface is deleted.  Otherwise, we are splitting one loop into two;
     * the newly created loop will contain eDel.Dst.  If the deletion of eDel
     * would create isolated vertices, those are deleted as well.
     *
     * This function could be implemented as two calls to __gl_meshSplice
     * plus a few calls to memFree, but this would allocate and delete
     * unnecessary vertices and faces.
     */
    fun glMeshDelete(eDel: GLUhalfEdge): Boolean {
        val eDelSym = eDel.sym!!
        var joiningLoops = false

        /** First step: disconnect the origin vertex eDel.Org.  We make all
         * changes to get a consistent mesh in this "intermediate" state.
         */
        if (eDel.lFace !== eDel.sym?.lFace) {
            /* We are joining two loops into one -- remove the left face */
            joiningLoops = true
            killFace(eDel.lFace!!, eDel.sym?.lFace)
        }
        if (eDel.oNext === eDel) {
            killVertex(eDel.org!!, null)
        } else {
            /* Make sure that eDel.Org and eDel.Sym.Lface point to valid half-edges */
            eDel.sym?.lFace?.anEdge = eDel.sym?.lNext
            eDel.org?.anEdge = eDel.oNext
            splice(eDel, eDel.sym?.lNext!!)
            if (!joiningLoops) {
                val newFace = GLUface()
                /* We are splitting one loop into two -- create a new loop for eDel. */
                makeFace(newFace, eDel, eDel.lFace!!)
            }
        }

        /**
         * Claim: the mesh is now in a consistent state, except that eDel.Org
         * may have been deleted.  Now we disconnect eDel.Dst.
         */
        if (eDelSym.oNext === eDelSym) {
            killVertex(eDelSym.org!!, null)
            killFace(eDelSym.lFace!!, null)
        } else {
            /* Make sure that eDel.Dst and eDel.Lface point to valid half-edges */
            eDel.lFace?.anEdge = eDelSym.sym?.lNext
            eDelSym.org?.anEdge = eDelSym.oNext
            splice(eDelSym, eDelSym.sym?.lNext!!)
        }

        /* Any isolated vertices or faces have already been freed. */
        killEdge(eDel)
        return true
    }

    /******************** Other Edge Operations  */
    /**
     * All these routines can be implemented with the basic edge
     * operations above.  They are provided for convenience and efficiency.
     */
    /**
     * __gl_meshAddEdgeVertex( eOrg ) creates a new edge eNew such that
     * eNew == eOrg.Lnext, and eNew.Dst is a newly created vertex.
     * eOrg and eNew will have the same left face.
     */
    fun glMeshAddEdgeVertex(eOrg: GLUhalfEdge): GLUhalfEdge {
        val eNew = makeEdge(eOrg)
        val eNewSym = eNew.sym!!

        /* Connect the new edge appropriately */
        splice(eNew, eOrg.lNext!!)

        /* Set the vertex and face information */
        eNew.org = eOrg.sym?.org
        run {
            val newVertex = GLUvertex()
            makeVertex(newVertex, eNewSym, eNew.org!!)
        }
        eNewSym.lFace = eOrg.lFace
        eNew.lFace = eNewSym.lFace
        return eNew
    }

    /**
     *  __gl_meshSplitEdge( eOrg ) splits eOrg into two edges eOrg and eNew,
     * such that eNew == eOrg.Lnext.  The new vertex is eOrg.Sym.Org == eNew.Org.
     * eOrg and eNew will have the same left face.
     */
    fun glMeshSplitEdge(eOrg: GLUhalfEdge): GLUhalfEdge {
        val tempHalfEdge = glMeshAddEdgeVertex(eOrg)
        val eNew = tempHalfEdge.sym!!

        /* Disconnect eOrg from eOrg.Sym.Org and connect it to eNew.Org */
        splice(eOrg.sym!!, eOrg.sym?.sym?.lNext!!)
        splice(eOrg.sym!!, eNew)

        /* Set the vertex and face information */
        eOrg.sym?.org = eNew.org
        eNew.sym?.org?.anEdge = eNew.sym /* may have pointed to eOrg.Sym */
        eNew.sym?.lFace = eOrg.sym?.lFace
        eNew.winding = eOrg.winding /* copy old winding information */
        eNew.sym?.winding = eOrg.sym!!.winding
        return eNew
    }

    /**
     * __gl_meshConnect( eOrg, eDst ) creates a new edge from eOrg.Sym.Org
     * to eDst.Org, and returns the corresponding half-edge eNew.
     * If eOrg.Lface == eDst.Lface, this splits one loop into two,
     * and the newly created loop is eNew.Lface.  Otherwise, two disjoint
     * loops are merged into one, and the loop eDst.Lface is destroyed.
     *
     * If (eOrg == eDst), the new face will have only two edges.
     * If (eOrg.Lnext == eDst), the old face is reduced to a single edge.
     * If (eOrg.Lnext.Lnext == eDst), the old face is reduced to two edges.
     */
    fun glMeshConnect(eOrg: GLUhalfEdge, eDst: GLUhalfEdge): GLUhalfEdge {
        var joiningLoops = false
        val eNew = makeEdge(eOrg)
        val eNewSym = eNew.sym!!
        if (eDst.lFace !== eOrg.lFace) {
            /* We are connecting two disjoint loops -- destroy eDst.Lface */
            joiningLoops = true
            killFace(eDst.lFace!!, eOrg.lFace)
        }

        /* Connect the new edge appropriately */
        splice(eNew, eOrg.lNext!!)
        splice(eNewSym, eDst)

        /* Set the vertex and face information */
        eNew.org = eOrg.sym?.org
        eNewSym.org = eDst.org
        eNewSym.lFace = eOrg.lFace
        eNew.lFace = eNewSym.lFace

        /* Make sure the old face points to a valid half-edge */
        eOrg.lFace?.anEdge = eNewSym
        if (!joiningLoops) {
            val newFace = GLUface()
            /* We split one loop into two -- the new loop is eNew.Lface */
            makeFace(newFace, eNew, eOrg.lFace!!)
        }
        return eNew
    }

    /******************** Other Operations  */
    /**
     * __gl_meshZapFace( fZap ) destroys a face and removes it from the
     * global face list.  All edges of fZap will have a null pointer as their
     * left face.  Any edges which also have a null pointer as their right face
     * are deleted entirely (along with any isolated vertices this produces).
     * An entire mesh can be deleted by zapping its faces, one at a time,
     * in any order.  Zapped faces cannot be used in further mesh operations!
     */
    fun glMeshZapFace(fZap: GLUface) {
        val eStart = fZap.anEdge!!

        /* walk around face, deleting edges whose right face is also null */
        var eNext = eStart.lNext!!
        do {
            val e = eNext
            eNext = e.lNext!!
            e.lFace = null
            if (e.sym?.lFace == null) {
                /* delete the edge -- see __gl_MeshDelete above */
                if (e.oNext === e) {
                    killVertex(e.org!!, null)
                } else {
                    /* Make sure that e.Org points to a valid half-edge */
                    e.org?.anEdge = e.oNext
                    splice(e, e.sym?.lNext!!)
                }
                val eSym = e.sym!!
                if (eSym.oNext === eSym) {
                    killVertex(eSym.org!!, null)
                } else {
                    /* Make sure that eSym.Org points to a valid half-edge */
                    eSym.org?.anEdge = eSym.oNext
                    splice(eSym, eSym.sym?.lNext!!)
                }
                killEdge(e)
            }
        } while (e !== eStart)

        /* delete from circular doubly-linked list */
        val fPrev = fZap.prev!!
        val fNext = fZap.next!!
        fNext.prev = fPrev
        fPrev.next = fNext
    }

    /**
     * __gl_meshNewMesh() creates a new mesh with no edges, no vertices,
     * and no loops (what we usually call a "face").
     */
    fun glMeshNewMesh(): GLUmesh {
        val mesh = GLUmesh()
        val v = mesh.vHead
        val f = mesh.fHead
        val e = mesh.eHead
        val eSym = mesh.eHeadSym
        v.prev = v
        v.next = v.prev
        v.anEdge = null
        v.data = null
        f.prev = f
        f.next = f.prev
        f.anEdge = null
        f.data = null
        f.trail = null
        f.marked = false
        f.inside = false
        e.next = e
        e.sym = eSym
        e.oNext = null
        e.lNext = null
        e.org = null
        e.lFace = null
        e.winding = 0
        e.activeRegion = null
        eSym.next = eSym
        eSym.sym = e
        eSym.oNext = null
        eSym.lNext = null
        eSym.org = null
        eSym.lFace = null
        eSym.winding = 0
        eSym.activeRegion = null
        return mesh
    }

    /**
     * glMeshUnion( mesh1, mesh2 ) forms the union of all structures in
     * both meshes, and returns the new mesh (the old meshes are destroyed).
     */
    fun glMeshUnion(mesh1: GLUmesh, mesh2: GLUmesh): GLUmesh {
        val f1 = mesh1.fHead
        val v1 = mesh1.vHead
        val e1 = mesh1.eHead
        val f2 = mesh2.fHead
        val v2 = mesh2.vHead
        val e2 = mesh2.eHead

        /* Add the faces, vertices, and edges of mesh2 to those of mesh1 */
        if (f2.next !== f2) {
            f1.prev?.next = f2.next
            f2.next?.prev = f1.prev
            f2.prev?.next = f1
            f1.prev = f2.prev
        }
        if (v2.next !== v2) {
            v1.prev?.next = v2.next
            v2.next?.prev = v1.prev
            v2.prev?.next = v1
            v1.prev = v2.prev
        }
        if (e2.next !== e2) {
            e1.sym?.next?.sym?.next = e2.next
            e2.next?.sym?.next = e1.sym?.next
            e2.sym?.next?.sym?.next = e1
            e1.sym?.next = e2.sym?.next
        }
        return mesh1
    }

    /**
     * glMeshDeleteMesh( mesh ) will free all storage for any valid mesh.
     */
    fun glMeshDeleteMeshZap(mesh: GLUmesh) {
        val fHead = mesh.fHead
        while (fHead.next !== fHead) {
            glMeshZapFace(fHead.next!!)
        }
    }

    /**
     * __gl_meshDeleteMesh( mesh ) will free all storage for any valid mesh.
     */
    fun glMeshDeleteMesh(mesh: GLUmesh) {
        var f = mesh.fHead.next!!
        while (f !== mesh.fHead) {
            val fNext = f.next!!
            f = fNext
        }
        var v = mesh.vHead.next!!
        while (v !== mesh.vHead) {
            val vNext = v.next!!
            v = vNext
        }
        var e = mesh.eHead.next!!
        while (e !== mesh.eHead) {
            /* One call frees both e and e.Sym (see EdgePair above) */
            val eNext = e.next!!
            e = eNext
        }
    }

    /**
     * __gl_meshCheckMesh( mesh ) checks a mesh for self-consistency.
     */
    fun glMeshCheckMesh(mesh: GLUmesh) {
        val fHead = mesh.fHead
        val vHead = mesh.vHead
        val eHead = mesh.eHead
        var fPrev = fHead
        while (true) {
            val f = fPrev.next!!
            if (f === fHead) break
            var e = f.anEdge!!
            do e = e.lNext!! while (e !== f.anEdge)
            fPrev = f
        }
        var vPrev = vHead
        while (true) {
            val v = vPrev.next!!
            if (v === vHead) break
            var e = v.anEdge!!
            do {
                e = e.oNext!!
            } while (e !== v.anEdge)
            vPrev = v
        }
        var ePrev = eHead
        while (true) {
            val e = ePrev.next!!
            if (e === eHead) break
            ePrev = e
        }
    }
}