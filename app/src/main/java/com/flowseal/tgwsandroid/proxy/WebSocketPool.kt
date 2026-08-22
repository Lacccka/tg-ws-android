package com.flowseal.tgwsandroid.proxy

import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit

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
 *
 * Upstream v1.9 also suppresses repeated failed refill waves with exponential
 * backoff. This Android port applies the same policy per DC/media key while
 * preserving its parallel refill reservations: a backoff failure is counted only
 * when the whole outstanding refill wave for the key completes without any
 * successful connection.
 */
class WebSocketPool(
    private val poolSize: Int,
    private val connector: RawWebSocketConnector,
    private val logger: ProxyLogger = ProxyLogger {},
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    private val path: String = ProxyServer.DEFAULT_WS_PATH,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val refillBackoffInitialMs: Long = DEFAULT_REFILL_BACKOFF_INITIAL_MS,
    private val refillBackoffMaxMs: Long = DEFAULT_REFILL_BACKOFF_MAX_MS,
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
    private val shouldSkipTarget: (Key, String) -> Boolean = { _, _ -> false },
    private val onSkippedTarget: (Key, String, String) -> Unit = { _, _, _ -> },
    private val onRefillBackoffSuppressed: (Key, String, Long) -> Unit = { _, _, _ -> },
) {
    private val lock = Any()
    private val enabled = AtomicBoolean(true)
    private val entries = mutableMapOf<Key, ArrayDeque<Entry>>()
    private val pendingRefills = mutableMapOf<Key, Int>()
    private val refillWaveHadSuccess = mutableMapOf<Key, Boolean>()
    private val refillFailures = mutableMapOf<Key, Int>()
    private val refillAfterMs = mutableMapOf<Key, Long>()
    private val closedIdlePrunedByKey = mutableMapOf<Key, Long>()
    private val generation = AtomicInteger(0)

    data class Key(
        val dc: Int,
        val isMedia: Boolean,
    )

    /** Returns one non-expired, still-open idle WebSocket for [dc]/[isMedia], or null on miss. */
    fun get(
        dc: Int,
        isMedia: Boolean,
        targetHost: String,
        domains: List<String>,
    ): WebSocketBinaryStream? {
        if (poolSize <= 0 || !enabled.get()) return null
        val key = Key(dc, isMedia)
        if (shouldSkipTarget(key, targetHost)) {
            onSkippedTarget(key, targetHost, REFILL_SOURCE_ON_MISS)
            return null
        }
        var pooled: WebSocketBinaryStream? = null
        val stale = mutableListOf<WebSocketBinaryStream>()
        synchronized(lock) {
            val queue = entries[key]
            while (queue != null && queue.isNotEmpty() && pooled == null) {
                val entry = queue.removeFirst()
                val staleReason = staleReason(entry)
                if (staleReason != null) {
                    if (staleReason == STALE_REASON_CLOSED) incrementClosedIdlePrunedLocked(key)
                    stale.add(entry.webSocket)
                } else {
                    pooled = entry.webSocket
                }
            }
            if (queue != null && queue.isEmpty()) entries.remove(key)
        }
        stale.forEach { closeBestEffort(it) }
        if (pooled != null) reportSuccess(dc, isMedia)
        scheduleRefill(dc, isMedia, targetHost, domains, source = REFILL_SOURCE_ON_MISS)
        return pooled
    }

    /** Clears refill backoff after any confirmed usable direct route for this key. */
    fun reportSuccess(dc: Int, isMedia: Boolean) {
        val key = Key(dc, isMedia)
        synchronized(lock) {
            refillFailures.remove(key)
            refillAfterMs.remove(key)
        }
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

    /** Closes all idle sockets and forgets pending/backoff bookkeeping. */
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
            refillWaveHadSuccess.clear()
            refillFailures.clear()
            refillAfterMs.clear()
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

    fun refillFailuresSnapshot(): Map<Key, Int> = synchronized(lock) { refillFailures.toMap() }

    fun refillBackoffUntilSnapshot(): Map<Key, Long> = synchronized(lock) { refillAfterMs.toMap() }

    fun closedIdlePrunedSnapshot(): Map<Key, Long> = synchronized(lock) { closedIdlePrunedByKey.toMap() }

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
        val key = Key(dc, isMedia)
        if (shouldSkipTarget(key, targetHost)) {
            onSkippedTarget(key, targetHost, source)
            return
        }
        val targetSize = desiredSize.coerceIn(0, poolSize)
        if (targetSize <= 0) return
        val refillGeneration = generation.get()
        val stale = mutableListOf<WebSocketBinaryStream>()
        val plan = synchronized(lock) {
            stale.addAll(pruneStaleLocked(key))
            val now = nowMs()
            val refillAfter = refillAfterMs[key] ?: 0L
            if (now < refillAfter) {
                RefillPlan(reservations = 0, backoffRemainingMs = refillAfter - now)
            } else {
                val ready = entries[key]?.size ?: 0
                val pending = pendingRefills[key] ?: 0
                val needed = (targetSize - ready - pending).coerceAtLeast(0)
                if (needed > 0) {
                    if (pending == 0) refillWaveHadSuccess[key] = false
                    pendingRefills[key] = pending + needed
                }
                RefillPlan(reservations = needed, backoffRemainingMs = 0L)
            }
        }
        stale.forEach { closeBestEffort(it) }
        if (plan.backoffRemainingMs > 0L) {
            onRefillBackoffSuppressed(key, source, plan.backoffRemainingMs)
            return
        }
        repeat(plan.reservations) {
            try {
                executor.execute { refillOne(key, targetHost, domains, refillGeneration, source) }
            } catch (_: RejectedExecutionException) {
                synchronized(lock) {
                    val remaining = (pendingRefills[key] ?: 1) - 1
                    if (remaining > 0) {
                        pendingRefills[key] = remaining
                    } else {
                        pendingRefills.remove(key)
                        refillWaveHadSuccess.remove(key)
                    }
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
            var discardedAfterRouteChange = false
            var backoffDelayMs = 0L
            synchronized(lock) {
                val validGeneration = enabled.get() && generation.get() == refillGeneration
                if (connected != null && validGeneration) {
                    refillWaveHadSuccess[key] = true
                    val queue = entries.getOrPut(key) { ArrayDeque() }
                    if (queue.size < poolSize) {
                        queue.addLast(Entry(connected!!, nowMs()))
                        connected = null
                    }
                }

                val pending = (pendingRefills[key] ?: 1) - 1
                if (pending > 0) {
                    pendingRefills[key] = pending
                } else {
                    pendingRefills.remove(key)
                    val waveSucceeded = refillWaveHadSuccess.remove(key) == true
                    if (validGeneration) {
                        if (waveSucceeded) {
                            refillFailures.remove(key)
                            refillAfterMs.remove(key)
                        } else {
                            val failures = (refillFailures[key] ?: 0) + 1
                            refillFailures[key] = failures
                            backoffDelayMs = refillBackoffDelayMs(failures)
                            refillAfterMs[key] = nowMs() + backoffDelayMs
                        }
                    }
                }

                if (connected != null) {
                    discardedAfterRouteChange = true
                }
                readyAfter = entries[key]?.size ?: 0
            }
            connected?.let {
                if (discardedAfterRouteChange) onResultDiscardedAfterRouteChange()
                closeBestEffort(it)
                logger.log("WS pool refill result discarded DC${key.dc} after route change or full pool")
            }
            if (backoffDelayMs > 0L) {
                logger.log("WS pool refill failed for DC${key.dc}${if (key.isMedia) "m" else ""}, retry in ${backoffDelayMs / 1_000}s")
            }
            logger.log("WS pool refilled DC${key.dc}: $readyAfter ready")
        }
    }

    private fun pruneStaleLocked(key: Key): List<WebSocketBinaryStream> {
        val queue = entries[key] ?: return emptyList()
        val stale = mutableListOf<WebSocketBinaryStream>()
        val kept = ArrayDeque<Entry>()
        while (queue.isNotEmpty()) {
            val entry = queue.removeFirst()
            val reason = staleReason(entry)
            if (reason != null) {
                if (reason == STALE_REASON_CLOSED) incrementClosedIdlePrunedLocked(key)
                stale.add(entry.webSocket)
            } else {
                kept.addLast(entry)
            }
        }
        if (kept.isEmpty()) entries.remove(key) else entries[key] = kept
        return stale
    }

    private fun staleReason(entry: Entry): String? {
        if (nowMs() - entry.createdAtMs >= maxAgeMs) return STALE_REASON_AGE
        return if (isUsableForPool(entry.webSocket)) null else STALE_REASON_CLOSED
    }

    private fun isUsableForPool(webSocket: WebSocketBinaryStream): Boolean =
        try {
            webSocket.isUsableForPool()
        } catch (_: Throwable) {
            false
        }

    private fun incrementClosedIdlePrunedLocked(key: Key) {
        closedIdlePrunedByKey[key] = (closedIdlePrunedByKey[key] ?: 0L) + 1L
    }

    private fun refillBackoffDelayMs(failures: Int): Long {
        val exponent = minOf((failures - 1).coerceAtLeast(0), 6)
        var delay = refillBackoffInitialMs.coerceAtLeast(0L)
        repeat(exponent) {
            delay = if (delay >= refillBackoffMaxMs / 2L) refillBackoffMaxMs else delay * 2L
        }
        return minOf(delay, refillBackoffMaxMs.coerceAtLeast(0L))
    }

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

    private data class RefillPlan(
        val reservations: Int,
        val backoffRemainingMs: Long,
    )

    companion object {
        const val DEFAULT_MAX_AGE_MS: Long = 30_000L
        const val DEFAULT_REFILL_BACKOFF_INITIAL_MS: Long = 60_000L
        const val DEFAULT_REFILL_BACKOFF_MAX_MS: Long = 3_600_000L
        private const val DEFAULT_MAX_THREADS = 4
        const val REFILL_SOURCE_NORMAL = "normal"
        const val REFILL_SOURCE_ON_MISS = "on-miss"
        const val REFILL_SOURCE_MAINTENANCE = "maintenance"
        const val REFILL_SOURCE_WAKE_PREWARM = "wake-prewarm"
        private const val STALE_REASON_AGE = "age"
        private const val STALE_REASON_CLOSED = "closed"
        private const val SHUTDOWN_WAIT_MS = 500L
        private fun failureDetail(error: Throwable): String =
            "${error::class.java.simpleName}: ${error.message ?: "no message"}"
    }
}

private val webSocketPoolThreadIds = AtomicInteger(0)

private fun webSocketPoolThreadFactory() = java.util.concurrent.ThreadFactory { runnable ->
    Thread(runnable, "WebSocketPool-${webSocketPoolThreadIds.incrementAndGet()}").also { it.isDaemon = true }
}
