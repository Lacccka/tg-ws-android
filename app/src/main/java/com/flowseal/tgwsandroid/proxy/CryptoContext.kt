package com.flowseal.tgwsandroid.proxy

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Stateful AES-CTR crypto context for re-encrypting client and Telegram streams.
 *
 * Mirrors upstream file `proxy/tg_ws_proxy.py`, function `_build_crypto_ctx`,
 * and upstream file `proxy/bridge.py`, class `CryptoCtx`. This class preserves
 * cipher state across chunked calls just like upstream's stream encryptors.
 */
class CryptoContext private constructor(
    private val clientDecryptor: Cipher,
    private val clientEncryptor: Cipher,
    private val telegramEncryptor: Cipher,
    private val telegramDecryptor: Cipher,
) {
    fun decryptFromClient(data: ByteArray): ByteArray = clientDecryptor.updateCompat(data)

    fun encryptToTelegram(data: ByteArray): ByteArray = telegramEncryptor.updateCompat(data)

    fun decryptFromTelegram(data: ByteArray): ByteArray = telegramDecryptor.updateCompat(data)

    fun encryptToClient(data: ByteArray): ByteArray = clientEncryptor.updateCompat(data)

    companion object {
        private const val KEY_LEN = 32
        private const val ZERO_64_LEN = 64

        fun build(
            clientDecPrekeyIv: ByteArray,
            secret: ByteArray,
            relayInit: ByteArray,
        ): CryptoContext {
            require(clientDecPrekeyIv.size == MtprotoHandshake.PREKEY_LEN + MtprotoHandshake.IV_LEN) {
                "clientDecPrekeyIv must be exactly ${MtprotoHandshake.PREKEY_LEN + MtprotoHandshake.IV_LEN} bytes"
            }
            require(relayInit.size == MtprotoHandshake.HANDSHAKE_LEN) {
                "relayInit must be exactly ${MtprotoHandshake.HANDSHAKE_LEN} bytes"
            }

            val clientDecPrekey = clientDecPrekeyIv.copyOfRange(0, MtprotoHandshake.PREKEY_LEN)
            val clientDecIv = clientDecPrekeyIv.copyOfRange(MtprotoHandshake.PREKEY_LEN, clientDecPrekeyIv.size)
            val clientDecKey = sha256(clientDecPrekey + secret)

            val clientEncPrekeyIv = clientDecPrekeyIv.reversedArray()
            val clientEncKey = sha256(clientEncPrekeyIv.copyOfRange(0, MtprotoHandshake.PREKEY_LEN) + secret)
            val clientEncIv = clientEncPrekeyIv.copyOfRange(MtprotoHandshake.PREKEY_LEN, clientEncPrekeyIv.size)

            val clientDecryptor = aesCtr(clientDecKey, clientDecIv)
            val clientEncryptor = aesCtr(clientEncKey, clientEncIv)
            clientDecryptor.updateCompat(ByteArray(ZERO_64_LEN))

            val relayEncKey =
                relayInit.copyOfRange(
                    MtprotoHandshake.SKIP_LEN,
                    MtprotoHandshake.SKIP_LEN + MtprotoHandshake.PREKEY_LEN,
                )
            val relayEncIv =
                relayInit.copyOfRange(
                    MtprotoHandshake.SKIP_LEN + MtprotoHandshake.PREKEY_LEN,
                    MtprotoHandshake.SKIP_LEN + MtprotoHandshake.PREKEY_LEN + MtprotoHandshake.IV_LEN,
                )

            val relayDecPrekeyIv =
                relayInit
                    .copyOfRange(
                        MtprotoHandshake.SKIP_LEN,
                        MtprotoHandshake.SKIP_LEN + KEY_LEN + MtprotoHandshake.IV_LEN,
                    ).reversedArray()
            val relayDecKey = relayDecPrekeyIv.copyOfRange(0, KEY_LEN)
            val relayDecIv = relayDecPrekeyIv.copyOfRange(KEY_LEN, relayDecPrekeyIv.size)

            val telegramEncryptor = aesCtr(relayEncKey, relayEncIv)
            val telegramDecryptor = aesCtr(relayDecKey, relayDecIv)
            telegramEncryptor.updateCompat(ByteArray(ZERO_64_LEN))

            return CryptoContext(clientDecryptor, clientEncryptor, telegramEncryptor, telegramDecryptor)
        }

        private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

        private fun aesCtr(
            key: ByteArray,
            iv: ByteArray,
        ): Cipher {
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            return cipher
        }
    }
}

private fun Cipher.updateCompat(data: ByteArray): ByteArray =
    if (data.isEmpty()) {
        ByteArray(0)
    } else {
        update(data) ?: ByteArray(0)
    }
