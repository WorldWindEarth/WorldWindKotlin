package earth.worldwind.render.buffer

import earth.worldwind.util.kgl.KglBuffer
import earth.worldwind.draw.DrawContext

val DEFAULT_BUFFER_POOL_BLOCK_SIZE : Int = 4 * 1024
val DEFAULT_BUFFER_POOL_MAX_BLOCK_COUNT : Int = 64
class StorageBufferPool(private var target : Int,
        private var usage : Int,
        private var blockSize : Int = DEFAULT_BUFFER_POOL_BLOCK_SIZE,
        private var blockCount : Int = DEFAULT_BUFFER_POOL_MAX_BLOCK_COUNT)
{
    private class StorageBufferObject() {
        private var offset : Int = 0
        private var size : Int = 0
        var id : KglBuffer = KglBuffer.NONE
        fun initAndAlloc(dc: DrawContext, size:Int, target:Int, usage:Int) : Boolean
        {
            this.size = size

            id = dc.gl.createBuffer();
            if(!id.isValid()) return false

            dc.gl.bindBuffer(target, id);

            //Only reserve memory
            dc.gl.bufferData(target, size, usage);

            dc.gl.bindBuffer(target, KglBuffer.NONE);
            return id.isValid()
        }
        fun subAllocAndPopulate(dc: DrawContext, target: Int, sourceData: FloatArray) : Int
        {
            val bufferSize = sourceData.size * 4
            if(offset + bufferSize > size)
                return -1

            //Update part of the buffer
            dc.gl.bufferSubData(target, offset, bufferSize, sourceData)

            val bindOffset = offset;
            offset += bufferSize
            return bindOffset
        }

        fun reset()
        {
            if(offset != 0)
                offset = 0;
        }

        fun free(dc: DrawContext)
        {
            if(id.isValid())
            {
                dc.gl.deleteBuffer(id);
                id = KglBuffer.NONE
            }
        }

        fun enoughSpace(sourceData: FloatArray) : Boolean
        {
            return offset + sourceData.size * Float.SIZE_BYTES <= size;
        }
    }

    private val buffers = Array<StorageBufferObject>(blockCount){StorageBufferObject()}
    private var freeIndex = 0

    fun reset()
    {
        freeIndex = 0;
        for (buffer in buffers)
        {
            if(buffer.id.isValid())
            {
                buffer.reset()
            }
        }
    }

    fun free(dc: DrawContext)
    {
        freeIndex = 0;
        for (buffer in buffers)
        {
            buffer.free(dc)
        }
    }

    private fun appendBuffer(dc: DrawContext) : Boolean
    {
        if(!buffers[freeIndex].id.isValid())
        {
            if(!buffers[freeIndex].initAndAlloc(dc, blockSize, target, usage))
                return false
        }
        buffers[freeIndex].reset()
        return true
    }

    fun bindBufferAndGetBindOffset(dc: DrawContext, sourceData: FloatArray) : Int
    {
        if(!buffers[freeIndex].id.isValid())
        {
            val result = appendBuffer(dc);
            if(!result) return -1
        }

        if(!buffers[freeIndex].enoughSpace(sourceData))
        {
            freeIndex = (freeIndex + 1) % blockCount;
            val result = appendBuffer(dc);
            if(!result) return -1
        }

        dc.gl.bindBuffer(target, buffers[freeIndex].id)

        return buffers[freeIndex].subAllocAndPopulate(dc, target, sourceData)
    }
}
