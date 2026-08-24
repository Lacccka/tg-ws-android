package com.flowseal.tgwsandroid

import IPtProxy.OnTransportEvents
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.flowseal.tgwsandroid.proxy.ProxyLogger
import com.flowseal.tgwsandroid.proxy.RawWebSocketConnector
import com.flowseal.tgwsandroid.proxy.SocksRawWebSocketConnector
import com.flowseal.tgwsandroid.proxy.TorSnowflakeUnavailableException
import com.flowseal.tgwsandroid.service.TorFallbackRuntime
import com.flowseal.tgwsandroid.service.TorFallbackRuntimeSnapshot
import org.torproject.jni.TorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Private-sideload production prototype for a warm mobile-only Tor/Snowflake fallback.
 *
 * Important lifecycle rule: once C Tor has been started in this Android process,
 * it is never intentionally destroyed and started again. libtor keeps process-global
 * native state and repeated in-process start/stop cycles are unsafe. Logical proxy
 * restart/stop/network changes only change whether the route is desired; the warm
 * Tor/Snowflake engine stays alive until Android terminates the app process.
 */
class SnowflakeTorFallbackRuntime(
    context: Context,
    private val logger: ProxyLogger,
) : TorFallbackRuntime {
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "TorSnowflakeFallback").also { it.isDaemon = true }
    }
    private val wanted = AtomicBoolean(false)
    private val mobileEligible = AtomicBoolean(false)
    private val processRestartRequired = AtomicBoolean(false)
    private val progress = AtomicInteger(0)
    private val phase = AtomicReference("idle")
    private val lastError = AtomicReference<String?>(null)
    private val warmupReason = AtomicReference<String?>(null)
    private val warmupRequestedAtMs = AtomicLong(0)
    private val readyConnector = AtomicReference<RawWebSocketConnector?>(null)
    private val lifecycleLock = Any()
    private val readyMonitor = Object()
    private val snowflakeTransportOwned = AtomicBoolean(false)
    private val torStartedInProcess = AtomicBoolean(false)

    @Volatile private var task: Future<*>? = null
    @Volatile private var torConnection: ServiceConnection? = null
    @Volatile private var boundTorService: TorService? = null

    override val connector: RawWebSocketConnector = object : RawWebSocketConnector {
        override fun connect(targetHost: String, domain: String, path: String, timeoutMs: Int) =
            currentConnector().connect(targetHost, domain, path, timeoutMs)

        override fun connectWithSni(
            targetHost: String,
            domain: String,
            path: String,
            timeoutMs: Int,
            sniHost: String,
        ) = currentConnector().connectWithSni(targetHost, domain, path, timeoutMs, sniHost)

        private fun currentConnector(): RawWebSocketConnector {
            if (processRestartRequired.get()) {
                throw TorSnowflakeUnavailableException(
                    "Tor/Snowflake requires app process restart: ${lastError.get().orEmpty()}",
                )
            }
            return readyConnector.get()
                ?: throw TorSnowflakeUnavailableException(
                    "Tor/Snowflake not ready: phase=${phase.get()} bootstrap=${progress.get()}%",
                )
        }
    }

    override fun onNetworkChanged(networkStatus: String) {
        val eligible = isMobile(networkStatus)
        val changed = mobileEligible.getAndSet(eligible) != eligible
        if (!eligible) {
            wanted.set(false)
            warmupReason.set(null)
            warmupRequestedAtMs.set(0)
            notifyReadyWaiters()
            if (changed && hasStartedProcessEngine()) {
                logger.log(
                    "Tor/Snowflake fallback not eligible on network=$networkStatus; keeping native Tor warm for process lifetime",
                )
            }
        }
    }

    override fun requestWarmup(reason: String) {
        if (!mobileEligible.get()) {
            logger.log("Tor/Snowflake lazy warmup ignored because current network is not mobile")
            return
        }
        wanted.set(true)
        warmupReason.set(reason.ifBlank { "ordinary mobile routes degraded" })
        warmupRequestedAtMs.compareAndSet(0, System.currentTimeMillis())
        logger.log("Tor/Snowflake lazy warmup requested: ${warmupReason.get()}")
        ensureStarted()
    }

    override fun awaitReady(timeoutMs: Long): Boolean {
        if (readyConnector.get() != null && !processRestartRequired.get()) return true
        if (!wanted.get() || processRestartRequired.get() || timeoutMs <= 0L) return false
        val deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        synchronized(readyMonitor) {
            while (readyConnector.get() == null && wanted.get() && !processRestartRequired.get()) {
                val remainingNs = deadlineNs - System.nanoTime()
                if (remainingNs <= 0L) break
                val waitMs = TimeUnit.NANOSECONDS.toMillis(remainingNs).coerceAtLeast(1L)
                try {
                    readyMonitor.wait(waitMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
        }
        return readyConnector.get() != null && !processRestartRequired.get()
    }

    override fun snapshot(): TorFallbackRuntimeSnapshot = TorFallbackRuntimeSnapshot(
        desired = wanted.get(),
        running = hasStartedProcessEngine(),
        ready = readyConnector.get() != null && !processRestartRequired.get(),
        bootstrapProgress = progress.get(),
        phase = phase.get(),
        lastError = lastError.get(),
        warmupReason = warmupReason.get(),
        warmupRequestedAtMs = warmupRequestedAtMs.get().takeIf { it > 0L },
    )

    /**
     * Logical release only. Do not unbind/stop TorService here.
     *
     * tor-android embeds libtor in-process and repeated stop/start can leave native
     * static state pointing at destroyed mutexes. The process itself is the safe
     * lifetime boundary for this prototype.
     */
    override fun stop() {
        wanted.set(false)
        warmupReason.set(null)
        warmupRequestedAtMs.set(0)
        notifyReadyWaiters()
        if (hasStartedProcessEngine()) {
            logger.log("Tor/Snowflake runtime released logically; native engine retained until process exit")
        }
    }

    private fun ensureStarted() {
        synchronized(lifecycleLock) {
            if (!mobileEligible.get() || !wanted.get() || processRestartRequired.get() || readyConnector.get() != null || task?.isDone == false) return
            if (torStartedInProcess.get()) {
                markProcessRestartRequired("Tor was already started in this process but is no longer ready")
                return
            }
            task = executor.submit { bootstrapLoop() }
        }
    }

    private fun bootstrapLoop() {
        var retry = 0
        while (!processRestartRequired.get() && readyConnector.get() == null && (wanted.get() || torStartedInProcess.get())) {
            try {
                bootstrapOnce()
                return
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } catch (error: Throwable) {
                val summary = "${error.javaClass.simpleName}: ${sanitize(error.message)}"
                if (torStartedInProcess.get()) {
                    markProcessRestartRequired(summary)
                    return
                }

                releaseSnowflakeBeforeTorStart()
                lastError.set(summary)
                phase.set("backoff")
                retry += 1
                val delayMs = retryBackoffMs(retry)
                logger.log("Tor/Snowflake pre-Tor bootstrap failed: $summary; retry in ${delayMs}ms")
                sleepBackoff(delayMs)
            }
        }
    }

    private fun bootstrapOnce() {
        check(!torStartedInProcess.get()) { "Refusing to start libtor twice in one app process" }
        readyConnector.set(null)
        progress.set(0)
        lastError.set(null)
        phase.set("snowflake")

        val transportEvents = object : OnTransportEvents {
            override fun connected(name: String?) {
                logger.log("Tor/Snowflake PT connected=${name ?: "unknown"}")
            }

            override fun error(name: String?, error: Exception?) {
                logger.log("Tor/Snowflake PT error=${name ?: "unknown"}: ${sanitize(error?.message)}")
            }

            override fun stopped(name: String?, error: Exception?) {
                logger.log("Tor/Snowflake PT stopped=${name ?: "unknown"}${error?.let { ": ${sanitize(it.message)}" }.orEmpty()}")
            }
        }

        val ptPort = SharedSnowflakeController.start(
            context = appContext,
            owner = SNOWFLAKE_OWNER,
            events = transportEvents,
        ) { sharedController ->
            sharedController.snowflakeBrokerUrl = SNOWFLAKE_BROKER_URL
            sharedController.snowflakeFrontDomains = SNOWFLAKE_FRONT_DOMAINS
            sharedController.snowflakeIceServers = SNOWFLAKE_ICE_SERVERS
            sharedController.snowflakeAmpCacheUrl = ""
            sharedController.snowflakeSqsUrl = ""
            sharedController.snowflakeSqsCreds = ""
        }
        snowflakeTransportOwned.set(true)
        logger.log("Tor/Snowflake PT ready on 127.0.0.1:$ptPort using shared process controller")

        phase.set("tor_config")
        val torrc = TorService.getTorrc(appContext)
        torrc.parentFile?.mkdirs()
        torrc.writeText(buildTorrc(ptPort))

        val serviceLatch = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                boundTorService = (service as? TorService.LocalBinder)?.service
                serviceLatch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                boundTorService = null
                handleTorServiceLost("service disconnected")
            }

            override fun onBindingDied(name: ComponentName?) {
                boundTorService = null
                handleTorServiceLost("binding died")
            }

            override fun onNullBinding(name: ComponentName?) {
                boundTorService = null
                serviceLatch.countDown()
                handleTorServiceLost("null binding")
            }
        }
        torConnection = connection
        phase.set("tor_service")
        val bound = appContext.bindService(Intent(appContext, TorService::class.java), connection, Context.BIND_AUTO_CREATE)
        check(bound) { "bindService(TorService) returned false" }
        // bindService(BIND_AUTO_CREATE) has now created TorService and started its native tor thread.
        // From this point onward we must never intentionally destroy/restart libtor in this process.
        torStartedInProcess.set(true)

        check(serviceLatch.await(TOR_SERVICE_BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            "TorService did not bind within ${TOR_SERVICE_BIND_TIMEOUT_SECONDS}s"
        }
        checkEngineUsable()
        val tor = checkNotNull(boundTorService) { "TorService binder returned no service" }

        val controlDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TOR_CONTROL_TIMEOUT_MS)
        while (tor.torControlConnection == null && System.nanoTime() < controlDeadline) {
            checkEngineUsable()
            Thread.sleep(100)
        }
        check(tor.torControlConnection != null) { "Tor control connection unavailable" }

        phase.set("bootstrap")
        waitForBootstrap(tor)
        checkEngineUsable()
        val socksPort = tor.socksPort
        check(socksPort in 1..65535) { "Tor SOCKS returned invalid port: $socksPort" }
        readyConnector.set(SocksRawWebSocketConnector("127.0.0.1", socksPort))
        phase.set("ready")
        lastError.set(null)
        notifyReadyWaiters()
        logger.log("Tor/Snowflake fallback READY on SOCKS 127.0.0.1:$socksPort")
    }

    private fun handleTorServiceLost(reason: String) {
        readyConnector.set(null)
        if (!torStartedInProcess.get()) return
        markProcessRestartRequired(reason)
    }

    private fun markProcessRestartRequired(reason: String) {
        if (!processRestartRequired.compareAndSet(false, true)) return
        readyConnector.set(null)
        val message = "$reason; refusing unsafe in-process libtor restart — restart app process"
        lastError.set(message)
        phase.set("process_restart_required")
        notifyReadyWaiters()
        logger.log("Tor/Snowflake $message")
    }

    /**
     * Once native Tor exists, bootstrap timeouts are diagnostic only. Restarting
     * Tor in this process is less safe than letting a slow Snowflake/Tor bootstrap
     * continue. Progress clears the warning automatically.
     */
    private fun waitForBootstrap(tor: TorService) {
        val startedNs = System.nanoTime()
        var lastProgress = -1
        var lastProgressNs = startedNs
        var lastPhase = ""
        var stallReportedForProgress = -1
        var hardTimeoutReported = false

        while (true) {
            checkEngineUsable()
            val now = System.nanoTime()
            val phaseInfo = tor.getInfo("status/bootstrap-phase").orEmpty()
            val current = BOOTSTRAP_PROGRESS_REGEX.find(phaseInfo)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (current != null && current != lastProgress) {
                lastProgress = current
                lastProgressNs = now
                lastPhase = sanitize(phaseInfo)
                stallReportedForProgress = -1
                progress.set(current.coerceIn(0, 100))
                phase.set("bootstrap")
                lastError.set(null)
                logger.log("Tor/Snowflake bootstrap $current%")
            }
            if (current != null && current >= 100) return

            if (
                lastProgress >= 0 &&
                stallReportedForProgress != lastProgress &&
                now - lastProgressNs >= TimeUnit.MILLISECONDS.toNanos(TOR_BOOTSTRAP_STALL_TIMEOUT_MS)
            ) {
                stallReportedForProgress = lastProgress
                val message = "Tor bootstrap stalled; progress=$lastProgress phase=$lastPhase; waiting without unsafe Tor restart"
                phase.set("bootstrap_stalled")
                lastError.set(message)
                logger.log(message)
            }

            if (!hardTimeoutReported && now - startedNs >= TimeUnit.MILLISECONDS.toNanos(TOR_BOOTSTRAP_HARD_TIMEOUT_MS)) {
                hardTimeoutReported = true
                val message = "Tor bootstrap exceeded ${TOR_BOOTSTRAP_HARD_TIMEOUT_MS / 1000}s; progress=$lastProgress phase=$lastPhase; continuing in-process"
                phase.set("bootstrap_slow")
                lastError.set(message)
                logger.log(message)
            }
            Thread.sleep(500)
        }
    }

    private fun checkEngineUsable() {
        if (processRestartRequired.get()) {
            error(lastError.get() ?: "Tor process restart required")
        }
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Tor fallback worker interrupted")
    }

    private fun releaseSnowflakeBeforeTorStart() {
        if (torStartedInProcess.get()) return
        if (snowflakeTransportOwned.getAndSet(false)) {
            runCatching { SharedSnowflakeController.stop(SNOWFLAKE_OWNER) }
                .onFailure { logger.log("Tor/Snowflake pre-Tor PT cleanup failed: ${it.javaClass.simpleName}: ${sanitize(it.message)}") }
        }
    }

    private fun notifyReadyWaiters() {
        synchronized(readyMonitor) { readyMonitor.notifyAll() }
    }

    private fun sleepBackoff(delayMs: Long) {
        var remaining = delayMs
        while (remaining > 0 && !processRestartRequired.get() && (wanted.get() || torStartedInProcess.get())) {
            val step = remaining.coerceAtMost(500L)
            Thread.sleep(step)
            remaining -= step
        }
    }

    private fun hasStartedProcessEngine(): Boolean =
        readyConnector.get() != null || task?.isDone == false || snowflakeTransportOwned.get() || torConnection != null || torStartedInProcess.get()

    private fun buildTorrc(ptPort: Int): String = buildString {
        appendLine("UseBridges 1")
        appendLine("ClientTransportPlugin snowflake socks5 127.0.0.1:$ptPort")
        SNOWFLAKE_BRIDGES.forEach { appendLine("Bridge $it") }
    }

    private fun retryBackoffMs(retry: Int): Long = when (retry) {
        1 -> 15_000L
        2 -> 30_000L
        else -> 60_000L
    }

    private fun isMobile(value: String): Boolean =
        value.equals("mobile", true) || value.equals("cellular", true)

    private fun sanitize(value: String?): String = value.orEmpty().replace('\n', ' ').replace('\r', ' ').take(700)

    companion object {
        private const val SNOWFLAKE_OWNER = "production-fallback"
        private const val TOR_SERVICE_BIND_TIMEOUT_SECONDS = 10L
        private const val TOR_CONTROL_TIMEOUT_MS = 15_000L
        private const val TOR_BOOTSTRAP_STALL_TIMEOUT_MS = 90_000L
        private const val TOR_BOOTSTRAP_HARD_TIMEOUT_MS = 5L * 60L * 1000L

        private const val SNOWFLAKE_BROKER_URL = "https://1098762253.rsc.cdn77.org/"
        private const val SNOWFLAKE_FRONT_DOMAINS = "app.datapacket.com,www.datapacket.com"
        private const val SNOWFLAKE_ICE_SERVERS =
            "stun:stun.epygi.com:3478,stun:stun.uls.co.za:3478,stun:stun.voipgate.com:3478," +
                "stun:stun.mixvoip.com:3478,stun:stun.telnyx.com:3478,stun:stun.hot-chilli.net:3478," +
                "stun:stun.fitauto.ru:3478,stun:stun.m-online.net:3478"

        private val SNOWFLAKE_BRIDGES = listOf(
            "snowflake 192.0.2.3:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72 fingerprint=2B280B23E1107BB62ABFC40DDCC8824814F80A72 url=https://1098762253.rsc.cdn77.org/ fronts=app.datapacket.com,www.datapacket.com ice=$SNOWFLAKE_ICE_SERVERS utls-imitate=hellorandomizedalpn",
            "snowflake 192.0.2.4:80 8838024498816A039FCBBAB14E6F40A0843051FA fingerprint=8838024498816A039FCBBAB14E6F40A0843051FA url=https://1098762253.rsc.cdn77.org/ fronts=app.datapacket.com,www.datapacket.com ice=$SNOWFLAKE_ICE_SERVERS utls-imitate=hellorandomizedalpn",
        )
        private val BOOTSTRAP_PROGRESS_REGEX = Regex("PROGRESS=(\\d+)")
    }
}
