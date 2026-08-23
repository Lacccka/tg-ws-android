package com.flowseal.tgwsandroid.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.flowseal.tgwsandroid.BuildConfig
import com.flowseal.tgwsandroid.MainActivity
import com.flowseal.tgwsandroid.proxy.ProxyLogger
import com.flowseal.tgwsandroid.proxy.ProxyServer
import com.flowseal.tgwsandroid.proxy.NetworkRouteMode
import com.flowseal.tgwsandroid.proxy.ProxyServerStats
import com.flowseal.tgwsandroid.telemetry.Telemetry
import com.flowseal.tgwsandroid.telemetry.TelemetryAggregator
import com.flowseal.tgwsandroid.telemetry.BatteryRestrictionDiagnostics
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import org.json.JSONArray

class ProxyForegroundService : Service() {
    private val lock = Any()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ProxyForegroundService-worker").also { it.isDaemon = true }
    }
    private val watchdogExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "ProxyForegroundService-watchdog").also { it.isDaemon = true }
    }
    private val routeDebouncer = NetworkRouteDebouncer(watchdogExecutor) { status ->
        executor.execute { applyRouteForNetwork(status) }
    }
    private var proxyServer: ProxyServer? = null
    private var torFallbackRuntime: TorFallbackRuntime? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var watchdogFuture: ScheduledFuture<*>? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val telemetryAggregator = TelemetryAggregator(telemetryEnabled = { runCatching { ProxyRuntimeConfig.appConfig(applicationContext).telemetryEnabled }.getOrDefault(false) })
    @Volatile
    private var lastDuplicateNetworkCallbackLogAtMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        ProxyRuntimeConfig.initialize(applicationContext)
        State.initialize(applicationContext, "service")
        State.markServiceStarted()
        State.addLog("service created", LogSeverity.INFO, "service")
        State.markServiceEvent("service_on_create")
        ensureNotificationChannel()
        State.setBatteryOptimizationStatus(detectBatteryOptimizationStatus())
        State.addLog("battery optimization status at start: ${State.batteryOptimizationStatus}", LogSeverity.INFO, "battery")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        State.markServiceEvent("service_on_start_command")
        when (intent?.action) {
            ACTION_STOP_FROM_UI, ACTION_STOP_FROM_NOTIFICATION, ACTION_STOP_FROM_TILE, ACTION_STOP_LEGACY -> {
                val stopSource = stopSourceForAction(intent.action)
                State.markServiceEvent("explicit_stop_${stopSource.markerReason}")
                State.addLog("=== Proxy stop ${LocalDateTime.now().format(RuntimeLogStore.TIME_FORMATTER)} ===", LogSeverity.INFO, stopSource.logSource)
                State.addLog(stopSource.logMessage, LogSeverity.INFO, "service")
                stopProxyAsync(stopSource.markerReason)
            }
            ACTION_RESTART_FROM_UI -> {
                State.markServiceEvent("restart_begin")
                State.addLog("=== Proxy restart ${LocalDateTime.now().format(RuntimeLogStore.TIME_FORMATTER)} ===", LogSeverity.INFO, "ui")
                State.addLog("restart command received from UI", LogSeverity.INFO, "service")
                State.setBatteryOptimizationStatus(detectBatteryOptimizationStatus())
                restartProxyAsync()
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

    override fun onTaskRemoved(rootIntent: Intent?) {
        State.markServiceEvent("service_on_task_removed")
        State.addLog("onTaskRemoved: app task removed while service running=${State.running}", LogSeverity.WARN, "service")
        super.onTaskRemoved(rootIntent)
    }

    override fun onTrimMemory(level: Int) {
        State.markTrimMemory(level)
        State.addLog("trim memory requested: level=$level", LogSeverity.WARN, "service")
        super.onTrimMemory(level)
    }

    override fun onLowMemory() {
        State.markLowMemory()
        State.addLog("low memory callback received", LogSeverity.WARN, "service")
        super.onLowMemory()
    }

    override fun onTimeout(type: Int, reason: Int) {
        val message = "foreground service timeout: type=$type reason=$reason running=${State.running} status=${State.lastStatus}"
        State.markForegroundTimeout(System.currentTimeMillis(), "type=$type reason=$reason")
        State.addLog(message, LogSeverity.ERROR, "service")
        State.markServiceEvent("foreground_service_timeout")
        stopProxyAsync("foreground_service_timeout")
    }

    override fun onDestroy() {
        State.addLog("service destroyed", LogSeverity.INFO, "service")
        State.markServiceEvent("service_on_destroy")
        stopProxyBlocking("service_destroyed")
        unregisterNetworkCallback()
        routeDebouncer.cancel()
        stopWatchdog()
        releaseWakeLock()
        executor.shutdownNow()
        watchdogExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun startProxyAsync() {
        if (!startOrRefreshForegroundNotification(stopServiceOnFailure = true)) return
        executor.execute { startProxyBlocking(stopServiceOnFailure = true, successEvent = null, failureEvent = null) }
    }

    private fun restartProxyAsync() {
        if (!startOrRefreshForegroundNotification(
                stopServiceOnFailure = false,
                failureEvent = "restart_failed_foreground_refresh",
                preserveRunningStateOnFailure = true,
            )
        ) return
        executor.execute {
            stopProxyBlocking("restart")
            State.markServiceEvent("restart_stop_completed")
            State.addLog("restart_stop_completed", LogSeverity.INFO, "service")
            startProxyBlocking(
                stopServiceOnFailure = false,
                successEvent = "restart_start_completed",
                failureEvent = "restart_failed",
            )
        }
    }

    private fun startOrRefreshForegroundNotification(
        stopServiceOnFailure: Boolean,
        failureEvent: String? = null,
        preserveRunningStateOnFailure: Boolean = false,
    ): Boolean {
        return try {
            val notification = buildNotification()
            State.markServiceEvent("foreground_notification_built")
            startForegroundCompat(notification)
            State.addLog(
                "foreground notification started: declared=${declaredForegroundServiceStrategy()} runtime=${runtimeForegroundServiceTypeName(Build.VERSION.SDK_INT)}",
                LogSeverity.INFO,
                "service",
            )
            State.markForegroundStarted()
            true
        } catch (error: Throwable) {
            val message = "service failed to start foreground: ${error.message ?: error::class.java.simpleName}"
            if (preserveRunningStateOnFailure) {
                val serverStillRunning = synchronized(lock) { proxyServer?.isRunning == true }
                State.setRunning(serverStillRunning, message)
                State.markServiceEvent("restart_failed")
                failureEvent?.let { State.markServiceEvent(it) }
                failureEvent?.let { State.addLog("$it: ${error.message ?: error::class.java.simpleName}", LogSeverity.ERROR, "service") }
            } else {
                State.setRunning(false, message)
            }
            if (stopServiceOnFailure) stopSelf()
            false
        }
    }

    private fun startProxyBlocking(stopServiceOnFailure: Boolean, successEvent: String?, failureEvent: String?) {
        synchronized(lock) {
            if (proxyServer?.isRunning == true) {
                State.setRunning(true, "Proxy already running on ${ProxyRuntimeConfig.endpointSummary(applicationContext)}")
                successEvent?.let { State.markServiceEvent(it) }
                successEvent?.let { State.addLog(it, LogSeverity.INFO, "service") }
                return
            }
            State.addLog("proxy start requested", LogSeverity.INFO, "service")
            State.markServiceEvent("proxy_start_requested")
            registerNetworkCallback()
            val logger = ProxyLogger { message -> State.addProxyLog(message) }
            val runtime = TorFallbackRuntimeLoader.create(applicationContext, logger)
            torFallbackRuntime = runtime
            runtime?.onNetworkChanged(State.networkStatus)
            val serverConfig = ProxyRuntimeConfig.proxyServerConfig(applicationContext, State.networkStatus).copy(
                torSnowflakeFallbackEnabled = runtime != null,
            )
            val server = ProxyServer(
                config = serverConfig,
                torSnowflakeConnector = runtime?.connector,
                logger = logger,
            )
            proxyServer = server
            State.setLiveStatsProvider { synchronized(lock) { proxyServer }?.stats() }
            try {
                server.start()
                val stats = server.stats()
                State.updateStats(stats)
                State.setRunning(true, "Proxy running on ${ProxyRuntimeConfig.endpointSummary(applicationContext)}")
                State.markProxyStarted()
                successEvent?.let { State.markServiceEvent(it) }
                successEvent?.let { State.addLog(it, LogSeverity.INFO, "service") }
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
                State.setLiveStatsProvider(null)
                State.updateStats(null)
                State.setRunning(false, "proxy start failed with exception: ${error.message ?: error::class.java.simpleName}")
                State.markProxyStopped(if (failureEvent == "restart_failed") "restart_failed" else "start_failed")
                failureEvent?.let { State.markServiceEvent(it) }
                failureEvent?.let { State.addLog("$it: ${error.message ?: error::class.java.simpleName}", LogSeverity.ERROR, "service") }
                unregisterNetworkCallback()
                releaseWakeLock()
                stopWatchdog()
                if (stopServiceOnFailure) {
                    stopForegroundCompat()
                    stopSelf()
                }
            }
        }
    }

    private fun stopProxyAsync(stopReason: String) {
        executor.execute {
            stopProxyBlocking(stopReason)
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun stopProxyBlocking(stopReason: String = "service_stop") {
        State.markServiceEvent("proxy_stop_begin")
        stopWatchdog()
        val wasRunning = State.running
        val server = synchronized(lock) {
            proxyServer.also { proxyServer = null }
        }
        State.setLiveStatsProvider(null)
        val torRuntime = synchronized(lock) {
            torFallbackRuntime.also { torFallbackRuntime = null }
        }
        torRuntime?.stop()
        if (server != null) {
            try {
                server.stop()
                State.updateStats(server.stats())
            } catch (error: Throwable) {
                State.addLog("Proxy stop failed: ${error.message ?: error::class.java.simpleName}", LogSeverity.WARN, "service")
            }
        }
        telemetryAggregator.flushOnStop()
        sendQueuedTelemetrySnapshots()
        releaseWakeLock()
        unregisterNetworkCallback()
        State.setRunning(false, "Proxy stopped")
        State.markServiceEvent("proxy_stop_completed")
        if (wasRunning || server != null) State.markProxyStopped(stopReason)
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Прокси Siberian Empire Proxy",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Статус фоновой работы прокси"
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
        val stopIntent = Intent(this, ProxyForegroundService::class.java).setAction(ACTION_STOP_FROM_NOTIFICATION)
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
            .setContentTitle("Siberian Empire Proxy")
            .setContentText("Прокси работает: ${ProxyRuntimeConfig.endpointSummary(applicationContext)}")
            .setContentIntent(activityPendingIntent)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_view),
                    "Открыть",
                    activityPendingIntent,
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Остановить",
                    stopPendingIntent,
                ).build(),
            )
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        State.markServiceEvent("foreground_service_type_selected")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val runtimeType = runtimeForegroundServiceType(Build.VERSION.SDK_INT)
            if (runtimeType != null) {
                startForeground(NOTIFICATION_ID, notification, runtimeType)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        State.markServiceEvent("start_foreground_succeeded")
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
            State.setWakeLockHeld(true)
            State.markServiceEvent("wake_lock_acquired")
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
                State.setWakeLockHeld(false)
                State.markServiceEvent("wake_lock_released")
                State.addLog("WakeLock released", LogSeverity.INFO, "battery")
            }
        } catch (error: Throwable) {
            State.addLog("WakeLock release failed: ${error.message ?: error::class.java.simpleName}", LogSeverity.WARN, "battery")
        } finally {
            wakeLock = null
            State.setWakeLockHeld(false)
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                handleNetworkChanged("network available", networkStatus(connectivityManager.getNetworkCapabilities(network)))
            }

            override fun onLost(network: Network) {
                handleNetworkChanged("network lost", "none", LogSeverity.WARN)
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                handleNetworkChanged("network capabilities changed", networkStatus(networkCapabilities))
            }
        }
        try {
            connectivityManager.registerDefaultNetworkCallback(callback)
            networkCallback = callback
            val initialStatus = networkStatus(connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork))
            State.setNetworkStatus(initialStatus)
            State.addLog("network callback registered", LogSeverity.INFO, "network")
            State.addLog("network changed: unknown -> $initialStatus", LogSeverity.INFO, "network")
        } catch (error: Throwable) {
            State.setNetworkStatus("unknown")
            State.addLog("network callback register failed: ${error.message ?: error::class.java.simpleName}", LogSeverity.WARN, "network")
        }
    }

    private fun unregisterNetworkCallback() {
        routeDebouncer.cancel()
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

    private fun handleNetworkChanged(
        event: String,
        status: String,
        severity: LogSeverity = LogSeverity.INFO,
    ) {
        val normalized = status.ifBlank { "unknown" }
        val previous = State.networkStatus
        val isNetworkLost = event == "network lost" || normalized.equals("none", ignoreCase = true)
        if (!isNetworkLost && previous == normalized) {
            logDuplicateNetworkCallback(event, normalized)
            return
        }
        State.setNetworkStatus(normalized)
        State.addLog("$event: $normalized", severity, "network")
        State.addLog("network changed: $previous -> $normalized", severity, "network")
        if (isNetworkLost) {
            State.addLog("network lost: applying safe route immediately", LogSeverity.WARN, "network")
            routeDebouncer.cancel()
            executor.execute { applyRouteForNetwork(normalized, immediate = true) }
        } else {
            routeDebouncer.submit(normalized)
        }
    }

    private fun logDuplicateNetworkCallback(event: String, normalized: String) {
        val now = System.currentTimeMillis()
        if (now - lastDuplicateNetworkCallbackLogAtMs < DUPLICATE_NETWORK_LOG_THROTTLE_MS) return
        lastDuplicateNetworkCallbackLogAtMs = now
        State.addLog("$event: $normalized (unchanged; throttled)", LogSeverity.DEBUG, "network")
    }

    private fun applyRouteForNetwork(networkStatus: String, immediate: Boolean = false) {
        torFallbackRuntime?.onNetworkChanged(networkStatus)
        val server = synchronized(lock) { proxyServer }
        if (server?.isRunning != true) {
            State.addLog("route unchanged: proxy not running for network=$networkStatus", LogSeverity.INFO, "network")
            return
        }
        val before = server.routeSnapshot()
        val result = if (immediate) server.applyNetworkRouteImmediately(networkStatus) else server.applyNetworkRoute(networkStatus)
        State.updateStats(server.stats())
        if (result.changed) {
            State.addLog(
                "route changed (${result.source}): ${result.previous.configValue} -> ${result.current.configValue} because network=$networkStatus",
                LogSeverity.INFO,
                "network",
            )
        } else {
            val override = if (before.configuredRouteMode != NetworkRouteMode.AUTO) {
                "; manual mode overrides network auto"
            } else {
                ""
            }
            State.addLog("route unchanged (${result.source}): ${result.current.configValue} for network=$networkStatus$override", LogSeverity.INFO, "network")
        }
    }

    private fun networkStatus(capabilities: NetworkCapabilities?): String = when {
        capabilities == null -> "none"
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
            if (stats != null) {
                telemetryAggregator.recordStats(
                    stats,
                    foregroundServiceActive = State.running,
                    wakeLockActive = State.isWakeLockHeld(),
                    batteryRestrictionDetected = BatteryRestrictionDiagnostics.collect(applicationContext).restrictionDetected,
                    previousRunEndedUnexpectedly = State.previousRunEndedUnexpectedly(),
                    unexpectedStopDetected = State.unexpectedStopDetected(),
                )
                sendQueuedTelemetrySnapshots()
            }
            State.markWatchdogHeartbeat()
            val line = "watchdog: running=${server?.isRunning == true} ${compactStats(stats)} " +
                "network=${State.networkStatus} route=${stats?.effectiveRouteMode ?: "unknown"} battery=${State.batteryOptimizationStatus}"
            State.addLog(line, LogSeverity.INFO, "service")
        }, WATCHDOG_INTERVAL_SECONDS, WATCHDOG_INTERVAL_SECONDS, TimeUnit.SECONDS)
    }

    private fun stopWatchdog() {
        watchdogFuture?.cancel(false)
        watchdogFuture = null
    }


    private fun sendQueuedTelemetrySnapshots() {
        if (!runCatching { ProxyRuntimeConfig.appConfig(applicationContext).telemetryEnabled }.getOrDefault(false)) return
        while (true) {
            val event = telemetryAggregator.pollSnapshot() ?: return
            runCatching { Telemetry.sendEvents(applicationContext, JSONArray().put(event)) }
                .onSuccess { sent -> telemetryAggregator.recordTelemetryDelivery(sent) }
                .onFailure { error ->
                    telemetryAggregator.recordTelemetryDelivery(false)
                    State.addLog("diagnostics telemetry send failed: ${error::class.java.simpleName}", LogSeverity.WARN, "telemetry")
                }
        }
    }

    private fun compactStats(stats: ProxyServerStats?): String = if (stats == null) {
        "stats=unknown"
    } else {
        "active=${stats.connectionsActive} total=${stats.connectionsTotal} wsErr=${stats.wsConnectErrors} " +
            "sessionTimeouts=${stats.sessionTimeouts} sessionEof=${stats.sessionEof} " +
            "sessionRemoteEof=${stats.sessionRemoteEof} sessionRemoteIdleEof=${stats.sessionRemoteIdleEof} " +
            "sessionRemoteEofShort=${stats.sessionRemoteEofShort} " +
            "sessionClientClosed=${stats.sessionClientClosed} sessionSocketClosed=${stats.sessionSocketClosed} " +
            "sessionUnexpectedErrors=${stats.sessionUnexpectedErrors} connReset=${stats.sessionEndDiagnostics.connectionReset.count} " +
            "connTimedOut=${stats.sessionEndDiagnostics.connectionTimedOut.count} cf=${stats.cfProxyConnections}/${stats.cfProxyErrors} " +
            "pool=${stats.poolHits}/${stats.poolMisses}/${stats.poolRefillErrors} poolStale=${stats.poolStale} " +
            "directPoolReadyByKey=${compactMap(stats.directPoolDiagnostics.readyByKey)} " +
            "directPoolHitsByKey=${compactMap(stats.directPoolDiagnostics.hitsByKey)} " +
            "directPoolMissesByKey=${compactMap(stats.directPoolDiagnostics.missesByKey)} " +
            "directPoolRefillErrorsByKey=${compactMap(stats.directPoolDiagnostics.refillErrorsByKey)} " +
            "directPoolStaleByKey=${compactMap(stats.directPoolDiagnostics.staleByKey)} " +
            "cfPoolReadyByKey=${compactMap(stats.cfPoolDiagnostics.readyByKey)} " +
            "cfPoolHits=${stats.cfPoolHits} cfPoolMisses=${stats.cfPoolMisses} " +
            "cfPoolRefillAttempts=${stats.cfPoolRefillAttempts} cfPoolRefillSuccesses=${stats.cfPoolRefillSuccesses} " +
            "cfPoolRefillErrors=${stats.cfPoolRefillErrors} cfPoolStale=${stats.cfPoolStale} " +
            "cfPoolLastDomainByKey=${compactMap(stats.cfPoolLastDomainByKey)} " +
            "directHealth=${stats.directHealthState} route=${stats.effectiveRouteMode} lastRoute=${stats.lastRouteUsed ?: "none"}"
    }

    private fun compactMap(values: Map<*, *>): String =
        if (values.isEmpty()) "none" else values.entries.joinToString(";") { "${it.key}=${it.value}" }

    companion object {
        const val ACTION_START = "com.flowseal.tgwsandroid.action.START_PROXY"
        const val ACTION_STOP_LEGACY = "com.flowseal.tgwsandroid.action.STOP_PROXY"
        const val ACTION_STOP_FROM_UI = "com.flowseal.tgwsandroid.action.STOP_PROXY_FROM_UI"
        const val ACTION_STOP_FROM_NOTIFICATION = "com.flowseal.tgwsandroid.action.STOP_PROXY_FROM_NOTIFICATION"
        const val ACTION_STOP_FROM_TILE = "com.flowseal.tgwsandroid.action.STOP_PROXY_FROM_TILE"
        const val ACTION_RESTART_FROM_UI = "com.flowseal.tgwsandroid.action.RESTART_PROXY_FROM_UI"
        private const val CHANNEL_ID = "proxy_foreground"
        private const val NOTIFICATION_ID = 1001
        private const val WATCHDOG_INTERVAL_SECONDS = 45L
        private const val DUPLICATE_NETWORK_LOG_THROTTLE_MS = 60_000L
        private const val FOREGROUND_SERVICE_TYPE_DATA_SYNC_NAME = "dataSync"
        private const val FOREGROUND_SERVICE_TYPE_SPECIAL_USE_NAME = "specialUse"
        private const val FOREGROUND_SERVICE_TYPE_NONE_NAME = "none"

        fun declaredForegroundServiceStrategy(): String = when (BuildConfig.DECLARED_FOREGROUND_SERVICE_STRATEGY) {
            FOREGROUND_SERVICE_TYPE_SPECIAL_USE_NAME -> FOREGROUND_SERVICE_TYPE_SPECIAL_USE_NAME
            else -> FOREGROUND_SERVICE_TYPE_DATA_SYNC_NAME
        }

        fun foregroundServiceTypeFromManifestBuildStrategy(): String = declaredForegroundServiceStrategy()

        fun runtimeForegroundServiceTypeName(sdkInt: Int = Build.VERSION.SDK_INT): String =
            runtimeForegroundServiceTypeNameForStrategy(declaredForegroundServiceStrategy(), sdkInt)

        fun runtimeForegroundServiceTypeNameForStrategy(strategy: String, sdkInt: Int): String = when (strategy) {
            FOREGROUND_SERVICE_TYPE_SPECIAL_USE_NAME -> {
                if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    FOREGROUND_SERVICE_TYPE_SPECIAL_USE_NAME
                } else {
                    FOREGROUND_SERVICE_TYPE_NONE_NAME
                }
            }
            else -> {
                if (sdkInt >= Build.VERSION_CODES.Q) {
                    FOREGROUND_SERVICE_TYPE_DATA_SYNC_NAME
                } else {
                    FOREGROUND_SERVICE_TYPE_NONE_NAME
                }
            }
        }

        fun runtimeForegroundServiceType(sdkInt: Int = Build.VERSION.SDK_INT): Int? = when (runtimeForegroundServiceTypeName(sdkInt)) {
            FOREGROUND_SERVICE_TYPE_SPECIAL_USE_NAME -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            FOREGROUND_SERVICE_TYPE_DATA_SYNC_NAME -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> null
        }

        fun startIntent(context: Context): Intent = Intent(context, ProxyForegroundService::class.java).setAction(ACTION_START)

        fun stopIntent(context: Context): Intent = Intent(context, ProxyForegroundService::class.java).setAction(ACTION_STOP_FROM_UI)

        fun restartIntent(context: Context): Intent = Intent(context, ProxyForegroundService::class.java).setAction(ACTION_RESTART_FROM_UI)

        fun stopFromTileIntent(context: Context): Intent = Intent(context, ProxyForegroundService::class.java).setAction(ACTION_STOP_FROM_TILE)

        data class StopSource(val logSource: String, val markerReason: String, val logMessage: String)

        fun stopSourceForAction(action: String?): StopSource = when (action) {
            ACTION_STOP_FROM_UI -> StopSource("ui", "ui", "stop command received from UI")
            ACTION_STOP_FROM_NOTIFICATION -> StopSource("notification", "notification", "stop command received from notification")
            ACTION_STOP_FROM_TILE -> StopSource("quick_settings", "quick_settings", "stop command received from quick settings tile")
            else -> StopSource("service", "legacy_unknown", "stop command received from legacy/unknown action")
        }
    }

    object State {
        private val logStore = RuntimeLogStore()
        private val initLock = Any()
        private var persistenceConfigured = false
        private var crashHandlerInstalled = false
        private var runMarker: ProxyRunMarker? = null
        @Volatile
        private var appContext: Context? = null

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
        @Volatile
        private var liveStatsProvider: (() -> ProxyServerStats?)? = null
        @Volatile
        private var previousRun: PreviousRunCheck? = null
        @Volatile
        private var serviceStartedAt: String? = null
        @Volatile
        private var foregroundStartedAt: String? = null
        @Volatile
        private var serviceStartedAtMs: Long? = null
        @Volatile
        private var proxyStartedAtMs: Long? = null
        @Volatile
        private var lastStopReason: String? = null
        @Volatile
        private var lastForegroundTimeoutTimeMs: Long? = null
        @Volatile
        private var lastForegroundTimeoutReason: String? = null
        @Volatile
        private var lastWatchdogHeartbeatAt: String? = null
        @Volatile
        private var wakeLockHeld: Boolean = false
        @Volatile
        private var currentProcessStartTime: String? = null
        @Volatile
        private var currentProcessStartReason: String? = null
        @Volatile
        private var lastTrimMemoryLevel: Int? = null
        @Volatile
        private var lastTrimMemoryTimeMs: Long? = null
        @Volatile
        private var lastLowMemoryTimeMs: Long? = null
        private val trimMemoryCounts = linkedMapOf<Int, Long>()

        fun initialize(context: Context, openedBy: String) {
            appContext = context.applicationContext
            synchronized(initLock) {
                if (!persistenceConfigured) {
                    val runtimeDir = File(context.filesDir, "runtime_logs")
                    val restored = logStore.configurePersistence(FileRuntimeLogPersistence(File(runtimeDir, "current.log")))
                    runMarker = ProxyRunMarker(File(runtimeDir, "proxy_run.marker"))
                    persistenceConfigured = true
                    installCrashHandlerLocked()
                    currentProcessStartTime = Instant.now().toString()
                    currentProcessStartReason = openedBy.ifBlank { "unknown" }
                    addLog("app process diagnostics initialized", LogSeverity.INFO, "process")
                    addLog("=== Process started ${LocalDateTime.now().format(RuntimeLogStore.TIME_FORMATTER)} ===", LogSeverity.INFO, "process")
                    if (restored > 0) addLog("Restored $restored persisted log lines", LogSeverity.INFO, "process")
                    inspectPreviousRunLocked()
                }
            }
            addLog("=== App opened ${LocalDateTime.now().format(RuntimeLogStore.TIME_FORMATTER)} by $openedBy ===", LogSeverity.INFO, "ui")
        }

        private fun installCrashHandlerLocked() {
            if (crashHandlerInstalled) return
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                addLog(
                    "unhandled exception on ${thread.name}: ${error.javaClass.simpleName}: ${error.message ?: "no message"}",
                    LogSeverity.ERROR,
                    "crash",
                )
                runMarker?.markCrash(error.javaClass.name, error.message, error.stackTrace.firstOrNull()?.toString())
                error.stackTrace.take(6).forEach { frame -> addLog("  at $frame", LogSeverity.ERROR, "crash") }
                if (previous != null) {
                    previous.uncaughtException(thread, error)
                } else {
                    throw error
                }
            }
            crashHandlerInstalled = true
        }

        private fun inspectPreviousRunLocked() {
            val previous = runMarker?.inspectPreviousRun() ?: return
            previousRun = previous
            addLog(
                "previous run marker loaded: running=${previous.wasRunning} wasUnexpected=${previous.wasUnexpected} " +
                    "run_id=${previous.runId ?: "unknown"} started_at=${previous.startedAt ?: "unknown"} " +
                    "lastHeartbeatAt=${previous.lastHeartbeatAt ?: "unknown"} lastServiceEvent=${previous.lastServiceEvent ?: "unknown"} " +
                    "foregroundStartedAt=${previous.lastForegroundStartedAt ?: "unknown"} stoppedAt=${previous.stoppedAt ?: "unknown"} " +
                    "lastStopReason=${previous.lastStopReason ?: "unknown"}",
                LogSeverity.INFO,
                "service",
            )
            if (previous.wasUnexpected) {
                running = false
                lastStatus = "Previous proxy run ended unexpectedly; proxy is stopped"
                addLog(
                    "previous proxy run appears to have ended unexpectedly: run_id=${previous.runId ?: "unknown"} started_at=${previous.startedAt ?: "unknown"}",
                    LogSeverity.WARN,
                    "service",
                )
            } else if (previous.hadMarker) {
                addLog("previous proxy run was graceful: reason=${previous.lastStopReason ?: "unknown"}", LogSeverity.INFO, "service")
            }
        }

        fun markProxyStarted() {
            lastWatchdogHeartbeatAt = null
            proxyStartedAtMs = System.currentTimeMillis()
            lastStopReason = null
            val runId = runMarker?.markStarted(networkStatus, currentStatsSnapshot()?.effectiveRouteMode ?: "unknown", wakeLockHeld, foregroundStartedAt != null) ?: return
            if (foregroundStartedAt != null) runMarker?.markForegroundStarted()
            addLog("proxy run marker started: run_id=$runId", LogSeverity.INFO, "service")
        }

        fun markServiceStarted() {
            serviceStartedAt = Instant.now().toString()
            serviceStartedAtMs = System.currentTimeMillis()
        }

        fun markForegroundStarted() {
            foregroundStartedAt = Instant.now().toString()
            runMarker?.markForegroundStarted()
        }

        fun markWatchdogHeartbeat() {
            lastWatchdogHeartbeatAt = Instant.now().toString()
            runMarker?.markHeartbeat(networkStatus, currentStatsSnapshot()?.effectiveRouteMode ?: "unknown", wakeLockHeld, foregroundStartedAt != null)
        }

        fun markServiceEvent(event: String) {
            runMarker?.markServiceEvent(event, networkStatus, currentStatsSnapshot()?.effectiveRouteMode ?: "unknown", wakeLockHeld, foregroundStartedAt != null)
        }

        fun markTrimMemory(level: Int) {
            synchronized(trimMemoryCounts) {
                trimMemoryCounts[level] = (trimMemoryCounts[level] ?: 0L) + 1L
            }
            lastTrimMemoryLevel = level
            lastTrimMemoryTimeMs = System.currentTimeMillis()
            markServiceEvent("trim_memory_level_$level")
        }

        fun markLowMemory() {
            lastLowMemoryTimeMs = System.currentTimeMillis()
            markServiceEvent("low_memory")
        }

        fun markForegroundTimeout(timeMs: Long, reason: String) {
            lastForegroundTimeoutTimeMs = timeMs
            lastForegroundTimeoutReason = reason.ifBlank { "unknown" }
        }

        fun markProxyStopped(reason: String) {
            lastStopReason = reason.ifBlank { "unknown" }
            proxyStartedAtMs = null
            runMarker?.markStopped(reason)
            previousRun = runMarker?.inspectPreviousRun() ?: previousRun
            addLog("proxy run marker stopped: reason=$reason", LogSeverity.INFO, "service")
        }

        fun setRunning(isRunning: Boolean, status: String) {
            running = isRunning
            lastStatus = status
            addLog(status, RuntimeLogStore.classifySeverity(status), "service")
            ProxyQuickSettingsTileService.requestTileRefresh(appContext)
        }

        fun setBatteryOptimizationStatus(status: String) {
            batteryOptimizationStatus = status.ifBlank { "unknown" }
        }

        fun setNetworkStatus(status: String) {
            networkStatus = status.ifBlank { "unknown" }
        }

        fun setWakeLockHeld(isHeld: Boolean) {
            wakeLockHeld = isHeld
        }

        fun isWakeLockHeld(): Boolean = wakeLockHeld

        fun previousRunEndedUnexpectedly(): Boolean = previousRun?.wasUnexpected == true

        fun unexpectedStopDetected(): Boolean = previousRunEndedUnexpectedly()

        fun updateStats(stats: ProxyServerStats?) {
            statsSnapshot = stats
        }

        fun setLiveStatsProvider(provider: (() -> ProxyServerStats?)?) {
            liveStatsProvider = provider
        }

        private fun currentStatsSnapshot(): ProxyServerStats? {
            val liveStats = runCatching { liveStatsProvider?.invoke() }.getOrNull()
            if (liveStats != null) {
                statsSnapshot = liveStats
                return liveStats
            }
            return statsSnapshot
        }

        fun stats(): ProxyServerStats? = currentStatsSnapshot()

        fun statsLine(): String = DiagnosticReportFormatter.formatStats(currentStatsSnapshot())

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

        fun diagnosticReport(): String {
            val context = appContext
            val endpoint = context?.let { ProxyRuntimeConfig.endpointSummary(it) } ?: "unknown"
            val secret = context?.let { ProxyRuntimeConfig.partialTelegramSecret(it) } ?: "unknown"
            val secretSource = context?.let { ProxyRuntimeConfig.secretSource(it) } ?: "unknown"
            val proxyLinkCurrent = context?.let { ProxyRuntimeConfig.proxyLinkCurrent(it) } ?: false
            val dcSummary = context?.let { ProxyRuntimeConfig.dcSummary(it) } ?: "unknown"
            val routeMode = context?.let { ProxyRuntimeConfig.appConfig(it).routeMode.name } ?: "unknown"
            val targetSdk = context?.applicationInfo?.targetSdkVersion ?: 0
            val fallbackEffectiveRouteMode = context?.let { ProxyRuntimeConfig.proxyServerConfig(it, networkStatus).effectiveRouteMode.name } ?: "unknown"
            val (logs, logMetadata) = logStore.snapshotWithMetadata()
            val runtimeLogTailUntilMs = System.currentTimeMillis()
            val stats = currentStatsSnapshot()
            val networkDiagnostics = context?.let { collectNetworkDiagnostics(it, stats) } ?: NetworkDiagnostics()
            val exitReasons = context?.let { collectHistoricalExitReasons(it) }
                ?: ProcessExitReasonDiagnostics(available = false, errorClass = "NoContext", errorMessage = "Application context unavailable")
            val effectiveRouteMode = stats?.effectiveRouteMode ?: fallbackEffectiveRouteMode
            return DiagnosticReportFormatter.format(
                DiagnosticReportFormatter.snapshot(
                    status = lastStatus,
                    endpoint = endpoint,
                    secret = secret,
                    secretSource = secretSource,
                    proxyLinkCurrent = proxyLinkCurrent,
                    dcSummary = dcSummary,
                    batteryOptimization = batteryOptimizationStatus,
                    network = networkStatus,
                    routeMode = routeMode,
                    effectiveRouteMode = effectiveRouteMode,
                    declaredForegroundServiceStrategy = declaredForegroundServiceStrategy(),
                    runtimeForegroundServiceType = runtimeForegroundServiceTypeName(),
                    foregroundServiceType = foregroundServiceTypeFromManifestBuildStrategy(),
                    targetSdk = targetSdk,
                    serviceStartTime = serviceStartedAt,
                    foregroundStartTime = foregroundStartedAt,
                    lastWatchdogHeartbeat = lastWatchdogHeartbeatAt,
                    wakeLockHeld = wakeLockHeld,
                    previousRun = previousRun,
                    currentProcessStartTime = currentProcessStartTime,
                    currentProcessStartReason = currentProcessStartReason,
                    historicalExitReasons = exitReasons,
                    trimMemory = TrimMemoryDiagnostics(
                        lastTrimMemoryLevel = lastTrimMemoryLevel,
                        lastTrimMemoryTimeMs = lastTrimMemoryTimeMs,
                        trimMemoryCountByLevel = synchronized(trimMemoryCounts) { trimMemoryCounts.toMap() },
                        lastLowMemoryTimeMs = lastLowMemoryTimeMs,
                    ),
                    previousEffectiveRouteMode = stats?.previousEffectiveRouteMode,
                    lastRouteChangeReason = stats?.lastRouteChangeReason ?: "unknown",
                    lastRouteChangeTimeMs = stats?.lastRouteChangeTimeMs,
                    networkAtLastRouteChange = stats?.networkAtLastRouteChange ?: "unknown",
                    stats = stats,
                    statsSnapshotTimeMs = stats?.statsSnapshotTimeMs?.takeIf { it > 0L },
                    runtimeLogTailUntilMs = runtimeLogTailUntilMs,
                    lastEffectiveRouteModeUpdateTimeMs = stats?.lastEffectiveRouteModeUpdateTimeMs,
                    lastRouteUsedUpdateTimeMs = stats?.lastRouteUsedUpdateTimeMs,
                    applicationId = BuildConfig.APPLICATION_ID,
                    versionName = BuildConfig.VERSION_NAME,
                    versionCode = BuildConfig.VERSION_CODE.toLong(),
                    buildType = BuildConfig.BUILD_TYPE,
                    flavor = BuildConfig.BUILD_FLAVOR_NAME,
                    debuggable = BuildConfig.DEBUG,
                    gitCommitSha = BuildConfig.GIT_COMMIT_SHA,
                    lastStopReason = lastStopReason,
                    lastForegroundTimeoutTimeMs = lastForegroundTimeoutTimeMs,
                    lastForegroundTimeoutReason = lastForegroundTimeoutReason,
                    androidSdkInt = Build.VERSION.SDK_INT,
                    androidRelease = Build.VERSION.RELEASE ?: "unknown",
                    manufacturer = Build.MANUFACTURER ?: "unknown",
                    model = Build.MODEL ?: "unknown",
                    notificationPermissionStatus = context?.let { notificationPermissionStatus(it) } ?: "unknown",
                    normalizedNetworkType = networkDiagnostics.normalizedType,
                    activeNetworkMetered = networkDiagnostics.metered,
                    networkCapabilitySummary = networkDiagnostics.capabilitySummary,
                    networkGeneration = stats?.networkGeneration,
                    lastNetworkAvailableAtMs = stats?.lastNetworkAvailableAtMs?.takeIf { it > 0L },
                    lastNetworkLostAtMs = stats?.lastNetworkLostAtMs?.takeIf { it > 0L },
                    serviceUptimeMs = serviceStartedAtMs?.let { (runtimeLogTailUntilMs - it).coerceAtLeast(0L) },
                    proxyUptimeMs = proxyStartedAtMs?.let { (runtimeLogTailUntilMs - it).coerceAtLeast(0L) },
                    logMetadata = logMetadata,
                    logs = logs,
                ),
            )
        }

        private data class NetworkDiagnostics(
            val normalizedType: String = "unknown",
            val metered: String = "unknown",
            val capabilitySummary: String = "unknown",
        )

        private fun collectNetworkDiagnostics(context: Context, stats: ProxyServerStats?): NetworkDiagnostics = try {
            val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
            val activeNetwork = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            NetworkDiagnostics(
                normalizedType = capabilities?.let { normalizedNetworkType(it) } ?: normalizedNetworkType(stats?.lastNetworkTypeAtRouteAttempt),
                metered = if (activeNetwork == null) "unknown" else if (connectivityManager.isActiveNetworkMetered) "metered" else "unmetered",
                capabilitySummary = networkCapabilitySummary(capabilities),
            )
        } catch (_: Throwable) {
            NetworkDiagnostics(normalizedType = normalizedNetworkType(networkStatus))
        }

        private fun normalizedNetworkType(capabilities: NetworkCapabilities?): String = when {
            capabilities == null -> "None"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile"
            else -> "Unknown"
        }

        private fun normalizedNetworkType(status: String?): String = when (status?.lowercase(Locale.US)) {
            "wi-fi", "wifi" -> "Wi-Fi"
            "mobile", "cellular" -> "Mobile"
            "none" -> "None"
            else -> "Unknown"
        }

        private fun networkCapabilitySummary(capabilities: NetworkCapabilities?): String {
            if (capabilities == null) return "available=false validated=false captive=false"
            val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val captive = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
            val available = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            return "available=$available validated=$validated captive=$captive"
        }

        private fun collectHistoricalExitReasons(context: Context): ProcessExitReasonDiagnostics {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                return ProcessExitReasonDiagnostics(
                    available = false,
                    errorClass = "UnsupportedApi",
                    errorMessage = "ApplicationExitInfo requires Android 11/API 30",
                )
            }
            return try {
                val activityManager = context.getSystemService(ActivityManager::class.java)
                val entries = activityManager.getHistoricalProcessExitReasons(context.packageName, 0, 5).map { info ->
                    ProcessExitReasonEntry(
                        timestamp = info.timestamp,
                        reasonCode = info.reason,
                        reasonLabel = applicationExitReasonLabel(info.reason),
                        status = info.status,
                        importance = info.importance,
                        pss = info.pss,
                        rss = info.rss,
                        description = info.description,
                        processName = info.processName,
                        pid = info.pid,
                        traceInputStreamPresent = runCatching { info.traceInputStream?.use { true } ?: false }.getOrDefault(false),
                    )
                }
                ProcessExitReasonDiagnostics(available = true, entries = entries)
            } catch (error: Throwable) {
                ProcessExitReasonDiagnostics(
                    available = false,
                    errorClass = error.javaClass.simpleName,
                    errorMessage = error.message ?: "no message",
                )
            }
        }

        fun applicationExitReasonLabel(reason: Int): String = when (reason) {
            0 -> "UNKNOWN"
            1 -> "EXIT_SELF"
            2 -> "SIGNALED"
            3 -> "LOW_MEMORY"
            4 -> "CRASH"
            5 -> "CRASH_NATIVE"
            6 -> "ANR"
            7 -> "INITIALIZATION_FAILURE"
            8 -> "PERMISSION_CHANGE"
            9 -> "EXCESSIVE_RESOURCE_USAGE"
            10 -> "USER_REQUESTED"
            11 -> "USER_STOPPED"
            12 -> "DEPENDENCY_DIED"
            13 -> "OTHER"
            14 -> "FREEZER"
            15 -> "PACKAGE_STATE_CHANGE"
            16 -> "PACKAGE_UPDATED"
            else -> "UNKNOWN_$reason"
        }

        private fun notificationPermissionStatus(context: Context): String = try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                "not_required"
            } else if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                "granted"
            } else {
                "denied"
            }
        } catch (_: Throwable) {
            "unknown"
        }
    }
}
