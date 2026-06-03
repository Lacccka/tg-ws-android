package com.flowseal.tgwsandroid.proxy

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * Parser for Telegram MTProto obfuscated client handshakes.
 *
 * Mirrors upstream file `proxy/tg_ws_proxy.py`, function `_try_handshake`, for
 * the 64-byte handshake parsing path only. This class intentionally does not
 * implement TCP server, WebSocket, bridge, Android service, or networking code.
 */
object MtprotoHandshake {
    const val HANDSHAKE_LEN = 64
    const val SKIP_LEN = 8
    const val PREKEY_LEN = 32
    const val IV_LEN = 16
    const val PROTO_TAG_POS = 56
    const val DC_IDX_POS = 60

    private val PROTO_TAG_ABRIDGED = byteArrayOf(0xef.toByte(), 0xef.toByte(), 0xef.toByte(), 0xef.toByte())
    private val PROTO_TAG_INTERMEDIATE = byteArrayOf(0xee.toByte(), 0xee.toByte(), 0xee.toByte(), 0xee.toByte())
    private val PROTO_TAG_SECURE = byteArrayOf(0xdd.toByte(), 0xdd.toByte(), 0xdd.toByte(), 0xdd.toByte())
    private val VALID_PROTO_TAGS = listOf(PROTO_TAG_ABRIDGED, PROTO_TAG_INTERMEDIATE, PROTO_TAG_SECURE)

    /**
     * Parses a 64-byte MTProto obfuscated handshake using a 16-byte secret
     * encoded as exactly 32 hexadecimal characters.
     *
     * @return parsed fields, or `null` when the decrypted protocol tag is not
     * one of upstream's accepted MTProto transport tags.
     * @throws IllegalArgumentException when the handshake length or secret hex
     * is malformed.
     */
    fun parse(handshake: ByteArray, secretHex: String): Result? {
        require(handshake.size == HANDSHAKE_LEN) {
            "MTProto obfuscated handshake must be exactly $HANDSHAKE_LEN bytes"
        }
        require(secretHex.length == 32) {
            "MTProto secret must be exactly 32 hex characters"
        }

        val secret = secretHex.hexToBytes()
        require(secret.size == 16) {
            "MTProto secret must decode to exactly 16 bytes"
        }

        val clientDecPrekeyIv = handshake.copyOfRange(SKIP_LEN, SKIP_LEN + PREKEY_LEN + IV_LEN)
        val decPrekey = clientDecPrekeyIv.copyOfRange(0, PREKEY_LEN)
        val decIv = clientDecPrekeyIv.copyOfRange(PREKEY_LEN, PREKEY_LEN + IV_LEN)
        val decKey = sha256(decPrekey + secret)

        val decrypted = aesCtr(decKey, decIv, handshake)
        val protoTag = decrypted.copyOfRange(PROTO_TAG_POS, PROTO_TAG_POS + 4)
        if (VALID_PROTO_TAGS.none { it.contentEquals(protoTag) }) {
            return null
        }

        val dcIdx = littleEndianInt16(decrypted, DC_IDX_POS)
        return Result(
            dcId = abs(dcIdx),
            isMedia = dcIdx < 0,
            protoTag = protoTag,
            clientDecPrekeyIv = clientDecPrekeyIv,
        )
    }

    data class Result(
        val dcId: Int,
        val isMedia: Boolean,
        val protoTag: ByteArray,
        val clientDecPrekeyIv: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Result) return false
            return dcId == other.dcId &&
                isMedia == other.isMedia &&
                protoTag.contentEquals(other.protoTag) &&
                clientDecPrekeyIv.contentEquals(other.clientDecPrekeyIv)
        }

        override fun hashCode(): Int {
            var result = dcId
            result = 31 * result + isMedia.hashCode()
            result = 31 * result + protoTag.contentHashCode()
            result = 31 * result + clientDecPrekeyIv.contentHashCode()
            return result
        }
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun aesCtr(key: ByteArray, iv: ByteArray, input: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(input)
    }

    private fun littleEndianInt16(bytes: ByteArray, offset: Int): Int {
        val low = bytes[offset].toInt() and 0xff
        val high = bytes[offset + 1].toInt()
        return (high shl 8) or low
    }

    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0) { "hex string must contain an even number of characters" }
        return ByteArray(length / 2) { index ->
            val high = this[index * 2].hexValue()
            val low = this[index * 2 + 1].hexValue()
            ((high shl 4) or low).toByte()
        }
    }

    private fun Char.hexValue(): Int = when (this) {
        in '0'..'9' -> this - '0'
        in 'a'..'f' -> this - 'a' + 10
        in 'A'..'F' -> this - 'A' + 10
        else -> throw IllegalArgumentException("secret contains a non-hex character")
    }
}
