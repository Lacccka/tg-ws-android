package com.flowseal.tgwsandroid.proxy

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class RelayInitParityTest {
    @Test
    fun generatesUpstreamRelayInitWithDeterministicRandom() {
        for (vector in loadCryptoVectors()) {
            val generated =
                RelayInit.generate(
                    protoTag = vector.getString("proto_tag_hex").hexToBytes(),
                    dcIdx = vector.getInt("dc_idx"),
                    randomBytes = recordedRandom(vector),
                )

            assertEquals(vector.getString("name"), vector.getString("relay_init_hex"), generated.toHex())
        }
    }

    @Test
    fun encodesDcIdxAsLittleEndianSignedInt16InEncryptedTail() {
        for (vector in loadCryptoVectors()) {
            val relayInit =
                RelayInit.generate(
                    protoTag = vector.getString("proto_tag_hex").hexToBytes(),
                    dcIdx = vector.getInt("dc_idx"),
                    randomBytes = recordedRandom(vector),
                )
            val decryptedTail = decryptRelayTail(relayInit)

            assertArrayEquals(
                vector.getString("name"),
                vector.getString("proto_tag_hex").hexToBytes(),
                decryptedTail.copyOfRange(0, 4),
            )
            assertEquals(vector.getString("name"), vector.getInt("dc_idx"), littleEndianInt16(decryptedTail, 4))
        }
    }

    private fun recordedRandom(vector: JSONObject): RelayInit.RandomBytes {
        val calls = vector.getJSONArray("relay_random_calls_hex")
        var callIndex = 0
        return RelayInit.RandomBytes { length ->
            val bytes = calls.getString(callIndex++).hexToBytes()
            assertEquals(vector.getString("name"), length, bytes.size)
            bytes
        }
    }

    private fun decryptRelayTail(relayInit: ByteArray): ByteArray {
        val key = relayInit.copyOfRange(RelayInit.SKIP_LEN, RelayInit.SKIP_LEN + RelayInit.PREKEY_LEN)
        val iv =
            relayInit.copyOfRange(
                RelayInit.SKIP_LEN + RelayInit.PREKEY_LEN,
                RelayInit.SKIP_LEN + RelayInit.PREKEY_LEN + RelayInit.IV_LEN,
            )
        val encryptedFull = aesCtr(key, iv, relayInit)
        val tail = ByteArray(8)
        for (offset in tail.indices) {
            val index = RelayInit.PROTO_TAG_POS + offset
            val keystreamByte = encryptedFull[index].toInt() xor relayInit[index].toInt()
            tail[offset] = (relayInit[index].toInt() xor keystreamByte).toByte()
        }
        return tail
    }

    private fun aesCtr(
        key: ByteArray,
        iv: ByteArray,
        input: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(input)
    }

    private fun littleEndianInt16(
        bytes: ByteArray,
        offset: Int,
    ): Int {
        val low = bytes[offset].toInt() and 0xff
        val high = bytes[offset + 1].toInt()
        return (high shl 8) or low
    }
}
