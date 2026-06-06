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
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
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

    private lateinit var contentHost: FrameLayout
    private lateinit var homeNavButton: Button
    private lateinit var settingsNavButton: Button

    private lateinit var statusText: TextView
    private lateinit var networkText: TextView
    private lateinit var routeText: TextView
    private lateinit var qualityText: TextView
    private lateinit var restartRequiredText: TextView
    private lateinit var telegramCleanupHintText: TextView
    private lateinit var hintsContainer: LinearLayout
    private lateinit var primaryControlButton: Button
    private lateinit var connectTelegramButton: Button
    private lateinit var restartPendingButton: Button

    private lateinit var batteryStatusText: TextView
    private lateinit var routeModeValueText: TextView
    private val routeModeButtons = mutableListOf<Pair<UserRouteModeOption, Button>>()
    private lateinit var secretStateText: TextView
    private lateinit var restartProxyButton: Button
    private lateinit var restartProxyHintText: TextView
    private lateinit var developerModeCheckBox: CheckBox
    private lateinit var developerSection: LinearLayout
    private lateinit var logsText: TextView
    private lateinit var rawRouteDetailsText: TextView
    private lateinit var cfDetailsText: TextView
    private lateinit var directDetailsText: TextView

    private var currentScreen: Screen = Screen.HOME
    private var pendingRestartRequired: Boolean = false
    private var transitionStatus: TransitionStatus = TransitionStatus.NONE

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

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
        currentScreen = Screen.valueOf(savedInstanceState?.getString(KEY_CURRENT_SCREEN) ?: Screen.HOME.name)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(buildRootView())
        showScreen(currentScreen)
        refreshState()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refreshRunnable)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_PENDING_RESTART_REQUIRED, pendingRestartRequired)
        outState.putString(KEY_CURRENT_SCREEN, currentScreen.name)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    private fun buildRootView(): LinearLayout {
        val density = resources.displayMetrics.density
        val padding = (16 * density).toInt()
        contentHost = FrameLayout(this).apply { setBackgroundColor(COLOR_BACKGROUND) }
        homeNavButton = createNavButton("Главная") { showScreen(Screen.HOME) }
        settingsNavButton = createNavButton("Настройки") { showScreen(Screen.SETTINGS) }
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            setPadding(padding / 2, padding / 2, padding / 2, padding / 2)
            addView(homeNavButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(settingsNavButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BACKGROUND)
            applySystemInsetsPadding(basePadding = 0)
            addView(contentHost, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(nav, matchWrapParams())
        }
    }

    private fun showScreen(screen: Screen) {
        currentScreen = screen
        contentHost.removeAllViews()
        val view = when (screen) {
            Screen.HOME -> buildHomeScreen()
            Screen.SETTINGS -> buildSettingsScreen()
        }
        contentHost.addView(view, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        homeNavButton.isSelected = screen == Screen.HOME
        settingsNavButton.isSelected = screen == Screen.SETTINGS
        homeNavButton.setTextColor(if (screen == Screen.HOME) COLOR_ACCENT else COLOR_TEXT_PRIMARY)
        settingsNavButton.setTextColor(if (screen == Screen.SETTINGS) COLOR_ACCENT else COLOR_TEXT_PRIMARY)
        refreshState()
    }

    private fun buildHomeScreen(): ScrollView {
        val density = resources.displayMetrics.density
        val padding = (16 * density).toInt()
        val rowGap = (8 * density).toInt()
        statusText = createValueText(textSize = 22f, bold = true)
        networkText = createValueText()
        routeText = createValueText()
        qualityText = createValueText()
        restartRequiredText = createValueText().apply {
            text = PendingRestartModel.RESTART_WARNING
            setTextColor(COLOR_WARNING)
            typeface = Typeface.DEFAULT_BOLD
        }
        telegramCleanupHintText = createValueText().apply {
            text = ""
            setTextColor(COLOR_TEXT_SECONDARY)
        }
        primaryControlButton = createButton("Запустить") {
            if (ProxyForegroundService.State.running) {
                transitionStatus = TransitionStatus.STOPPING
                startService(ProxyForegroundService.stopIntent(this))
            } else {
                transitionStatus = TransitionStatus.STARTING
                requestNotificationPermissionIfNeeded()
                startProxyService()
            }
            refreshState()
        }
        connectTelegramButton = createButton("Подключить Telegram") { openTelegramProxyLink() }
        restartPendingButton = createButton("Перезагрузить прокси") { restartProxyService() }
        hintsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(COLOR_BACKGROUND)
            addHeader()
            addView(hintsContainer, matchWrapParams(bottomMargin = rowGap))
            addView(createCard("Главная") {
                addView(createTextRow("Статус", statusText), matchWrapParams())
                addView(createTextRow("Текущая сеть", networkText), matchWrapParams(topMargin = rowGap))
                addView(createTextRow("Маршрут", routeText), matchWrapParams(topMargin = rowGap))
                addView(createTextRow("Telegram", qualityText), matchWrapParams(topMargin = rowGap))
                addView(restartRequiredText, matchWrapParams(topMargin = rowGap))
                addView(telegramCleanupHintText, matchWrapParams(topMargin = rowGap))
                addView(primaryControlButton, matchWrapParams(topMargin = rowGap))
                addView(restartPendingButton, matchWrapParams(topMargin = rowGap))
                addView(connectTelegramButton, matchWrapParams(topMargin = rowGap))
            }, cardParams())
        }
        return ScrollView(this).apply {
            setBackgroundColor(COLOR_BACKGROUND)
            addView(content, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun buildSettingsScreen(): ScrollView {
        val density = resources.displayMetrics.density
        val padding = (16 * density).toInt()
        val rowGap = (8 * density).toInt()
        routeModeButtons.clear()
        routeModeValueText = createValueText()
        batteryStatusText = createValueText()
        secretStateText = createValueText().apply { setTextColor(COLOR_SUCCESS) }
        restartProxyHintText = createValueText().apply { setTextColor(COLOR_TEXT_SECONDARY) }
        restartProxyButton = createButton("Перезагрузить прокси") { restartProxyService() }
        developerModeCheckBox = CheckBox(this).apply {
            text = "Режим разработчика"
            isAllCaps = false
            isChecked = developerModeEnabled()
            setTextColor(COLOR_TEXT_PRIMARY)
            setOnCheckedChangeListener { _, enabled ->
                prefs.edit().putBoolean(PREF_DEVELOPER_MODE, enabled).apply()
                developerSection.visibility = if (enabled) View.VISIBLE else View.GONE
                refreshState()
            }
        }
        logsText = createValueText(textSize = 13f).apply { setTextIsSelectable(true) }
        rawRouteDetailsText = createValueText(textSize = 13f).apply { setTextIsSelectable(true) }
        cfDetailsText = createValueText(textSize = 13f).apply { setTextIsSelectable(true) }
        directDetailsText = createValueText(textSize = 13f).apply { setTextIsSelectable(true) }
        developerSection = createCard("Режим разработчика") {
            addView(createTextRow("Подробности маршрута", rawRouteDetailsText), matchWrapParams())
            addView(createTextRow("Состояние резервных доменов", cfDetailsText), matchWrapParams(topMargin = rowGap))
            addView(createTextRow("Состояние прямого маршрута", directDetailsText), matchWrapParams(topMargin = rowGap))
            addView(createSectionTitle("Действия"), matchWrapParams(topMargin = rowGap))
            addView(createButton("Копировать диагностику") { copyDiagnostics() }, matchWrapParams(topMargin = rowGap))
            addView(createButton("Очистить логи") {
                ProxyForegroundService.State.clearLogs()
                refreshState()
            }, matchWrapParams(topMargin = rowGap))
            addView(createButton("Копировать ссылку прокси") {
                copyProxyLink()
                Toast.makeText(this@MainActivity, "Ссылка прокси скопирована", Toast.LENGTH_SHORT).show()
            }, matchWrapParams(topMargin = rowGap))
            addView(createButton("Поделиться диагностикой") { shareDiagnostics() }, matchWrapParams(topMargin = rowGap))
            addView(createSectionTitle("Логи"), matchWrapParams(topMargin = rowGap))
            addView(logsText, matchWrapParams(topMargin = rowGap))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(COLOR_BACKGROUND)
            addHeader()
            addView(createCard("Режим подключения") {
                UserRouteModes.normalOptions.forEach { option ->
                    addView(createRouteModeButton(option), matchWrapParams(topMargin = rowGap))
                }
                addView(routeModeValueText, matchWrapParams(topMargin = rowGap))
            }, cardParams())
            addView(createCard(SettingsUiText.BATTERY_BACKGROUND_TITLE) {
                addView(batteryStatusText, matchWrapParams())
                addView(createButton("Открыть настройки батареи") { openBatterySettings() }, matchWrapParams(topMargin = rowGap))
                addView(createValueText().apply {
                    text = if (BatterySettingsIntentPlan.isXiaomiFamily(Build.MANUFACTURER.orEmpty())) {
                        SettingsUiText.BATTERY_XIAOMI_AUTOSTART_TEXT
                    } else {
                        SettingsUiText.BATTERY_BUTTON_HELP_TEXT
                    }
                    setTextColor(COLOR_TEXT_SECONDARY)
                }, matchWrapParams(topMargin = rowGap))
            }, cardParams(topMargin = padding))
            addView(createCard(SettingsUiText.QS_TILE_TITLE) {
                addView(createValueText().apply {
                    text = SettingsUiText.QS_TILE_TEXT
                    setTextColor(COLOR_TEXT_SECONDARY)
                }, matchWrapParams())
                addView(createButton(SettingsUiText.QS_TILE_HELP_BUTTON) { showQuickSettingsTileHelp() }, matchWrapParams(topMargin = rowGap))
            }, cardParams(topMargin = padding))
            addView(createCard("Telegram") {
                addView(createButton("Обновить секрет") { confirmResetSecret() }, matchWrapParams())
                addView(secretStateText, matchWrapParams(topMargin = rowGap))
            }, cardParams(topMargin = padding))
            addView(createCard("Прокси") {
                addView(restartProxyButton, matchWrapParams())
                addView(restartProxyHintText, matchWrapParams(topMargin = rowGap))
            }, cardParams(topMargin = padding))
            addView(createCard("Дополнительно") {
                addView(developerModeCheckBox, matchWrapParams())
            }, cardParams(topMargin = padding))
            addView(developerSection, cardParams(topMargin = padding, bottomMargin = padding))
        }
        return ScrollView(this).apply {
            setBackgroundColor(COLOR_BACKGROUND)
            addView(content, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun LinearLayout.addHeader() {
        val smallPadding = (8 * resources.displayMetrics.density).toInt()
        addView(TextView(this@MainActivity).apply {
            text = "TG WS Android"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT_PRIMARY)
        }, matchWrapParams())
        addView(TextView(this@MainActivity).apply {
            text = "Локальный прокси для Telegram"
            textSize = 15f
            setTextColor(COLOR_TEXT_SECONDARY)
            setPadding(0, smallPadding / 2, 0, smallPadding)
        }, matchWrapParams())
    }

    private fun createRouteModeButton(option: UserRouteModeOption): Button {
        val button = createButton(
            UserRouteModes.buttonText(option, option.routeMode == ProxyRuntimeConfig.appConfig(applicationContext).routeMode),
        ) {
            val store = AppConfigStore.from(applicationContext)
            store.saveConfig(store.loadConfig().copy(routeMode = option.routeMode))
            ProxyRuntimeConfig.initialize(applicationContext)
            val running = ProxyForegroundService.State.running
            pendingRestartRequired = PendingRestartModel.pendingAfterRouteModeChange(running)
            val logMessage = if (pendingRestartRequired) {
                "route mode changed to ${option.routeMode.configValue}; restart required"
            } else {
                "route mode changed to ${option.routeMode.configValue}; will apply on next proxy start"
            }
            ProxyForegroundService.State.addLog(logMessage, LogSeverity.INFO, "ui")
            refreshState()
        }
        routeModeButtons.add(option to button)
        return button
    }

    private fun refreshState() {
        ProxyRuntimeConfig.initialize(applicationContext)
        refreshLocalDiagnostics()
        val running = ProxyForegroundService.State.running
        if (!running && transitionStatus != TransitionStatus.STARTING) pendingRestartRequired = false
        if (transitionStatus == TransitionStatus.STARTING && running) transitionStatus = TransitionStatus.NONE
        if (transitionStatus == TransitionStatus.STOPPING && !running) transitionStatus = TransitionStatus.NONE
        val failed = ProxyForegroundService.State.lastStatus.contains("failed", ignoreCase = true) ||
            ProxyForegroundService.State.lastStatus.contains("error", ignoreCase = true)

        if (::statusText.isInitialized) {
            statusText.text = when {
                failed -> "Есть проблема"
                transitionStatus == TransitionStatus.STARTING -> "Запускается..."
                transitionStatus == TransitionStatus.STOPPING -> "Останавливается..."
                running -> "Прокси работает"
                else -> "Прокси остановлен"
            }
            val routeLabel = userRouteLabel()
            networkText.text = userNetworkLabel(ProxyForegroundService.State.networkStatus)
            routeText.text = routeLabel
            val stats = ProxyForegroundService.State.stats()
            qualityText.text = ConnectionStatusMapper.status(
                running = running,
                networkStatus = ProxyForegroundService.State.networkStatus,
                stats = stats,
                checking = transitionStatus == TransitionStatus.STARTING,
            )
            primaryControlButton.text = if (running) "Остановить" else "Запустить"
            connectTelegramButton.text = TelegramStatusUiText.actionText(stats)
            val showRestartWarning = pendingRestartRequired && running
            restartRequiredText.visibility = if (showRestartWarning) View.VISIBLE else View.GONE
            restartPendingButton.visibility = if (showRestartWarning) View.VISIBLE else View.GONE
            val mobileRouteHelper = HomeRouteLabelMapper.mobileCompatibleHelper(ProxyForegroundService.State.networkStatus, routeLabel)
            val telegramHelper = TelegramStatusUiText.helper(stats, mobileRouteHelper)
            telegramCleanupHintText.text = telegramHelper.orEmpty()
            telegramCleanupHintText.visibility = if (telegramHelper == null) View.GONE else View.VISIBLE
            refreshHints()
        }

        if (::routeModeValueText.isInitialized) {
            val config = ProxyRuntimeConfig.appConfig(applicationContext)
            routeModeButtons.forEach { (option, button) ->
                button.text = UserRouteModes.buttonText(option, option.routeMode == config.routeMode)
            }
            routeModeValueText.text = UserRouteModes.helperFor(config.routeMode)
            batteryStatusText.text = SettingsUiText.batteryStatusLine(userBatteryLabel(ProxyForegroundService.State.batteryOptimizationStatus))
            developerModeCheckBox.isChecked = developerModeEnabled()
            developerSection.visibility = if (developerModeEnabled()) View.VISIBLE else View.GONE
            restartProxyButton.isEnabled = PendingRestartModel.restartActionEnabled(running)
            restartProxyHintText.text = if (running) "" else PendingRestartModel.RESTART_DISABLED_HINT
            rawRouteDetailsText.text = routeDetailsLine()
            cfDetailsText.text = cfDetailsLine()
            directDetailsText.text = directDetailsLine()
            secretStateText.text = secretStateLine()
            logsText.text = ProxyForegroundService.State.recentLogs()
                .takeLast(MAX_VISIBLE_LOG_LINES)
                .takeIf { it.isNotEmpty() }
                ?.joinToString("\n")
                ?: "Логов пока нет"
        }
    }

    private fun refreshHints() {
        hintsContainer.removeAllViews()
        val candidates = buildList {
            if (!prefs.getBoolean(PREF_HINT_FIRST_START_DISMISSED, false) && !prefs.getBoolean(PREF_PROXY_EVER_STARTED, false)) {
                add(HintCard("Начните с запуска прокси", "Нажмите «Запустить», затем «Подключить Telegram».", "Понятно", onPrimary = {
                    prefs.edit().putBoolean(PREF_HINT_FIRST_START_DISMISSED, true).apply()
                    refreshHints()
                }))
            }
            if (!prefs.getBoolean(PREF_HINT_BATTERY_DISMISSED, false) && detectBatteryOptimizationStatus() != "unrestricted") {
                add(HintCard("Разрешите работу в фоне", "Чтобы прокси не останавливался, отключите ограничения батареи для приложения.", "Открыть настройки", {
                    openBatterySettings()
                    prefs.edit().putBoolean(PREF_HINT_BATTERY_DISMISSED, true).apply()
                    refreshHints()
                }, "Позже") {
                    prefs.edit().putBoolean(PREF_HINT_BATTERY_DISMISSED, true).apply()
                    refreshHints()
                })
            }
            if (!prefs.getBoolean(PREF_HINT_NOTIFICATION_DISMISSED, false) && notificationPermissionMissing()) {
                add(HintCard("Разрешите уведомление", "Уведомление нужно, чтобы прокси стабильно работал в фоне и им можно было управлять из шторки.", "Разрешить", {
                    requestNotificationPermissionIfNeeded()
                    prefs.edit().putBoolean(PREF_HINT_NOTIFICATION_DISMISSED, true).apply()
                    refreshHints()
                }, "Позже") {
                    prefs.edit().putBoolean(PREF_HINT_NOTIFICATION_DISMISSED, true).apply()
                    refreshHints()
                })
            }
            if (!prefs.getBoolean(PREF_HINT_MOBILE_DISMISSED, false) && ProxyForegroundService.State.networkStatus.equals("mobile", ignoreCase = true)) {
                add(HintCard("Мобильная сеть", "На мобильной сети используется совместимый маршрут. Ping может быть выше, чем на Wi-Fi.", "Понятно", onPrimary = {
                    prefs.edit().putBoolean(PREF_HINT_MOBILE_DISMISSED, true).apply()
                    refreshHints()
                }))
            }
        }.take(MAX_HINTS)
        candidates.forEach { hintsContainer.addView(createHintCard(it), cardParams(bottomMargin = (8 * resources.displayMetrics.density).toInt())) }
    }

    private fun createHintCard(hint: HintCard): LinearLayout = createCard(hint.title) {
        addView(createValueText().apply {
            text = hint.text
            setTextColor(COLOR_TEXT_SECONDARY)
        }, matchWrapParams())
        addView(createButton(hint.primaryAction, hint.onPrimary), matchWrapParams(topMargin = (8 * resources.displayMetrics.density).toInt()))
        if (hint.secondaryAction != null && hint.onSecondary != null) {
            addView(createButton(hint.secondaryAction, hint.onSecondary), matchWrapParams(topMargin = (6 * resources.displayMetrics.density).toInt()))
        }
    }

    private fun userNetworkLabel(network: String): String = when {
        network.equals("Wi-Fi", ignoreCase = true) || network.equals("wifi", ignoreCase = true) -> "Wi-Fi"
        network.equals("mobile", ignoreCase = true) || network.equals("cellular", ignoreCase = true) -> "Мобильная сеть"
        network.equals("none", ignoreCase = true) -> "Нет сети"
        network.equals("unknown", ignoreCase = true) || network.isBlank() -> "Неизвестно"
        else -> "Неизвестно"
    }

    private fun userRouteLabel(): String {
        val config = ProxyRuntimeConfig.appConfig(applicationContext)
        val stats = ProxyForegroundService.State.stats()
        val fallbackEffective = if (config.routeMode == NetworkRouteMode.AUTO) {
            null
        } else {
            ProxyRuntimeConfig.proxyServerConfig(config, ProxyForegroundService.State.networkStatus).effectiveRouteMode.configValue
        }
        return HomeRouteLabelMapper.label(
            networkStatus = ProxyForegroundService.State.networkStatus,
            configuredRouteMode = config.routeMode,
            stats = stats,
            fallbackEffectiveRouteMode = fallbackEffective,
        )
    }

    private fun userBatteryLabel(status: String): String = when (status) {
        "unrestricted" -> "Без ограничений"
        "optimized" -> "Может ограничиваться системой"
        else -> "Неизвестно"
    }

    private fun routeDetailsLine(): String {
        val config = ProxyRuntimeConfig.appConfig(applicationContext)
        val stats = ProxyForegroundService.State.stats()
        return "configured=${config.routeMode.name}/${config.routeMode.configValue}, effective=${stats?.effectiveRouteMode ?: "unknown"}, previous=${stats?.previousEffectiveRouteMode ?: "none"}, state=${stats?.lastRouteChangeReason ?: "unknown"}"
    }

    private fun cfDetailsLine(): String {
        val stats = ProxyForegroundService.State.stats() ?: return "CF health: unknown"
        return "CF health=${stats.cfHealthEnabled}, domains=${stats.cfDomainsTotal}, cooldown=${stats.cfDomainsInCooldown}, 429=${stats.cf429Count}, queueFailures=${stats.cfQueueControlledFailures}, pool=${stats.poolHits}/${stats.poolMisses}"
    }

    private fun directDetailsLine(): String {
        val stats = ProxyForegroundService.State.stats() ?: return "direct health: unknown"
        return "direct health=${stats.directHealthState}, attempts=${stats.directAttempts}, failures=${stats.directHealthFailures}, cooldownUntil=${stats.directCooldownUntil}, route state=${stats.lastRouteUsed ?: "none"}; " +
            "Invalid MTProto handshake storm=${stats.badHandshakeStormRecent}, badHandshakeRatio=${String.format(java.util.Locale.US, "%.3f", stats.badHandshakeRatio)}, " +
            "handshakeDiagnosticState=${stats.handshakeDiagnosticState}, handshakeDiagnosticReason=${stats.handshakeDiagnosticReason}, " +
            "badHandshakeRecommendation=${stats.badHandshakeRecommendation}"
    }

    private fun secretStateLine(): String = "Текущий secret: ${ProxyRuntimeConfig.partialTelegramSecret(applicationContext)}; " +
        "источник=${ProxyRuntimeConfig.secretSource(applicationContext)}; ссылка актуальна=${ProxyRuntimeConfig.proxyLinkCurrent(applicationContext)}"

    private fun startProxyService() {
        prefs.edit().putBoolean(PREF_PROXY_EVER_STARTED, true).apply()
        val intent = ProxyForegroundService.startIntent(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun restartProxyService() {
        if (!ProxyForegroundService.State.running) {
            Toast.makeText(this, "Доступно после запуска прокси", Toast.LENGTH_SHORT).show()
            refreshState()
            return
        }
        requestNotificationPermissionIfNeeded()
        pendingRestartRequired = false
        startService(ProxyForegroundService.stopIntent(this))
        handler.postDelayed({
            transitionStatus = TransitionStatus.STARTING
            startProxyService()
            refreshState()
        }, RESTART_DELAY_MS)
        refreshState()
    }

    private fun confirmResetSecret() {
        AlertDialog.Builder(this)
            .setTitle("Обновить секрет?")
            .setMessage("После обновления секрета нужно заново подключить Telegram. Старое подключение перестанет работать.")
            .setPositiveButton("Обновить") { _, _ -> resetSecret() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun resetSecret() {
        ProxyRuntimeConfig.resetSecret(applicationContext)
        val running = ProxyForegroundService.State.running
        pendingRestartRequired = false
        val logMessage = if (running) {
            "proxy secret reset; restarting proxy to apply new secret"
        } else {
            "proxy secret reset; will apply on next proxy start"
        }
        ProxyForegroundService.State.addLog(logMessage, LogSeverity.INFO, "ui")
        if (running) restartProxyService()
        Toast.makeText(this, SecretUpdatedMessageModel.MESSAGE, Toast.LENGTH_LONG).show()
        refreshState()
    }

    private fun openTelegramProxyLink() {
        val telegramIntent = Intent(Intent.ACTION_VIEW, Uri.parse(ProxyRuntimeConfig.telegramProxyUri(this)))
        if (tryStartActivity(telegramIntent)) return
        val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(ProxyRuntimeConfig.telegramProxyUrl(this)))
        if (tryStartActivity(fallbackIntent)) return
        copyProxyLink()
        Toast.makeText(this, "Не удалось открыть ссылку. Ссылка скопирована.", Toast.LENGTH_LONG).show()
    }

    private fun tryStartActivity(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    private fun copyProxyLink() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Ссылка прокси Telegram", ProxyRuntimeConfig.telegramProxyUri(this)))
    }

    private fun copyDiagnostics() {
        if (!ProxyForegroundService.State.hasLogs()) {
            Toast.makeText(this, "Диагностики пока нет", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Диагностика TG WS Android", ProxyForegroundService.State.diagnosticReport()))
        Toast.makeText(this, "Диагностика скопирована", Toast.LENGTH_SHORT).show()
    }

    private fun copyDiagnosticsToClipboard(diagnosticsReport: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Диагностика TG WS Android", diagnosticsReport))
    }

    private fun shareDiagnostics() {
        if (!ProxyForegroundService.State.hasLogs()) {
            Toast.makeText(this, "Диагностики пока нет", Toast.LENGTH_SHORT).show()
            return
        }
        val diagnosticsReport = ProxyForegroundService.State.diagnosticReport()
        if (diagnosticsReport.isBlank()) {
            Toast.makeText(this, "Диагностики пока нет", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "Не удалось поделиться файлом. Диагностика скопирована.", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Диагностика TG WS Android")
            putExtra(Intent.EXTRA_TEXT, "Файл диагностики TG WS Android во вложении.")
            putExtra(Intent.EXTRA_STREAM, export.uri)
            clipData = ClipData.newUri(contentResolver, export.file.name, export.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, "Поделиться диагностикой"))
        } catch (_: ActivityNotFoundException) {
            ProxyForegroundService.State.addLog("No app can share diagnostics", LogSeverity.WARN, "ui")
            Toast.makeText(this, "Нет приложения для отправки диагностики", Toast.LENGTH_LONG).show()
        }
    }

    private fun openBatterySettings() {
        if (!BatterySettingsNavigator(this).open()) {
            Toast.makeText(this, "Не удалось открыть настройки батареи", Toast.LENGTH_LONG).show()
        }
    }

    private fun showQuickSettingsTileHelp() {
        AlertDialog.Builder(this)
            .setTitle(SettingsUiText.QS_TILE_HELP_TITLE)
            .setMessage(SettingsUiText.QS_TILE_HELP_MESSAGE)
            .setPositiveButton("Понятно", null)
            .show()
    }

    private fun refreshLocalDiagnostics() {
        ProxyForegroundService.State.setBatteryOptimizationStatus(detectBatteryOptimizationStatus())
        if (!ProxyForegroundService.State.running) ProxyForegroundService.State.setNetworkStatus(detectNetworkStatus())
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

    private fun notificationPermissionMissing(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    private fun requestNotificationPermissionIfNeeded() {
        if (!notificationPermissionMissing()) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_POST_NOTIFICATIONS)
    }

    private fun developerModeEnabled(): Boolean = prefs.getBoolean(PREF_DEVELOPER_MODE, false)

    private fun createCard(title: String, body: LinearLayout.() -> Unit): LinearLayout = LinearLayout(this).apply {
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

    private fun createButton(label: String, onClick: (View) -> Unit = {}): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener(onClick)
    }

    private fun createNavButton(label: String, onClick: (View) -> Unit): Button = createButton(label, onClick)

    private fun createTextRow(label: String, value: TextView): LinearLayout = LinearLayout(this).apply {
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

    private fun cardParams(topMargin: Int = 0, bottomMargin: Int = 0): LinearLayout.LayoutParams = matchWrapParams(topMargin, bottomMargin)

    private fun View.applySystemInsetsPadding(basePadding: Int) {
        setPadding(basePadding, basePadding, basePadding, basePadding)
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val systemInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.updatePadding(
                left = basePadding + systemInsets.left,
                top = basePadding + systemInsets.top,
                right = basePadding + systemInsets.right,
                bottom = basePadding + systemInsets.bottom,
            )
            insets
        }
    }

    private enum class Screen { HOME, SETTINGS }
    private enum class TransitionStatus { NONE, STARTING, STOPPING }

    private data class HintCard(
        val title: String,
        val text: String,
        val primaryAction: String,
        val onPrimary: (View) -> Unit,
        val secondaryAction: String? = null,
        val onSecondary: ((View) -> Unit)? = null,
    )

    companion object {
        private const val REFRESH_MS = 1_000L
        private const val RESTART_DELAY_MS = 350L
        private const val REQUEST_POST_NOTIFICATIONS = 2001
        private const val KEY_PENDING_RESTART_REQUIRED = "pending_restart_required"
        private const val KEY_CURRENT_SCREEN = "current_screen"
        private const val PREFS_NAME = "main_ui"
        private const val PREF_DEVELOPER_MODE = "developer_mode"
        private const val PREF_PROXY_EVER_STARTED = "proxy_ever_started"
        private const val PREF_HINT_FIRST_START_DISMISSED = "hint_first_start_dismissed"
        private const val PREF_HINT_BATTERY_DISMISSED = "hint_battery_dismissed"
        private const val PREF_HINT_NOTIFICATION_DISMISSED = "hint_notification_dismissed"
        private const val PREF_HINT_MOBILE_DISMISSED = "hint_mobile_dismissed"
        private const val MAX_HINTS = 2
        private const val MAX_VISIBLE_LOG_LINES = 12
        private const val COLOR_BACKGROUND = 0xFFF6F7FB.toInt()
        private const val COLOR_CARD_STROKE = 0xFFE5E7EB.toInt()
        private const val COLOR_TEXT_PRIMARY = 0xFF111827.toInt()
        private const val COLOR_TEXT_SECONDARY = 0xFF4B5563.toInt()
        private const val COLOR_TEXT_MUTED = 0xFF6B7280.toInt()
        private const val COLOR_WARNING = 0xFFB45309.toInt()
        private const val COLOR_ACCENT = 0xFF2563EB.toInt()
        private const val COLOR_SUCCESS = 0xFF047857.toInt()
    }
}
