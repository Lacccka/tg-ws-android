package com.flowseal.tgwsandroid.proxy

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RawWebSocketCodecParityTest {
    @Test
    fun builtFramesMatchUpstreamBytes() {
        val frames = loadWebSocketVectors().getJSONArray("frames")
        for (index in 0 until frames.length()) {
            val vector = frames.getJSONObject(index)
            val maskKey = vector.optString("mask_key_hex", "").hexToBytes()
            val actual =
                RawWebSocketCodec.buildFrame(
                    opcode = vector.getInt("opcode"),
                    data = vector.getString("payload_hex").hexToBytes(),
                    mask = vector.getBoolean("mask"),
                    randomProvider = { length ->
                        assertEquals("${vector.getString("name")} mask length", 4, length)
                        maskKey
                    },
                )
            assertEquals(vector.getString("name"), vector.getString("expected_frame_hex"), actual.toHex())
        }
    }

    @Test
    fun parsedFramesMatchUpstreamReadFrameExpectations() {
        val frames = loadWebSocketVectors().getJSONArray("frames")
        for (index in 0 until frames.length()) {
            val vector = frames.getJSONObject(index)
            val expected = vector.getJSONObject("expected_read")
            val parsed = RawWebSocketCodec.parseFrame(vector.getString("expected_frame_hex").hexToBytes())
            assertEquals(vector.getString("name"), expected.getInt("opcode"), parsed.opcode)
            assertEquals(vector.getString("name"), expected.getBoolean("masked_input"), parsed.masked)
            assertEquals(vector.getString("name"), expected.getString("payload_hex"), parsed.payload.toHex())
            assertEquals(
                vector.getString("name"),
                expected
                    .getString("payload_hex")
                    .hexToBytes()
                    .size
                    .toLong(),
                parsed.length,
            )
        }
    }

    @Test
    fun upgradeRequestsMatchUpstreamConnectFormat() {
        val requests = loadWebSocketVectors().getJSONArray("requests")
        for (index in 0 until requests.length()) {
            val vector = requests.getJSONObject(index)
            val actual =
                RawWebSocketCodec.buildUpgradeRequest(
                    path = vector.getString("path"),
                    domain = vector.getString("domain"),
                    secWebSocketKey = vector.getString("sec_websocket_key"),
                )
            assertEquals(vector.getString("name"), vector.getString("expected_request_text"), actual)
        }
    }

    @Test
    fun handshakeResponsesMatchUpstreamStatusAndHeaderBehavior() {
        val responses = loadWebSocketVectors().getJSONArray("responses")
        for (index in 0 until responses.length()) {
            val vector = responses.getJSONObject(index)
            val expected = vector.getJSONObject("expected")
            val actual = RawWebSocketCodec.parseHandshakeResponse(vector.getString("raw_response_hex").hexToBytes())

            assertEquals(vector.getString("name"), expected.getBoolean("success"), actual.success)
            assertEquals(vector.getString("name"), expected.getInt("status_code"), actual.statusCode)
            assertEquals(vector.getString("name"), expected.getString("status_line"), actual.statusLine)
            assertEquals(vector.getString("name"), expected.getBoolean("is_redirect"), actual.isRedirect)
            assertEquals(vector.getString("name"), expected.nullableString("location"), actual.location)
            assertEquals(vector.getString("name"), expected.nullableString("error_message"), actual.errorMessage)
            assertEquals(vector.getString("name"), expected.getJSONObject("headers").toStringMap(), actual.headers)
        }
    }

    @Test
    fun maskAndUnmaskRoundtrip() {
        val payload = "roundtrip-payload-with-binary-\u0000-data".toByteArray(Charsets.UTF_8)
        val maskKey = byteArrayOf(0x01, 0x23, 0x45, 0x67)
        val masked = RawWebSocketCodec.xorMask(payload, maskKey)
        assertFalse(payload.contentEquals(masked))
        assertArrayEquals(payload, RawWebSocketCodec.xorMask(masked, maskKey))

        val frame =
            RawWebSocketCodec.buildFrame(
                opcode = RawWebSocketCodec.OP_BINARY,
                data = payload,
                mask = true,
                randomProvider = { maskKey },
            )
        val parsed = RawWebSocketCodec.parseFrame(frame)
        assertTrue(parsed.masked)
        assertEquals(RawWebSocketCodec.OP_BINARY, parsed.opcode)
        assertArrayEquals(payload, parsed.payload)
    }

    @Test
    fun successfulHandshakeHasNoErrorMessage() {
        val response =
            RawWebSocketCodec.parseHandshakeResponse(
                "HTTP/1.1 101 Switching Protocols\r\n\r\n".toByteArray(Charsets.UTF_8),
            )
        assertTrue(response.success)
        assertNull(response.errorMessage)
    }

    private fun loadWebSocketVectors(): JSONObject {
        val stream = javaClass.classLoader!!.getResourceAsStream("websocket_vectors.json")
        return JSONObject(stream.reader(Charsets.UTF_8).readText())
    }

    private fun JSONObject.toStringMap(): Map<String, String> =
        buildMap {
            val keys = keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, getString(key))
            }
        }

    private fun JSONObject.nullableString(name: String): String? = if (isNull(name)) null else getString(name)
}
