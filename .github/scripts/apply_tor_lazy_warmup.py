from pathlib import Path

ROOT = Path('.')


def replace_once(path: str, old: str, new: str) -> None:
    p = ROOT / path
    text = p.read_text(encoding='utf-8')
    if old not in text:
        raise SystemExit(f'missing patch anchor in {path}: {old[:120]!r}')
    if text.count(old) != 1:
        raise SystemExit(f'non-unique patch anchor in {path}: count={text.count(old)}')
    p.write_text(text.replace(old, new, 1), encoding='utf-8')


# Pure policy: mobile route failures trigger Tor only after a short sustained burst.
policy_path = ROOT / 'app/src/main/java/com/flowseal/tgwsandroid/service/TorFallbackWarmupPolicy.kt'
policy_path.write_text('''package com.flowseal.tgwsandroid.service

import java.util.ArrayDeque

data class TorFallbackWarmupDecision(
    val triggered: Boolean,
    val newlyTriggered: Boolean,
    val recentFailureCount: Int,
    val reason: String? = null,
    val triggeredAtMs: Long? = null,
)

data class TorFallbackWarmupPolicySnapshot(
    val networkStatus: String = "unknown",
    val recentFailureCount: Int = 0,
    val triggered: Boolean = false,
    val reason: String? = null,
    val triggeredAtMs: Long? = null,
)

/**
 * Starts expensive Tor/Snowflake warmup only after ordinary mobile routing has
 * been exhausted repeatedly in a short window. A single transient CF/direct
 * failure must not pay the Tor battery/memory cost.
 */
class TorFallbackWarmupPolicy(
    private val failureThreshold: Int = DEFAULT_FAILURE_THRESHOLD,
    private val failureWindowMs: Long = DEFAULT_FAILURE_WINDOW_MS,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private val failureTimesMs = ArrayDeque<Long>()
    private var networkStatus: String = "unknown"
    private var triggered: Boolean = false
    private var triggerReason: String? = null
    private var triggeredAtMs: Long? = null

    init {
        require(failureThreshold >= 1) { "failureThreshold must be >= 1" }
        require(failureWindowMs > 0L) { "failureWindowMs must be > 0" }
    }

    @Synchronized
    fun onNetworkChanged(status: String) {
        val normalized = status.ifBlank { "unknown" }
        if (normalized.equals(networkStatus, ignoreCase = true)) return
        networkStatus = normalized
        if (!isMobile(normalized)) resetWindowLocked()
    }

    @Synchronized
    fun recordOrdinaryRouteExhausted(reason: String): TorFallbackWarmupDecision {
        val now = clockMs()
        if (!isMobile(networkStatus)) {
            return TorFallbackWarmupDecision(
                triggered = triggered,
                newlyTriggered = false,
                recentFailureCount = failureTimesMs.size,
                reason = triggerReason,
                triggeredAtMs = triggeredAtMs,
            )
        }

        pruneLocked(now)
        failureTimesMs.addLast(now)
        if (!triggered && failureTimesMs.size >= failureThreshold) {
            triggered = true
            triggerReason = reason.ifBlank { "ordinary routes repeatedly exhausted" }
            triggeredAtMs = now
            return TorFallbackWarmupDecision(
                triggered = true,
                newlyTriggered = true,
                recentFailureCount = failureTimesMs.size,
                reason = triggerReason,
                triggeredAtMs = triggeredAtMs,
            )
        }

        return TorFallbackWarmupDecision(
            triggered = triggered,
            newlyTriggered = false,
            recentFailureCount = failureTimesMs.size,
            reason = triggerReason,
            triggeredAtMs = triggeredAtMs,
        )
    }

    @Synchronized
    fun snapshot(): TorFallbackWarmupPolicySnapshot {
        val now = clockMs()
        pruneLocked(now)
        return TorFallbackWarmupPolicySnapshot(
            networkStatus = networkStatus,
            recentFailureCount = failureTimesMs.size,
            triggered = triggered,
            reason = triggerReason,
            triggeredAtMs = triggeredAtMs,
        )
    }

    @Synchronized
    fun reset() {
        resetWindowLocked()
    }

    private fun pruneLocked(now: Long) {
        val cutoff = now - failureWindowMs
        while (failureTimesMs.isNotEmpty() && failureTimesMs.first() < cutoff) {
            failureTimesMs.removeFirst()
        }
    }

    private fun resetWindowLocked() {
        failureTimesMs.clear()
        triggered = false
        triggerReason = null
        triggeredAtMs = null
    }

    private fun isMobile(value: String): Boolean =
        value.equals("mobile", ignoreCase = true) || value.equals("cellular", ignoreCase = true)

    companion object {
        const val DEFAULT_FAILURE_THRESHOLD = 3
        const val DEFAULT_FAILURE_WINDOW_MS = 10_000L
    }
}
''', encoding='utf-8')

policy_test = ROOT / 'app/src/test/java/com/flowseal/tgwsandroid/service/TorFallbackWarmupPolicyTest.kt'
policy_test.parent.mkdir(parents=True, exist_ok=True)
policy_test.write_text('''package com.flowseal.tgwsandroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TorFallbackWarmupPolicyTest {
    @Test
    fun healthyMobileDoesNotTriggerWithoutFailures() {
        var now = 1_000L
        val policy = TorFallbackWarmupPolicy(clockMs = { now })
        policy.onNetworkChanged("mobile")

        val snapshot = policy.snapshot()
        assertFalse(snapshot.triggered)
        assertEquals(0, snapshot.recentFailureCount)
        assertNull(snapshot.reason)
    }

    @Test
    fun threeOrdinaryRouteFailuresInsideWindowTriggerOnce() {
        var now = 1_000L
        val policy = TorFallbackWarmupPolicy(clockMs = { now })
        policy.onNetworkChanged("mobile")

        assertFalse(policy.recordOrdinaryRouteExhausted("first").triggered)
        now += 1_000L
        assertFalse(policy.recordOrdinaryRouteExhausted("second").triggered)
        now += 1_000L
        val third = policy.recordOrdinaryRouteExhausted("cf/direct exhausted for DC2")

        assertTrue(third.triggered)
        assertTrue(third.newlyTriggered)
        assertEquals(3, third.recentFailureCount)
        assertEquals("cf/direct exhausted for DC2", third.reason)

        now += 100L
        val fourth = policy.recordOrdinaryRouteExhausted("again")
        assertTrue(fourth.triggered)
        assertFalse(fourth.newlyTriggered)
    }

    @Test
    fun isolatedFailuresAgeOutAndDoNotTrigger() {
        var now = 1_000L
        val policy = TorFallbackWarmupPolicy(failureWindowMs = 10_000L, clockMs = { now })
        policy.onNetworkChanged("mobile")

        assertFalse(policy.recordOrdinaryRouteExhausted("one").triggered)
        now += 11_000L
        assertFalse(policy.recordOrdinaryRouteExhausted("two").triggered)
        now += 11_000L
        assertFalse(policy.recordOrdinaryRouteExhausted("three").triggered)
        assertEquals(1, policy.snapshot().recentFailureCount)
    }

    @Test
    fun leavingMobileResetsFailureBurst() {
        var now = 1_000L
        val policy = TorFallbackWarmupPolicy(clockMs = { now })
        policy.onNetworkChanged("mobile")
        policy.recordOrdinaryRouteExhausted("one")
        now += 100L
        policy.recordOrdinaryRouteExhausted("two")

        policy.onNetworkChanged("Wi-Fi")
        assertEquals(0, policy.snapshot().recentFailureCount)
        assertFalse(policy.snapshot().triggered)

        policy.onNetworkChanged("mobile")
        now += 100L
        assertFalse(policy.recordOrdinaryRouteExhausted("new mobile generation").triggered)
    }
}
''', encoding='utf-8')

# Runtime contract and diagnostics fields.
replace_once(
    'app/src/main/java/com/flowseal/tgwsandroid/service/TorFallbackRuntime.kt',
    '''    val bootstrapProgress: Int = 0,\n    val phase: String = "disabled",\n    val lastError: String? = null,\n)''',
    '''    val bootstrapProgress: Int = 0,\n    val phase: String = "disabled",\n    val lastError: String? = null,\n    val warmupReason: String? = null,\n    val warmupRequestedAtMs: Long? = null,\n)''',
)
replace_once(
    'app/src/main/java/com/flowseal/tgwsandroid/service/TorFallbackRuntime.kt',
    '''    fun onNetworkChanged(networkStatus: String)\n    fun snapshot(): TorFallbackRuntimeSnapshot\n    fun stop()''',
    '''    fun onNetworkChanged(networkStatus: String)\n    fun requestWarmup(reason: String)\n    fun awaitReady(timeoutMs: Long): Boolean\n    fun snapshot(): TorFallbackRuntimeSnapshot\n    fun stop()''',
)

# ProxyServer: report ordinary-route exhaustion to service and wait briefly for an already-starting Tor.
replace_once(
    'app/src/main/java/com/flowseal/tgwsandroid/proxy/ProxyServer.kt',
    '''    /** Separate connector so Tor success never contaminates direct/CF health or pooling. */\n    private val torSnowflakeConnector: RawWebSocketConnector? = null,\n    private val bridgeRunner: ProxyBridgeRunner''',
    '''    /** Separate connector so Tor success never contaminates direct/CF health or pooling. */\n    private val torSnowflakeConnector: RawWebSocketConnector? = null,\n    /** Called only after ordinary direct/CF/recovery paths are exhausted for a client. */\n    private val onOrdinaryRoutesExhausted: ((dcId: Int, isMedia: Boolean, reason: String) -> Unit)? = null,\n    /** Optional service-owned readiness gate; null preserves standalone/core behavior. */\n    private val torSnowflakeReadyProvider: (() -> Boolean)? = null,\n    /** Optional bounded wait so Telegram clients can survive an in-progress Tor bootstrap. */\n    private val torSnowflakeWaitUntilReady: ((timeoutMs: Long) -> Boolean)? = null,\n    private val bridgeRunner: ProxyBridgeRunner''',
)
replace_once(
    'app/src/main/java/com/flowseal/tgwsandroid/proxy/ProxyServer.kt',
    '''    private val lastTorSnowflakeError = AtomicReference<String?>(null)\n    private val lastTorSnowflakeTimeMs = AtomicLong(0)''',
    '''    private val lastTorSnowflakeError = AtomicReference<String?>(null)\n    private val lastTorSnowflakeTimeMs = AtomicLong(0)\n    private val lastTorSnowflakeWarmingLogAtMs = AtomicLong(0)''',
)
replace_once(
    'app/src/main/java/com/flowseal/tgwsandroid/proxy/ProxyServer.kt',
    '''                logger.log("DC${parsed.dcId} has no direct redirect; trying Tor/Snowflake with tunnel-side DNS")\n                if (tryTorSnowflakeFallback(client, parsed, null, relayInit, cryptoContext, splitter)) return true''',
    '''                notifyOrdinaryRoutesExhausted(parsed, "unknown DC ordinary routes exhausted after CF")\n                logger.log("DC${parsed.dcId} has no direct redirect; trying Tor/Snowflake with tunnel-side DNS")\n                if (tryTorSnowflakeFallback(client, parsed, null, relayInit, cryptoContext, splitter)) return true''',
)
replace_once(
    'app/src/main/java/com/flowseal/tgwsandroid/proxy/ProxyServer.kt',
    '''                if (tryEmergencyDirectFallback(client, parsed, targetHost, relayInit, cryptoContext, splitter, routeAttemptStartGeneration, cfFirstDirectFallbackAttempted)) return true\n                if (tryCfInflightWaitBeforeNoRoute(client, parsed, relayInit, cryptoContext, splitter)) return true\n                if (tryTorSnowflakeFallback(client, parsed, targetHost, relayInit, cryptoContext, splitter)) return true''',
    '''                if (tryEmergencyDirectFallback(client, parsed, targetHost, relayInit, cryptoContext, splitter, routeAttemptStartGeneration, cfFirstDirectFallbackAttempted)) return true\n                if (tryCfInflightWaitBeforeNoRoute(client, parsed, relayInit, cryptoContext, splitter)) return true\n                notifyOrdinaryRoutesExhausted(parsed, "CF-first ordinary routes exhausted")\n                if (tryTorSnowflakeFallback(client, parsed, targetHost, relayInit, cryptoContext, splitter)) return true''',
)
replace_once(
    'app/src/main/java/com/flowseal/tgwsandroid/proxy/ProxyServer.kt',
    '''                if (tryDirectRoute(client, parsed, targetHost, relayInit, cryptoContext, splitter, usePool = true, timeoutMs = RawWebSocket.DEFAULT_CONNECT_TIMEOUT_MS)) return true\n                if (config.cfproxyEnabled && tryCfProxyFallback(client, parsed, relayInit, cryptoContext, splitter)) return true\n                if (tryTorSnowflakeFallback(client, parsed, targetHost, relayInit, cryptoContext, splitter)) return true''',
    '''                if (tryDirectRoute(client, parsed, targetHost, relayInit, cryptoContext, splitter, usePool = true, timeoutMs = RawWebSocket.DEFAULT_CONNECT_TIMEOUT_MS)) return true\n                if (config.cfproxyEnabled && tryCfProxyFallback(client, parsed, relayInit, cryptoContext, splitter)) return true\n                notifyOrdinaryRoutesExhausted(parsed, "direct/CF ordinary routes exhausted")\n                if (tryTorSnowflakeFallback(client, parsed, targetHost, relayInit, cryptoContext, splitter)) return true''',
)
replace_once(
    'app/src/main/java/com/flowseal/tgwsandroid/proxy/ProxyServer.kt',
    '''\n\n    private fun tryTorSnowflakeFallback(\n''',
    '''\n\n    private fun notifyOrdinaryRoutesExhausted(parsed: MtprotoHandshake.Result, reason: String) {\n        val callback = onOrdinaryRoutesExhausted ?: return\n        runCatching { callback(parsed.dcId, parsed.isMedia, reason) }\n            .onFailure { logger.log("DC${parsed.dcId} Tor warmup signal callback failed: ${failureDetail(it)}") }\n    }\n\n    private fun tryTorSnowflakeFallback(\n''',
)
replace_once(
    'app/src/main/java/com/flowseal/tgwsandroid/proxy/ProxyServer.kt',
    '''        if (!config.torSnowflakeFallbackEnabled || !isMobile(currentNetworkStatus)) return false\n        val connector = torSnowflakeConnector ?: return false\n        for (domain in wsDomains(parsed.dcId, parsed.isMedia)) {''',
    '''        if (!config.torSnowflakeFallbackEnabled || !isMobile(currentNetworkStatus)) return false\n        val connector = torSnowflakeConnector ?: return false\n        if (torSnowflakeReadyProvider?.invoke() == false) {\n            val becameReady = torSnowflakeWaitUntilReady?.invoke(TOR_SNOWFLAKE_WARMING_CLIENT_WAIT_MS) == true\n            if (!becameReady) {\n                torSnowflakeUnavailable.incrementAndGet()\n                val detail = "Tor/Snowflake warmup pending; no connector attempt"\n                lastTorSnowflakeError.set(detail)\n                val now = System.currentTimeMillis()\n                val previous = lastTorSnowflakeWarmingLogAtMs.get()\n                if (now - previous >= TOR_SNOWFLAKE_WARMING_LOG_THROTTLE_MS && lastTorSnowflakeWarmingLogAtMs.compareAndSet(previous, now)) {\n                    logger.log("DC${parsed.dcId} $detail")\n                }\n                return false\n            }\n        }\n        for (domain in wsDomains(parsed.dcId, parsed.isMedia)) {''',
)
# Insert constants into ProxyServer companion using a stable existing constant nearby.
replace_once(
    'app/src/main/java/com/flowseal/tgwsandroid/proxy/ProxyServer.kt',
    '''        const val DEFAULT_WS_PATH = "/apiws"''',
    '''        const val DEFAULT_WS_PATH = "/apiws"\n        internal const val TOR_SNOWFLAKE_WARMING_CLIENT_WAIT_MS = 8_000L\n        internal const val TOR_SNOWFLAKE_WARMING_LOG_THROTTLE_MS = 5_000L''',
)

# Service: policy owns cold->warming decision; private forced test can still warm immediately.
replace_once(
    'app/src/main/java/com/flowseal/tgwsandroid/service/ProxyForegroundService.kt',
    '''    private var proxyServer: ProxyServer? = null\n    private var torFallbackRuntime: TorFallbackRuntime? = null''',
    '''    private var proxyServer: ProxyServer? = null\n    private var torFallbackRuntime: TorFallbackRuntime? = null\n    private val torFallbackWarmupPolicy = TorFallbackWarmupPolicy()''',
)
replace_once(
    'app/src/main/java/com/flowseal/tgwsandroid/service/ProxyForegroundService.kt',
    '''            val runtime = TorFallbackRuntimeLoader.create(applicationContext, logger)\n            torFallbackRuntime = runtime\n            runtime?.onNetworkChanged(State.networkStatus)\n            State.updateTorFallbackRuntimeSnapshot(runCatching { runtime?.snapshot() }.getOrNull())''',
    '''            val runtime = TorFallbackRuntimeLoader.create(applicationContext, logger)\n            torFallbackRuntime = runtime\n            torFallbackWarmupPolicy.onNetworkChanged(State.networkStatus)\n            runtime?.onNetworkChanged(State.networkStatus)\n            if (forceOrdinaryRouteFailureForTorTest) {\n                runtime?.requestWarmup("private production fallback test")\n            }\n            State.updateTorFallbackRuntimeSnapshot(runCatching { runtime?.snapshot() }.getOrNull())''',
)
replace_once(
    'app/src/main/java/com/flowseal/tgwsandroid/service/ProxyForegroundService.kt',
    '''                webSocketConnector = ordinaryWebSocketConnectorForCurrentRun(),\n                torSnowflakeConnector = runtime?.connector,\n                logger = logger,''',
    '''                webSocketConnector = ordinaryWebSocketConnectorForCurrentRun(),\n                torSnowflakeConnector = runtime?.connector,\n                onOrdinaryRoutesExhausted = { dcId, isMedia, reason ->\n                    val decision = torFallbackWarmupPolicy.recordOrdinaryRouteExhausted(reason)\n                    if (decision.newlyTriggered) {\n                        val warmupReason = "${decision.reason}; DC$dcId media=$isMedia failures=${decision.recentFailureCount}"\n                        State.addLog("Tor/Snowflake lazy warmup triggered: $warmupReason", LogSeverity.WARN, "network")\n                        runtime?.requestWarmup(warmupReason)\n                        State.updateTorFallbackRuntimeSnapshot(runCatching { runtime?.snapshot() }.getOrNull())\n                    }\n                },\n                torSnowflakeReadyProvider = { runCatching { runtime?.snapshot()?.ready == true }.getOrDefault(false) },\n                torSnowflakeWaitUntilReady = { timeoutMs -> runCatching { runtime?.awaitReady(timeoutMs) == true }.getOrDefault(false) },\n                logger = logger,''',
)
replace_once(
    'app/src/main/java/com/flowseal/tgwsandroid/service/ProxyForegroundService.kt',
    '''    private fun applyRouteForNetwork(networkStatus: String, immediate: Boolean = false) {\n        torFallbackRuntime?.onNetworkChanged(networkStatus)''',
    '''    private fun applyRouteForNetwork(networkStatus: String, immediate: Boolean = false) {\n        torFallbackWarmupPolicy.onNetworkChanged(networkStatus)\n        torFallbackRuntime?.onNetworkChanged(networkStatus)''',
)
replace_once(
    'app/src/main/java/com/flowseal/tgwsandroid/service/ProxyForegroundService.kt',
    '''            val line = "watchdog: running=${server?.isRunning == true} ${compactStats(stats, torRuntimeSnapshot)} " +\n                "torTestOverride=${State.isTorFallbackTestOverrideActive()} " +''',
    '''            val line = "watchdog: running=${server?.isRunning == true} ${compactStats(stats, torRuntimeSnapshot)} " +\n                "torWarmupPolicy=${compactTorWarmupPolicy(torFallbackWarmupPolicy.snapshot())} " +\n                "torTestOverride=${State.isTorFallbackTestOverrideActive()} " +''',
)
replace_once(
    'app/src/main/java/com/flowseal/tgwsandroid/service/ProxyForegroundService.kt',
    '''            "bootstrap=${snapshot.bootstrapProgress},phase=${snapshot.phase},lastError=${snapshot.lastError ?: "none"}"\n    }\n\n    private fun compactMap''',
    '''            "bootstrap=${snapshot.bootstrapProgress},phase=${snapshot.phase},lastError=${snapshot.lastError ?: "none"}," +\n            "warmupReason=${snapshot.warmupReason ?: "none"},warmupAt=${snapshot.warmupRequestedAtMs ?: 0L}"\n    }\n\n    private fun compactTorWarmupPolicy(snapshot: TorFallbackWarmupPolicySnapshot): String =\n        "network=${snapshot.networkStatus},failures=${snapshot.recentFailureCount},triggered=${snapshot.triggered}," +\n            "reason=${snapshot.reason ?: "none"},triggeredAt=${snapshot.triggeredAtMs ?: 0L}"\n\n    private fun compactMap''',
)

# Private runtime becomes lazy: mobile eligibility alone no longer starts Tor.
replace_once(
    'app/src/privateSideload/java/com/flowseal/tgwsandroid/SnowflakeTorFallbackRuntime.kt',
    '''import java.util.concurrent.atomic.AtomicInteger\nimport java.util.concurrent.atomic.AtomicReference''',
    '''import java.util.concurrent.atomic.AtomicInteger\nimport java.util.concurrent.atomic.AtomicLong\nimport java.util.concurrent.atomic.AtomicReference''',
)
replace_once(
    'app/src/privateSideload/java/com/flowseal/tgwsandroid/SnowflakeTorFallbackRuntime.kt',
    '''    private val wanted = AtomicBoolean(false)\n    private val processRestartRequired = AtomicBoolean(false)\n    private val progress = AtomicInteger(0)\n    private val phase = AtomicReference("idle")\n    private val lastError = AtomicReference<String?>(null)\n    private val readyConnector = AtomicReference<RawWebSocketConnector?>(null)\n    private val lifecycleLock = Any()''',
    '''    private val wanted = AtomicBoolean(false)\n    private val mobileEligible = AtomicBoolean(false)\n    private val processRestartRequired = AtomicBoolean(false)\n    private val progress = AtomicInteger(0)\n    private val phase = AtomicReference("idle")\n    private val lastError = AtomicReference<String?>(null)\n    private val warmupReason = AtomicReference<String?>(null)\n    private val warmupRequestedAtMs = AtomicLong(0)\n    private val readyConnector = AtomicReference<RawWebSocketConnector?>(null)\n    private val lifecycleLock = Any()\n    private val readyMonitor = Object()''',
)
replace_once(
    'app/src/privateSideload/java/com/flowseal/tgwsandroid/SnowflakeTorFallbackRuntime.kt',
    '''    override fun onNetworkChanged(networkStatus: String) {\n        val shouldRun = isMobile(networkStatus)\n        val changed = wanted.getAndSet(shouldRun) != shouldRun\n        if (shouldRun) {\n            ensureStarted()\n        } else if (changed && hasStartedProcessEngine()) {\n            logger.log(\n                "Tor/Snowflake route not desired on network=$networkStatus; keeping native Tor warm for process lifetime",\n            )\n        }\n    }\n\n    override fun snapshot(): TorFallbackRuntimeSnapshot = TorFallbackRuntimeSnapshot(''',
    '''    override fun onNetworkChanged(networkStatus: String) {\n        val eligible = isMobile(networkStatus)\n        val changed = mobileEligible.getAndSet(eligible) != eligible\n        if (!eligible) {\n            wanted.set(false)\n            warmupReason.set(null)\n            warmupRequestedAtMs.set(0)\n            notifyReadyWaiters()\n            if (changed && hasStartedProcessEngine()) {\n                logger.log(\n                    "Tor/Snowflake fallback not eligible on network=$networkStatus; keeping native Tor warm for process lifetime",\n                )\n            }\n        }\n    }\n\n    override fun requestWarmup(reason: String) {\n        if (!mobileEligible.get()) {\n            logger.log("Tor/Snowflake lazy warmup ignored because current network is not mobile")\n            return\n        }\n        wanted.set(true)\n        warmupReason.set(reason.ifBlank { "ordinary mobile routes degraded" })\n        warmupRequestedAtMs.compareAndSet(0, System.currentTimeMillis())\n        logger.log("Tor/Snowflake lazy warmup requested: ${warmupReason.get()}")\n        ensureStarted()\n    }\n\n    override fun awaitReady(timeoutMs: Long): Boolean {\n        if (readyConnector.get() != null && !processRestartRequired.get()) return true\n        if (!wanted.get() || processRestartRequired.get() || timeoutMs <= 0L) return false\n        val deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)\n        synchronized(readyMonitor) {\n            while (readyConnector.get() == null && wanted.get() && !processRestartRequired.get()) {\n                val remainingNs = deadlineNs - System.nanoTime()\n                if (remainingNs <= 0L) break\n                val waitMs = TimeUnit.NANOSECONDS.toMillis(remainingNs).coerceAtLeast(1L)\n                try {\n                    readyMonitor.wait(waitMs)\n                } catch (_: InterruptedException) {\n                    Thread.currentThread().interrupt()\n                    return false\n                }\n            }\n        }\n        return readyConnector.get() != null && !processRestartRequired.get()\n    }\n\n    override fun snapshot(): TorFallbackRuntimeSnapshot = TorFallbackRuntimeSnapshot(''',
)
replace_once(
    'app/src/privateSideload/java/com/flowseal/tgwsandroid/SnowflakeTorFallbackRuntime.kt',
    '''        phase = phase.get(),\n        lastError = lastError.get(),\n    )''',
    '''        phase = phase.get(),\n        lastError = lastError.get(),\n        warmupReason = warmupReason.get(),\n        warmupRequestedAtMs = warmupRequestedAtMs.get().takeIf { it > 0L },\n    )''',
)
replace_once(
    'app/src/privateSideload/java/com/flowseal/tgwsandroid/SnowflakeTorFallbackRuntime.kt',
    '''    override fun stop() {\n        wanted.set(false)\n        if (hasStartedProcessEngine()) {''',
    '''    override fun stop() {\n        wanted.set(false)\n        warmupReason.set(null)\n        warmupRequestedAtMs.set(0)\n        notifyReadyWaiters()\n        if (hasStartedProcessEngine()) {''',
)
replace_once(
    'app/src/privateSideload/java/com/flowseal/tgwsandroid/SnowflakeTorFallbackRuntime.kt',
    '''            if (!wanted.get() || processRestartRequired.get() || readyConnector.get() != null || task?.isDone == false) return''',
    '''            if (!mobileEligible.get() || !wanted.get() || processRestartRequired.get() || readyConnector.get() != null || task?.isDone == false) return''',
)
replace_once(
    'app/src/privateSideload/java/com/flowseal/tgwsandroid/SnowflakeTorFallbackRuntime.kt',
    '''        while (!processRestartRequired.get() && readyConnector.get() == null) {''',
    '''        while (!processRestartRequired.get() && readyConnector.get() == null && (wanted.get() || torStartedInProcess.get())) {''',
)
replace_once(
    'app/src/privateSideload/java/com/flowseal/tgwsandroid/SnowflakeTorFallbackRuntime.kt',
    '''        readyConnector.set(SocksRawWebSocketConnector("127.0.0.1", socksPort))\n        phase.set("ready")\n        lastError.set(null)\n        logger.log("Tor/Snowflake fallback READY on SOCKS 127.0.0.1:$socksPort")''',
    '''        readyConnector.set(SocksRawWebSocketConnector("127.0.0.1", socksPort))\n        phase.set("ready")\n        lastError.set(null)\n        notifyReadyWaiters()\n        logger.log("Tor/Snowflake fallback READY on SOCKS 127.0.0.1:$socksPort")''',
)
replace_once(
    'app/src/privateSideload/java/com/flowseal/tgwsandroid/SnowflakeTorFallbackRuntime.kt',
    '''        phase.set("process_restart_required")\n        logger.log("Tor/Snowflake $message")''',
    '''        phase.set("process_restart_required")\n        notifyReadyWaiters()\n        logger.log("Tor/Snowflake $message")''',
)
replace_once(
    'app/src/privateSideload/java/com/flowseal/tgwsandroid/SnowflakeTorFallbackRuntime.kt',
    '''    private fun sleepBackoff(delayMs: Long) {\n        var remaining = delayMs\n        while (remaining > 0 && !processRestartRequired.get()) {''',
    '''    private fun notifyReadyWaiters() {\n        synchronized(readyMonitor) { readyMonitor.notifyAll() }\n    }\n\n    private fun sleepBackoff(delayMs: Long) {\n        var remaining = delayMs\n        while (remaining > 0 && !processRestartRequired.get() && (wanted.get() || torStartedInProcess.get())) {''',
)

# ProxyServer regression tests and helper wiring.
proxy_test_path = 'app/src/test/java/com/flowseal/tgwsandroid/proxy/ProxyServerTest.kt'
replace_once(
    proxy_test_path,
    '''    @Test\n    fun protoTagsMapToExpectedSplitterProtoInts() {''',
    '''    @Test\n    fun serviceReadinessGateSkipsColdTorConnectorButSignalsOrdinaryExhaustion() {\n        val server = FakeTcpServerTransport()\n        val signals = CopyOnWriteArrayList<String>()\n        val tor = RecordingConnector(FakeWebSocketBinaryStream())\n        val proxy = newProxy(\n            server = server,\n            torSnowflakeConnector = tor,\n            onOrdinaryRoutesExhausted = { dcId, isMedia, reason -> signals.add("$dcId/$isMedia/$reason") },\n            torSnowflakeReadyProvider = { false },\n            torSnowflakeWaitUntilReady = { false },\n            config = baseConfig().copy(\n                routeMode = NetworkRouteMode.AUTO,\n                networkStatus = "mobile",\n                cfproxyEnabled = false,\n                torSnowflakeFallbackEnabled = true,\n            ),\n        )\n\n        proxy.start()\n        val client = FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes())\n        server.enqueue(client)\n        waitUntil("cold Tor client closes") { client.closed }\n        proxy.stop()\n\n        assertEquals(1, signals.size)\n        assertTrue(signals.first().contains("CF-first ordinary routes exhausted"))\n        assertTrue(tor.domains.isEmpty())\n        assertEquals(0L, proxy.stats().torSnowflakeAttempts)\n        assertEquals(1L, proxy.stats().torSnowflakeUnavailable)\n    }\n\n    @Test\n    fun serviceReadinessGateCanWaitForWarmTorWithoutTelegramReconnect() {\n        val server = FakeTcpServerTransport()\n        val tor = RecordingConnector(FakeWebSocketBinaryStream())\n        var ready = false\n        var waits = 0\n        val proxy = newProxy(\n            server = server,\n            torSnowflakeConnector = tor,\n            onOrdinaryRoutesExhausted = { _, _, _ -> },\n            torSnowflakeReadyProvider = { ready },\n            torSnowflakeWaitUntilReady = {\n                waits += 1\n                ready = true\n                true\n            },\n            config = baseConfig().copy(\n                routeMode = NetworkRouteMode.AUTO,\n                networkStatus = "mobile",\n                cfproxyEnabled = false,\n                torSnowflakeFallbackEnabled = true,\n            ),\n        )\n\n        proxy.start()\n        server.enqueue(FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes()))\n        waitUntil("warming wait should continue same client through Tor") { proxy.stats().lastRouteUsed == TOR_SNOWFLAKE_ROUTE_TYPE }\n        proxy.stop()\n\n        assertEquals(1, waits)\n        assertEquals(1L, proxy.stats().torSnowflakeAttempts)\n        assertEquals(1L, proxy.stats().torSnowflakeSuccesses)\n        assertEquals(0L, proxy.stats().torSnowflakeUnavailable)\n    }\n\n    @Test\n    fun protoTagsMapToExpectedSplitterProtoInts() {''',
)
replace_once(
    proxy_test_path,
    '''        cfDomainHealth: CfDomainHealth = CfDomainHealth(config.cfProxyDomains),\n        torSnowflakeConnector: RawWebSocketConnector? = null,\n    ): ProxyServer =''',
    '''        cfDomainHealth: CfDomainHealth = CfDomainHealth(config.cfProxyDomains),\n        torSnowflakeConnector: RawWebSocketConnector? = null,\n        onOrdinaryRoutesExhausted: ((Int, Boolean, String) -> Unit)? = null,\n        torSnowflakeReadyProvider: (() -> Boolean)? = null,\n        torSnowflakeWaitUntilReady: ((Long) -> Boolean)? = null,\n    ): ProxyServer =''',
)
replace_once(
    proxy_test_path,
    '''            webSocketConnector = connector,\n            torSnowflakeConnector = torSnowflakeConnector,\n            bridgeRunner = runner,''',
    '''            webSocketConnector = connector,\n            torSnowflakeConnector = torSnowflakeConnector,\n            onOrdinaryRoutesExhausted = onOrdinaryRoutesExhausted,\n            torSnowflakeReadyProvider = torSnowflakeReadyProvider,\n            torSnowflakeWaitUntilReady = torSnowflakeWaitUntilReady,\n            bridgeRunner = runner,''',
)

print('lazy Tor warmup patch applied')
