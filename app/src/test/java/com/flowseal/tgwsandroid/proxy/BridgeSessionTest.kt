package com.flowseal.tgwsandroid.proxy

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class BridgeSessionTest {
    @Test
    fun clientToWebSocketReencryptsChunksAccordingToCryptoVectors() {
        val vector = firstCryptoVector()
        val chunkSizes = vector.chunkSizes()
        val clientCiphertext = vector.getString("sample_client_ciphertext_hex").hexToBytes()
        val clientChunks = clientCiphertext.chunkBy(chunkSizes)
        val client = FakeClientByteStream(reads = clientChunks, eofAfterReads = true)
        val webSocket = FakeWebSocketBinaryStream(blockOnEmptyRecv = true)
        val session = BridgeSession(client, webSocket, buildContext(vector))

        session.runBlocking()

        assertEquals(vector.getString("expected_telegram_ciphertext_hex"), webSocket.sentPayloads().joinToByteArray().toHex())
        assertEquals(clientCiphertext.size.toLong(), session.counters.bytesUp)
        assertEquals(clientChunks.size.toLong(), session.counters.packetsUp)
        assertTrue(client.closed)
        assertTrue(webSocket.closed)
    }

    @Test
    fun webSocketToClientReencryptsFramesAccordingToCryptoVectors() {
        val vector = firstCryptoVector()
        val telegramCiphertext = vector.getString("sample_telegram_ciphertext_hex").hexToBytes()
        val webSocket = FakeWebSocketBinaryStream(recvs = telegramCiphertext.chunkBy(vector.chunkSizes()), nullAfterRecvs = true)
        val client = FakeClientByteStream(blockOnRead = true)
        val session = BridgeSession(client, webSocket, buildContext(vector))

        session.runBlocking()

        assertEquals(vector.getString("expected_client_ciphertext_hex"), client.writes().joinToByteArray().toHex())
        assertEquals(telegramCiphertext.size.toLong(), session.counters.bytesDown)
        assertEquals(telegramCiphertext.chunkBy(vector.chunkSizes()).size.toLong(), session.counters.packetsDown)
        assertTrue(client.closed)
        assertTrue(webSocket.closed)
    }

    @Test
    fun clientToWebSocketWithMsgSplitterUsesSendBatchForMultipleSplitParts() {
        val vector = firstCryptoVector()
        val relayInit = vector.getString("relay_init_hex").hexToBytes()
        val telegramCiphertext =
            telegramCiphertextForPlainPackets(
                relayInit,
                packet(payload = byteArrayOf(1, 2, 3, 4)),
                packet(payload = byteArrayOf(5, 6, 7, 8)),
            )
        val clientCiphertext = clientCiphertextForTelegramCiphertext(vector, telegramCiphertext)
        val client = FakeClientByteStream(reads = listOf(clientCiphertext), eofAfterReads = true)
        val webSocket = FakeWebSocketBinaryStream(blockOnEmptyRecv = true)
        val splitter = MsgSplitter(relayInit, MsgSplitter.PROTO_INTERMEDIATE_INT)
        val session = BridgeSession(client, webSocket, buildContext(vector), splitter)

        session.runBlocking()

        val expectedParts = MsgSplitter(relayInit, MsgSplitter.PROTO_INTERMEDIATE_INT).split(telegramCiphertext)
        assertEquals(listOf("batch"), webSocket.sendKinds())
        assertEquals(expectedParts.map { it.toHex() }, webSocket.sentPayloads().map { it.toHex() })
    }

    @Test
    fun clientEofFlushesSplitterTailLikeUpstream() {
        val vector = firstCryptoVector()
        val relayInit = vector.getString("relay_init_hex").hexToBytes()
        val incompleteTelegramCiphertext =
            telegramCiphertextForPlainPackets(
                relayInit,
                packet(payload = byteArrayOf(9, 10, 11, 12)),
            ).copyOfRange(0, 5)
        val clientCiphertext = clientCiphertextForTelegramCiphertext(vector, incompleteTelegramCiphertext)
        val client = FakeClientByteStream(reads = listOf(clientCiphertext), eofAfterReads = true)
        val webSocket = FakeWebSocketBinaryStream(blockOnEmptyRecv = true)
        val session =
            BridgeSession(
                client = client,
                webSocket = webSocket,
                cryptoContext = buildContext(vector),
                splitter = MsgSplitter(relayInit, MsgSplitter.PROTO_INTERMEDIATE_INT),
            )

        session.runBlocking()

        assertEquals(listOf("send"), webSocket.sendKinds())
        assertArrayEquals(incompleteTelegramCiphertext, webSocket.sentPayloads().single())
    }

    @Test
    fun wsRecvNullClosesClientAndWebSocket() {
        val client = FakeClientByteStream(blockOnRead = true)
        val webSocket = FakeWebSocketBinaryStream(nullAfterRecvs = true)

        BridgeSession(client, webSocket, buildContext(firstCryptoVector())).runBlocking()

        assertTrue(client.closed)
        assertTrue(webSocket.closed)
    }

    @Test
    fun clientEofClosesClientAndWebSocket() {
        val client = FakeClientByteStream(eofAfterReads = true)
        val webSocket = FakeWebSocketBinaryStream(blockOnEmptyRecv = true)

        BridgeSession(client, webSocket, buildContext(firstCryptoVector())).runBlocking()

        assertTrue(client.closed)
        assertTrue(webSocket.closed)
    }

    @Test
    fun exceptionsInOneDirectionCloseBothSidesBestEffort() {
        val client = FakeClientByteStream(readException = RuntimeException("boom"))
        val webSocket = FakeWebSocketBinaryStream(blockOnEmptyRecv = true, closeException = RuntimeException("close boom"))

        BridgeSession(client, webSocket, buildContext(firstCryptoVector())).runBlocking()

        assertTrue(client.closed)
        assertTrue(webSocket.closed)
    }

    @Test
    fun runBlockingDoesNotStartOutboundWebSocketKeepaliveThread() {
        val client = FakeClientByteStream(eofAfterReads = true)
        val webSocket = FakeWebSocketBinaryStream(blockOnEmptyRecv = true)

        BridgeSession(client, webSocket, buildContext(firstCryptoVector())).runBlocking()

        assertTrue(
            Thread.getAllStackTraces().keys.none { it.name == "BridgeSession-websocket-keepalive" },
        )
    }

    private fun firstCryptoVector(): JSONObject = loadCryptoVectors().first()

    private fun buildContext(vector: JSONObject): CryptoContext =
        CryptoContext.build(
            clientDecPrekeyIv = vector.getString("client_dec_prekey_iv_hex").hexToBytes(),
            secret = vector.getString("secret_hex").hexToBytes(),
            relayInit = vector.getString("relay_init_hex").hexToBytes(),
        )

    private fun JSONObject.chunkSizes(): List<Int> =
        getJSONArray("chunk_sizes").let { sizes ->
            List(sizes.length()) { index -> sizes.getInt(index) }
        }

    private fun packet(payload: ByteArray): ByteArray = littleEndianInt(payload.size) + payload

    private fun telegramCiphertextForPlainPackets(
        relayInit: ByteArray,
        vararg packets: ByteArray,
    ): ByteArray {
        val cipher =
            aesCtr(
                relayInit.copyOfRange(MtprotoHandshake.SKIP_LEN, MtprotoHandshake.SKIP_LEN + 32),
                relayInit.copyOfRange(MtprotoHandshake.SKIP_LEN + 32, MtprotoHandshake.SKIP_LEN + 32 + 16),
            )
        cipher.update(ByteArray(64))
        return cipher.update(packets.toList().joinToByteArray())
    }

    private fun clientCiphertextForTelegramCiphertext(
        vector: JSONObject,
        telegramCiphertext: ByteArray,
    ): ByteArray {
        val relayInit = vector.getString("relay_init_hex").hexToBytes()
        val telegramDecryptor =
            aesCtr(
                relayInit.copyOfRange(MtprotoHandshake.SKIP_LEN, MtprotoHandshake.SKIP_LEN + 32),
                relayInit.copyOfRange(MtprotoHandshake.SKIP_LEN + 32, MtprotoHandshake.SKIP_LEN + 32 + 16),
            )
        telegramDecryptor.update(ByteArray(64))
        val plain = telegramDecryptor.update(telegramCiphertext)

        val clientDecPrekeyIv = vector.getString("client_dec_prekey_iv_hex").hexToBytes()
        val clientDecPrekey = clientDecPrekeyIv.copyOfRange(0, MtprotoHandshake.PREKEY_LEN)
        val clientDecIv = clientDecPrekeyIv.copyOfRange(MtprotoHandshake.PREKEY_LEN, clientDecPrekeyIv.size)
        val clientDecKey = MessageDigest.getInstance("SHA-256").digest(clientDecPrekey + vector.getString("secret_hex").hexToBytes())
        val clientCipher = aesCtr(clientDecKey, clientDecIv)
        clientCipher.update(ByteArray(64))
        return clientCipher.update(plain)
    }

    private fun aesCtr(
        key: ByteArray,
        iv: ByteArray,
    ): Cipher {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher
    }

    private fun littleEndianInt(value: Int): ByteArray =
        byteArrayOf(
            (value and 0xff).toByte(),
            ((value shr 8) and 0xff).toByte(),
            ((value shr 16) and 0xff).toByte(),
            ((value shr 24) and 0xff).toByte(),
        )

    private fun List<ByteArray>.joinToByteArray(): ByteArray = flatMap { it.asIterable() }.toByteArray()

    private fun ByteArray.chunkBy(sizes: List<Int>): List<ByteArray> {
        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        for (size in sizes) {
            if (offset >= this.size) break
            val end = minOf(this.size, offset + size)
            chunks.add(copyOfRange(offset, end))
            offset = end
        }
        if (offset < this.size) {
            chunks.add(copyOfRange(offset, this.size))
        }
        return chunks
    }
}

private class FakeClientByteStream(
    private val reads: List<ByteArray> = emptyList(),
    private val eofAfterReads: Boolean = false,
    private val blockOnRead: Boolean = false,
    private val readException: RuntimeException? = null,
) : ClientByteStream {
    private val lock = Object()
    private val written = mutableListOf<ByteArray>()
    private var readIndex = 0

    @Volatile var closed: Boolean = false

    override fun read(bufferSize: Int): ByteArray? {
        readException?.let { throw it }
        synchronized(lock) {
            if (readIndex < reads.size) return reads[readIndex++]
            if (eofAfterReads || !blockOnRead || closed) return null
            while (!closed) lock.wait(50)
            return null
        }
    }

    override fun write(data: ByteArray) {
        synchronized(lock) {
            written.add(data)
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            lock.notifyAll()
        }
    }

    fun writes(): List<ByteArray> = synchronized(lock) { written.toList() }
}

private class FakeWebSocketBinaryStream(
    private val recvs: List<ByteArray> = emptyList(),
    private val nullAfterRecvs: Boolean = false,
    private val blockOnEmptyRecv: Boolean = false,
    private val closeException: RuntimeException? = null,
) : WebSocketBinaryStream {
    private val lock = Object()
    private val sent = mutableListOf<Pair<String, ByteArray>>()
    private var recvIndex = 0

    @Volatile var closed: Boolean = false

    override fun send(data: ByteArray) {
        synchronized(lock) { sent.add("send" to data) }
    }

    override fun sendBatch(parts: List<ByteArray>) {
        synchronized(lock) { parts.forEach { sent.add("batch" to it) } }
    }

    override fun recv(): ByteArray? {
        synchronized(lock) {
            if (recvIndex < recvs.size) return recvs[recvIndex++]
            if (nullAfterRecvs || !blockOnEmptyRecv || closed) return null
            while (!closed) lock.wait(50)
            return null
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            lock.notifyAll()
        }
        closeException?.let { throw it }
    }

    fun sentPayloads(): List<ByteArray> = synchronized(lock) { sent.map { it.second } }

    fun sendKinds(): List<String> = synchronized(lock) { sent.map { it.first }.distinct() }
}
