package com.flowseal.tgwsandroid.proxy

import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android-independent idle direct WebSocket pool keyed by Telegram DC and media route.
 *
 * The pool mirrors upstream's lightweight `ws_pool`: startup warmup creates direct
 * RawWebSocket connections for configured DC redirects, client handlers take a
 * ready idle socket when available, and every take/miss schedules a bounded
 * background refill. Idle sockets are capped at a short age because Telegram
 * WebSocket servers and Android/mobile networks may close quiet pooled sockets
 * earlier than desktop/server environments. Only direct Telegram WebSocket routes are pooled; CF-proxy
 * fallback connections stay one-shot.
 */
class WebSocketPool(
    private val poolSize: Int,
    private val connector: RawWebSocketConnector,
    private val logger: ProxyLogger = ProxyLogger {},
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    private val path: String = ProxyServer.DEFAULT_WS_PATH,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val executor: ExecutorService = Executors.newFixedThreadPool(
        maxOf(1, minOf(DEFAULT_MAX_THREADS, maxOf(1, poolSize))),
        webSocketPoolThreadFactory(),
    ),
    private val ownsExecutor: Boolean = true,
    private val onRefillError: (Key, String, Throwable) -> Unit = { _, _, _ -> },
    private val onRefillAttempt: (Key, String) -> Unit = { _, _ -> },
    private val onRefillSuccess: (Key, String) -> Unit = { _, _ -> },
    private val onRefillCancelled: (Int) -> Unit = {},
    private val onResultDiscardedAfterRouteChange: () -> Unit = {},
) {
    private val lock = Any()
    private val enabled = AtomicBoolean(true)
    private val entries = mutableMapOf<Key, ArrayDeque<Entry>>()
    private val pendingRefills = mutableMapOf<Key, Int>()
    private val generation = AtomicInteger(0)

    data class Key(
        val dc: Int,
        val isMedia: Boolean,
    )

    /** Returns one non-expired idle WebSocket for [dc]/[isMedia], or null on miss. */
    fun get(
        dc: Int,
        isMedia: Boolean,
        targetHost: String,
        domains: List<String>,
    ): WebSocketBinaryStream? {
        if (poolSize <= 0 || !enabled.get()) return null
        val key = Key(dc, isMedia)
        var pooled: WebSocketBinaryStream? = null
        val expired = mutableListOf<WebSocketBinaryStream>()
        synchronized(lock) {
            val queue = entries[key]
            while (queue != null && queue.isNotEmpty() && pooled == null) {
                val entry = queue.removeFirst()
                if (isExpired(entry)) {
                    expired.add(entry.webSocket)
                } else {
                    pooled = entry.webSocket
                }
            }
            if (queue != null && queue.isEmpty()) entries.remove(key)
        }
        expired.forEach { closeBestEffort(it) }
        scheduleRefill(dc, isMedia, targetHost, domains, source = REFILL_SOURCE_ON_MISS)
        return pooled
    }

    /** Starts non-blocking warmup for all configured direct DC redirects and both media modes. */
    fun warmup(
        dcRedirects: Map<Int, String>,
        wsDomainsProvider: (dc: Int, isMedia: Boolean) -> List<String>,
    ) {
        if (poolSize <= 0 || dcRedirects.isEmpty()) return
        enabled.set(true)
        logger.log("WS pool warmup started for ${dcRedirects.size} DC(s)")
        for ((dc, targetHost) in dcRedirects) {
            scheduleRefill(dc, isMedia = false, targetHost, wsDomainsProvider(dc, false), source = REFILL_SOURCE_NORMAL)
            scheduleRefill(dc, isMedia = true, targetHost, wsDomainsProvider(dc, true), source = REFILL_SOURCE_NORMAL)
        }
    }

    /** Starts non-blocking warmup for one direct-capable DC and both media modes. */
    fun prewarmDc(
        dc: Int,
        targetHost: String,
        wsDomainsProvider: (dc: Int, isMedia: Boolean) -> List<String>,
    ) {
        if (poolSize <= 0) return
        enabled.set(true)
        scheduleRefill(dc, isMedia = false, targetHost, wsDomainsProvider(dc, false), source = REFILL_SOURCE_WAKE_PREWARM)
        scheduleRefill(dc, isMedia = true, targetHost, wsDomainsProvider(dc, true), source = REFILL_SOURCE_WAKE_PREWARM)
    }

    /**
     * Best-effort idle maintenance for one direct-capable DC.
     *
     * Unlike startup/wake warmup, this only tops each route key up to [minReady]
     * entries so background traffic stays conservative even when [poolSize] is
     * larger.
     */
    fun ensureMinReadyForDc(
        dc: Int,
        targetHost: String,
        minReady: Int,
        wsDomainsProvider: (dc: Int, isMedia: Boolean) -> List<String>,
    ) {
        if (poolSize <= 0 || minReady <= 0) return
        enabled.set(true)
        val desired = minOf(poolSize, minReady)
        scheduleRefill(dc, isMedia = false, targetHost, wsDomainsProvider(dc, false), desiredSize = desired, source = REFILL_SOURCE_MAINTENANCE)
        scheduleRefill(dc, isMedia = true, targetHost, wsDomainsProvider(dc, true), desiredSize = desired, source = REFILL_SOURCE_MAINTENANCE)
    }

    /** Disables future pool use and closes all currently idle sockets without shutting down the refill executor. */
    fun disableAndClear() {
        enabled.set(false)
        generation.incrementAndGet()
        clearIdle(countPendingAsCancelled = true)
    }

    /** Enables future pool use without starting warmup by itself. */
    fun enable() {
        enabled.set(true)
    }

    /** Closes all idle sockets and forgets pending bookkeeping. */
    fun reset() = clearIdle()

    /** Alias for [closeAll] for callers that treat the pool as a closeable lifecycle object. */
    fun close() = closeAll()

    /** Closes all currently idle sockets and forgets pending bookkeeping without shutting down the executor. */
    fun clearIdle() = clearIdle(countPendingAsCancelled = false)

    private fun clearIdle(countPendingAsCancelled: Boolean) {
        val idle = mutableListOf<WebSocketBinaryStream>()
        val cancelled: Int
        synchronized(lock) {
            for (queue in entries.values) {
                while (queue.isNotEmpty()) idle.add(queue.removeFirst().webSocket)
            }
            entries.clear()
            cancelled = if (countPendingAsCancelled) pendingRefills.values.sum() else 0
            pendingRefills.clear()
        }
        if (cancelled > 0) onRefillCancelled(cancelled)
        idle.forEach { closeBestEffort(it) }
    }

    /** Closes all currently idle sockets. In-flight refills are best-effort cancelled by shutdown on owned executors. */
    fun closeAll() {
        enabled.set(false)
        clearIdle()
        if (ownsExecutor) {
            executor.shutdownNow()
            try {
                executor.awaitTermination(SHUTDOWN_WAIT_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    fun readyCount(
        dc: Int,
        isMedia: Boolean,
    ): Int = synchronized(lock) { entries[Key(dc, isMedia)]?.size ?: 0 }

    fun readySnapshot(): Map<Key, Int> = synchronized(lock) { entries.mapValues { it.value.size } }

    fun pendingRefillsSnapshot(): Map<Key, Int> = synchronized(lock) { pendingRefills.toMap() }

    fun isEnabled(): Boolean = enabled.get()

    private fun scheduleRefill(
        dc: Int,
        isMedia: Boolean,
        targetHost: String,
        domains: List<String>,
        desiredSize: Int = poolSize,
        source: String = REFILL_SOURCE_NORMAL,
    ) {
        if (poolSize <= 0 || domains.isEmpty() || !enabled.get()) return
        val targetSize = desiredSize.coerceIn(0, poolSize)
        if (targetSize <= 0) return
        val refillGeneration = generation.get()
        val key = Key(dc, isMedia)
        val expired = mutableListOf<WebSocketBinaryStream>()
        val reservations = synchronized(lock) {
            expired.addAll(pruneExpiredLocked(key))
            val ready = entries[key]?.size ?: 0
            val pending = pendingRefills[key] ?: 0
            val needed = (targetSize - ready - pending).coerceAtLeast(0)
            if (needed > 0) pendingRefills[key] = pending + needed
            needed
        }
        expired.forEach { closeBestEffort(it) }
        repeat(reservations) {
            try {
                executor.execute { refillOne(key, targetHost, domains, refillGeneration, source) }
            } catch (_: RejectedExecutionException) {
                synchronized(lock) {
                    val remaining = (pendingRefills[key] ?: 1) - 1
                    if (remaining > 0) pendingRefills[key] = remaining else pendingRefills.remove(key)
                }
                onRefillCancelled(1)
            }
        }
    }

    private fun refillOne(
        key: Key,
        targetHost: String,
        domains: List<String>,
        refillGeneration: Int,
        source: String,
    ) {
        var connected: WebSocketBinaryStream? = null
        try {
            if (!enabled.get() || generation.get() != refillGeneration) return
            for (domain in domains) {
                if (!enabled.get() || generation.get() != refillGeneration) break
                try {
                    onRefillAttempt(key, source)
                    connected = connector.connect(targetHost, domain, path, RawWebSocket.DEFAULT_CONNECT_TIMEOUT_MS)
                    onRefillSuccess(key, source)
                    break
                } catch (error: Throwable) {
                    onRefillError(key, source, error)
                    logger.log("DC${key.dc} direct WS pool refill failed via $domain: ${failureDetail(error)}")
                }
            }
        } finally {
            val readyAfter: Int
            synchronized(lock) {
                val pending = (pendingRefills[key] ?: 1) - 1
                if (pending > 0) pendingRefills[key] = pending else pendingRefills.remove(key)
                if (connected != null && enabled.get() && generation.get() == refillGeneration) {
                    val queue = entries.getOrPut(key) { ArrayDeque() }
                    if (queue.size < poolSize) {
                        queue.addLast(Entry(connected!!, nowMs()))
                        connected = null
                    }
                }
                readyAfter = entries[key]?.size ?: 0
            }
            connected?.let {
                onResultDiscardedAfterRouteChange()
                closeBestEffort(it)
                logger.log("WS pool refill result discarded DC${key.dc} after route change")
            }
            logger.log("WS pool refilled DC${key.dc}: $readyAfter ready")
        }
    }

    private fun pruneExpiredLocked(key: Key): List<WebSocketBinaryStream> {
        val queue = entries[key] ?: return emptyList()
        val expired = mutableListOf<WebSocketBinaryStream>()
        val kept = ArrayDeque<Entry>()
        while (queue.isNotEmpty()) {
            val entry = queue.removeFirst()
            if (isExpired(entry)) expired.add(entry.webSocket) else kept.addLast(entry)
        }
        if (kept.isEmpty()) entries.remove(key) else entries[key] = kept
        return expired
    }

    private fun isExpired(entry: Entry): Boolean = nowMs() - entry.createdAtMs >= maxAgeMs

    private fun closeBestEffort(webSocket: WebSocketBinaryStream) {
        try {
            webSocket.close()
        } catch (_: Throwable) {
            // Pool cleanup is best-effort.
        }
    }

    private data class Entry(
        val webSocket: WebSocketBinaryStream,
        val createdAtMs: Long,
    )

    companion object {
        const val DEFAULT_MAX_AGE_MS: Long = 30_000L
        private const val DEFAULT_MAX_THREADS = 4
        const val REFILL_SOURCE_NORMAL = "normal"
        const val REFILL_SOURCE_ON_MISS = "on-miss"
        const val REFILL_SOURCE_MAINTENANCE = "maintenance"
        const val REFILL_SOURCE_WAKE_PREWARM = "wake-prewarm"
        private const val SHUTDOWN_WAIT_MS = 500L
        private fun failureDetail(error: Throwable): String =
            "${error::class.java.simpleName}: ${error.message ?: "no message"}"
    }
}


private val webSocketPoolThreadIds = AtomicInteger(0)

private fun webSocketPoolThreadFactory() = java.util.concurrent.ThreadFactory { runnable ->
    Thread(runnable, "WebSocketPool-${webSocketPoolThreadIds.incrementAndGet()}").also { it.isDaemon = true }
}
