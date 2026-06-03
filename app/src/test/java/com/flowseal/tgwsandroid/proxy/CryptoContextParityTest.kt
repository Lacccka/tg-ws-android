package com.flowseal.tgwsandroid.proxy

import org.junit.Assert.assertEquals
import org.junit.Test

class CryptoContextParityTest {
    @Test
    fun matchesUpstreamStreamTransformationsForFullBuffers() {
        for (vector in loadCryptoVectors()) {
            val ctx = buildContext(vector)
            val plainFromClient = ctx.decryptFromClient(vector.getString("sample_client_ciphertext_hex").hexToBytes())
            val telegramCiphertext = ctx.encryptToTelegram(plainFromClient)
            val plainFromTelegram = ctx.decryptFromTelegram(vector.getString("sample_telegram_ciphertext_hex").hexToBytes())
            val clientCiphertext = ctx.encryptToClient(plainFromTelegram)

            assertEquals(vector.getString("name"), vector.getString("expected_plain_from_client_hex"), plainFromClient.toHex())
            assertEquals(vector.getString("name"), vector.getString("expected_telegram_ciphertext_hex"), telegramCiphertext.toHex())
            assertEquals(vector.getString("name"), vector.getString("expected_plain_from_telegram_hex"), plainFromTelegram.toHex())
            assertEquals(vector.getString("name"), vector.getString("expected_client_ciphertext_hex"), clientCiphertext.toHex())
        }
    }

    @Test
    fun matchesUpstreamStreamTransformationsForChunkedBuffers() {
        for (vector in loadCryptoVectors()) {
            val ctx = buildContext(vector)
            val chunkSizes = vector.getJSONArray("chunk_sizes").let { sizes ->
                List(sizes.length()) { index -> sizes.getInt(index) }
            }

            val plainFromClient = updateInChunks(
                vector.getString("sample_client_ciphertext_hex").hexToBytes(),
                chunkSizes,
            ) { ctx.decryptFromClient(it) }
            val telegramCiphertext = updateInChunks(plainFromClient, chunkSizes) { ctx.encryptToTelegram(it) }
            val plainFromTelegram = updateInChunks(
                vector.getString("sample_telegram_ciphertext_hex").hexToBytes(),
                chunkSizes,
            ) { ctx.decryptFromTelegram(it) }
            val clientCiphertext = updateInChunks(plainFromTelegram, chunkSizes) { ctx.encryptToClient(it) }

            assertEquals(vector.getString("name"), vector.getString("expected_plain_from_client_hex"), plainFromClient.toHex())
            assertEquals(vector.getString("name"), vector.getString("expected_telegram_ciphertext_hex"), telegramCiphertext.toHex())
            assertEquals(vector.getString("name"), vector.getString("expected_plain_from_telegram_hex"), plainFromTelegram.toHex())
            assertEquals(vector.getString("name"), vector.getString("expected_client_ciphertext_hex"), clientCiphertext.toHex())
        }
    }

    private fun buildContext(vector: org.json.JSONObject): CryptoContext = CryptoContext.build(
        clientDecPrekeyIv = vector.getString("client_dec_prekey_iv_hex").hexToBytes(),
        secret = vector.getString("secret_hex").hexToBytes(),
        relayInit = vector.getString("relay_init_hex").hexToBytes(),
    )

    private fun updateInChunks(input: ByteArray, chunkSizes: List<Int>, transform: (ByteArray) -> ByteArray): ByteArray {
        val output = ArrayList<Byte>()
        var offset = 0
        for (size in chunkSizes) {
            if (offset >= input.size) break
            val end = minOf(input.size, offset + size)
            output.addAll(transform(input.copyOfRange(offset, end)).asIterable())
            offset = end
        }
        if (offset < input.size) {
            output.addAll(transform(input.copyOfRange(offset, input.size)).asIterable())
        }
        return output.toByteArray()
    }
}
