package com.flowseal.tgwsandroid

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import com.flowseal.tgwsandroid.config.AppConfigStore
import com.flowseal.tgwsandroid.service.LogSeverity
import com.flowseal.tgwsandroid.service.ProxyForegroundService
import com.flowseal.tgwsandroid.service.ProxyRuntimeConfig

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusText: TextView
<<<<<<< ours
    private lateinit var configText: TextView
    private lateinit var diagnosticsText: TextView
=======
    private lateinit var networkText: TextView
    private lateinit var batteryText: TextView
    private lateinit var lastStatusText: TextView
    private lateinit var endpointText: TextView
    private lateinit var secretText: TextView
    private lateinit var dcText: TextView
    private lateinit var cfFallbackText: TextView
    private lateinit var statsText: TextView
>>>>>>> theirs
    private lateinit var logsText: TextView
    private lateinit var primaryControlButton: Button
    private lateinit var restartButton: Button
    private lateinit var resetSecretButton: Button
    private lateinit var connectTelegramButton: Button
    private lateinit var copyProxyLinkButton: Button
<<<<<<< ours
    private lateinit var resetSecretButton: Button
    private lateinit var resetConfigButton: Button
=======
>>>>>>> theirs
    private lateinit var clearLogsButton: Button
    private lateinit var copyDiagnosticsButton: Button
    private lateinit var shareDiagnosticsButton: Button
    private lateinit var batterySettingsButton: Button

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshState()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProxyRuntimeConfig.initialize(applicationContext)
        ProxyForegroundService.State.initialize(applicationContext, "activity")
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(buildContentView())
        primaryControlButton.setOnClickListener {
            if (ProxyForegroundService.State.running) {
                startService(ProxyForegroundService.stopIntent(this))
            } else {
                requestNotificationPermissionIfNeeded()
                startProxyService()
            }
        }
        restartButton.setOnClickListener {
            restartProxyService()
        }
        resetSecretButton.setOnClickListener {
            resetSecret()
        }
        connectTelegramButton.setOnClickListener {
            openTelegramProxyLink()
        }
        copyProxyLinkButton.setOnClickListener {
            copyProxyLink()
            Toast.makeText(this, "Proxy link copied", Toast.LENGTH_SHORT).show()
        }
<<<<<<< ours
        resetSecretButton.setOnClickListener {
            AppConfigStore.resetSecret(this)
            refreshState()
            Toast.makeText(this, "Secret reset", Toast.LENGTH_SHORT).show()
        }
        resetConfigButton.setOnClickListener {
            AppConfigStore.resetConfig(this)
            refreshState()
            Toast.makeText(this, "Config reset", Toast.LENGTH_SHORT).show()
        }
=======
>>>>>>> theirs
        clearLogsButton.setOnClickListener {
            ProxyForegroundService.State.clearLogs()
            refreshState()
        }
        copyDiagnosticsButton.setOnClickListener {
            copyDiagnostics()
        }
        shareDiagnosticsButton.setOnClickListener {
            shareDiagnostics()
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

    private fun buildContentView(): ScrollView {
        val density = resources.displayMetrics.density
        val padding = (16 * density).toInt()
        val smallPadding = (8 * density).toInt()
        val rowGap = (6 * density).toInt()

        statusText = createValueText(textSize = 22f, bold = true)
        networkText = createValueText()
        batteryText = createValueText()
        lastStatusText = createValueText()
        endpointText = createValueText()
        secretText = createValueText()
        dcText = createValueText()
        cfFallbackText = createValueText()
        statsText = createValueText()

        primaryControlButton = createButton("Start proxy")
        restartButton = createButton("Restart proxy")
        resetSecretButton = createButton("Reset secret")
        connectTelegramButton = createButton("Connect in Telegram")
        copyProxyLinkButton = createButton("Copy proxy link")
        clearLogsButton = createButton("Clear logs")
        copyDiagnosticsButton = createButton("Copy diagnostics")
        shareDiagnosticsButton = createButton("Share diagnostics")
        batterySettingsButton = createButton("Battery settings")

        logsText = TextView(this).apply {
            text = "No logs yet"
            setTextColor(COLOR_TEXT_SECONDARY)
            textSize = 13f
            setTextIsSelectable(true)
        }

<<<<<<< ours
        configText = TextView(this).apply {
            setPadding(0, 0, 0, smallPadding)
        }
=======
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(COLOR_BACKGROUND)
            applySystemInsetsPadding(basePadding = padding)
>>>>>>> theirs

            addView(TextView(this@MainActivity).apply {
                text = "TG WS Android"
                textSize = 26f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLOR_TEXT_PRIMARY)
            }, matchWrapParams())
            addView(TextView(this@MainActivity).apply {
                text = "Local Telegram WebSocket proxy"
                textSize = 15f
                setTextColor(COLOR_TEXT_SECONDARY)
                setPadding(0, smallPadding / 2, 0, smallPadding)
            }, matchWrapParams())

            addView(createCard("Status") {
                addView(createTextRow("Status", statusText, rowGap))
                addView(createTextRow("Network", networkText, rowGap))
                addView(createTextRow("Battery optimization", batteryText, rowGap))
                addView(createTextRow("Last status", lastStatusText, rowGap))
                addView(batterySettingsButton, matchWrapParams(topMargin = smallPadding))
            }, cardParams(topMargin = smallPadding))

            addView(createCard("Connection") {
                addView(createTextRow("Endpoint", endpointText, rowGap))
                addView(createTextRow("Secret", secretText, rowGap))
                addView(createTextRow("DC summary", dcText, rowGap))
                addView(createTextRow("CF fallback", cfFallbackText, rowGap))
                addView(connectTelegramButton, matchWrapParams(topMargin = smallPadding))
                addView(copyProxyLinkButton, matchWrapParams(topMargin = rowGap))
            }, cardParams(topMargin = padding))

            addView(createCard("Controls") {
                addView(primaryControlButton, matchWrapParams())
                addView(restartButton, matchWrapParams(topMargin = rowGap))
                addView(resetSecretButton, matchWrapParams(topMargin = rowGap))
            }, cardParams(topMargin = padding))

            addView(createCard("Diagnostics") {
                addView(createTextRow("Stats", statsText, rowGap))
                addView(createSectionTitle("Recent logs"), matchWrapParams(topMargin = smallPadding))
                addView(logsText, matchWrapParams(topMargin = rowGap))
                addView(shareDiagnosticsButton, matchWrapParams(topMargin = smallPadding))
                addView(copyDiagnosticsButton, matchWrapParams(topMargin = rowGap))
                addView(clearLogsButton, matchWrapParams(topMargin = rowGap))
            }, cardParams(topMargin = padding, bottomMargin = padding))
        }

<<<<<<< ours
        startButton = Button(this).apply { text = "Start proxy" }
        stopButton = Button(this).apply { text = "Stop proxy" }
        connectTelegramButton = Button(this).apply { text = "Connect in Telegram" }
        copyProxyLinkButton = Button(this).apply { text = "Copy proxy link" }
        resetSecretButton = Button(this).apply { text = "Reset secret" }
        resetConfigButton = Button(this).apply { text = "Reset config" }
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
=======
        return ScrollView(this).apply {
            setBackgroundColor(COLOR_BACKGROUND)
            addView(content, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
>>>>>>> theirs
        }
    }

    private fun createCard(title: String, body: LinearLayout.() -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.WHITE)
                cornerRadius = 16 * resources.displayMetrics.density
                setStroke((1 * resources.displayMetrics.density).toInt().coerceAtLeast(1), COLOR_CARD_STROKE)
            }
            val innerPadding = (16 * resources.displayMetrics.density).toInt()
            setPadding(innerPadding, innerPadding, innerPadding, innerPadding)
            addView(createSectionTitle(title), matchWrapParams())
            body()
        }

    private fun createSectionTitle(title: String): TextView = TextView(this).apply {
        text = title
        textSize = 17f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(COLOR_TEXT_PRIMARY)
    }

    private fun createButton(label: String): Button = Button(this).apply {
        text = label
        isAllCaps = false
    }

    private fun createTextRow(label: String, value: TextView, bottomPadding: Int): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, bottomPadding, 0, 0)
            addView(TextView(this@MainActivity).apply {
<<<<<<< ours
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
            addView(copyProxyLinkButton, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(resetSecretButton, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(resetConfigButton, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(batterySettingsButton, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(TextView(this@MainActivity).apply {
                text = "Recent logs"
                textSize = 16f
                setPadding(0, smallPadding, 0, smallPadding)
            })
            addView(logsButtons, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(logsScroll)
=======
                text = label
                textSize = 12f
                setTextColor(COLOR_TEXT_MUTED)
                typeface = Typeface.DEFAULT_BOLD
            }, matchWrapParams())
            addView(value, matchWrapParams(topMargin = 2))
>>>>>>> theirs
        }

    private fun createValueText(textSize: Float = 14f, bold: Boolean = false): TextView = TextView(this).apply {
        this.textSize = textSize
        setTextColor(COLOR_TEXT_PRIMARY)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun matchWrapParams(topMargin: Int = 0, bottomMargin: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            this.topMargin = topMargin
            this.bottomMargin = bottomMargin
        }

    private fun cardParams(topMargin: Int = 0, bottomMargin: Int = 0): LinearLayout.LayoutParams =
        matchWrapParams(topMargin, bottomMargin)

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

    private fun restartProxyService() {
        requestNotificationPermissionIfNeeded()
        if (!ProxyForegroundService.State.running) {
            startProxyService()
            return
        }

        startService(ProxyForegroundService.stopIntent(this))
        handler.postDelayed({ startProxyService() }, RESTART_DELAY_MS)
    }

    private fun resetSecret() {
        val wasRunning = ProxyForegroundService.State.running
        ProxyRuntimeConfig.resetSecret(applicationContext)
        ProxyForegroundService.State.addLog("proxy secret reset", LogSeverity.INFO, "ui")
        refreshState()
        val message = if (wasRunning) "Restart proxy to apply new secret" else "Secret reset"
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun openTelegramProxyLink() {
        val telegramIntent = Intent(Intent.ACTION_VIEW, Uri.parse(ProxyRuntimeConfig.telegramProxyUri(this)))
        if (tryStartActivity(telegramIntent)) return

        val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(ProxyRuntimeConfig.telegramProxyUrl(this)))
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
        clipboard.setPrimaryClip(ClipData.newPlainText("Telegram proxy link", ProxyRuntimeConfig.telegramProxyUrl(this)))
    }

    private fun copyDiagnostics() {
        if (!ProxyForegroundService.State.hasLogs()) {
            Toast.makeText(this, "No diagnostics to copy", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("TG WS Android diagnostics", ProxyForegroundService.State.diagnosticReport()))
        Toast.makeText(this, "Diagnostics copied", Toast.LENGTH_SHORT).show()
    }

    private fun copyDiagnosticsToClipboard(diagnosticsReport: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("TG WS Android diagnostics", diagnosticsReport))
    }

    private fun shareDiagnostics() {
        if (!ProxyForegroundService.State.hasLogs()) {
            Toast.makeText(this, "No diagnostics to share", Toast.LENGTH_SHORT).show()
            return
        }
        val diagnosticsReport = ProxyForegroundService.State.diagnosticReport()
        if (diagnosticsReport.isBlank()) {
            Toast.makeText(this, "No diagnostics to share", Toast.LENGTH_SHORT).show()
            return
        }

        val export = try {
            DiagnosticsFileExporter(this).exportWithFile(diagnosticsReport)
        } catch (error: Throwable) {
            ProxyForegroundService.State.addLog(
                "Failed to export diagnostics: ${error.javaClass.simpleName}: ${error.message ?: "no message"}",
                LogSeverity.ERROR,
                "ui",
            )
            copyDiagnosticsToClipboard(diagnosticsReport)
            Toast.makeText(this, "Failed to share file; diagnostics copied", Toast.LENGTH_LONG).show()
            return
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "TG WS Android diagnostics")
            putExtra(Intent.EXTRA_TEXT, "TG WS Android diagnostics log attached.")
            putExtra(Intent.EXTRA_STREAM, export.uri)
            clipData = ClipData.newUri(contentResolver, export.file.name, export.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, "Share TG WS Android diagnostics"))
        } catch (_: ActivityNotFoundException) {
            ProxyForegroundService.State.addLog("No app can share diagnostics", LogSeverity.WARN, "ui")
            Toast.makeText(this, "No app can share diagnostics", Toast.LENGTH_LONG).show()
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
        ProxyRuntimeConfig.initialize(applicationContext)
        refreshLocalDiagnostics()
        val running = ProxyForegroundService.State.running
<<<<<<< ours
        configText.text = buildString {
            appendLine("Endpoint: ${ProxyRuntimeConfig.endpointSummary(this@MainActivity)}")
            appendLine("Secret: ${ProxyRuntimeConfig.partialTelegramSecret(this@MainActivity)}")
            appendLine("DCs: ${ProxyRuntimeConfig.dcSummary(this@MainActivity)}")
            appendLine("CF fallback: ${ProxyRuntimeConfig.cfFallbackSummary(this@MainActivity)}")
            appendLine("Hint: tap Start proxy before connecting Telegram.")
        }
        statusText.text = "Status: ${ProxyForegroundService.State.lastStatus}"
        diagnosticsText.text = buildString {
            appendLine("Battery optimization: ${ProxyForegroundService.State.batteryOptimizationStatus}")
            appendLine("Network: ${ProxyForegroundService.State.networkStatus}")
            append("Stats: ${ProxyForegroundService.State.statsLine()}")
        }
        startButton.isEnabled = !running
        stopButton.isEnabled = running
=======
        val statusLabel = if (running) "Running" else if (ProxyForegroundService.State.lastStatus.contains("failed", ignoreCase = true)) "Error" else "Stopped"
        statusText.text = statusLabel
        networkText.text = ProxyForegroundService.State.networkStatus
        batteryText.text = ProxyForegroundService.State.batteryOptimizationStatus
        lastStatusText.text = ProxyForegroundService.State.lastStatus
        endpointText.text = ProxyRuntimeConfig.endpointSummary()
        secretText.text = ProxyRuntimeConfig.partialTelegramSecret()
        dcText.text = ProxyRuntimeConfig.dcSummary()
        cfFallbackText.text = ProxyRuntimeConfig.cfFallbackSummary()
        statsText.text = ProxyForegroundService.State.statsLine()
        primaryControlButton.text = if (running) "Stop proxy" else "Start proxy"
        restartButton.isEnabled = true
>>>>>>> theirs
        connectTelegramButton.isEnabled = true
        logsText.text = ProxyForegroundService.State.recentLogs()
            .takeLast(MAX_VISIBLE_LOG_LINES)
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n")
            ?: "No logs yet"
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
        private const val RESTART_DELAY_MS = 350L
        private const val REQUEST_POST_NOTIFICATIONS = 2001
        private const val MAX_VISIBLE_LOG_LINES = 8
        private const val COLOR_BACKGROUND = 0xFFF6F7FB.toInt()
        private const val COLOR_CARD_STROKE = 0xFFE5E7EB.toInt()
        private const val COLOR_TEXT_PRIMARY = 0xFF111827.toInt()
        private const val COLOR_TEXT_SECONDARY = 0xFF4B5563.toInt()
        private const val COLOR_TEXT_MUTED = 0xFF6B7280.toInt()
    }
}
