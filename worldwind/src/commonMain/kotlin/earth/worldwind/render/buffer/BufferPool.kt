package earth.worldwind.render.buffer

import earth.worldwind.util.kgl.KglBuffer
import earth.worldwind.draw.DrawContext

const val DEFAULT_BUFFER_POOL_BLOCK_SIZE : Int = 64 * 1024 //64 KB
const val DEFAULT_BUFFER_POOL_MAX_BLOCK_COUNT : Int = 64
class BufferPool(private var target : Int,
        private var usage : Int,
        private var blockSize : Int = DEFAULT_BUFFER_POOL_BLOCK_SIZE,
        private var blockCount : Int = DEFAULT_BUFFER_POOL_MAX_BLOCK_COUNT)
{
    private val buffers = Array<KglBuffer>(blockCount){ KglBuffer.NONE }
    private var curIndex = 0
    private var curOffset = 0
    private fun allocNewBuffer(dc: DrawContext) : Boolean
    {
        val id = dc.gl.createBuffer();
        if(!id.isValid()) return false

        dc.gl.bindBuffer(target, id);

        //Only reserve memory
        dc.gl.bufferData(target, blockSize, null, usage);

        dc.gl.bindBuffer(target, KglBuffer.NONE);

        buffers[curIndex] = id;
        return id.isValid()
    }
    private fun subAllocAndPopulate(dc: DrawContext, sourceData: FloatArray) : Int
    {
        val bufferSize = sourceData.size * Float.SIZE_BYTES
        if(curOffset + bufferSize > blockSize)
            return -1

        //Update part of the buffer
        dc.gl.bufferSubData(target, curOffset, bufferSize, sourceData)

        val bindOffset = curOffset;
        curOffset += bufferSize
        return bindOffset
    }

    fun reset()
    {
        curIndex = 0
        curOffset = 0
    }

    private fun enoughSpace(sourceData: FloatArray) : Boolean
    {
        return curOffset + sourceData.size * Float.SIZE_BYTES <= blockSize;
    }

    fun free(dc: DrawContext)
    {
        reset()

        for (i in buffers.indices)
        {
            if(buffers[i].isValid())
            {
                dc.gl.deleteBuffer(buffers[i]);
                buffers[i] = KglBuffer.NONE
            }
        }
    }

    private fun appendBuffer(dc: DrawContext) : Boolean
    {
        if(!buffers[curIndex].isValid())
        {
            if(!allocNewBuffer(dc))
                return false
        }
        curOffset = 0
        return true
    }

    fun bindBufferAndGetBindOffset(dc: DrawContext, sourceData: FloatArray) : Int
    {
        if(blockSize < sourceData.size * Float.SIZE_BYTES)
            return -1;

        if(!buffers[curIndex].isValid())
        {
            val result = appendBuffer(dc);
            if(!result) return -1
        }

        if(!enoughSpace(sourceData))
        {
            curIndex = (curIndex + 1) % blockCount;
            val result = appendBuffer(dc);
            if(!result) return -1
        }

        dc.gl.bindBuffer(target, buffers[curIndex])

        return subAllocAndPopulate(dc, sourceData)
    }
}
