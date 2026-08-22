package com.flowseal.tgwsandroid.proxy

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Blocking client byte stream used by [BridgeSession]. */
interface ClientByteStream {
    fun read(bufferSize: Int = BridgeSession.DEFAULT_BUFFER_SIZE): ByteArray?

    fun write(data: ByteArray)

    fun close()
}

/** Blocking binary WebSocket stream used by [BridgeSession]. */
interface WebSocketBinaryStream {
    fun send(data: ByteArray)

    fun sendBatch(parts: List<ByteArray>)

    fun recv(): ByteArray?

    /**
     * Non-consuming liveness hint for idle-pool pruning.
     *
     * Test/fake streams default to usable so existing callers remain source-compatible.
     * Live RawWebSocket adapters override this with their underlying transport state.
     */
    fun isUsableForPool(): Boolean = true

    fun close()
}

/** Adapter that lets the bridge use the live RawWebSocket implementation without coupling tests to sockets. */
class RawWebSocketBinaryStream(
    private val rawWebSocket: RawWebSocket,
) : WebSocketBinaryStream {
    override fun send(data: ByteArray) = rawWebSocket.send(data)

    override fun sendBatch(parts: List<ByteArray>) = rawWebSocket.sendBatch(parts)

    override fun recv(): ByteArray? = rawWebSocket.recv()

    override fun isUsableForPool(): Boolean = rawWebSocket.isUsableForPool()

    override fun close() = rawWebSocket.close()
}

/** Mutable byte/packet counters for a bridge session. */
class BridgeSessionCounters {
    private val bytesUpAtomic = AtomicLong(0)
    private val bytesDownAtomic = AtomicLong(0)
    private val packetsUpAtomic = AtomicLong(0)
    private val packetsDownAtomic = AtomicLong(0)
    private val closeReasonAtomic = AtomicReference<String?>(null)

    val bytesUp: Long get() = bytesUpAtomic.get()
    val bytesDown: Long get() = bytesDownAtomic.get()
    val packetsUp: Long get() = packetsUpAtomic.get()
    val packetsDown: Long get() = packetsDownAtomic.get()
    val closeReason: String? get() = closeReasonAtomic.get()

    fun recordUp(bytes: Int) {
        bytesUpAtomic.addAndGet(bytes.toLong())
        packetsUpAtomic.incrementAndGet()
    }

    fun recordDown(bytes: Int) {
        bytesDownAtomic.addAndGet(bytes.toLong())
        packetsDownAtomic.incrementAndGet()
    }

    fun finish(reason: String) {
        closeReasonAtomic.compareAndSet(null, reason)
    }
}

internal fun bridgeExceptionReason(error: Throwable): String =
    "exception: ${error.javaClass.simpleName}: ${error.message ?: "no message"}"

/**
 * Blocking/threaded client TCP <-> Telegram WebSocket bridge with MTProto re-encryption.
 *
 * This is the reusable session-layer equivalent of upstream
 * `proxy/bridge.py::bridge_ws_reencrypt`: data read from the client is decrypted
 * with the client-side AES-CTR stream and re-encrypted for Telegram before being
 * sent as binary WebSocket frames, while frames received from Telegram are
 * decrypted and re-encrypted back to the client stream. The supplied
 * [CryptoContext] is intentionally shared for the whole session so AES-CTR state
 * is preserved across chunks.
 */
class BridgeSession(
    private val client: ClientByteStream,
    private val webSocket: WebSocketBinaryStream,
    private val cryptoContext: CryptoContext,
    private val splitter: MsgSplitter? = null,
    val counters: BridgeSessionCounters = BridgeSessionCounters(),
    private val bufferSize: Int = DEFAULT_BUFFER_SIZE,
) {
    private val closed = AtomicBoolean(false)

    /** Starts both bridge directions and blocks until either direction finishes, then closes both sides. */
    fun runBlocking() {
        val finished = CountDownLatch(1)
        val clientToWebSocket =
            Thread({
                try {
                    clientToWebSocketLoop()
                } finally {
                    finished.countDown()
                }
            }, "BridgeSession-client-to-websocket")
        val webSocketToClient =
            Thread({
                try {
                    webSocketToClientLoop()
                } finally {
                    finished.countDown()
                }
            }, "BridgeSession-websocket-to-client")

        clientToWebSocket.isDaemon = true
        webSocketToClient.isDaemon = true
        clientToWebSocket.start()
        webSocketToClient.start()

        try {
            finished.await()
        } finally {
            closeBothBestEffort()
            joinBestEffort(clientToWebSocket)
            joinBestEffort(webSocketToClient)
            counters.finish("completed")
        }
    }

    private fun clientToWebSocketLoop() {
        try {
            while (!closed.get()) {
                val chunk = client.read(bufferSize)
                if (chunk == null || chunk.isEmpty()) {
                    counters.finish("client closed")
                    flushSplitterTail()
                    break
                }

                counters.recordUp(chunk.size)
                val plain = cryptoContext.decryptFromClient(chunk)
                val telegramCiphertext = cryptoContext.encryptToTelegram(plain)
                sendTelegramCiphertext(telegramCiphertext)
            }
        } catch (error: Throwable) {
            counters.finish(bridgeExceptionReason(error))
            // Match upstream bridge behavior: direction errors end the session.
        }
    }

    private fun webSocketToClientLoop() {
        try {
            while (!closed.get()) {
                val frame = webSocket.recv()
                if (frame == null) {
                    counters.finish("websocket closed")
                    break
                }
                counters.recordDown(frame.size)
                val plain = cryptoContext.decryptFromTelegram(frame)
                val clientCiphertext = cryptoContext.encryptToClient(plain)
                client.write(clientCiphertext)
            }
        } catch (error: Throwable) {
            counters.finish(bridgeExceptionReason(error))
            // Match upstream bridge behavior: direction errors end the session.
        }
    }

    private fun sendTelegramCiphertext(ciphertext: ByteArray) {
        val activeSplitter = splitter
        if (activeSplitter == null) {
            webSocket.send(ciphertext)
            return
        }

        val parts = activeSplitter.split(ciphertext)
        when (parts.size) {
            0 -> Unit
            1 -> webSocket.send(parts[0])
            else -> webSocket.sendBatch(parts)
        }
    }

    private fun flushSplitterTail() {
        val tail = splitter?.flush().orEmpty()
        if (tail.isNotEmpty()) {
            webSocket.send(tail[0])
        }
    }

    private fun closeBothBestEffort() {
        if (!closed.compareAndSet(false, true)) return
        try {
            webSocket.close()
        } catch (_: Throwable) {
            // Best-effort close.
        }
        try {
            client.close()
        } catch (_: Throwable) {
            // Best-effort close.
        }
    }

    private fun joinBestEffort(thread: Thread) {
        if (Thread.currentThread() == thread) return
        try {
            thread.join(JOIN_TIMEOUT_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        const val DEFAULT_BUFFER_SIZE: Int = 65_536
        private const val JOIN_TIMEOUT_MS: Long = 1_000
    }
}
