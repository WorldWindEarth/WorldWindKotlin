package earth.worldwind.formats.kml.serializer

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.math.roundToInt

internal object FlexibleIntSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("FlexibleInt", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder) = decoder.decodeString().trim().lowercase().run {
        toIntOrNull() ?: toDoubleOrNull()?.roundToInt() ?: 0
    }

    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeString(value.toString())
}