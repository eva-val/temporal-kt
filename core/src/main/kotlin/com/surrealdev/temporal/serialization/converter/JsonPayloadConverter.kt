package com.surrealdev.temporal.serialization.converter

import com.surrealdev.temporal.common.TemporalByteString
import com.surrealdev.temporal.common.TemporalPayload
import com.surrealdev.temporal.common.exceptions.PayloadSerializationException
import com.surrealdev.temporal.serialization.PayloadConverter
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.serializer
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KType

private val JSON_METADATA =
    mapOf(TemporalPayload.METADATA_ENCODING to TemporalByteString.fromUtf8(TemporalPayload.ENCODING_JSON))

private val JSON_ENCODING_BYTES = TemporalByteString.fromUtf8(TemporalPayload.ENCODING_JSON)

/**
 * JSON converter using kotlinx.serialization.
 *
 * Produces payloads with `encoding: json/plain`. This is a catch-all converter
 * that always returns a payload (never returns null from [toPayload]), so it
 * should be placed last in the converter chain.
 *
 * @param json The [Json] instance to use for serialization/deserialization
 * @param includeSerialNameAsMessageType When true, the top-level serializer's
 *   `descriptor.serialName` is written to [TemporalPayload.METADATA_MESSAGE_TYPE].
 */
class JsonPayloadConverter(
    private val json: Json = DEFAULT_JSON,
    val includeSerialNameAsMessageType: Boolean = false,
) : PayloadConverter {
    override val encoding: String = TemporalPayload.ENCODING_JSON

    private val metadataCache = ConcurrentHashMap<KType, Map<String, TemporalByteString>>()

    @OptIn(ExperimentalSerializationApi::class)
    private fun metadataFor(
        typeInfo: KType,
        serializer: KSerializer<*>,
    ): Map<String, TemporalByteString> =
        if (!includeSerialNameAsMessageType) {
            JSON_METADATA
        } else {
            metadataCache.getOrPut(typeInfo) {
                mapOf(
                    TemporalPayload.METADATA_ENCODING to JSON_ENCODING_BYTES,
                    TemporalPayload.METADATA_MESSAGE_TYPE to
                        TemporalByteString.fromUtf8(serializer.descriptor.serialName),
                )
            }
        }

    override fun toPayload(
        typeInfo: KType,
        value: Any?,
    ): TemporalPayload =
        try {
            @OptIn(ExperimentalSerializationApi::class)
            val serializer = json.serializersModule.serializer(typeInfo)
            TemporalPayload.create(metadataFor(typeInfo, serializer)) { stream ->
                @OptIn(ExperimentalSerializationApi::class)
                json.encodeToStream(serializer, value!!, stream)
            }
        } catch (e: Exception) {
            throw PayloadSerializationException(
                "Failed to serialize value of type $typeInfo to JSON: ${e.message}",
                e,
            )
        }

    override fun fromPayload(
        typeInfo: KType,
        payload: TemporalPayload,
    ): Any? =
        try {
            @OptIn(ExperimentalSerializationApi::class)
            json.decodeFromStream(json.serializersModule.serializer(typeInfo), payload.dataInputStream())
        } catch (e: Exception) {
            throw PayloadSerializationException(
                "Failed to deserialize JSON to type $typeInfo: ${e.message}",
                e,
            )
        }

    companion object {
        private val DEFAULT_JSON =
            Json {
                encodeDefaults = true
                ignoreUnknownKeys = true
            }

        fun default(): JsonPayloadConverter = JsonPayloadConverter(DEFAULT_JSON)
    }
}
