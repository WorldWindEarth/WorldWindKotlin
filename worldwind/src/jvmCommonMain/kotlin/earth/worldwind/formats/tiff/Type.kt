package earth.worldwind.formats.tiff

import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage

enum class Type {
    UBYTE, ASCII, USHORT, ULONG, RATIONAL, SBYTE, UNDEFINED, SSHORT, SLONG, SRATIONAL, FLOAT, DOUBLE;

    val sizeInBytes get() = when (this) {
        UBYTE, ASCII, SBYTE, UNDEFINED -> 1
        USHORT, SSHORT -> 2
        ULONG, SLONG, FLOAT -> 4
        RATIONAL, SRATIONAL, DOUBLE -> 8
    }

    val specificationTag get() = ordinal + 1

    companion object {
        @JvmStatic
        fun decode(type: Int): Type {
            val values = values()
            require(type > 0 && type <= values.size) {
                logMessage(ERROR, "Type", "decode", "invalid type")
            }
            return values[type - 1]
        }
    }
}