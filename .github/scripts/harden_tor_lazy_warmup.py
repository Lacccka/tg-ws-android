from pathlib import Path

ROOT = Path('.')

def repl(path: str, old: str, new: str) -> None:
    p = ROOT / path
    text = p.read_text(encoding='utf-8')
    if text.count(old) != 1:
        raise SystemExit(f'{path}: anchor count={text.count(old)} for {old[:100]!r}')
    p.write_text(text.replace(old, new, 1), encoding='utf-8')

proxy = 'app/src/main/java/com/flowseal/tgwsandroid/proxy/ProxyServer.kt'

# Public diagnostics counters for bounded warmup waits.
repl(proxy,
'''    var torSnowflakeUnavailable: Long = 0
    var lastTorSnowflakeError: String? = null''',
'''    var torSnowflakeUnavailable: Long = 0
    var torSnowflakeWarmupWaits: Long = 0
    var torSnowflakeWarmupWaitSuccesses: Long = 0
    var torSnowflakeWarmupWaitTimeouts: Long = 0
    var torSnowflakeWarmupWaitSkippedLimit: Long = 0
    var torSnowflakeWarmupWaitersActive: Int = 0
    var lastTorSnowflakeError: String? = null''')

repl(proxy,
'''    private val torSnowflakeUnavailable = AtomicLong(0)
    private val lastTorSnowflakeError = AtomicReference<String?>(null)''',
'''    private val torSnowflakeUnavailable = AtomicLong(0)
    private val torSnowflakeWarmupWaits = AtomicLong(0)
    private val torSnowflakeWarmupWaitSuccesses = AtomicLong(0)
    private val torSnowflakeWarmupWaitTimeouts = AtomicLong(0)
    private val torSnowflakeWarmupWaitSkippedLimit = AtomicLong(0)
    private val torSnowflakeWarmupWaitersActive = AtomicInteger(0)
    private val lastTorSnowflakeError = AtomicReference<String?>(null)''')

repl(proxy,
'''        snapshot.torSnowflakeUnavailable = torSnowflakeUnavailable.get()
        snapshot.lastTorSnowflakeError = lastTorSnowflakeError.get()''',
'''        snapshot.torSnowflakeUnavailable = torSnowflakeUnavailable.get()
        snapshot.torSnowflakeWarmupWaits = torSnowflakeWarmupWaits.get()
        snapshot.torSnowflakeWarmupWaitSuccesses = torSnowflakeWarmupWaitSuccesses.get()
        snapshot.torSnowflakeWarmupWaitTimeouts = torSnowflakeWarmupWaitTimeouts.get()
        snapshot.torSnowflakeWarmupWaitSkippedLimit = torSnowflakeWarmupWaitSkippedLimit.get()
        snapshot.torSnowflakeWarmupWaitersActive = torSnowflakeWarmupWaitersActive.get()
        snapshot.lastTorSnowflakeError = lastTorSnowflakeError.get()''')

old_gate = '''        if (torSnowflakeReadyProvider?.invoke() == false) {
            val becameReady = torSnowflakeWaitUntilReady?.invoke(TOR_SNOWFLAKE_WARMING_CLIENT_WAIT_MS) == true
            if (!becameReady) {
                torSnowflakeUnavailable.incrementAndGet()
                val detail = "Tor/Snowflake warmup pending; no connector attempt"
                lastTorSnowflakeError.set(detail)
                val now = System.currentTimeMillis()
                val previous = lastTorSnowflakeWarmingLogAtMs.get()
                if (now - previous >= TOR_SNOWFLAKE_WARMING_LOG_THROTTLE_MS && lastTorSnowflakeWarmingLogAtMs.compareAndSet(previous, now)) {
                    logger.log("DC${parsed.dcId} $detail")
                }
                return false
            }
        }'''
new_gate = '''        if (torSnowflakeReadyProvider?.invoke() == false) {
            var becameReady = false
            var waitSkippedByLimit = false
            val waiter = torSnowflakeWaitUntilReady
            if (waiter != null && tryAcquireTorSnowflakeWarmupWaiter()) {
                torSnowflakeWarmupWaits.incrementAndGet()
                try {
                    becameReady = waiter(TOR_SNOWFLAKE_WARMING_CLIENT_WAIT_MS)
                    if (becameReady) {
                        torSnowflakeWarmupWaitSuccesses.incrementAndGet()
                    } else {
                        torSnowflakeWarmupWaitTimeouts.incrementAndGet()
                    }
                } finally {
                    torSnowflakeWarmupWaitersActive.decrementAndGet()
                }
            } else if (waiter != null) {
                waitSkippedByLimit = true
                torSnowflakeWarmupWaitSkippedLimit.incrementAndGet()
                // READY may have raced with the limiter check; do not reject a route that is already usable.
                becameReady = torSnowflakeReadyProvider?.invoke() == true
            }
            if (!becameReady) {
                torSnowflakeUnavailable.incrementAndGet()
                val detail = if (waitSkippedByLimit) {
                    "Tor/Snowflake warmup pending; waiter limit reached; no connector attempt"
                } else {
                    "Tor/Snowflake warmup pending; no connector attempt"
                }
                lastTorSnowflakeError.set(detail)
                val now = System.currentTimeMillis()
                val previous = lastTorSnowflakeWarmingLogAtMs.get()
                if (now - previous >= TOR_SNOWFLAKE_WARMING_LOG_THROTTLE_MS && lastTorSnowflakeWarmingLogAtMs.compareAndSet(previous, now)) {
                    logger.log("DC${parsed.dcId} $detail activeWaiters=${torSnowflakeWarmupWaitersActive.get()}")
                }
                return false
            }
        }'''
repl(proxy, old_gate, new_gate)

repl(proxy,
'''    private fun tryTorSnowflakeFallback(
''',
'''    private fun tryAcquireTorSnowflakeWarmupWaiter(): Boolean {
        while (true) {
            val current = torSnowflakeWarmupWaitersActive.get()
            if (current >= TOR_SNOWFLAKE_MAX_WARMING_WAITERS) return false
            if (torSnowflakeWarmupWaitersActive.compareAndSet(current, current + 1)) return true
        }
    }

    private fun tryTorSnowflakeFallback(
''')

repl(proxy,
'''        internal const val TOR_SNOWFLAKE_WARMING_CLIENT_WAIT_MS = 8_000L
        internal const val TOR_SNOWFLAKE_WARMING_LOG_THROTTLE_MS = 5_000L''',
'''        internal const val TOR_SNOWFLAKE_WARMING_CLIENT_WAIT_MS = 8_000L
        internal const val TOR_SNOWFLAKE_MAX_WARMING_WAITERS = 4
        internal const val TOR_SNOWFLAKE_WARMING_LOG_THROTTLE_MS = 5_000L''')

# Service should re-assert an already-triggered policy after a logical service restart;
# requestWarmup itself is idempotent.
service = 'app/src/main/java/com/flowseal/tgwsandroid/service/ProxyForegroundService.kt'
repl(service,
'''                    if (decision.newlyTriggered) {
                        val warmupReason = "${decision.reason}; DC$dcId media=$isMedia failures=${decision.recentFailureCount}"
                        State.addLog("Tor/Snowflake lazy warmup triggered: $warmupReason", LogSeverity.WARN, "network")
                        runtime?.requestWarmup(warmupReason)
                        State.updateTorFallbackRuntimeSnapshot(runCatching { runtime?.snapshot() }.getOrNull())
                    }''',
'''                    if (decision.triggered) {
                        val warmupReason = "${decision.reason}; DC$dcId media=$isMedia failures=${decision.recentFailureCount}"
                        if (decision.newlyTriggered) {
                            State.addLog("Tor/Snowflake lazy warmup triggered: $warmupReason", LogSeverity.WARN, "network")
                        }
                        runtime?.requestWarmup(warmupReason)
                        State.updateTorFallbackRuntimeSnapshot(runCatching { runtime?.snapshot() }.getOrNull())
                    }''')

repl(service,
'''            "torUnavailable=${stats.torSnowflakeUnavailable} " +
            "torRuntime=${compactTorRuntime(torRuntime)} " +''',
'''            "torUnavailable=${stats.torSnowflakeUnavailable} " +
            "torWarmupWait=${stats.torSnowflakeWarmupWaitSuccesses}/${stats.torSnowflakeWarmupWaits}/${stats.torSnowflakeWarmupWaitTimeouts} " +
            "torWarmupWaitSkippedLimit=${stats.torSnowflakeWarmupWaitSkippedLimit} active=${stats.torSnowflakeWarmupWaitersActive} " +
            "torRuntime=${compactTorRuntime(torRuntime)} " +''')

# Private runtime: idempotent requests and safe cancellation before native Tor has actually started.
runtime = 'app/src/privateSideload/java/com/flowseal/tgwsandroid/SnowflakeTorFallbackRuntime.kt'
repl(runtime,
'''        wanted.set(true)
        warmupReason.set(reason.ifBlank { "ordinary mobile routes degraded" })
        warmupRequestedAtMs.compareAndSet(0, System.currentTimeMillis())
        logger.log("Tor/Snowflake lazy warmup requested: ${warmupReason.get()}")
        ensureStarted()''',
'''        val newlyWanted = wanted.compareAndSet(false, true)
        if (newlyWanted || warmupReason.get() == null) {
            warmupReason.set(reason.ifBlank { "ordinary mobile routes degraded" })
            warmupRequestedAtMs.compareAndSet(0, System.currentTimeMillis())
        }
        if (newlyWanted) {
            logger.log("Tor/Snowflake lazy warmup requested: ${warmupReason.get()}")
        }
        ensureStarted()''')

repl(runtime,
'''        snowflakeTransportOwned.set(true)
        logger.log("Tor/Snowflake PT ready on 127.0.0.1:$ptPort using shared process controller")

        phase.set("tor_config")''',
'''        snowflakeTransportOwned.set(true)
        logger.log("Tor/Snowflake PT ready on 127.0.0.1:$ptPort using shared process controller")
        if (!mobileEligible.get() || !wanted.get()) {
            logger.log("Tor/Snowflake lazy warmup cancelled before libtor start because mobile fallback is no longer desired")
            releaseSnowflakeBeforeTorStart()
            phase.set("idle")
            return
        }

        phase.set("tor_config")''')

repl(runtime,
'''        torConnection = connection
        phase.set("tor_service")
        val bound = appContext.bindService''',
'''        torConnection = connection
        if (!mobileEligible.get() || !wanted.get()) {
            logger.log("Tor/Snowflake lazy warmup cancelled before TorService bind because mobile fallback is no longer desired")
            torConnection = null
            releaseSnowflakeBeforeTorStart()
            phase.set("idle")
            return
        }
        phase.set("tor_service")
        val bound = appContext.bindService''')

# Human-readable report exposes lazy warmup state too.
report = 'app/src/main/java/com/flowseal/tgwsandroid/service/DiagnosticReport.kt'
repl(report,
'''        appendLine("  phase: ${runtime?.phase ?: "unknown"}")
        appendLine("  lastError: ${runtime?.lastError ?: "none"}")''',
'''        appendLine("  phase: ${runtime?.phase ?: "unknown"}")
        appendLine("  lastError: ${runtime?.lastError ?: "none"}")
        appendLine("  warmupReason: ${runtime?.warmupReason ?: "none"}")
        appendLine("  warmupRequestedAtMs: ${runtime?.warmupRequestedAtMs?.toString() ?: "none"}")''')

# Existing readiness tests now assert the wait diagnostics.
test = 'app/src/test/java/com/flowseal/tgwsandroid/proxy/ProxyServerTest.kt'
repl(test,
'''        assertEquals(0L, proxy.stats().torSnowflakeAttempts)
        assertEquals(1L, proxy.stats().torSnowflakeUnavailable)
    }

    @Test
    fun serviceReadinessGateCanWaitForWarmTorWithoutTelegramReconnect()''',
'''        assertEquals(0L, proxy.stats().torSnowflakeAttempts)
        assertEquals(1L, proxy.stats().torSnowflakeUnavailable)
        assertEquals(1L, proxy.stats().torSnowflakeWarmupWaits)
        assertEquals(1L, proxy.stats().torSnowflakeWarmupWaitTimeouts)
        assertEquals(0, proxy.stats().torSnowflakeWarmupWaitersActive)
    }

    @Test
    fun serviceReadinessGateCanWaitForWarmTorWithoutTelegramReconnect()''')

repl(test,
'''        assertEquals(1L, proxy.stats().torSnowflakeSuccesses)
        assertEquals(0L, proxy.stats().torSnowflakeUnavailable)
    }

    @Test
    fun protoTagsMapToExpectedSplitterProtoInts()''',
'''        assertEquals(1L, proxy.stats().torSnowflakeSuccesses)
        assertEquals(0L, proxy.stats().torSnowflakeUnavailable)
        assertEquals(1L, proxy.stats().torSnowflakeWarmupWaits)
        assertEquals(1L, proxy.stats().torSnowflakeWarmupWaitSuccesses)
        assertEquals(0L, proxy.stats().torSnowflakeWarmupWaitTimeouts)
        assertEquals(0, proxy.stats().torSnowflakeWarmupWaitersActive)
    }

    @Test
    fun warmupWaitersAreBoundedDuringReconnectStorm() {
        val server = FakeTcpServerTransport()
        val entered = CountDownLatch(ProxyServer.TOR_SNOWFLAKE_MAX_WARMING_WAITERS)
        val release = CountDownLatch(1)
        val proxy = newProxy(
            server = server,
            torSnowflakeConnector = RecordingConnector(FakeWebSocketBinaryStream()),
            onOrdinaryRoutesExhausted = { _, _, _ -> },
            torSnowflakeReadyProvider = { false },
            torSnowflakeWaitUntilReady = {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                false
            },
            config = baseConfig().copy(
                routeMode = NetworkRouteMode.AUTO,
                networkStatus = "mobile",
                cfproxyEnabled = false,
                torSnowflakeFallbackEnabled = true,
            ),
        )

        proxy.start()
        repeat(ProxyServer.TOR_SNOWFLAKE_MAX_WARMING_WAITERS + 4) {
            server.enqueue(FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes()))
        }
        assertTrue("expected all warmup waiter slots to fill", entered.await(5, TimeUnit.SECONDS))
        waitUntil("overflow reconnects should skip warmup wait") {
            proxy.stats().torSnowflakeWarmupWaitSkippedLimit > 0L
        }
        val during = proxy.stats()
        assertEquals(ProxyServer.TOR_SNOWFLAKE_MAX_WARMING_WAITERS, during.torSnowflakeWarmupWaitersActive)
        release.countDown()
        waitUntil("warmup waiters should drain") { proxy.stats().torSnowflakeWarmupWaitersActive == 0 }
        proxy.stop()

        val stats = proxy.stats()
        assertTrue(stats.torSnowflakeWarmupWaitSkippedLimit > 0L)
        assertEquals(ProxyServer.TOR_SNOWFLAKE_MAX_WARMING_WAITERS.toLong(), stats.torSnowflakeWarmupWaits)
        assertEquals(0, stats.torSnowflakeWarmupWaitersActive)
    }

    @Test
    fun protoTagsMapToExpectedSplitterProtoInts()''')

print('lazy Tor hardening patch applied')
