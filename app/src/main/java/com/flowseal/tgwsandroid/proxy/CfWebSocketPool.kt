package com.flowseal.tgwsandroid.proxy

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit

/** Conservative one-slot Cloudflare WebSocket prewarm pool for mobile cf_first demand. */
class CfWebSocketPool(
    private val connector: RawWebSocketConnector,
    private val cfDomainHealth: CfDomainHealth,
    private val logger: ProxyLogger = ProxyLogger {},
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val executor: ExecutorService = Executors.newFixedThreadPool(1, cfPoolThreadFactory()),
    private val ownsExecutor: Boolean = true,
    private val onAttempt: (WebSocketPool.Key, String) -> Unit = { _, _ -> },
    private val onSuccess: (WebSocketPool.Key, String) -> Unit = { _, _ -> },
    private val onError: (WebSocketPool.Key, String, Throwable) -> Unit = { _, _, _ -> },
) {
    private val lock = Any()
    private val entries = mutableMapOf<WebSocketPool.Key, Entry>()
    private val pending = mutableSetOf<WebSocketPool.Key>()

    fun get(dc: Int, isMedia: Boolean): WebSocketBinaryStream? {
        val key = WebSocketPool.Key(dc, isMedia)
        val expired = mutableListOf<WebSocketBinaryStream>()
        val hit = synchronized(lock) {
            val entry = entries.remove(key) ?: return@synchronized null
            if (nowMs() - entry.createdAtMs >= maxAgeMs) {
                expired.add(entry.webSocket)
                null
            } else {
                entry.webSocket
            }
        }
        expired.forEach { closeBestEffort(it) }
        return hit
    }

    fun scheduleRefill(dc: Int, isMedia: Boolean, baseDomain: String) {
        val key = WebSocketPool.Key(dc, isMedia)
        val accepted = synchronized(lock) {
            pruneExpiredLocked(key).forEach { closeBestEffort(it) }
            if (entries.containsKey(key) || pending.contains(key)) false else {
                pending.add(key)
                true
            }
        }
        if (!accepted) return
        try {
            executor.execute { refillOne(key, baseDomain) }
        } catch (_: RejectedExecutionException) {
            synchronized(lock) { pending.remove(key) }
        }
    }

    fun readySnapshot(): Map<WebSocketPool.Key, Int> = synchronized(lock) { entries.mapValues { 1 } }
    fun pendingSnapshot(): Map<WebSocketPool.Key, Int> = synchronized(lock) { pending.associateWith { 1 } }
    fun lastDomainSnapshot(): Map<WebSocketPool.Key, String> = synchronized(lock) { entries.mapValues { it.value.baseDomain } }

    fun closeAll() {
        val idle = synchronized(lock) {
            val sockets = entries.values.map { it.webSocket }
            entries.clear()
            pending.clear()
            sockets
        }
        idle.forEach { closeBestEffort(it) }
        if (ownsExecutor) {
            executor.shutdownNow()
            try { executor.awaitTermination(SHUTDOWN_WAIT_MS, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
    }

    private fun refillOne(key: WebSocketPool.Key, baseDomain: String) {
        var socket: WebSocketBinaryStream? = null
        var acquired = false
        try {
            when (cfDomainHealth.acquirePrewarmConnectDecision(key.dc, key.isMedia, baseDomain)) {
                CfConnectAcquireResult.ACQUIRED -> acquired = true
                else -> return
            }
            val target = CfProxyConnectTarget.forDcBaseDomain(key.dc, baseDomain)
            onAttempt(key, baseDomain)
            socket = connector.connect(target.targetHost, target.domain, target.path, target.timeoutMs)
            onSuccess(key, baseDomain)
            synchronized(lock) {
                if (!entries.containsKey(key)) {
                    entries[key] = Entry(socket!!, baseDomain, nowMs())
                    socket = null
                }
            }
            logger.log("DC${key.dc} CF pool refilled via $baseDomain")
        } catch (error: Throwable) {
            onError(key, baseDomain, error)
            logger.log("DC${key.dc} CF pool refill failed via $baseDomain: ${error::class.java.simpleName}: ${error.message ?: "no message"}")
        } finally {
            if (acquired) cfDomainHealth.releaseConnect(key.dc, key.isMedia, baseDomain)
            synchronized(lock) { pending.remove(key) }
            socket?.let { closeBestEffort(it) }
        }
    }

    private fun pruneExpiredLocked(key: WebSocketPool.Key): List<WebSocketBinaryStream> {
        val entry = entries[key] ?: return emptyList()
        return if (nowMs() - entry.createdAtMs >= maxAgeMs) {
            entries.remove(key)
            listOf(entry.webSocket)
        } else emptyList()
    }

    private fun closeBestEffort(webSocket: WebSocketBinaryStream) { try { webSocket.close() } catch (_: Throwable) {} }

    private data class Entry(val webSocket: WebSocketBinaryStream, val baseDomain: String, val createdAtMs: Long)

    companion object {
        const val DEFAULT_MAX_AGE_MS: Long = 30_000L
        private const val SHUTDOWN_WAIT_MS = 500L
    }
}

private val cfPoolThreadIds = AtomicInteger(0)
private fun cfPoolThreadFactory() = java.util.concurrent.ThreadFactory { runnable ->
    Thread(runnable, "CfWebSocketPool-${cfPoolThreadIds.incrementAndGet()}").also { it.isDaemon = true }
}