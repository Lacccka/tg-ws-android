package com.flowseal.tgwsandroid.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.flowseal.tgwsandroid.MainActivity
import com.flowseal.tgwsandroid.proxy.ProxyLogger
import com.flowseal.tgwsandroid.proxy.ProxyServer
import com.flowseal.tgwsandroid.proxy.ProxyServerStats
import java.time.LocalDateTime
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class ProxyForegroundService : Service() {
    private val lock = Any()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ProxyForegroundService-worker").also { it.isDaemon = true }
    }
    private val watchdogExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "ProxyForegroundService-watchdog").also { it.isDaemon = true }
    }
    private var proxyServer: ProxyServer? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var watchdogFuture: ScheduledFuture<*>? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        State.addLog("service created", LogSeverity.INFO, "service")
        ensureNotificationChannel()
        State.setBatteryOptimizationStatus(detectBatteryOptimizationStatus())
        State.addLog("battery optimization status at start: ${State.batteryOptimizationStatus}", LogSeverity.INFO, "battery")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                State.addLog("=== Proxy stop ${LocalDateTime.now().format(RuntimeLogStore.TIME_FORMATTER)} ===", LogSeverity.INFO, "ui")
                State.addLog("stop command received", LogSeverity.INFO, "service")
                stopProxyAsync()
            }
            ACTION_START, null -> {
                State.addLog("=== Proxy start ${LocalDateTime.now().format(RuntimeLogStore.TIME_FORMATTER)} ===", LogSeverity.INFO, "ui")
                State.addLog("start command received", LogSeverity.INFO, "service")
                State.setBatteryOptimizationStatus(detectBatteryOptimizationStatus())
                if (State.batteryOptimizationStatus == "optimized") {
                    State.addLog("battery optimization enabled; long-running proxy may be restricted", LogSeverity.WARN, "battery")
                } else {
                    State.addLog("battery optimization status at start: ${State.batteryOptimizationStatus}", LogSeverity.INFO, "battery")
                }
                startProxyAsync()
            }
            else -> updateStatus("Ignored unknown action: ${intent.action}")
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        State.addLog("service destroyed", LogSeverity.INFO, "service")
        stopProxyBlocking()
        unregisterNetworkCallback()
        stopWatchdog()
        releaseWakeLock()
        executor.shutdownNow()
        watchdogExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun startProxyAsync() {
        try {
            startForegroundCompat(buildNotification())
            State.addLog("foreground notification started", LogSeverity.INFO, "service")
        } catch (error: Throwable) {
            State.setRunning(false, "service failed to start foreground: ${error.message ?: error::class.java.simpleName}")
            stopSelf()
            return
        }
        executor.execute {
            synchronized(lock) {
                if (proxyServer?.isRunning == true) {
                    State.setRunning(true, "Proxy already running on ${ProxyRuntimeConfig.endpointSummary()}")
                    return@execute
                }
                State.addLog("proxy start requested", LogSeverity.INFO, "service")
                registerNetworkCallback()
                val logger = ProxyLogger { message -> State.addProxyLog(message) }
                val server = ProxyServer(ProxyRuntimeConfig.proxyServerConfig(), logger = logger)
                proxyServer = server
                try {
                    server.start()
                    val stats = server.stats()
                    State.updateStats(stats)
                    State.setRunning(true, "Proxy running on ${ProxyRuntimeConfig.endpointSummary()}")
                    State.addLog("proxy started", LogSeverity.INFO, "service")
                    acquireWakeLock()
                    startWatchdog()
                } catch (error: Throwable) {
                    try {
                        server.stop()
                    } catch (_: Throwable) {
                        // Best-effort cleanup after a partial start failure.
                    }
                    proxyServer = null
                    State.updateStats(null)
                    State.setRunning(false, "proxy start failed with exception: ${error.message ?: error::class.java.simpleName}")
                    unregisterNetworkCallback()
                    releaseWakeLock()
                    stopWatchdog()
                    stopForegroundCompat()
                    stopSelf()
                }
            }
        }
    }

    private fun stopProxyAsync() {
        executor.execute {
            stopProxyBlocking()
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun stopProxyBlocking() {
        stopWatchdog()
        val server = synchronized(lock) {
            proxyServer.also { proxyServer = null }
        }
        if (server != null) {
            try {
                server.stop()
                State.updateStats(server.stats())
            } catch (error: Throwable) {
                State.addLog("Proxy stop failed: ${error.message ?: error::class.java.simpleName}", LogSeverity.WARN, "service")
            }
        }
        releaseWakeLock()
        unregisterNetworkCallback()
        State.setRunning(false, "Proxy stopped")
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "TG WS Android proxy",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Foreground proxy runtime status"
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val activityIntent = Intent(this, MainActivity::class.java)
        val activityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            activityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = Intent(this, ProxyForegroundService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("TG WS Android")
            .setContentText("Proxy running on ${ProxyRuntimeConfig.endpointSummary()}")
            .setContentIntent(activityPendingIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
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

    private fun updateStatus(status: String) {
        State.setRunning(State.running, status)
    }

    private fun detectBatteryOptimizationStatus(): String = try {
        val powerManager = getSystemService(PowerManager::class.java)
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) "unrestricted" else "optimized"
    } catch (_: Throwable) {
        "unknown"
    }

    private fun acquireWakeLock() {
        try {
            val existing = wakeLock
            if (existing?.isHeld == true) return
            val powerManager = getSystemService(PowerManager::class.java)
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:ProxyWakeLock").apply {
                setReferenceCounted(false)
                acquire()
            }
            State.addLog("WakeLock acquired", LogSeverity.INFO, "battery")
        } catch (error: Throwable) {
            State.addLog("WakeLock acquire failed: ${error.message ?: error::class.java.simpleName}", LogSeverity.WARN, "battery")
        }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        try {
            if (lock.isHeld) {
                lock.release()
                State.addLog("WakeLock released", LogSeverity.INFO, "battery")
            }
        } catch (error: Throwable) {
            State.addLog("WakeLock release failed: ${error.message ?: error::class.java.simpleName}", LogSeverity.WARN, "battery")
        } finally {
            wakeLock = null
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val status = networkStatus(connectivityManager.getNetworkCapabilities(network))
                State.setNetworkStatus(status)
                State.addLog("network available: $status", LogSeverity.INFO, "network")
            }

            override fun onLost(network: Network) {
                State.setNetworkStatus("none")
                State.addLog("network lost", LogSeverity.WARN, "network")
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                val status = networkStatus(networkCapabilities)
                State.setNetworkStatus(status)
                State.addLog("network capabilities changed: $status", LogSeverity.INFO, "network")
            }
        }
        try {
            connectivityManager.registerDefaultNetworkCallback(callback)
            networkCallback = callback
            State.setNetworkStatus(networkStatus(connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)))
            State.addLog("network callback registered", LogSeverity.INFO, "network")
        } catch (error: Throwable) {
            State.setNetworkStatus("unknown")
            State.addLog("network callback register failed: ${error.message ?: error::class.java.simpleName}", LogSeverity.WARN, "network")
        }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        try {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback)
            State.addLog("network callback unregistered", LogSeverity.INFO, "network")
        } catch (error: Throwable) {
            State.addLog("network callback unregister failed: ${error.message ?: error::class.java.simpleName}", LogSeverity.WARN, "network")
        } finally {
            networkCallback = null
            if (!State.running) State.setNetworkStatus("unknown")
        }
    }

    private fun networkStatus(capabilities: NetworkCapabilities?): String = when {
        capabilities == null -> "unknown"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        else -> "unknown"
    }

    private fun startWatchdog() {
        if (watchdogFuture?.isCancelled == false && watchdogFuture?.isDone == false) return
        watchdogFuture = watchdogExecutor.scheduleAtFixedRate({
            val server = synchronized(lock) { proxyServer }
            val stats = try {
                server?.stats()
            } catch (_: Throwable) {
                null
            }
            State.updateStats(stats)
            State.setBatteryOptimizationStatus(detectBatteryOptimizationStatus())
            val line = "watchdog: running=${server?.isRunning == true} ${compactStats(stats)} " +
                "network=${State.networkStatus} battery=${State.batteryOptimizationStatus}"
            State.addLog(line, LogSeverity.INFO, "service")
        }, WATCHDOG_INTERVAL_SECONDS, WATCHDOG_INTERVAL_SECONDS, TimeUnit.SECONDS)
    }

    private fun stopWatchdog() {
        watchdogFuture?.cancel(false)
        watchdogFuture = null
    }

    private fun compactStats(stats: ProxyServerStats?): String = if (stats == null) {
        "stats=unknown"
    } else {
        "active=${stats.connectionsActive} total=${stats.connectionsTotal} wsErr=${stats.wsConnectErrors} " +
            "cf=${stats.cfProxyConnections}/${stats.cfProxyErrors} pool=${stats.poolHits}/${stats.poolMisses}/${stats.poolRefillErrors}"
    }

    companion object {
        const val ACTION_START = "com.flowseal.tgwsandroid.action.START_PROXY"
        const val ACTION_STOP = "com.flowseal.tgwsandroid.action.STOP_PROXY"
        private const val CHANNEL_ID = "proxy_foreground"
        private const val NOTIFICATION_ID = 1001
        private const val WATCHDOG_INTERVAL_SECONDS = 45L

        fun startIntent(context: Context): Intent = Intent(context, ProxyForegroundService::class.java).setAction(ACTION_START)

        fun stopIntent(context: Context): Intent = Intent(context, ProxyForegroundService::class.java).setAction(ACTION_STOP)
    }

    object State {
        private val logStore = RuntimeLogStore()

        @Volatile
        var running: Boolean = false
            private set
        @Volatile
        var lastStatus: String = "Proxy stopped"
            private set
        @Volatile
        var batteryOptimizationStatus: String = "unknown"
            private set
        @Volatile
        var networkStatus: String = "unknown"
            private set
        @Volatile
        private var statsSnapshot: ProxyServerStats? = null

        fun setRunning(isRunning: Boolean, status: String) {
            running = isRunning
            lastStatus = status
            addLog(status, RuntimeLogStore.classifySeverity(status), "service")
        }

        fun setBatteryOptimizationStatus(status: String) {
            batteryOptimizationStatus = status.ifBlank { "unknown" }
        }

        fun setNetworkStatus(status: String) {
            networkStatus = status.ifBlank { "unknown" }
        }

        fun updateStats(stats: ProxyServerStats?) {
            statsSnapshot = stats
        }

        fun stats(): ProxyServerStats? = statsSnapshot

        fun statsLine(): String = DiagnosticReportFormatter.formatStats(statsSnapshot)

        fun addLog(message: String, severity: LogSeverity = RuntimeLogStore.classifySeverity(message), source: String = "service") {
            logStore.append(message, severity, source)
        }

        fun addProxyLog(message: String) {
            logStore.appendProxy(message)
        }

        fun clearLogs() {
            logStore.clear()
            logStore.append("Logs cleared", LogSeverity.INFO, "ui")
        }

        fun recentLogs(): List<String> = logStore.lines()

        fun hasLogs(): Boolean = !logStore.isEmpty()

        fun diagnosticReport(): String = DiagnosticReportFormatter.format(
            DiagnosticReportFormatter.snapshot(
                status = lastStatus,
                endpoint = ProxyRuntimeConfig.endpointSummary(),
                secret = ProxyRuntimeConfig.partialTelegramSecret(),
                dcSummary = ProxyRuntimeConfig.dcSummary(),
                batteryOptimization = batteryOptimizationStatus,
                network = networkStatus,
                stats = statsSnapshot,
                logs = logStore.snapshot(),
            ),
        )
    }
}
