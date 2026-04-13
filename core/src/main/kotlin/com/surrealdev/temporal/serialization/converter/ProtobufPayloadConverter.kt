package com.surrealdev.temporal.serialization.converter

import com.surrealdev.temporal.common.TemporalByteString
import com.surrealdev.temporal.common.TemporalPayload
import com.surrealdev.temporal.common.exceptions.PayloadSerializationException
import com.surrealdev.temporal.serialization.PayloadConverter
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.serializer
import kotlin.reflect.KType

private val PROTOBUF_METADATA =
    mapOf(TemporalPayload.METADATA_ENCODING to TemporalByteString.fromUtf8(TemporalPayload.ENCODING_PROTOBUF))

/**
 * Protobuf converter using kotlinx.serialization.
 *
 * @param protoBuf The [ProtoBuf] instance to use for serialization/deserialization
 */
@OptIn(ExperimentalSerializationApi::class)
class ProtobufPayloadConverter(
    private val protoBuf: ProtoBuf = ProtoBuf.Default,
) : PayloadConverter {
    override val encoding: String = TemporalPayload.ENCODING_PROTOBUF

    override fun toPayload(
        typeInfo: KType,
        value: Any?,
    ): TemporalPayload? {
        if (value == null) return null
        val serializer =
            try {
                protoBuf.serializersModule.serializer(typeInfo)
            } catch (_: Exception) {
                return null // Not @Serializable — fall through to next converter
            }
        return try {
            TemporalPayload.create(PROTOBUF_METADATA) { stream ->
                stream.write(protoBuf.encodeToByteArray(serializer, value))
            }
        } catch (e: IllegalStateException) {
            // Special JsonElement Exception
            // Internals do not expose a better way of falling back.
            if (e.message?.startsWith("This serializer can be used only with Json format.") == true) {
                null
            } else {
                throw PayloadSerializationException(
                    "Failed to serialize value of type $typeInfo to protobuf: ${e.message}",
                    e,
                )
            }
        } catch (e: Exception) {
            throw PayloadSerializationException(
                "Failed to serialize value of type $typeInfo to protobuf: ${e.message}",
                e,
            )
        }
    }

    override fun fromPayload(
        typeInfo: KType,
        payload: TemporalPayload,
    ): Any? =
        try {
            val serializer = protoBuf.serializersModule.serializer(typeInfo)
            protoBuf.decodeFromByteArray(serializer, payload.data)
        } catch (e: Exception) {
            throw PayloadSerializationException(
                "Failed to deserialize protobuf to type $typeInfo: ${e.message}",
                e,
            )
        }

    companion object {
        fun default(): ProtobufPayloadConverter = ProtobufPayloadConverter(ProtoBuf.Default)
    }
}
