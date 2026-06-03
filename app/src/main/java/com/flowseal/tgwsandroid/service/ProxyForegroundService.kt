package com.flowseal.tgwsandroid.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.flowseal.tgwsandroid.MainActivity
import com.flowseal.tgwsandroid.proxy.ProxyLogger
import com.flowseal.tgwsandroid.proxy.ProxyServer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ProxyForegroundService : Service() {
    private val lock = Any()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ProxyForegroundService-worker").also { it.isDaemon = true }
    }
    private var proxyServer: ProxyServer? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopProxyAsync()
            ACTION_START, null -> startProxyAsync()
            else -> updateStatus("Ignored unknown action: ${intent.action}")
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopProxyBlocking()
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun startProxyAsync() {
        startForegroundCompat(buildNotification())
        executor.execute {
            synchronized(lock) {
                if (proxyServer?.isRunning == true) {
                    State.setRunning(true, "Proxy already running on ${ProxyRuntimeConfig.endpointSummary()}")
                    return@execute
                }
                val logger = ProxyLogger { message -> State.addLog(message) }
                val server = ProxyServer(ProxyRuntimeConfig.proxyServerConfig(), logger = logger)
                proxyServer = server
                try {
                    server.start()
                    State.setRunning(true, "Proxy running on ${ProxyRuntimeConfig.endpointSummary()}")
                } catch (error: Throwable) {
                    proxyServer = null
                    State.setRunning(false, "Proxy start failed: ${error.message ?: error::class.java.simpleName}")
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
        val server = synchronized(lock) {
            proxyServer.also { proxyServer = null }
        }
        if (server != null) {
            try {
                server.stop()
            } catch (error: Throwable) {
                State.addLog("Proxy stop failed: ${error.message ?: error::class.java.simpleName}")
            }
        }
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

    companion object {
        const val ACTION_START = "com.flowseal.tgwsandroid.action.START_PROXY"
        const val ACTION_STOP = "com.flowseal.tgwsandroid.action.STOP_PROXY"
        private const val CHANNEL_ID = "proxy_foreground"
        private const val NOTIFICATION_ID = 1001

        fun startIntent(context: Context): Intent = Intent(context, ProxyForegroundService::class.java).setAction(ACTION_START)

        fun stopIntent(context: Context): Intent = Intent(context, ProxyForegroundService::class.java).setAction(ACTION_STOP)
    }

    object State {
        private const val MAX_LOG_LINES = 80
        private val logLock = Any()
        private val timestampFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
        @Volatile
        var running: Boolean = false
            private set
        @Volatile
        var lastStatus: String = "Proxy stopped"
            private set

        private val logs: ArrayDeque<String> = ArrayDeque()

        fun setRunning(isRunning: Boolean, status: String) {
            running = isRunning
            lastStatus = status
            addLog(status)
        }

        fun addLog(message: String) {
            synchronized(logLock) {
                val line = "${timestampFormat.format(Date())} $message"
                logs.addLast(line)
                while (logs.size > MAX_LOG_LINES) {
                    logs.removeFirst()
                }
            }
        }

        fun recentLogs(): List<String> = synchronized(logLock) { logs.toList() }
    }
}
