@file:OptIn(ExperimentalSerializationApi::class)

package com.surrealdev.temporal.serialization

import com.surrealdev.temporal.common.TemporalPayload
import com.surrealdev.temporal.common.exceptions.PayloadSerializationException
import com.surrealdev.temporal.serialization.converter.JsonPayloadConverter
import com.surrealdev.temporal.serialization.converter.NullPayloadConverter
import com.surrealdev.temporal.serialization.converter.ProtobufPayloadConverter
import com.surrealdev.temporal.serialization.converter.SerializedKType
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.protobuf.ProtoBuf
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

    @Serializable
    sealed class Shape {
        @Serializable
        data class Circle(
            val radius: Double,
        ) : Shape()

        @Serializable
        data class Rectangle(
            val width: Double,
            val height: Double,
        ) : Shape()

        @Serializable
        data object Unit : Shape()
    }

    @Serializable
    data class ShapeHolder(
        val label: String,
        val shape: Shape,
    )

    private val converter = ProtobufPayloadConverter.default()
    private val typedConverter = ProtobufPayloadConverter(includeType = true)

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

    // ================================================================
    // reconstructFromMetadata
    // ================================================================

    @Test
    fun `reconstructFromMetadata round-trips Serializable data class`() {
        val original = TestData("reconstruct", 7)
        val payload = typedConverter.toPayload(typeOf<TestData>(), original)!!

        val (type, value) = typedConverter.reconstructFromMetadata(payload)
        assertEquals(typeOf<TestData>(), type)
        assertEquals(original, value)
    }

    @Test
    fun `reconstructFromMetadata round-trips nested generic types`() {
        val original =
            NestedData(
                items = listOf(TestData("a", 1), TestData("b", 2)),
                label = "nested",
            )
        val payload = typedConverter.toPayload(typeOf<NestedData>(), original)!!

        val (type, value) = typedConverter.reconstructFromMetadata(payload)
        assertEquals(typeOf<NestedData>(), type)
        assertEquals(original, value)
    }

    @Test
    fun `reconstructFromMetadata round-trips parameterized List`() {
        val original = listOf(TestData("x", 10), TestData("y", 20))
        val payload = typedConverter.toPayload(typeOf<List<TestData>>(), original)!!

        val (type, value) = typedConverter.reconstructFromMetadata(payload)
        assertEquals(typeOf<List<TestData>>(), type)
        assertEquals(original, value)
    }

    @Test
    fun `reconstructFromMetadata throws when type metadata is missing`() {
        val payload = converter.toPayload(typeOf<TestData>(), TestData("no-meta", 1))!!

        assertFailsWith<PayloadSerializationException> {
            typedConverter.reconstructFromMetadata(payload)
        }
    }

    @Test
    fun `reconstructFromMetadata throws on malformed type metadata`() {
        // Random garbage bytes that are not valid protobuf for SerializedKType
        val badPayload =
            TemporalPayload.create(
                byteArrayOf(0x00),
                mapOf(
                    TemporalPayload.METADATA_ENCODING to
                        com.surrealdev.temporal.common.TemporalByteString
                            .fromUtf8(TemporalPayload.ENCODING_PROTOBUF),
                    "ktMessageType" to
                        com.surrealdev.temporal.common.TemporalByteString
                            .from(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())),
                ),
            )

        assertFailsWith<PayloadSerializationException> {
            typedConverter.reconstructFromMetadata(badPayload)
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `reconstructFromMetadata throws when classifier class is not found`() {
        val badType =
            SerializedKType(
                classifier = "com.does.not.Exist",
                arguments = emptyList(),
                nullable = false,
            )
        val badBytes = ProtoBuf { encodeDefaults = true }.encodeToByteArray(badType)
        val badPayload =
            TemporalPayload.create(
                byteArrayOf(0x00),
                mapOf(
                    TemporalPayload.METADATA_ENCODING to
                        com.surrealdev.temporal.common.TemporalByteString
                            .fromUtf8(TemporalPayload.ENCODING_PROTOBUF),
                    "ktMessageType" to
                        com.surrealdev.temporal.common.TemporalByteString
                            .from(badBytes),
                ),
            )

        assertFailsWith<PayloadSerializationException> {
            typedConverter.reconstructFromMetadata(badPayload)
        }
    }

    @Test
    fun `reconstructFromMetadata throws when classifier is not in whitelist`() {
        val payload = typedConverter.toPayload(typeOf<TestData>(), TestData("blocked", 1))!!

        val ex =
            assertFailsWith<PayloadSerializationException> {
                typedConverter.reconstructFromMetadata(payload, reflectionWhiteList = setOf("com.other.Thing"))
            }
        assertTrue(ex.message?.contains("not whitelisted") == true)
    }

    @Test
    fun `reconstructFromMetadata succeeds when classifier is in whitelist`() {
        val original = TestData("allowed", 2)
        val payload = typedConverter.toPayload(typeOf<TestData>(), original)!!

        val (type, value) =
            typedConverter.reconstructFromMetadata(
                payload,
                reflectionWhiteList = setOf(TestData::class.java.name),
            )
        assertEquals(typeOf<TestData>(), type)
        assertEquals(original, value)
    }

    @Test
    fun `toPayload with includeType=false omits type metadata`() {
        val payload = converter.toPayload(typeOf<TestData>(), TestData("bare", 3))!!

        assertNull(payload.getMetadataString("ktMessageType"))
    }

    @Test
    fun `toPayload with includeType=true populates type metadata`() {
        val payload = typedConverter.toPayload(typeOf<TestData>(), TestData("typed", 4))!!

        assertNotNull(payload.getMetadataString("ktMessageType"))
    }

    // ================================================================
    // Sealed class polymorphism
    // ================================================================

    @Test
    fun `round-trips sealed class subtype via sealed base type`() {
        val circle: Shape = Shape.Circle(3.14)
        val payload = converter.toPayload(typeOf<Shape>(), circle)!!

        val result = converter.fromPayload(typeOf<Shape>(), payload)
        assertEquals(circle, result)
    }

    @Test
    fun `round-trips all sealed subtypes via sealed base type`() {
        val cases: List<Shape> =
            listOf(
                Shape.Circle(1.5),
                Shape.Rectangle(2.0, 4.0),
                Shape.Unit,
            )

        for (original in cases) {
            val payload = converter.toPayload(typeOf<Shape>(), original)!!
            val result = converter.fromPayload(typeOf<Shape>(), payload)
            assertEquals(original, result)
        }
    }

    @Test
    fun `round-trips sealed class nested inside another Serializable`() {
        val original = ShapeHolder("holder", Shape.Rectangle(5.0, 6.0))
        val payload = converter.toPayload(typeOf<ShapeHolder>(), original)!!

        val result = converter.fromPayload(typeOf<ShapeHolder>(), payload)
        assertEquals(original, result)
    }

    @Test
    fun `round-trips list of sealed base type with mixed subtypes`() {
        val original: List<Shape> =
            listOf(
                Shape.Circle(0.5),
                Shape.Rectangle(1.0, 2.0),
                Shape.Unit,
                Shape.Circle(9.9),
            )
        val payload = converter.toPayload(typeOf<List<Shape>>(), original)!!

        val result = converter.fromPayload(typeOf<List<Shape>>(), payload)
        assertEquals(original, result)
    }

    @Test
    fun `reconstructFromMetadata round-trips sealed class via base type`() {
        val original: Shape = Shape.Rectangle(3.0, 4.0)
        val payload = typedConverter.toPayload(typeOf<Shape>(), original)!!

        val (type, value) = typedConverter.reconstructFromMetadata(payload)
        assertEquals(typeOf<Shape>(), type)
        assertEquals(original, value)
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `reconstructFromMetadata sealed base classifier is the sealed type not the subtype`() {
        val payload = typedConverter.toPayload(typeOf<Shape>(), Shape.Circle(1.0))!!

        val metaBytes = payload.metadataByteStrings["ktMessageType"]?.toByteArray()
        assertNotNull(metaBytes)
        val decoded = ProtoBuf { encodeDefaults = true }.decodeFromByteArray<SerializedKType>(metaBytes)
        assertEquals(Shape::class.java.name, decoded.classifier)
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
