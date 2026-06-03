package com.flowseal.tgwsandroid.proxy

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Generates Telegram relay obfuscation init bytes.
 *
 * Mirrors upstream file `proxy/tg_ws_proxy.py`, function `_generate_relay_init`.
 * The optional [RandomBytes] injection exists for deterministic parity tests;
 * production callers use [SecureRandomBytes].
 */
object RelayInit {
    const val HANDSHAKE_LEN = MtprotoHandshake.HANDSHAKE_LEN
    const val SKIP_LEN = MtprotoHandshake.SKIP_LEN
    const val PREKEY_LEN = MtprotoHandshake.PREKEY_LEN
    const val IV_LEN = MtprotoHandshake.IV_LEN
    const val PROTO_TAG_POS = MtprotoHandshake.PROTO_TAG_POS
    const val DC_IDX_POS = MtprotoHandshake.DC_IDX_POS

    val PROTO_TAG_ABRIDGED: ByteArray = byteArrayOf(0xef.toByte(), 0xef.toByte(), 0xef.toByte(), 0xef.toByte())
    val PROTO_TAG_INTERMEDIATE: ByteArray = byteArrayOf(0xee.toByte(), 0xee.toByte(), 0xee.toByte(), 0xee.toByte())
    val PROTO_TAG_SECURE: ByteArray = byteArrayOf(0xdd.toByte(), 0xdd.toByte(), 0xdd.toByte(), 0xdd.toByte())

    private val RESERVED_FIRST_BYTES = setOf(0xef)
    private val RESERVED_STARTS =
        setOf(
            byteArrayOf(0x48, 0x45, 0x41, 0x44),
            byteArrayOf(0x50, 0x4f, 0x53, 0x54),
            byteArrayOf(0x47, 0x45, 0x54, 0x20),
            byteArrayOf(0xee.toByte(), 0xee.toByte(), 0xee.toByte(), 0xee.toByte()),
            byteArrayOf(0xdd.toByte(), 0xdd.toByte(), 0xdd.toByte(), 0xdd.toByte()),
            byteArrayOf(0x16, 0x03, 0x01, 0x02),
        )
    private val RESERVED_CONTINUE = byteArrayOf(0x00, 0x00, 0x00, 0x00)

    fun interface RandomBytes {
        fun nextBytes(length: Int): ByteArray
    }

    object SecureRandomBytes : RandomBytes {
        private val secureRandom = SecureRandom()

        override fun nextBytes(length: Int): ByteArray = ByteArray(length).also { secureRandom.nextBytes(it) }
    }

    fun generate(
        protoTag: ByteArray,
        dcIdx: Int,
        randomBytes: RandomBytes = SecureRandomBytes,
    ): ByteArray {
        require(protoTag.size == 4) { "protoTag must be exactly 4 bytes" }
        require(dcIdx in Short.MIN_VALUE..Short.MAX_VALUE) { "dcIdx must fit in a signed int16" }

        val rnd = generateAllowedPrefix(randomBytes)
        val encKey = rnd.copyOfRange(SKIP_LEN, SKIP_LEN + PREKEY_LEN)
        val encIv = rnd.copyOfRange(SKIP_LEN + PREKEY_LEN, SKIP_LEN + PREKEY_LEN + IV_LEN)
        val encryptedFull = aesCtr(encKey, encIv, rnd)
        val tailPlain = protoTag + littleEndianInt16(dcIdx) + randomBytes.nextBytes(2)

        val result = rnd.copyOf()
        for (offset in 0 until 8) {
            val sourceIndex = PROTO_TAG_POS + offset
            val keystreamByte = (encryptedFull[sourceIndex].toInt() xor rnd[sourceIndex].toInt()).toByte()
            result[sourceIndex] = (tailPlain[offset].toInt() xor keystreamByte.toInt()).toByte()
        }
        return result
    }

    private fun generateAllowedPrefix(randomBytes: RandomBytes): ByteArray {
        while (true) {
            val candidate = randomBytes.nextBytes(HANDSHAKE_LEN)
            require(candidate.size == HANDSHAKE_LEN) { "random provider returned ${candidate.size} bytes, expected $HANDSHAKE_LEN" }
            if ((candidate[0].toInt() and 0xff) in RESERVED_FIRST_BYTES) continue
            if (RESERVED_STARTS.any { candidate.startsWith(it) }) continue
            if (candidate.copyOfRange(4, 8).contentEquals(RESERVED_CONTINUE)) continue
            return candidate
        }
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

    private fun littleEndianInt16(value: Int): ByteArray =
        byteArrayOf(
            (value and 0xff).toByte(),
            ((value shr 8) and 0xff).toByte(),
        )

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (index in prefix.indices) {
            if (this[index] != prefix[index]) return false
        }
        return true
    }
}
