package com.surrealdev.temporal.serialization

import com.surrealdev.temporal.common.TemporalPayload
import com.surrealdev.temporal.common.exceptions.PayloadSerializationException
import com.surrealdev.temporal.serialization.converter.JsonPayloadConverter
import com.surrealdev.temporal.serialization.converter.NullPayloadConverter
import com.surrealdev.temporal.serialization.converter.ProtobufPayloadConverter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtobufPayloadConverterTest {
    @Serializable
    data class TestData(
        val name: String,
        val count: Int,
    )

    @Serializable
    data class NestedData(
        val items: List<TestData>,
        val label: String,
    )

    @Serializable
    data class WithJsonElement(
        val name: String,
        val payload: JsonElement,
    )

    private val converter = ProtobufPayloadConverter.default()

    // ================================================================
    // Basic serialization
    // ================================================================

    @Test
    fun `serializes Serializable data class with protobuf encoding`() {
        val payload = converter.toPayload(typeOf<TestData>(), TestData("hello", 42))

        assertNotNull(payload)
        assertEquals(TemporalPayload.ENCODING_PROTOBUF, payload.encoding)
        assertTrue(payload.dataSize > 0)
    }

    @Test
    fun `round-trips Serializable data class`() {
        val original = TestData("world", 99)
        val payload = converter.toPayload(typeOf<TestData>(), original)!!

        val result = converter.fromPayload(typeOf<TestData>(), payload)
        assertEquals(original, result)
    }

    @Test
    fun `round-trips nested Serializable types`() {
        val original =
            NestedData(
                items = listOf(TestData("a", 1), TestData("b", 2)),
                label = "test",
            )
        val payload = converter.toPayload(typeOf<NestedData>(), original)!!

        val result = converter.fromPayload(typeOf<NestedData>(), payload)
        assertEquals(original, result)
    }

    @Test
    fun `round-trips list of Serializable types`() {
        val original = listOf(TestData("x", 10), TestData("y", 20))
        val payload = converter.toPayload(typeOf<List<TestData>>(), original)!!

        assertEquals(TemporalPayload.ENCODING_PROTOBUF, payload.encoding)
        val result = converter.fromPayload(typeOf<List<TestData>>(), payload)
        assertEquals(original, result)
    }

    @Test
    fun `round-trips primitives`() {
        // Int
        val intPayload = converter.toPayload(typeOf<Int>(), 42)!!
        assertEquals(42, converter.fromPayload(typeOf<Int>(), intPayload))

        // String
        val strPayload = converter.toPayload(typeOf<String>(), "hello")!!
        assertEquals("hello", converter.fromPayload(typeOf<String>(), strPayload))

        // Boolean
        val boolPayload = converter.toPayload(typeOf<Boolean>(), true)!!
        assertEquals(true, converter.fromPayload(typeOf<Boolean>(), boolPayload))
    }

    // ================================================================
    // Null handling
    // ================================================================

    @Test
    fun `returns null for null values`() {
        val payload = converter.toPayload(typeOf<TestData?>(), null)
        assertNull(payload)
    }

    // ================================================================
    // Error handling
    // ================================================================

    @Test
    fun `fromPayload throws PayloadSerializationException on corrupted data`() {
        val badPayload =
            TemporalPayload.create(
                byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0xFD.toByte()),
                mapOf(
                    TemporalPayload.METADATA_ENCODING to
                        com.surrealdev.temporal.common.TemporalByteString
                            .fromUtf8(TemporalPayload.ENCODING_PROTOBUF),
                ),
            )

        assertFailsWith<PayloadSerializationException> {
            converter.fromPayload(typeOf<TestData>(), badPayload)
        }
    }

    // ================================================================
    // JsonElement fallthrough
    // ================================================================

    @Test
    fun `returns null for JsonElement directly - falls through to JSON`() {
        val element: JsonElement = buildJsonObject { put("key", "value") }
        val payload = converter.toPayload(typeOf<JsonElement>(), element)
        assertNull(payload)
    }

    @Test
    fun `returns null for JsonObject subtype - falls through to JSON`() {
        val obj: JsonObject = buildJsonObject { put("x", 1) }
        val payload = converter.toPayload(typeOf<JsonObject>(), obj)
        assertNull(payload)
    }

    @Test
    fun `returns null for class containing JsonElement field - falls through to JSON`() {
        val value = WithJsonElement("test", buildJsonObject { put("a", "b") })
        val payload = converter.toPayload(typeOf<WithJsonElement>(), value)
        assertNull(payload)
    }

    @Test
    fun `chain routes JsonElement-containing class to JSON encoding`() {
        val chain =
            CompositePayloadSerializer(
                listOf(NullPayloadConverter, ProtobufPayloadConverter.default(), JsonPayloadConverter.default()),
            )
        val value = WithJsonElement("test", buildJsonObject { put("a", "b") })
        val payload = chain.serialize(typeOf<WithJsonElement>(), value)

        assertEquals(TemporalPayload.ENCODING_JSON, payload.encoding)

        val result = chain.deserialize(typeOf<WithJsonElement>(), payload) as WithJsonElement
        assertEquals(value, result)
    }

    // ================================================================
    // Chain integration
    // ================================================================

    @Test
    fun `protobuf before JSON in chain - Serializable types get protobuf encoding`() {
        val chain =
            CompositePayloadSerializer(
                listOf(NullPayloadConverter, ProtobufPayloadConverter.default(), JsonPayloadConverter.default()),
            )

        val payload = chain.serialize(typeOf<TestData>(), TestData("chain", 1))
        assertEquals(TemporalPayload.ENCODING_PROTOBUF, payload.encoding)

        val result = chain.deserialize(typeOf<TestData>(), payload)
        assertEquals(TestData("chain", 1), result)
    }

    @Test
    fun `chain round-trips null values through NullPayloadConverter`() {
        val chain =
            CompositePayloadSerializer(
                listOf(NullPayloadConverter, ProtobufPayloadConverter.default(), JsonPayloadConverter.default()),
            )

        val payload = chain.serialize(typeOf<TestData?>(), null)
        assertEquals(TemporalPayload.ENCODING_NULL, payload.encoding)

        val result = chain.deserialize(typeOf<TestData?>(), payload)
        assertNull(result)
    }

    // ================================================================
    // Protobuf is more compact than JSON
    // ================================================================

    @Test
    fun `protobuf payload is smaller than JSON for same data`() {
        val data = TestData("hello world", 12345)
        val protobufPayload = assertNotNull(ProtobufPayloadConverter.default().toPayload(typeOf<TestData>(), data))
        val jsonPayload = assertNotNull(JsonPayloadConverter.default().toPayload(typeOf<TestData>(), data))

        assertTrue(
            protobufPayload.dataSize < jsonPayload.dataSize,
            "Protobuf (${protobufPayload.dataSize} bytes) should be smaller than JSON (${jsonPayload.dataSize} bytes)",
        )
    }
}
