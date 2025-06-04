package earth.worldwind.formats.kml.serializer

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

internal object FlexibleBooleanSerializer : KSerializer<Boolean?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("FlexibleBoolean", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder) = when (decoder.decodeString().trim().lowercase()) {
        "1", "true" -> true
        "0", "false" -> false
        else -> null
    }

    override fun serialize(encoder: Encoder, value: Boolean?) = encoder.encodeString(
        when (value) {
            true -> "1"
            false -> "0"
            null -> ""
        }
    )
}