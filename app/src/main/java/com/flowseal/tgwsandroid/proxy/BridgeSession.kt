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

    fun sendPing(payload: ByteArray = ByteArray(0))

    fun recv(): ByteArray?

    fun close()
}

/** Adapter that lets the bridge use the live RawWebSocket implementation without coupling tests to sockets. */
class RawWebSocketBinaryStream(
    private val rawWebSocket: RawWebSocket,
) : WebSocketBinaryStream {
    override fun send(data: ByteArray) = rawWebSocket.send(data)

    override fun sendBatch(parts: List<ByteArray>) = rawWebSocket.sendBatch(parts)

    override fun sendPing(payload: ByteArray) = rawWebSocket.sendPing(payload)

    override fun recv(): ByteArray? = rawWebSocket.recv()

    override fun close() = rawWebSocket.close()
}

/** Mutable byte/packet counters for a bridge session. */
class BridgeSessionCounters {
    private val bytesUpAtomic = AtomicLong(0)
    private val bytesDownAtomic = AtomicLong(0)
    private val packetsUpAtomic = AtomicLong(0)
    private val packetsDownAtomic = AtomicLong(0)
    private val closeReasonAtomic = AtomicReference<String?>(null)
    private val wsKeepalivePingsSentAtomic = AtomicLong(0)
    private val wsKeepaliveFailuresAtomic = AtomicLong(0)
    private val lastWsKeepaliveFailureAtomic = AtomicReference<String?>(null)

    val bytesUp: Long get() = bytesUpAtomic.get()
    val bytesDown: Long get() = bytesDownAtomic.get()
    val packetsUp: Long get() = packetsUpAtomic.get()
    val packetsDown: Long get() = packetsDownAtomic.get()
    val wsKeepalivePingsSent: Long get() = wsKeepalivePingsSentAtomic.get()
    val wsKeepaliveFailures: Long get() = wsKeepaliveFailuresAtomic.get()
    val lastWsKeepaliveFailure: String? get() = lastWsKeepaliveFailureAtomic.get()
    val closeReason: String? get() = closeReasonAtomic.get()

    fun recordUp(bytes: Int) {
        bytesUpAtomic.addAndGet(bytes.toLong())
        packetsUpAtomic.incrementAndGet()
    }

    fun recordDown(bytes: Int) {
        bytesDownAtomic.addAndGet(bytes.toLong())
        packetsDownAtomic.incrementAndGet()
    }

    fun recordWsKeepalivePing() {
        wsKeepalivePingsSentAtomic.incrementAndGet()
    }

    fun recordWsKeepaliveFailure(reason: String) {
        wsKeepaliveFailuresAtomic.incrementAndGet()
        lastWsKeepaliveFailureAtomic.set(reason)
        finish("websocket keepalive failed")
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
    private val wsKeepaliveIntervalSeconds: Double = 0.0,
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
        val keepalive = keepaliveThread(finished)

        clientToWebSocket.isDaemon = true
        webSocketToClient.isDaemon = true
        keepalive?.isDaemon = true
        clientToWebSocket.start()
        webSocketToClient.start()
        keepalive?.start()

        try {
            finished.await()
        } finally {
            closeBothBestEffort()
            keepalive?.interrupt()
            joinBestEffort(clientToWebSocket)
            joinBestEffort(webSocketToClient)
            keepalive?.let { joinBestEffort(it) }
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

    private fun keepaliveThread(finished: CountDownLatch): Thread? {
        val intervalMs = keepaliveIntervalMillis(wsKeepaliveIntervalSeconds)
        if (intervalMs <= 0L) return null
        return Thread({
            while (!closed.get()) {
                try {
                    Thread.sleep(intervalMs)
                    if (closed.get()) break
                    webSocket.sendPing()
                    counters.recordWsKeepalivePing()
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } catch (error: Throwable) {
                    counters.recordWsKeepaliveFailure(bridgeExceptionReason(error))
                    closeBothBestEffort()
                    finished.countDown()
                    break
                }
            }
        }, "BridgeSession-websocket-keepalive")
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
        private const val MIN_KEEPALIVE_INTERVAL_MS: Long = 1_000

        fun keepaliveIntervalMillis(seconds: Double): Long {
            if (!seconds.isFinite() || seconds <= 0.0) return 0L
            return maxOf((seconds * 1_000).toLong(), MIN_KEEPALIVE_INTERVAL_MS)
        }
    }
}
