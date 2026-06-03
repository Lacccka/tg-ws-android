package com.flowseal.tgwsandroid.proxy

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MtprotoHandshakeParityTest {
    @Test
    fun parsesGeneratedUpstreamParityVectors() {
        for (vector in loadVectors()) {
            val expected = vector.optJSONObject("expected")
            if (expected == null) {
                assertInvalidVector(vector)
            } else {
                val parsed = MtprotoHandshake.parse(
                    handshake = vector.getString("handshake_hex").hexToBytes(),
                    secretHex = vector.getString("secret_hex"),
                )

                assertNotNull(vector.getString("name"), parsed)
                requireNotNull(parsed)
                assertEquals(vector.getString("name"), expected.getInt("dc_id"), parsed.dcId)
                assertEquals(vector.getString("name"), expected.getBoolean("is_media"), parsed.isMedia)
                assertEquals(vector.getString("name"), expected.getString("proto_tag_hex"), parsed.protoTag.toHex())
                assertEquals(
                    vector.getString("name"),
                    expected.getString("client_dec_prekey_iv_hex"),
                    parsed.clientDecPrekeyIv.toHex(),
                )
            }
        }
    }

    @Test
    fun malformedLengthIsRejected() {
        val vector = loadVectors().first { it.getString("name") == "abridged_dc2" }
        val malformed = vector.getString("handshake_hex").hexToBytes().copyOf(MtprotoHandshake.HANDSHAKE_LEN - 1)

        try {
            MtprotoHandshake.parse(malformed, vector.getString("secret_hex"))
        } catch (expected: IllegalArgumentException) {
            assertEquals(
                "MTProto obfuscated handshake must be exactly ${MtprotoHandshake.HANDSHAKE_LEN} bytes",
                expected.message,
            )
            return
        }
        throw AssertionError("Expected malformed handshake length to be rejected")
    }

    private fun assertInvalidVector(vector: JSONObject) {
        try {
            val parsed = MtprotoHandshake.parse(
                handshake = vector.getString("handshake_hex").hexToBytes(),
                secretHex = vector.getString("secret_hex"),
            )
            assertNull(vector.getString("name"), parsed)
        } catch (expected: IllegalArgumentException) {
            assertEquals("invalid_malformed_length", vector.getString("name"))
        }
    }

    private fun loadVectors(): List<JSONObject> {
        val stream = javaClass.classLoader!!.getResourceAsStream("handshake_vectors.json")
        val json = JSONObject(stream.reader(Charsets.UTF_8).readText())
        val vectors = json.getJSONArray("vectors")
        return buildList {
            for (index in 0 until vectors.length()) {
                add(vectors.getJSONObject(index))
            }
        }
    }

    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0)
        return ByteArray(length / 2) { index ->
            val high = Character.digit(this[index * 2], 16)
            val low = Character.digit(this[index * 2 + 1], 16)
            require(high >= 0 && low >= 0)
            ((high shl 4) or low).toByte()
        }
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}
