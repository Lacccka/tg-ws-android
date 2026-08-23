package com.flowseal.tgwsandroid

import IPtProxy.Controller
import IPtProxy.IPtProxy
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
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Private-sideload production prototype for a warm mobile-only Tor/Snowflake fallback.
 *
 * It never replaces direct/CF route health. The ProxyServer gets a dedicated connector
 * which is usable only after Tor reaches bootstrap 100%.
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
    private val stopped = AtomicBoolean(false)
    private val restartScheduled = AtomicBoolean(false)
    private val progress = AtomicInteger(0)
    private val phase = AtomicReference("idle")
    private val lastError = AtomicReference<String?>(null)
    private val readyConnector = AtomicReference<RawWebSocketConnector?>(null)
    private val lifecycleLock = Any()

    @Volatile private var task: Future<*>? = null
    @Volatile private var controller: Controller? = null
    @Volatile private var torConnection: ServiceConnection? = null

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

        private fun currentConnector(): RawWebSocketConnector = readyConnector.get()
            ?: throw TorSnowflakeUnavailableException(
                "Tor/Snowflake not ready: phase=${phase.get()} bootstrap=${progress.get()}%",
            )
    }

    override fun onNetworkChanged(networkStatus: String) {
        if (stopped.get()) return
        val shouldRun = isMobile(networkStatus)
        wanted.set(shouldRun)
        if (shouldRun) ensureStarted() else stopTransport("network=$networkStatus")
    }

    override fun snapshot(): TorFallbackRuntimeSnapshot = TorFallbackRuntimeSnapshot(
        desired = wanted.get(),
        running = task?.isDone == false,
        ready = readyConnector.get() != null,
        bootstrapProgress = progress.get(),
        phase = phase.get(),
        lastError = lastError.get(),
    )

    override fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        wanted.set(false)
        restartScheduled.set(false)
        task?.cancel(true)
        task = null
        cleanup()
        executor.shutdownNow()
        phase.set("stopped")
    }

    private fun ensureStarted() {
        synchronized(lifecycleLock) {
            if (!wanted.get() || stopped.get() || readyConnector.get() != null || task?.isDone == false) return
            task = executor.submit { bootstrapLoop() }
        }
    }

    private fun stopTransport(reason: String) {
        restartScheduled.set(false)
        task?.cancel(true)
        task = null
        cleanup()
        progress.set(0)
        phase.set("idle")
        lastError.set(null)
        logger.log("Tor/Snowflake fallback stopped because $reason")
    }

    private fun bootstrapLoop() {
        var retry = 0
        while (wanted.get() && !stopped.get()) {
            try {
                bootstrapOnce()
                return
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                cleanup()
                return
            } catch (error: Throwable) {
                cleanup()
                lastError.set("${error.javaClass.simpleName}: ${sanitize(error.message)}")
                phase.set("backoff")
                retry += 1
                val delayMs = retryBackoffMs(retry)
                logger.log("Tor/Snowflake bootstrap failed: ${lastError.get()}; retry in ${delayMs}ms")
                sleepWhileWanted(delayMs)
            }
        }
    }

    private fun bootstrapOnce() {
        readyConnector.set(null)
        progress.set(0)
        lastError.set(null)
        phase.set("snowflake")

        val stateDir = File(appContext.noBackupFilesDir, "snowflake-pt-fallback").apply { mkdirs() }
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

        val newController = Controller(stateDir.absolutePath, true, false, "INFO", transportEvents).also {
            it.snowflakeBrokerUrl = SNOWFLAKE_BROKER_URL
            it.snowflakeFrontDomains = SNOWFLAKE_FRONT_DOMAINS
            it.snowflakeIceServers = SNOWFLAKE_ICE_SERVERS
            it.snowflakeAmpCacheUrl = ""
            it.snowflakeSqsUrl = ""
            it.snowflakeSqsCreds = ""
        }
        controller = newController
        newController.start(IPtProxy.Snowflake, null)
        val ptPort = newController.port(IPtProxy.Snowflake).toInt()
        check(ptPort in 1..65535) { "Snowflake listener returned invalid port: $ptPort" }
        logger.log("Tor/Snowflake PT ready on 127.0.0.1:$ptPort")

        phase.set("tor_config")
        val torrc = TorService.getTorrc(appContext)
        torrc.parentFile?.mkdirs()
        torrc.writeText(buildTorrc(ptPort))

        val serviceLatch = CountDownLatch(1)
        var boundTor: TorService? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                boundTor = (service as? TorService.LocalBinder)?.service
                serviceLatch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                boundTor = null
                handleTorServiceLost("service disconnected")
            }

            override fun onBindingDied(name: ComponentName?) {
                boundTor = null
                handleTorServiceLost("binding died")
            }

            override fun onNullBinding(name: ComponentName?) {
                boundTor = null
                serviceLatch.countDown()
                handleTorServiceLost("null binding")
            }
        }
        torConnection = connection
        phase.set("tor_service")
        check(appContext.bindService(Intent(appContext, TorService::class.java), connection, Context.BIND_AUTO_CREATE)) {
            "bindService(TorService) returned false"
        }
        check(serviceLatch.await(TOR_SERVICE_BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            "TorService did not bind within ${TOR_SERVICE_BIND_TIMEOUT_SECONDS}s"
        }
        checkWanted()
        val tor = checkNotNull(boundTor) { "TorService binder returned no service" }

        val controlDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TOR_CONTROL_TIMEOUT_MS)
        while (tor.torControlConnection == null && System.nanoTime() < controlDeadline) {
            checkWanted()
            Thread.sleep(100)
        }
        check(tor.torControlConnection != null) { "Tor control connection unavailable" }

        phase.set("bootstrap")
        waitForBootstrap(tor)
        checkWanted()
        val socksPort = tor.socksPort
        check(socksPort in 1..65535) { "Tor SOCKS returned invalid port: $socksPort" }
        readyConnector.set(SocksRawWebSocketConnector("127.0.0.1", socksPort))
        phase.set("ready")
        lastError.set(null)
        logger.log("Tor/Snowflake fallback READY on SOCKS 127.0.0.1:$socksPort")
    }

    private fun handleTorServiceLost(reason: String) {
        readyConnector.set(null)
        if (!wanted.get() || stopped.get()) return
        phase.set("tor_disconnected")
        lastError.set(reason)
        logger.log("Tor/Snowflake $reason; scheduling transport restart")
        if (!restartScheduled.compareAndSet(false, true)) return
        executor.execute {
            try {
                if (!wanted.get() || stopped.get()) return@execute
                cleanup()
                synchronized(lifecycleLock) { task = null }
                ensureStarted()
            } finally {
                restartScheduled.set(false)
            }
        }
    }

    private fun waitForBootstrap(tor: TorService) {
        val startedNs = System.nanoTime()
        var lastProgress = -1
        var lastProgressNs = startedNs
        var lastPhase = ""
        while (true) {
            checkWanted()
            val now = System.nanoTime()
            val phaseInfo = tor.getInfo("status/bootstrap-phase").orEmpty()
            val current = BOOTSTRAP_PROGRESS_REGEX.find(phaseInfo)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (current != null && current != lastProgress) {
                lastProgress = current
                lastProgressNs = now
                lastPhase = sanitize(phaseInfo)
                progress.set(current.coerceIn(0, 100))
                logger.log("Tor/Snowflake bootstrap $current%")
            }
            if (current != null && current >= 100) return
            if (now - startedNs >= TimeUnit.MILLISECONDS.toNanos(TOR_BOOTSTRAP_HARD_TIMEOUT_MS)) {
                error("Tor bootstrap hard timeout; progress=$lastProgress phase=$lastPhase")
            }
            if (lastProgress >= 0 && now - lastProgressNs >= TimeUnit.MILLISECONDS.toNanos(TOR_BOOTSTRAP_STALL_TIMEOUT_MS)) {
                error("Tor bootstrap stalled; progress=$lastProgress phase=$lastPhase")
            }
            Thread.sleep(500)
        }
    }

    private fun cleanup() {
        readyConnector.set(null)
        val connection = torConnection
        torConnection = null
        if (connection != null) runCatching { appContext.unbindService(connection) }
        runCatching { appContext.stopService(Intent(appContext, TorService::class.java)) }
        val activeController = controller
        controller = null
        if (activeController != null) runCatching { activeController.stop(IPtProxy.Snowflake) }
    }

    private fun buildTorrc(ptPort: Int): String = buildString {
        appendLine("UseBridges 1")
        appendLine("ClientTransportPlugin snowflake socks5 127.0.0.1:$ptPort")
        SNOWFLAKE_BRIDGES.forEach { appendLine("Bridge $it") }
    }

    private fun checkWanted() {
        if (!wanted.get() || stopped.get() || Thread.currentThread().isInterrupted) throw InterruptedException("Tor fallback no longer desired")
    }

    private fun sleepWhileWanted(delayMs: Long) {
        var remaining = delayMs
        while (remaining > 0 && wanted.get() && !stopped.get()) {
            val step = remaining.coerceAtMost(500L)
            Thread.sleep(step)
            remaining -= step
        }
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
