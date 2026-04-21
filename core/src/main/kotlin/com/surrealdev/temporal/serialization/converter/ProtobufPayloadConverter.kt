@file:OptIn(ExperimentalSerializationApi::class)

package com.surrealdev.temporal.serialization.converter

import com.surrealdev.temporal.common.TemporalByteString
import com.surrealdev.temporal.common.TemporalPayload
import com.surrealdev.temporal.common.exceptions.PayloadSerializationException
import com.surrealdev.temporal.serialization.PayloadConverter
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import kotlinx.serialization.serializer
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.KTypeProjection
import kotlin.reflect.full.createType

@Serializable
enum class SerializedVariance {
    @ProtoNumber(0)
    INVARIANT,

    @ProtoNumber(1)
    IN,

    @ProtoNumber(2)
    OUT,
}

@Serializable
data class SerializedKType(
    @ProtoNumber(1) val classifier: String, // JVM canonical class name
    @ProtoNumber(2) val arguments: List<SerializedKTypeProjection> = emptyList(),
    @ProtoNumber(3) val nullable: Boolean = false,
)

@Serializable
data class SerializedKTypeProjection(
    // null variance = star projection
    @ProtoNumber(1) val variance: SerializedVariance? = SerializedVariance.INVARIANT,
    @ProtoNumber(2) val type: SerializedKType? = null, // null = star projection
)

private fun KType.toSerialized(): SerializedKType {
    val kClass =
        classifier as? KClass<*>
            ?: error("Cannot serialize type parameter classifiers: $this")
    return SerializedKType(
        classifier = kClass.java.name, // ← java.name, NOT canonicalName
        arguments =
            arguments.map { projection ->
                SerializedKTypeProjection(
                    variance =
                        when (projection.variance) {
                            null -> null
                            kotlin.reflect.KVariance.INVARIANT -> SerializedVariance.INVARIANT
                            kotlin.reflect.KVariance.IN -> SerializedVariance.IN
                            kotlin.reflect.KVariance.OUT -> SerializedVariance.OUT
                        },
                    type = projection.type?.toSerialized(),
                )
            },
        nullable = isMarkedNullable,
    )
}

private fun SerializedKType.toKType(): KType {
    val kClass = Class.forName(classifier).kotlin // works because we stored java.name
    val typeArguments =
        arguments.map { projection ->
            when (projection.variance) {
                null -> KTypeProjection.STAR
                SerializedVariance.INVARIANT -> KTypeProjection.invariant(projection.type!!.toKType())
                SerializedVariance.IN -> KTypeProjection.contravariant(projection.type!!.toKType())
                SerializedVariance.OUT -> KTypeProjection.covariant(projection.type!!.toKType())
            }
        }
    return kClass.createType(typeArguments, nullable)
}

const val KOTLINX_PROTO_TYPE_METADATA = "ktMessageType"

private val kMetadataProto = ProtoBuf { encodeDefaults = true }

private fun buildMetadata(
    includeType: Boolean,
    type: KType,
    cache: ConcurrentHashMap<KType, TemporalByteString>,
): Map<String, TemporalByteString> =
    if (!includeType) {
        mapOf(TemporalPayload.METADATA_ENCODING to TemporalByteString.fromUtf8(TemporalPayload.ENCODING_PROTOBUF))
    } else {
        mapOf(
            TemporalPayload.METADATA_ENCODING to TemporalByteString.fromUtf8(TemporalPayload.ENCODING_PROTOBUF),
            KOTLINX_PROTO_TYPE_METADATA to
                cache.getOrPut(type) {
                    TemporalByteString.from(kMetadataProto.encodeToByteArray(type.toSerialized()))
                },
        )
    }

/**
 * Protobuf converter using kotlinx.serialization.
 *
 * @param protoBuf The [ProtoBuf] instance to use for serialization/deserialization
 * @param includeType include type to assist reconstruction later
 */
class ProtobufPayloadConverter(
    private val protoBuf: ProtoBuf = ProtoBuf.Default,
    val includeType: Boolean = false,
) : PayloadConverter {
    private val typeCache = ConcurrentHashMap<KType, TemporalByteString>()
    private val inverseCache = ConcurrentHashMap<SerializedKType, KType>()

    override val encoding: String = TemporalPayload.ENCODING_PROTOBUF

    /**
     * Attempt to reconstruct the kotlinx serialization protobuf instance from a payload using metadata
     * This is useful to convert histories to JSON
     *
     * Note that this is a potential attack vector and should not be exposed externally... Although kotlinx serialization
     * does minimize the risk significantly as any class without compile time generation will fail.
     *
     * And obviously any minification will break this
     */
    fun reconstructFromMetadata(
        payload: TemporalPayload,
        reflectionWhiteList: Set<String>? = null,
    ): Pair<KType, Any?> {
        val typeBytes =
            payload.metadataByteStrings[KOTLINX_PROTO_TYPE_METADATA] ?: throw PayloadSerializationException(
                "Cannot reconstruct kotlinx serialization proto payload when $KOTLINX_PROTO_TYPE_METADATA is missing",
            )

        val type =
            try {
                kMetadataProto.decodeFromByteArray<SerializedKType>(typeBytes.toByteArray())
            } catch (e: Exception) {
                throw PayloadSerializationException(
                    "Exception parsing kt metadata",
                    e,
                )
            }

        if (reflectionWhiteList != null && type.classifier !in reflectionWhiteList) {
            throw PayloadSerializationException(
                "Attempted to access ${type.classifier} but it is not whitelisted",
            )
        }

        val realType =
            inverseCache.getOrPut(type) {
                try {
                    type.toKType()
                } catch (e: Exception) {
                    throw PayloadSerializationException(
                        "Exception parsing kt metadata to real type",
                        e,
                    )
                }
            }

        return realType to fromPayload(realType, payload)
    }

    override fun toPayload(
        typeInfo: KType,
        value: Any?,
    ): TemporalPayload? {
        if (value == null) return null
        val serializer =
            try {
                // ktype does not use reflection and is safe to call repeatedly
                protoBuf.serializersModule.serializer(typeInfo)
            } catch (_: Exception) {
                return null // Not @Serializable — fall through to next converter
            }
        return try {
            TemporalPayload.create(buildMetadata(includeType, typeInfo, typeCache)) { stream ->
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
