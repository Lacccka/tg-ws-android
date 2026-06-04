package com.flowseal.tgwsandroid

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.flowseal.tgwsandroid.service.LogSeverity
import com.flowseal.tgwsandroid.service.ProxyForegroundService
import com.flowseal.tgwsandroid.service.ProxyRuntimeConfig

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusText: TextView
    private lateinit var diagnosticsText: TextView
    private lateinit var logsText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var connectTelegramButton: Button
    private lateinit var clearLogsButton: Button
    private lateinit var copyLogsButton: Button
    private lateinit var shareLogsButton: Button
    private lateinit var batterySettingsButton: Button

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshState()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(buildContentView())
        startButton.setOnClickListener {
            requestNotificationPermissionIfNeeded()
            startProxyService()
        }
        stopButton.setOnClickListener {
            startService(ProxyForegroundService.stopIntent(this))
        }
        connectTelegramButton.setOnClickListener {
            openTelegramProxyLink()
        }
        clearLogsButton.setOnClickListener {
            ProxyForegroundService.State.clearLogs()
            refreshState()
        }
        copyLogsButton.setOnClickListener {
            copyLogs()
        }
        shareLogsButton.setOnClickListener {
            shareLogs()
        }
        batterySettingsButton.setOnClickListener {
            openBatterySettings()
        }
        refreshState()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    private fun buildContentView(): LinearLayout {
        val density = resources.displayMetrics.density
        val padding = (16 * density).toInt()
        val smallPadding = (8 * density).toInt()

        statusText = TextView(this).apply {
            textSize = 18f
            setPadding(0, 0, 0, smallPadding)
        }

        val configText = TextView(this).apply {
            text = buildString {
                appendLine("Endpoint: ${ProxyRuntimeConfig.endpointSummary()}")
                appendLine("Secret: ${ProxyRuntimeConfig.partialTelegramSecret()}")
                appendLine("DCs: ${ProxyRuntimeConfig.dcSummary()}")
                appendLine("Hint: tap Start proxy before connecting Telegram.")
            }
            setPadding(0, 0, 0, smallPadding)
        }

        diagnosticsText = TextView(this).apply {
            setPadding(0, 0, 0, smallPadding)
        }

        startButton = Button(this).apply { text = "Start proxy" }
        stopButton = Button(this).apply { text = "Stop proxy" }
        connectTelegramButton = Button(this).apply { text = "Connect in Telegram" }
        clearLogsButton = Button(this).apply { text = "Clear logs" }
        copyLogsButton = Button(this).apply { text = "Copy logs" }
        shareLogsButton = Button(this).apply { text = "Share logs" }
        batterySettingsButton = Button(this).apply { text = "Battery settings" }

        logsText = TextView(this).apply {
            text = "No logs yet"
            setTextIsSelectable(true)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        val logsScroll = ScrollView(this).apply {
            addView(logsText)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }

        val logsButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(clearLogsButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(copyLogsButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(shareLogsButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            applySystemInsetsPadding(basePadding = padding)
            addView(TextView(this@MainActivity).apply {
                text = "TG WS Android"
                textSize = 22f
                setPadding(0, 0, 0, smallPadding)
            }, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(statusText, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(configText, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(diagnosticsText, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(startButton, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(stopButton, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(connectTelegramButton, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(batterySettingsButton, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(TextView(this@MainActivity).apply {
                text = "Recent logs"
                textSize = 16f
                setPadding(0, smallPadding, 0, smallPadding)
            })
            addView(logsButtons, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(logsScroll)
        }
    }

    private fun LinearLayout.applySystemInsetsPadding(basePadding: Int) {
        setPadding(basePadding, basePadding, basePadding, basePadding)
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val systemInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.updatePadding(
                left = basePadding + systemInsets.left,
                top = basePadding + systemInsets.top,
                right = basePadding + systemInsets.right,
                bottom = basePadding + systemInsets.bottom,
            )
            insets
        }
    }

    private fun startProxyService() {
        val intent = ProxyForegroundService.startIntent(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun openTelegramProxyLink() {
        val telegramIntent = Intent(Intent.ACTION_VIEW, Uri.parse(ProxyRuntimeConfig.telegramProxyUri()))
        if (tryStartActivity(telegramIntent)) return

        val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(ProxyRuntimeConfig.telegramProxyUrl()))
        if (tryStartActivity(fallbackIntent)) return

        copyProxyLink()
        Toast.makeText(this, "No app can open the proxy link; copied instead", Toast.LENGTH_LONG).show()
    }

    private fun tryStartActivity(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    private fun copyProxyLink() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Telegram proxy link", ProxyRuntimeConfig.telegramProxyUrl()))
    }

    private fun copyLogs() {
        if (!ProxyForegroundService.State.hasLogs()) {
            Toast.makeText(this, "No logs to copy", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("TG WS Android logs", ProxyForegroundService.State.diagnosticReport()))
        Toast.makeText(this, "Logs copied", Toast.LENGTH_SHORT).show()
    }

    private fun shareLogs() {
        if (!ProxyForegroundService.State.hasLogs()) {
            Toast.makeText(this, "No logs to share", Toast.LENGTH_SHORT).show()
            return
        }
        val diagnosticsReport = ProxyForegroundService.State.diagnosticReport()
        if (diagnosticsReport.isBlank()) {
            Toast.makeText(this, "No logs to share", Toast.LENGTH_SHORT).show()
            return
        }

        val logsUri = try {
            DiagnosticsFileExporter(this).export(diagnosticsReport)
        } catch (error: Throwable) {
            ProxyForegroundService.State.addLog(
                "Failed to export logs: ${error.message ?: error.javaClass.simpleName}",
                LogSeverity.ERROR,
                "ui",
            )
            Toast.makeText(this, "Failed to export logs", Toast.LENGTH_LONG).show()
            return
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "TG WS Android diagnostics")
            putExtra(Intent.EXTRA_TEXT, "TG WS Android diagnostics log attached.")
            putExtra(Intent.EXTRA_STREAM, logsUri)
            clipData = ClipData.newUri(contentResolver, "TG WS Android diagnostics", logsUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, "Share TG WS Android logs"))
        } catch (_: ActivityNotFoundException) {
            ProxyForegroundService.State.addLog("No app can share logs", LogSeverity.WARN, "ui")
            Toast.makeText(this, "No app can share logs", Toast.LENGTH_LONG).show()
        }
    }

    private fun openBatterySettings() {
        if (detectBatteryOptimizationStatus() == "optimized") {
            val packageUri = Uri.parse("package:$packageName")
            val requestIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri)
            if (tryStartActivity(requestIntent)) return
        }

        val settingsIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        if (tryStartActivity(settingsIntent)) return

        Toast.makeText(this, "Battery settings cannot be opened", Toast.LENGTH_LONG).show()
    }

    private fun refreshState() {
        refreshLocalDiagnostics()
        val running = ProxyForegroundService.State.running
        statusText.text = "Status: ${ProxyForegroundService.State.lastStatus}"
        diagnosticsText.text = buildString {
            appendLine("Battery optimization: ${ProxyForegroundService.State.batteryOptimizationStatus}")
            appendLine("Network: ${ProxyForegroundService.State.networkStatus}")
            append("Stats: ${ProxyForegroundService.State.statsLine()}")
        }
        startButton.isEnabled = !running
        stopButton.isEnabled = running
        connectTelegramButton.isEnabled = true
        logsText.text = ProxyForegroundService.State.recentLogs().takeIf { it.isNotEmpty() }?.joinToString("\n") ?: "No logs yet"
    }

    private fun refreshLocalDiagnostics() {
        ProxyForegroundService.State.setBatteryOptimizationStatus(detectBatteryOptimizationStatus())
        if (!ProxyForegroundService.State.running) {
            ProxyForegroundService.State.setNetworkStatus(detectNetworkStatus())
        }
    }

    private fun detectBatteryOptimizationStatus(): String = try {
        val powerManager = getSystemService(PowerManager::class.java)
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) "unrestricted" else "optimized"
    } catch (_: Throwable) {
        "unknown"
    }

    private fun detectNetworkStatus(): String = try {
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork) ?: return "none"
        when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "unknown"
        }
    } catch (_: Throwable) {
        "unknown"
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_POST_NOTIFICATIONS)
    }

    companion object {
        private const val REFRESH_MS = 1_000L
        private const val REQUEST_POST_NOTIFICATIONS = 2001
    }
}
