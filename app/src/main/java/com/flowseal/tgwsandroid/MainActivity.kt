package com.flowseal.tgwsandroid

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
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
import android.view.View
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
import com.flowseal.tgwsandroid.proxy.NetworkRouteMode
import com.flowseal.tgwsandroid.service.LogSeverity
import com.flowseal.tgwsandroid.service.ProxyForegroundService
import com.flowseal.tgwsandroid.service.ProxyRuntimeConfig

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var statusText: TextView
    private lateinit var networkText: TextView
    private lateinit var batteryText: TextView
    private lateinit var lastStatusText: TextView
    private lateinit var endpointText: TextView
    private lateinit var secretText: TextView
    private lateinit var dcText: TextView
    private lateinit var cfFallbackText: TextView
    private lateinit var routeModeText: TextView
    private lateinit var lastRouteChangeText: TextView
    private lateinit var statsText: TextView
    private lateinit var logsText: TextView
    private lateinit var restartRequiredText: TextView
    private lateinit var telegramCleanupHintText: TextView
    private lateinit var advancedCard: LinearLayout

    private var pendingRestartRequired: Boolean = false
    private var showTelegramCleanupHint: Boolean = false
    private var advancedExpanded: Boolean = false

    private lateinit var primaryControlButton: Button
    private lateinit var restartDashboardButton: Button
    private lateinit var restartAdvancedButton: Button
    private lateinit var resetSecretButton: Button
    private lateinit var routeModeButton: Button
    private lateinit var connectTelegramButton: Button
    private lateinit var advancedToggleButton: Button
    private lateinit var copyProxyLinkButton: Button
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
        pendingRestartRequired = savedInstanceState?.getBoolean(KEY_PENDING_RESTART_REQUIRED) ?: false
        showTelegramCleanupHint = savedInstanceState?.getBoolean(KEY_SHOW_TELEGRAM_CLEANUP_HINT) ?: false
        advancedExpanded = savedInstanceState?.getBoolean(KEY_ADVANCED_EXPANDED) ?: false
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
        restartDashboardButton.setOnClickListener { restartProxyService() }
        restartAdvancedButton.setOnClickListener { restartProxyService() }
        resetSecretButton.setOnClickListener { confirmResetSecret() }
        routeModeButton.setOnClickListener { showRouteModeDialog() }
        connectTelegramButton.setOnClickListener { openTelegramProxyLink() }
        advancedToggleButton.setOnClickListener {
            advancedExpanded = !advancedExpanded
            refreshState()
        }
        copyProxyLinkButton.setOnClickListener {
            copyProxyLink()
            Toast.makeText(this, "Proxy link copied", Toast.LENGTH_SHORT).show()
        }
        clearLogsButton.setOnClickListener {
            ProxyForegroundService.State.clearLogs()
            refreshState()
        }
        copyDiagnosticsButton.setOnClickListener { copyDiagnostics() }
        shareDiagnosticsButton.setOnClickListener { shareDiagnostics() }
        batterySettingsButton.setOnClickListener { openBatterySettings() }
        refreshState()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refreshRunnable)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_PENDING_RESTART_REQUIRED, pendingRestartRequired)
        outState.putBoolean(KEY_SHOW_TELEGRAM_CLEANUP_HINT, showTelegramCleanupHint)
        outState.putBoolean(KEY_ADVANCED_EXPANDED, advancedExpanded)
        super.onSaveInstanceState(outState)
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
        routeModeText = createValueText()
        lastRouteChangeText = createValueText()
        statsText = createValueText()
        logsText = TextView(this).apply {
            text = "No logs yet"
            setTextColor(COLOR_TEXT_SECONDARY)
            textSize = 13f
            setTextIsSelectable(true)
        }
        restartRequiredText = createValueText().apply {
            text = "Restart required to apply new secret"
            setTextColor(COLOR_WARNING)
            typeface = Typeface.DEFAULT_BOLD
        }
        telegramCleanupHintText = createValueText().apply {
            text = "If Telegram keeps reconnecting, remove old 127.0.0.1:1443 proxy entries and connect again."
            setTextColor(COLOR_TEXT_SECONDARY)
        }

        primaryControlButton = createButton("Start proxy")
        restartDashboardButton = createButton("Restart proxy")
        restartAdvancedButton = createButton("Restart proxy")
        resetSecretButton = createButton("Reset secret")
        routeModeButton = createButton("Route mode")
        connectTelegramButton = createButton("Connect in Telegram")
        advancedToggleButton = createButton("Diagnostics / Advanced")
        copyProxyLinkButton = createButton("Copy proxy link")
        clearLogsButton = createButton("Clear logs")
        copyDiagnosticsButton = createButton("Copy diagnostics")
        shareDiagnosticsButton = createButton("Share diagnostics")
        batterySettingsButton = createButton("Battery settings")

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(COLOR_BACKGROUND)
            applySystemInsetsPadding(basePadding = padding)

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

            addView(createCard("Dashboard") {
                addView(createTextRow("Status", statusText), matchWrapParams())
                addView(createTextRow("Network", networkText), matchWrapParams(topMargin = rowGap))
                addView(createTextRow("Route", routeModeText), matchWrapParams(topMargin = rowGap))
                addView(createTextRow("Last route change", lastRouteChangeText), matchWrapParams(topMargin = rowGap))
                addView(createTextRow("Battery optimization", batteryText), matchWrapParams(topMargin = rowGap))
                addView(createTextRow("Endpoint", endpointText), matchWrapParams(topMargin = rowGap))
                addView(createTextRow("Last status", lastStatusText), matchWrapParams(topMargin = rowGap))
                addView(createTextRow("Stats", statsText), matchWrapParams(topMargin = rowGap))
                addView(restartRequiredText, matchWrapParams(topMargin = smallPadding))
                addView(telegramCleanupHintText, matchWrapParams(topMargin = rowGap))
                addView(primaryControlButton, matchWrapParams(topMargin = smallPadding))
                addView(restartDashboardButton, matchWrapParams(topMargin = rowGap))
                addView(connectTelegramButton, matchWrapParams(topMargin = rowGap))
                addView(advancedToggleButton, matchWrapParams(topMargin = rowGap))
            }, cardParams(topMargin = smallPadding))

            advancedCard = createCard("Diagnostics / Advanced") {
                addView(createTextRow("Secret", secretText), matchWrapParams())
                addView(createTextRow("DC summary", dcText), matchWrapParams(topMargin = rowGap))
                addView(createTextRow("CF fallback", cfFallbackText), matchWrapParams(topMargin = rowGap))
                addView(routeModeButton, matchWrapParams(topMargin = rowGap))
                addView(createSectionTitle("Actions"), matchWrapParams(topMargin = smallPadding))
                addView(batterySettingsButton, matchWrapParams(topMargin = rowGap))
                addView(copyProxyLinkButton, matchWrapParams(topMargin = rowGap))
                addView(copyDiagnosticsButton, matchWrapParams(topMargin = rowGap))
                addView(shareDiagnosticsButton, matchWrapParams(topMargin = rowGap))
                addView(clearLogsButton, matchWrapParams(topMargin = rowGap))
                addView(resetSecretButton, matchWrapParams(topMargin = rowGap))
                addView(restartAdvancedButton, matchWrapParams(topMargin = rowGap))
                addView(createSectionTitle("Recent logs"), matchWrapParams(topMargin = smallPadding))
                addView(logsText, matchWrapParams(topMargin = rowGap))
            }
            addView(advancedCard, cardParams(topMargin = padding, bottomMargin = padding))
        }

        return ScrollView(this).apply {
            setBackgroundColor(COLOR_BACKGROUND)
            addView(content, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
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
            addView(createSectionTitle(title), matchWrapParams(bottomMargin = (8 * resources.displayMetrics.density).toInt()))
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

    private fun createTextRow(label: String, value: TextView): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 12f
                setTextColor(COLOR_TEXT_MUTED)
                typeface = Typeface.DEFAULT_BOLD
            }, matchWrapParams())
            addView(value, matchWrapParams(topMargin = 2))
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
        val hadPendingRestart = pendingRestartRequired
        pendingRestartRequired = false
        if (hadPendingRestart) showTelegramCleanupHint = true
        if (!ProxyForegroundService.State.running) {
            startProxyService()
            refreshState()
            return
        }

        startService(ProxyForegroundService.stopIntent(this))
        handler.postDelayed({
            startProxyService()
            refreshState()
        }, RESTART_DELAY_MS)
        refreshState()
    }

    private fun confirmResetSecret() {
        AlertDialog.Builder(this)
            .setTitle("Reset secret?")
            .setMessage("Reset secret changes Telegram proxy link. You will need to reconnect Telegram.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Reset") { _, _ -> resetSecret() }
            .show()
    }

    private fun resetSecret() {
        ProxyRuntimeConfig.resetSecret(applicationContext)
        pendingRestartRequired = true
        showTelegramCleanupHint = false
        ProxyForegroundService.State.addLog("proxy secret reset; restart required to apply new secret", LogSeverity.INFO, "ui")
        refreshState()
        Toast.makeText(this, "Restart required to apply new secret", Toast.LENGTH_LONG).show()
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

    private fun showRouteModeDialog() {
        val current = ProxyRuntimeConfig.appConfig(applicationContext).routeMode
        val modes = NetworkRouteMode.entries.toTypedArray()
        val labels = modes.map { it.displayName }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Route mode")
            .setSingleChoiceItems(labels, modes.indexOf(current)) { dialog, which ->
                val selected = modes[which]
                val store = AppConfigStore.from(applicationContext)
                val updated = store.loadConfig().copy(routeMode = selected)
                store.saveConfig(updated)
                ProxyRuntimeConfig.initialize(applicationContext)
                pendingRestartRequired = true
                ProxyForegroundService.State.addLog("route mode changed to ${selected.configValue}; restart required", LogSeverity.INFO, "ui")
                dialog.dismiss()
                refreshState()
            }
            .setNegativeButton("Cancel", null)
            .show()
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
        val failed = ProxyForegroundService.State.lastStatus.contains("failed", ignoreCase = true) ||
            ProxyForegroundService.State.lastStatus.contains("error", ignoreCase = true)
        statusText.text = when {
            running -> "Running"
            failed -> "Error"
            else -> "Stopped"
        }
        networkText.text = ProxyForegroundService.State.networkStatus
        batteryText.text = ProxyForegroundService.State.batteryOptimizationStatus
        lastStatusText.text = ProxyForegroundService.State.lastStatus
        endpointText.text = ProxyRuntimeConfig.endpointSummary()
        secretText.text = ProxyRuntimeConfig.partialTelegramSecret()
        dcText.text = ProxyRuntimeConfig.dcSummary()
        cfFallbackText.text = ProxyRuntimeConfig.cfFallbackSummary()
        routeModeText.text = routeSummaryLine()
        lastRouteChangeText.text = lastRouteChangeLine()
        statsText.text = conciseStatsLine()
        primaryControlButton.text = if (running) "Stop proxy" else "Start proxy"
        restartRequiredText.visibility = if (pendingRestartRequired) View.VISIBLE else View.GONE
        restartDashboardButton.visibility = if (pendingRestartRequired) View.VISIBLE else View.GONE
        telegramCleanupHintText.visibility = if (showTelegramCleanupHint) View.VISIBLE else View.GONE
        advancedCard.visibility = if (advancedExpanded) View.VISIBLE else View.GONE
        advancedToggleButton.text = if (advancedExpanded) "Hide Diagnostics / Advanced" else "Diagnostics / Advanced"
        restartDashboardButton.isEnabled = true
        restartAdvancedButton.isEnabled = true
        connectTelegramButton.isEnabled = true
        logsText.text = ProxyForegroundService.State.recentLogs()
            .takeLast(MAX_VISIBLE_LOG_LINES)
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n")
            ?: "No logs yet"
    }

    private fun routeSummaryLine(): String {
        val config = ProxyRuntimeConfig.appConfig()
        val stats = ProxyForegroundService.State.stats()
        val effective = stats?.effectiveRouteMode
            ?: ProxyRuntimeConfig.proxyServerConfig(config, ProxyForegroundService.State.networkStatus).effectiveRouteMode.configValue
        return if (config.routeMode == NetworkRouteMode.AUTO) {
            "Auto → $effective"
        } else {
            "${config.routeMode.configValue} (manual mode overrides network auto)"
        }
    }

    private fun lastRouteChangeLine(): String {
        val stats = ProxyForegroundService.State.stats() ?: return "none"
        val previous = stats.previousEffectiveRouteMode ?: "none"
        return "${stats.networkAtLastRouteChange}: $previous → ${stats.effectiveRouteMode} (${stats.lastRouteChangeReason})"
    }

    private fun conciseStatsLine(): String {
        val stats = ProxyForegroundService.State.stats() ?: return "active=0, total=0, bad=0, wsErrors=0, timeouts=0"
        return "active=${stats.connectionsActive}, total=${stats.connectionsTotal}, bad=${stats.connectionsBad}, " +
            "wsErrors=${stats.wsConnectErrors}, timeouts=${stats.sessionTimeouts}"
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
        private const val KEY_PENDING_RESTART_REQUIRED = "pending_restart_required"
        private const val KEY_SHOW_TELEGRAM_CLEANUP_HINT = "show_telegram_cleanup_hint"
        private const val KEY_ADVANCED_EXPANDED = "advanced_expanded"
        private const val MAX_VISIBLE_LOG_LINES = 8
        private const val COLOR_BACKGROUND = 0xFFF6F7FB.toInt()
        private const val COLOR_CARD_STROKE = 0xFFE5E7EB.toInt()
        private const val COLOR_TEXT_PRIMARY = 0xFF111827.toInt()
        private const val COLOR_TEXT_SECONDARY = 0xFF4B5563.toInt()
        private const val COLOR_TEXT_MUTED = 0xFF6B7280.toInt()
        private const val COLOR_WARNING = 0xFFB45309.toInt()
    }
}
