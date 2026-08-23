package com.flowseal.tgwsandroid

import IPtProxy.OnTransportEvents
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import com.flowseal.tgwsandroid.proxy.NetworkRouteMode
import com.flowseal.tgwsandroid.proxy.ProxyLogger
import com.flowseal.tgwsandroid.proxy.ProxyServer
import com.flowseal.tgwsandroid.proxy.ProxyServerConfig
import com.flowseal.tgwsandroid.proxy.ProxyServerStats
import com.flowseal.tgwsandroid.proxy.SocksRawWebSocketConnector
import com.flowseal.tgwsandroid.service.ProxyForegroundService
import com.flowseal.tgwsandroid.service.ProxyRuntimeConfig
import org.torproject.jni.TorService
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Private-sideload integration proof:
 * Telegram -> local ProxyServer -> Tor SOCKS -> Snowflake -> Telegram WebSocket.
 *
 * This service intentionally does not participate in the normal AUTO/CF production routing.
 * It forces DIRECT_FIRST with the direct WebSocket connector replaced by Tor SOCKS, disables
 * both WebSocket pools and CF fallback, and keeps the whole chain alive while Telegram is in
 * the foreground.
 */
class SnowflakeTorProxyService : Service() {
    private val lifecycleLock = Any()
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SnowflakeTorProxy-worker").also { it.isDaemon = true }
    }
    private val statsExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "SnowflakeTorProxy-stats").also { it.isDaemon = true }
    }

    @Volatile
    private var startInProgress = false

    private var snowflakeTransportOwned: Boolean = false
    private var torServiceConnection: ServiceConnection? = null
    private var proxyServer: ProxyServer? = null
    private var statsFuture: ScheduledFuture<*>? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat(buildNotification())
        when (intent?.action ?: ACTION_START) {
            ACTION_STOP -> worker.execute {
                stopPrivateProxy("stopped by user")
                stopForegroundCompat()
                stopSelf()
            }
            ACTION_START -> worker.execute { startPrivateProxy() }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        worker.shutdownNow()
        statsFuture?.cancel(false)
        statsExecutor.shutdownNow()
        cleanupRuntime()
        if (SnowflakeTorProxyTestStatus.running || SnowflakeTorProxyTestStatus.ready) {
            SnowflakeTorProxyTestStatus.markStopped("service destroyed")
        }
        super.onDestroy()
    }

    private fun startPrivateProxy() {
        synchronized(lifecycleLock) {
            if (startInProgress || SnowflakeTorProxyTestStatus.ready) return
            startInProgress = true
        }

        SnowflakeTorProxyTestStatus.reset()
        SnowflakeTorProxyTestStatus.setPhase("PREPARE", "Preparing isolated private proxy test")

        try {
            check(BuildConfig.SNOWFLAKE_TOR_PACKAGED) { "Snowflake/Tor is not packaged in this build" }

            ProxyRuntimeConfig.initialize(applicationContext)
            val appConfig = ProxyRuntimeConfig.appConfig(applicationContext)
            val networkStatus = currentNetworkStatus()
            val serverConfig = ProxyServerConfig.fromAppConfig(appConfig, networkStatus).copy(
                poolSize = 0,
                cfproxyEnabled = false,
                cfPoolEnabled = false,
                routeMode = NetworkRouteMode.DIRECT_FIRST,
                networkStatus = networkStatus,
                directFallbackTimeoutMs = TOR_WEBSOCKET_TIMEOUT_MS,
            )

            SnowflakeTorProxyTestStatus.log(
                "TEST endpoint=${serverConfig.host}:${serverConfig.port} route=direct_first pools=off cf=off network=$networkStatus",
            )
            SnowflakeTorProxyTestStatus.log("Stopping normal ProxyForegroundService if it is active")
            stopService(Intent(this, ProxyForegroundService::class.java))
            waitForLocalPortFree(serverConfig.host, serverConfig.port)

            SnowflakeTorProxyTestStatus.setPhase("SNOWFLAKE", "Starting Snowflake pluggable transport")
            val transportEvents = object : OnTransportEvents {
                override fun connected(name: String?) {
                    SnowflakeTorProxyTestStatus.log("PT connected=${name ?: "unknown"}")
                }

                override fun error(name: String?, error: Exception?) {
                    SnowflakeTorProxyTestStatus.log(
                        "PT error=${name ?: "unknown"}: ${error?.javaClass?.simpleName ?: "unknown"}: ${sanitize(error?.message)}",
                    )
                }

                override fun stopped(name: String?, error: Exception?) {
                    val suffix = error?.let { ": ${it.javaClass.simpleName}: ${sanitize(it.message)}" }.orEmpty()
                    SnowflakeTorProxyTestStatus.log("PT stopped=${name ?: "unknown"}$suffix")
                }
            }

            val ptPort = SharedSnowflakeController.start(
                context = applicationContext,
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
            snowflakeTransportOwned = true
            SnowflakeTorProxyTestStatus.log("Snowflake SOCKS listener=127.0.0.1:$ptPort")

            SnowflakeTorProxyTestStatus.setPhase("TOR_CONFIG", "Configuring embedded Tor")
            val torrc = TorService.getTorrc(this)
            torrc.parentFile?.mkdirs()
            torrc.writeText(buildTorrc(ptPort))
            SnowflakeTorProxyTestStatus.log("torrc written: UseBridges=1 transport=snowflake bridges=${SNOWFLAKE_BRIDGES.size}")

            val serviceLatch = CountDownLatch(1)
            var boundTorService: TorService? = null
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    boundTorService = (service as? TorService.LocalBinder)?.service
                    serviceLatch.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    boundTorService = null
                    SnowflakeTorProxyTestStatus.log("TorService disconnected")
                }
            }
            torServiceConnection = connection

            SnowflakeTorProxyTestStatus.setPhase("TOR_SERVICE", "Starting embedded Tor")
            val bound = bindService(Intent(this, TorService::class.java), connection, Context.BIND_AUTO_CREATE)
            check(bound) { "bindService(TorService) returned false" }
            check(serviceLatch.await(TOR_SERVICE_BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "TorService did not bind within ${TOR_SERVICE_BIND_TIMEOUT_SECONDS}s"
            }
            val torService = checkNotNull(boundTorService) { "TorService binder returned no service" }
            SnowflakeTorProxyTestStatus.log("TorService bound")

            val controlDeadline = System.nanoTime() + TOR_CONTROL_TIMEOUT_MS * 1_000_000L
            while (torService.torControlConnection == null && System.nanoTime() < controlDeadline) {
                Thread.sleep(100)
            }
            check(torService.torControlConnection != null) {
                "Tor control connection unavailable after ${TOR_CONTROL_TIMEOUT_MS}ms"
            }
            SnowflakeTorProxyTestStatus.log("Tor control connection ready")

            SnowflakeTorProxyTestStatus.setPhase("TOR_BOOTSTRAP", "Bootstrapping Tor through Snowflake")
            waitForTorBootstrap(torService)
            val torSocksPort = torService.socksPort
            check(torSocksPort in 1..65535) { "Tor SOCKS returned invalid port: $torSocksPort" }
            SnowflakeTorProxyTestStatus.log("Tor circuit ready; SOCKS=127.0.0.1:$torSocksPort")

            SnowflakeTorProxyTestStatus.setPhase("PROXY_START", "Starting real local ProxyServer through Tor SOCKS")
            val connector = SocksRawWebSocketConnector("127.0.0.1", torSocksPort)
            val logger = ProxyLogger { message -> SnowflakeTorProxyTestStatus.log("PROXY $message") }
            val server = ProxyServer(
                config = serverConfig,
                webSocketConnector = connector,
                logger = logger,
            )
            proxyServer = server
            server.start()

            SnowflakeTorProxyTestStatus.markReady(
                endpoint = "${serverConfig.host}:${serverConfig.port}",
                socksPort = torSocksPort,
            )
            SnowflakeTorProxyTestStatus.log(
                "READY: Telegram -> ${serverConfig.host}:${serverConfig.port} -> ProxyServer -> Tor SOCKS -> Snowflake",
            )
            startStatsWatchdog()
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            cleanupRuntime()
            SnowflakeTorProxyTestStatus.markFailed("startup interrupted")
        } catch (error: Throwable) {
            cleanupRuntime()
            SnowflakeTorProxyTestStatus.markFailed("${error.javaClass.simpleName}: ${sanitize(error.message)}")
        } finally {
            synchronized(lifecycleLock) { startInProgress = false }
        }
    }

    private fun stopPrivateProxy(reason: String) {
        SnowflakeTorProxyTestStatus.setPhase("STOPPING", reason)
        cleanupRuntime()
        SnowflakeTorProxyTestStatus.markStopped(reason)
    }

    private fun cleanupRuntime() {
        statsFuture?.cancel(false)
        statsFuture = null

        val server = proxyServer
        proxyServer = null
        if (server != null) {
            runCatching { server.stop() }
                .onFailure { SnowflakeTorProxyTestStatus.log("ProxyServer stop failed: ${it.javaClass.simpleName}: ${sanitize(it.message)}") }
        }

        val connection = torServiceConnection
        torServiceConnection = null
        if (connection != null) runCatching { unbindService(connection) }
        runCatching { stopService(Intent(this, TorService::class.java)) }

        if (snowflakeTransportOwned) {
            snowflakeTransportOwned = false
            runCatching { SharedSnowflakeController.stop(SNOWFLAKE_OWNER) }
                .onFailure { SnowflakeTorProxyTestStatus.log("Shared Snowflake stop failed: ${it.javaClass.simpleName}: ${sanitize(it.message)}") }
        }
    }

    private fun waitForTorBootstrap(service: TorService) {
        val deadline = System.nanoTime() + TOR_BOOTSTRAP_TIMEOUT_MS * 1_000_000L
        var lastProgress = -1
        var lastPhase = ""
        while (System.nanoTime() < deadline) {
            val phase = service.getInfo("status/bootstrap-phase").orEmpty()
            val progress = BOOTSTRAP_PROGRESS_REGEX.find(phase)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (progress != null && progress != lastProgress) {
                lastProgress = progress
                lastPhase = sanitize(phase)
                SnowflakeTorProxyTestStatus.setTorBootstrapProgress(progress)
                SnowflakeTorProxyTestStatus.log("Tor bootstrap: $progress%")
            }
            if (progress != null && progress >= 100) return
            Thread.sleep(500)
        }
        error(
            "Tor bootstrap timeout after ${TOR_BOOTSTRAP_TIMEOUT_MS / 1000}s; lastProgress=$lastProgress phase=${sanitize(lastPhase)}",
        )
    }

    private fun waitForLocalPortFree(host: String, port: Int) {
        val deadline = System.nanoTime() + LOCAL_PORT_RELEASE_TIMEOUT_MS * 1_000_000L
        var lastError: Throwable? = null
        while (System.nanoTime() < deadline) {
            try {
                ServerSocket().use { socket ->
                    socket.reuseAddress = true
                    socket.bind(InetSocketAddress(host, port))
                }
                return
            } catch (error: Throwable) {
                lastError = error
                Thread.sleep(100)
            }
        }
        error("Local proxy port $host:$port is still busy: ${lastError?.javaClass?.simpleName}: ${sanitize(lastError?.message)}")
    }

    private fun startStatsWatchdog() {
        statsFuture?.cancel(false)
        statsFuture = statsExecutor.scheduleAtFixedRate({
            val stats = runCatching { proxyServer?.stats() }.getOrNull() ?: return@scheduleAtFixedRate
            SnowflakeTorProxyTestStatus.updateStats(stats)
        }, 0, 2, TimeUnit.SECONDS)
    }

    private fun buildTorrc(ptPort: Int): String = buildString {
        appendLine("UseBridges 1")
        appendLine("ClientTransportPlugin snowflake socks5 127.0.0.1:$ptPort")
        for (bridge in SNOWFLAKE_BRIDGES) appendLine("Bridge $bridge")
    }

    private fun currentNetworkStatus(): String = try {
        val manager = getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
        when {
            capabilities == null -> "none"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "unknown"
        }
    } catch (_: Throwable) {
        "unknown"
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Snowflake/Tor proxy test",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Private diagnostic Telegram proxy through Snowflake and Tor"
            },
        )
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, SnowflakeTorProxyActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("Snowflake/Tor Telegram test")
            .setContentText("Private ProxyServer test is active")
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Остановить", stopPendingIntent)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun sanitize(value: String?): String = value.orEmpty()
        .replace('\n', ' ')
        .replace('\r', ' ')
        .take(1000)

    companion object {
        private const val SNOWFLAKE_OWNER = "real-telegram-proof"
        const val ACTION_START = "com.flowseal.tgwsandroid.action.START_SNOWFLAKE_TOR_PROXY_TEST"
        const val ACTION_STOP = "com.flowseal.tgwsandroid.action.STOP_SNOWFLAKE_TOR_PROXY_TEST"

        private const val CHANNEL_ID = "snowflake_tor_proxy_test"
        private const val NOTIFICATION_ID = 1901
        private const val TOR_SERVICE_BIND_TIMEOUT_SECONDS = 10L
        private const val TOR_CONTROL_TIMEOUT_MS = 15_000L
        private const val TOR_BOOTSTRAP_TIMEOUT_MS = 120_000L
        private const val LOCAL_PORT_RELEASE_TIMEOUT_MS = 5_000L
        private const val TOR_WEBSOCKET_TIMEOUT_MS = 15_000

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

        fun startIntent(context: Context): Intent =
            Intent(context, SnowflakeTorProxyService::class.java).setAction(ACTION_START)

        fun stopIntent(context: Context): Intent =
            Intent(context, SnowflakeTorProxyService::class.java).setAction(ACTION_STOP)
    }
}

/** Process-local diagnostics for the private real-Telegram integration test. */
object SnowflakeTorProxyTestStatus {
    data class Snapshot(
        val running: Boolean,
        val ready: Boolean,
        val phase: String,
        val detail: String,
        val endpoint: String?,
        val torSocksPort: Int?,
        val torBootstrapProgress: Int,
        val statsLine: String,
        val logText: String,
    )

    private val logLock = Any()
    private val lines = ArrayDeque<String>()

    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    var ready: Boolean = false
        private set

    @Volatile
    private var phase: String = "IDLE"

    @Volatile
    private var detail: String = "Not started"

    @Volatile
    private var endpoint: String? = null

    @Volatile
    private var torSocksPort: Int? = null

    @Volatile
    private var torBootstrapProgress: Int = 0

    @Volatile
    private var statsLine: String = "No ProxyServer stats yet"

    fun reset() {
        running = true
        ready = false
        phase = "STARTING"
        detail = "Starting"
        endpoint = null
        torSocksPort = null
        torBootstrapProgress = 0
        statsLine = "No ProxyServer stats yet"
        synchronized(logLock) { lines.clear() }
        log("Snowflake/Tor real Telegram proxy test started")
    }

    fun setPhase(newPhase: String, newDetail: String) {
        phase = newPhase
        detail = newDetail
    }

    fun setTorBootstrapProgress(progress: Int) {
        torBootstrapProgress = progress.coerceIn(0, 100)
    }

    fun markReady(endpoint: String, socksPort: Int) {
        this.endpoint = endpoint
        this.torSocksPort = socksPort
        torBootstrapProgress = 100
        phase = "READY"
        detail = "Open Telegram and use the existing local proxy"
        running = true
        ready = true
    }

    fun markFailed(message: String) {
        ready = false
        running = false
        phase = "FAILED"
        detail = message
        log("FAILED: $message")
    }

    fun markStopped(reason: String) {
        ready = false
        running = false
        phase = "STOPPED"
        detail = reason
        log("STOPPED: $reason")
    }

    fun updateStats(stats: ProxyServerStats) {
        statsLine = buildString {
            append("accepted=${stats.sessions.acceptedHandshakes}")
            append(" active=${stats.connectionsActive}")
            append(" total=${stats.connectionsTotal}")
            append(" bad=${stats.connectionsBad}")
            append(" wsErr=${stats.wsConnectErrors}")
            append(" directAttempts=${stats.directAttempts}")
            append(" lastRoute=${stats.lastRouteUsed ?: "none"}")
            append(" up=${stats.bytesUp}")
            append(" down=${stats.bytesDown}")
            append(" clientClosed=${stats.sessionClientClosed}")
            append(" remoteEof=${stats.sessionRemoteEof}")
            append(" connReset=${stats.sessionEndDiagnostics.connectionReset.count}")
        }
    }

    fun log(message: String) {
        synchronized(logLock) {
            if (lines.size >= MAX_LOG_LINES) lines.removeFirst()
            lines.addLast(message)
        }
    }

    fun snapshot(): Snapshot = Snapshot(
        running = running,
        ready = ready,
        phase = phase,
        detail = detail,
        endpoint = endpoint,
        torSocksPort = torSocksPort,
        torBootstrapProgress = torBootstrapProgress,
        statsLine = statsLine,
        logText = synchronized(logLock) { lines.joinToString("\n") },
    )

    private const val MAX_LOG_LINES = 500
}
