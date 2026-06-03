package com.flowseal.tgwsandroid.proxy

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Splits encrypted Telegram MTProto transport stream chunks into encrypted packet frames.
 *
 * Mirrors upstream file `proxy/bridge.py`, class `MsgSplitter`. The splitter decrypts
 * only enough state to parse MTProto transport packet lengths while returning the original
 * ciphertext packet bytes, preserving AES-CTR and buffer state across calls.
 */
class MsgSplitter(relayInit: ByteArray, private val protoInt: Int) {
    private val decryptor: Cipher
    private var cipherBuffer = ByteArray(0)
    private var plainBuffer = ByteArray(0)
    private var disabled = false

    init {
        require(relayInit.size == MtprotoHandshake.HANDSHAKE_LEN) {
            "relayInit must be exactly ${MtprotoHandshake.HANDSHAKE_LEN} bytes"
        }
        decryptor = aesCtr(
            relayInit.copyOfRange(MtprotoHandshake.SKIP_LEN, MtprotoHandshake.SKIP_LEN + KEY_LEN),
            relayInit.copyOfRange(MtprotoHandshake.SKIP_LEN + KEY_LEN, MtprotoHandshake.SKIP_LEN + KEY_LEN + IV_LEN),
        )
        decryptor.updateCompat(ByteArray(ZERO_64_LEN))
    }

    fun split(chunk: ByteArray): List<ByteArray> {
        if (chunk.isEmpty()) return emptyList()
        if (disabled) return listOf(chunk)

        cipherBuffer += chunk
        plainBuffer += decryptor.updateCompat(chunk)

        val parts = mutableListOf<ByteArray>()
        var offset = 0
        val bufferLength = cipherBuffer.size
        while (offset < bufferLength) {
            val packetLength = nextPacketLength(offset, bufferLength - offset) ?: break
            if (packetLength <= 0) {
                parts.add(cipherBuffer.copyOfRange(offset, bufferLength))
                offset = bufferLength
                disabled = true
                break
            }
            parts.add(cipherBuffer.copyOfRange(offset, offset + packetLength))
            offset += packetLength
        }

        if (offset > 0) {
            cipherBuffer = cipherBuffer.copyOfRange(offset, cipherBuffer.size)
            plainBuffer = plainBuffer.copyOfRange(offset, plainBuffer.size)
        }
        return parts
    }

    fun flush(): List<ByteArray> {
        if (cipherBuffer.isEmpty()) return emptyList()
        val tail = cipherBuffer
        cipherBuffer = ByteArray(0)
        plainBuffer = ByteArray(0)
        return listOf(tail)
    }

    private fun nextPacketLength(offset: Int, available: Int): Int? {
        if (available <= 0) return null
        return when (protoInt) {
            PROTO_ABRIDGED_INT -> nextAbridgedLength(offset, available)
            PROTO_INTERMEDIATE_INT, PROTO_PADDED_INTERMEDIATE_INT -> nextIntermediateLength(offset, available)
            else -> 0
        }
    }

    private fun nextAbridgedLength(offset: Int, available: Int): Int? {
        val first = plainBuffer[offset].toInt() and 0xff
        val payloadLength: Int
        val headerLength: Int
        if (first == 0x7f || first == 0xff) {
            if (available < 4) return null
            payloadLength = littleEndianUInt24(plainBuffer, offset + 1) * 4
            headerLength = 4
        } else {
            payloadLength = (first and 0x7f) * 4
            headerLength = 1
        }
        if (payloadLength <= 0) return 0
        val packetLength = headerLength + payloadLength
        if (available < packetLength) return null
        return packetLength
    }

    private fun nextIntermediateLength(offset: Int, available: Int): Int? {
        if (available < 4) return null
        val payloadLength = littleEndianInt(plainBuffer, offset) and 0x7fffffff
        if (payloadLength <= 0) return 0
        val packetLength = 4 + payloadLength
        if (available < packetLength) return null
        return packetLength
    }

    private fun littleEndianUInt24(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16)

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            (bytes[offset + 3].toInt() shl 24)

    private fun aesCtr(key: ByteArray, iv: ByteArray): Cipher {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher
    }

    private fun Cipher.updateCompat(data: ByteArray): ByteArray = if (data.isEmpty()) {
        ByteArray(0)
    } else {
        update(data) ?: ByteArray(0)
    }

    companion object {
        private const val KEY_LEN = 32
        private const val IV_LEN = 16
        private const val ZERO_64_LEN = 64
        const val PROTO_ABRIDGED_INT: Int = -269488145
        const val PROTO_INTERMEDIATE_INT: Int = -286331154
        const val PROTO_PADDED_INTERMEDIATE_INT: Int = -572662307
    }
}
