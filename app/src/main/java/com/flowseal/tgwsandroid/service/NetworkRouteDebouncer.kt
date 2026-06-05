package com.flowseal.tgwsandroid.service

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class NetworkRouteDebouncer(
    private val scheduler: ScheduledExecutorService,
    private val delayMs: Long = DEFAULT_DELAY_MS,
    private val applyLatest: (String) -> Unit,
) {
    private val lock = Any()
    private var latestNetworkStatus: String = "unknown"
    private var pending: ScheduledFuture<*>? = null

    fun submit(networkStatus: String) {
        synchronized(lock) {
            latestNetworkStatus = networkStatus.ifBlank { "unknown" }
            pending?.cancel(false)
            pending = scheduler.schedule({ flush() }, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    fun cancel() {
        synchronized(lock) {
            pending?.cancel(false)
            pending = null
        }
    }

    private fun flush() {
        val status = synchronized(lock) {
            pending = null
            latestNetworkStatus
        }
        applyLatest(status)
    }

    companion object {
        const val DEFAULT_DELAY_MS = 2_500L
    }
}
