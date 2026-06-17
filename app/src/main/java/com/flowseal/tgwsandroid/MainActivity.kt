package com.flowseal.tgwsandroid

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.StatusBarManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.Switch
import androidx.core.content.res.use
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.flowseal.tgwsandroid.config.AppConfigStore
import com.flowseal.tgwsandroid.config.Appearance
import com.flowseal.tgwsandroid.proxy.NetworkRouteMode
import com.flowseal.tgwsandroid.service.LogSeverity
import com.flowseal.tgwsandroid.service.ProxyForegroundService
import com.flowseal.tgwsandroid.service.ProxyRuntimeConfig
import com.flowseal.tgwsandroid.service.ProxyQuickSettingsTileService
import com.flowseal.tgwsandroid.telemetry.Telemetry
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var contentHost: FrameLayout
    private lateinit var navigationBar: BottomNavigationView

    private lateinit var statusText: TextView
    private lateinit var heroSubtitleText: TextView
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
    private lateinit var notificationStatusText: TextView
    private lateinit var autostartStatusText: TextView
    private lateinit var telemetryStatusText: TextView
    private lateinit var themeStatusText: TextView
    private lateinit var hostStatusText: TextView
    private lateinit var portStatusText: TextView
    private lateinit var secretStateText: TextView
    private lateinit var diagnosticsSummaryText: TextView
    private lateinit var telemetryCheckBox: CheckBox
    private lateinit var telemetrySwitch: Switch
    private lateinit var telemetryTestButton: Button
    private lateinit var developerModeCheckBox: CheckBox
    private lateinit var developerSection: LinearLayout
    private lateinit var logsText: TextView
    private lateinit var rawRouteDetailsText: TextView
    private lateinit var cfDetailsText: TextView
    private lateinit var directDetailsText: TextView

    private var currentScreen: Screen = Screen.HOME
    private var renderingScreen: Boolean = false
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
        contentHost = FrameLayout(this).apply { setBackgroundColor(COLOR_BACKGROUND) }
        navigationBar = createNavigationBar()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BACKGROUND)
            applySystemInsetsPadding(basePadding = 0)
            addView(contentHost, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(navigationBar, matchWrapParams())
        }
    }

    private fun showScreen(screen: Screen, forceRefresh: Boolean = false) {
        if (renderingScreen || (screen == currentScreen && contentHost.childCount > 0 && !forceRefresh)) {
            return
        }
        renderingScreen = true
        try {
            currentScreen = screen
            contentHost.removeAllViews()
            val view = when (screen) {
                Screen.HOME -> buildHomeScreen()
                Screen.SETTINGS -> buildSettingsScreen()
                Screen.DIAGNOSTICS -> buildDiagnosticsScreen()
            }
            contentHost.addView(view, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            if (::navigationBar.isInitialized && navigationBar.selectedItemId != screen.itemId) {
                navigationBar.menu.findItem(screen.itemId)?.isChecked = true
            }
        } finally {
            renderingScreen = false
        }
        refreshState()
    }

    private fun buildHomeScreen(): ScrollView {
        val density = resources.displayMetrics.density
        val padding = (16 * density).toInt()
        val rowGap = (8 * density).toInt()
        val chipGap = (6 * density).toInt()
        statusText = createValueText(textSize = 30f, bold = true)
        heroSubtitleText = createValueText(textSize = 17f).apply { setTextColor(COLOR_TEXT_SECONDARY) }
        networkText = Badge("Wi-Fi")
        routeText = Badge("Авто")
        qualityText = Badge("Проверка…")
        restartRequiredText = createValueText().apply {
            text = PendingRestartModel.RESTART_WARNING
            setTextColor(COLOR_WARNING)
            typeface = Typeface.DEFAULT_BOLD
        }
        telegramCleanupHintText = createValueText().apply {
            text = ""
            setTextColor(COLOR_TEXT_SECONDARY)
        }
        primaryControlButton = createFilledButton("Включить") { handleMainPrimaryAction() }
        connectTelegramButton = createOutlinedButton("Перезапустить") { restartProxyService() }
        restartPendingButton = createOutlinedButton("Подключить Telegram") { openTelegramProxyLink() }
        hintsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val chipsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(networkText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = chipGap })
            addView(routeText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = chipGap })
            addView(qualityText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(COLOR_BACKGROUND)
            addHeader()
            addView(SettingsSection("") {
                addView(statusText, matchWrapParams())
                addView(heroSubtitleText, matchWrapParams(topMargin = rowGap))
                addView(chipsRow, matchWrapParams(topMargin = padding))
                addView(restartRequiredText, matchWrapParams(topMargin = rowGap))
                addView(telegramCleanupHintText, matchWrapParams(topMargin = rowGap))
                addView(primaryControlButton, matchWrapParams(topMargin = padding))
                addView(connectTelegramButton, matchWrapParams(topMargin = rowGap))
                addView(restartPendingButton, matchWrapParams(topMargin = rowGap))
            }, cardParams())
            addView(hintsContainer, matchWrapParams(topMargin = padding))
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
        routeModeValueText = createValueText()
        notificationStatusText = createValueText()
        batteryStatusText = createValueText()
        autostartStatusText = createValueText()
        telemetryStatusText = createValueText()
        themeStatusText = createValueText()
        hostStatusText = createValueText()
        portStatusText = createValueText()
        secretStateText = createValueText().apply { setTextColor(COLOR_SUCCESS) }
        telemetryCheckBox = CheckBox(this).apply {
            text = "Отправлять анонимную диагностику"
            isAllCaps = false
            setTextColor(COLOR_TEXT_PRIMARY)
            setOnCheckedChangeListener { _, enabled ->
                val store = AppConfigStore.from(applicationContext)
                store.saveConfig(store.loadConfig().copy(telemetryEnabled = enabled))
                ProxyRuntimeConfig.initialize(applicationContext)
                ProxyForegroundService.State.addLog("telemetry_enabled changed to $enabled", LogSeverity.INFO, "ui")
                refreshState()
            }
        }
        telemetryTestButton = createButton("Отправить тестовую телеметрию") { sendTestTelemetry() }
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
        developerSection = SettingsSection("Технические данные") {
            addView(createTextRow("Подробности маршрута", rawRouteDetailsText), matchWrapParams())
            addView(createTextRow("Состояние резервных доменов", cfDetailsText), matchWrapParams(topMargin = rowGap))
            addView(createTextRow("Состояние прямого маршрута", directDetailsText), matchWrapParams(topMargin = rowGap))
            addView(createSectionTitle("Действия"), matchWrapParams(topMargin = rowGap))
            addView(telemetryTestButton, matchWrapParams(topMargin = rowGap))
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
            addView(SettingsSection("Важное для стабильной работы", "Эти параметры помогают прокси не отключаться в фоне.") {
                addView(SettingsStatusRow("Уведомления", "Показывают состояние подключения.", notificationStatusText, Badge("Рекомендуется")) { openNotificationSettingsFlow() }, matchWrapParams())
                addView(SettingsStatusRow("Работа в фоне", "Помогает сохранять подключение после блокировки экрана.", batteryStatusText, Badge("Важно", COLOR_WARNING, Color.WHITE)) { openBatterySettings() }, matchWrapParams(topMargin = rowGap))
                addView(SettingsStatusRow("Автозапуск", "Позволяет запускать прокси после перезагрузки устройства.", autostartStatusText, Badge("Важно", COLOR_WARNING, Color.WHITE)) { openAutostartSettings() }, matchWrapParams(topMargin = rowGap))
                addView(SettingsSwitchRow("Анонимная диагностика", "Помогает улучшать стабильность без личных данных.", telemetryStatusText, Badge("Рекомендуется")) { toggleTelemetry() }, matchWrapParams(topMargin = rowGap))
            }, cardParams())
            addView(SettingsSection("Подключение") {
                addView(ConnectionModeSelector(), matchWrapParams())
            }, cardParams(topMargin = padding))
            addView(SettingsSection("Telegram MTProto", "Параметры локального подключения Telegram.") {
                addView(SettingsValueRow("IP-адрес", "Адрес локального прокси.", hostStatusText), matchWrapParams())
                addView(SettingsValueRow("Порт", "Порт локального прокси.", portStatusText), matchWrapParams(topMargin = rowGap))
                addView(SettingsValueRow("Secret", "Скрыт в обычном интерфейсе.", secretStateText), matchWrapParams(topMargin = rowGap))
                addView(SettingsActionRow(SettingsUiText.RESET_SECRET_TITLE, "После обновления нужно заново подключить Telegram.") { confirmResetSecret() }, matchWrapParams(topMargin = rowGap))
            }, cardParams(topMargin = padding))
            addView(SettingsSection("Внешний вид") {
                addView(SettingsValueRow(SettingsUiText.THEME_TITLE, "Выберите оформление приложения.", themeStatusText) { showThemeDialog() }, matchWrapParams())
            }, cardParams(topMargin = padding))
            addView(SettingsSection("Для разработчика") {
                addView(developerModeCheckBox, matchWrapParams())
                developerSection.visibility = if (developerModeEnabled()) View.VISIBLE else View.GONE
                addView(developerSection, matchWrapParams(topMargin = rowGap))
            }, cardParams(topMargin = padding, bottomMargin = padding))

        }
        return ScrollView(this).apply {
            setBackgroundColor(COLOR_BACKGROUND)
            addView(content, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun LinearLayout.addHeader() {
        val smallPadding = (8 * resources.displayMetrics.density).toInt()
        addView(TextView(this@MainActivity).apply {
            text = "TG WS"
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

    private fun setRouteMode(routeMode: NetworkRouteMode) {
        val store = AppConfigStore.from(applicationContext)
        store.saveConfig(store.loadConfig().copy(routeMode = routeMode))
        ProxyRuntimeConfig.initialize(applicationContext)
        val running = ProxyForegroundService.State.running
        pendingRestartRequired = PendingRestartModel.pendingAfterRouteModeChange(running)
        val logMessage = if (pendingRestartRequired) {
            "route mode changed to ${routeMode.configValue}; restart required"
        } else {
            "route mode changed to ${routeMode.configValue}; will apply on next proxy start"
        }
        ProxyForegroundService.State.addLog(logMessage, LogSeverity.INFO, "ui")
        refreshState()
    }

    private fun showConnectionModeDialog() {
        val options = UserRouteModes.normalOptions
        val labels = options.map { it.title }.toTypedArray()
        val current = ProxyRuntimeConfig.appConfig(applicationContext).routeMode
        AlertDialog.Builder(this)
            .setTitle(SettingsUiText.CONNECTION_MODE_TITLE)
            .setSingleChoiceItems(labels, options.indexOfFirst { it.routeMode == current }.coerceAtLeast(0)) { dialog, index ->
                setRouteMode(options[index].routeMode)
                dialog.dismiss()
            }
            .setNegativeButton("Отмена", null)
            .show()
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
            val stats = ProxyForegroundService.State.stats()
            val healthLabel = userHealthLabel(running, stats, transitionStatus == TransitionStatus.STARTING, failed)
            val telegramConnected = telegramConnected(running, stats)
            val unstable = failed || healthLabel == "Нестабильно" || TelegramStatusUiText.showTelegramReconnectWarning(stats)
            val starting = transitionStatus == TransitionStatus.STARTING || (transitionStatus == TransitionStatus.STOPPING && running)

            val hero = MainHeroStateMapper.state(
                running = running,
                starting = starting,
                failed = failed,
                healthLabel = healthLabel,
                telegramReconnectWarning = TelegramStatusUiText.showTelegramReconnectWarning(stats),
                telegramConnected = telegramConnected,
            )

            statusText.text = hero.title
            heroSubtitleText.text = hero.subtitle
            networkText.text = userNetworkChipLabel(ProxyForegroundService.State.networkStatus)
            routeText.text = userModeChipLabel()
            qualityText.text = healthLabel

            val proxyEnabled = running || starting
            val actions = MainActionModelMapper.actions(
                proxyEnabled = proxyEnabled,
                settingsChangedPendingRestart = pendingRestartRequired && running,
            )

            primaryControlButton.text = actions.primaryAction.orEmpty()
            primaryControlButton.visibility = if (actions.primaryAction == null) View.GONE else View.VISIBLE
            primaryControlButton.isEnabled = actions.primaryAction != null

            connectTelegramButton.text = actions.restartAction.orEmpty()
            connectTelegramButton.visibility = if (actions.restartAction == null) View.GONE else View.VISIBLE
            connectTelegramButton.isEnabled = running
            connectTelegramButton.setOnClickListener { restartProxyService() }

            restartRequiredText.text = actions.restartNote.orEmpty()
            restartRequiredText.visibility = if (actions.restartNote == null) View.GONE else View.VISIBLE

            restartPendingButton.text = actions.telegramAction.orEmpty()
            restartPendingButton.visibility = if (actions.telegramAction == null) View.GONE else View.VISIBLE
            restartPendingButton.isEnabled = actions.telegramAction != null
            restartPendingButton.setOnClickListener { openTelegramProxyLink() }
            val telegramHelper = if (running && TelegramStatusUiText.showTelegramReconnectWarning(stats)) TelegramStatusUiText.RECONNECT_EXTRA_HELPER else null
            telegramCleanupHintText.text = telegramHelper.orEmpty()
            telegramCleanupHintText.visibility = if (telegramHelper == null) View.GONE else View.VISIBLE
            refreshHints()
        }

        if (::routeModeValueText.isInitialized) {
            val config = ProxyRuntimeConfig.appConfig(applicationContext)
            routeModeValueText.text = UserRouteModes.labelFor(config.routeMode)
            notificationStatusText.text = if (notificationPermissionMissing()) "Выключено" else "Включено"
            batteryStatusText.text = userBatteryLabel(ProxyForegroundService.State.batteryOptimizationStatus)
            autostartStatusText.text = if (config.autostart) "Включено" else "Не проверено"
            telemetryStatusText.text = if (config.telemetryEnabled) "Включено" else "Выключено"
            themeStatusText.text = appearanceLabel(config.appearance)
            hostStatusText.text = config.host
            portStatusText.text = config.port.toString()
            developerModeCheckBox.isChecked = developerModeEnabled()
            developerSection.visibility = if (developerModeEnabled()) View.VISIBLE else View.GONE
            telemetryCheckBox.isChecked = config.telemetryEnabled
            if (::telemetrySwitch.isInitialized) telemetrySwitch.isChecked = config.telemetryEnabled
            telemetryTestButton.isEnabled = config.telemetryEnabled
            secretStateText.text = "Secret скрыт"
        }

        if (::diagnosticsSummaryText.isInitialized) {
            diagnosticsSummaryText.text = diagnosticsSummaryLine()
            rawRouteDetailsText.text = routeDetailsLine()
            cfDetailsText.text = cfDetailsLine()
            directDetailsText.text = directDetailsLine()
            developerSection.visibility = if (developerModeEnabled()) View.VISIBLE else View.GONE
        }
        if (::logsText.isInitialized) {
            logsText.text = ProxyForegroundService.State.recentLogs()
                .takeLast(MAX_VISIBLE_LOG_LINES)
                .takeIf { it.isNotEmpty() }
                ?.joinToString("\n")
                ?: "Логов пока нет"
        }
    }

    private fun buildDiagnosticsScreen(): ScrollView {
        val density = resources.displayMetrics.density
        val padding = (16 * density).toInt()
        val rowGap = (8 * density).toInt()
        diagnosticsSummaryText = createValueText()
        logsText = createValueText(textSize = 13f).apply { setTextIsSelectable(true) }
        rawRouteDetailsText = createValueText(textSize = 13f).apply { setTextIsSelectable(true) }
        cfDetailsText = createValueText(textSize = 13f).apply { setTextIsSelectable(true) }
        directDetailsText = createValueText(textSize = 13f).apply { setTextIsSelectable(true) }
        developerSection = SettingsSection("Технические данные") {
            addView(SettingsRow("Подробности маршрута", rawRouteDetailsText), matchWrapParams())
            addView(SettingsRow("Состояние резервных доменов", cfDetailsText), matchWrapParams(topMargin = rowGap))
            addView(SettingsRow("Состояние прямого маршрута", directDetailsText), matchWrapParams(topMargin = rowGap))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(COLOR_BACKGROUND)
            addHeader()
            addView(SettingsSection("Диагностика") {
                addView(StatusChip("Сводка"), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                addView(diagnosticsSummaryText, matchWrapParams(topMargin = rowGap))
                addView(createButton("Копировать диагностику") { copyDiagnostics() }, matchWrapParams(topMargin = rowGap))
                addView(createButton("Поделиться диагностикой") { shareDiagnostics() }, matchWrapParams(topMargin = rowGap))
            }, cardParams())
            addView(SettingsSection("Журнал") {
                addView(Badge("Последние события"), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                addView(logsText, matchWrapParams(topMargin = rowGap))
            }, cardParams(topMargin = padding))
            developerSection.visibility = if (developerModeEnabled()) View.VISIBLE else View.GONE
            addView(developerSection, cardParams(topMargin = padding, bottomMargin = padding))
        }
        return ScrollView(this).apply {
            setBackgroundColor(COLOR_BACKGROUND)
            addView(content, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun refreshHints() {
        hintsContainer.removeAllViews()
        recommendationCards()
            .take(MAX_HINTS)
            .forEach { recommendation ->
                hintsContainer.addView(
                    RecommendationCard(recommendation),
                    cardParams(bottomMargin = (8 * resources.displayMetrics.density).toInt()),
                )
            }
    }

    private fun recommendationCards(): List<RecommendationCardModel> {
        val config = ProxyRuntimeConfig.appConfig(applicationContext)
        return buildList {
            if (notificationPermissionMissing() && !recommendationInCooldown(RECOMMENDATION_NOTIFICATIONS)) {
                add(recommendation(RECOMMENDATION_NOTIFICATIONS, RecommendationUiText.ENABLE_ACTION) {
                    openNotificationSettingsFlow()
                    coolDownRecommendation(RECOMMENDATION_NOTIFICATIONS)
                })
            }
            if (detectBatteryOptimizationStatus() != "unrestricted" && !recommendationInCooldown(RECOMMENDATION_BATTERY)) {
                add(recommendation(RECOMMENDATION_BATTERY, RecommendationUiText.OPEN_ACTION) {
                    openBatterySettings()
                    coolDownRecommendation(RECOMMENDATION_BATTERY)
                })
            }
            if (!config.autostart && !recommendationInCooldown(RECOMMENDATION_AUTOSTART)) {
                add(recommendation(RECOMMENDATION_AUTOSTART, RecommendationUiText.OPEN_ACTION) {
                    openBatterySettings()
                    coolDownRecommendation(RECOMMENDATION_AUTOSTART)
                })
            }
            if (!prefs.getBoolean(PREF_RECOMMENDATION_QS_DONE, false) && !recommendationInCooldown(RECOMMENDATION_QS)) {
                add(recommendation(RECOMMENDATION_QS, RecommendationUiText.ADD_ACTION) {
                    requestQuickSettingsTile()
                    coolDownRecommendation(RECOMMENDATION_QS)
                })
            }
            if (!config.telemetryEnabled && !recommendationInCooldown(RECOMMENDATION_TELEMETRY)) {
                add(recommendation(RECOMMENDATION_TELEMETRY, RecommendationUiText.ENABLE_ACTION) {
                    enableTelemetryFromRecommendation()
                    coolDownRecommendation(RECOMMENDATION_TELEMETRY)
                })
            }
        }
    }

    private fun recommendation(id: String, action: String, onClick: (View) -> Unit): RecommendationCardModel {
        val copy = RecommendationUiText.cards.getValue(id)
        return RecommendationCardModel(id, copy.first, copy.second, action, onClick)
    }

    private fun recommendationInCooldown(id: String): Boolean = prefs.getLong(recommendationCooldownKey(id), 0L) > System.currentTimeMillis()

    private fun coolDownRecommendation(id: String) {
        prefs.edit().putLong(recommendationCooldownKey(id), System.currentTimeMillis() + RECOMMENDATION_COOLDOWN_MS).apply()
    }

    private fun recommendationCooldownKey(id: String): String = "recommendation_${id}_cooldown_until"

    private fun RecommendationCard(model: RecommendationCardModel): LinearLayout {
        val density = resources.displayMetrics.density
        val gap = (8 * density).toInt()
        val card = SettingsSection(model.title) {
            addView(createValueText().apply {
                text = model.subtitle
                setTextColor(COLOR_TEXT_SECONDARY)
            }, matchWrapParams())
            val actions = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(createOutlinedButton(RecommendationUiText.DISMISS_ACTION) {
                    coolDownRecommendation(model.id)
                    refreshHints()
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = gap })
                addView(createFilledButton(model.actionLabel) { view ->
                    model.onClick(view)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            addView(actions, matchWrapParams(topMargin = gap))
        }
        card.isClickable = true
        card.isFocusable = true
        card.foreground = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).use { attrs -> attrs.getDrawable(0) }
        card.setOnClickListener { view ->
            view.isEnabled = false
            model.onClick(view)
            view.post { view.isEnabled = true }
        }
        return card
    }

    private fun handleMainPrimaryAction() {
        if (isFinishing || isDestroyed) return
        when (primaryControlButton.text.toString()) {
            MainActionModelMapper.START_ACTION -> {
                transitionStatus = TransitionStatus.STARTING
                requestNotificationPermissionIfNeeded()
                startProxyService()
            }
            MainActionModelMapper.STOP_ACTION -> confirmStopProxy()
        }
        refreshState()
    }

    private fun confirmStopProxy() {
        AlertDialog.Builder(this)
            .setTitle("Отключить прокси?")
            .setMessage("Telegram потеряет подключение через локальный прокси.")
            .setPositiveButton("Отключить") { _, _ ->
                transitionStatus = TransitionStatus.STOPPING
                startService(ProxyForegroundService.stopIntent(this))
                refreshState()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun telegramConnected(running: Boolean, stats: com.flowseal.tgwsandroid.proxy.ProxyServerStats?): Boolean =
        running && ConnectionStatusMapper.status(
            running = true,
            networkStatus = ProxyForegroundService.State.networkStatus,
            stats = stats,
        ) == "Подключён"

    private fun userHealthLabel(running: Boolean, stats: com.flowseal.tgwsandroid.proxy.ProxyServerStats?, checking: Boolean, failed: Boolean): String {
        if (checking || (running && stats == null)) return "Проверка…"
        if (failed) return "Нестабильно"
        val status = ConnectionStatusMapper.status(running, ProxyForegroundService.State.networkStatus, stats)
        return if (status == "Нестабильное соединение" || TelegramStatusUiText.showTelegramReconnectWarning(stats)) "Нестабильно" else "Стабильно"
    }

    private fun userNetworkChipLabel(network: String): String = when {
        network.equals("Wi-Fi", ignoreCase = true) || network.equals("wifi", ignoreCase = true) -> "Wi-Fi"
        network.equals("mobile", ignoreCase = true) || network.equals("cellular", ignoreCase = true) -> "Мобильная сеть"
        else -> "Нет сети"
    }

    private fun userModeChipLabel(): String = UserRouteModes.labelFor(ProxyRuntimeConfig.appConfig(applicationContext).routeMode)

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

    private fun diagnosticsSummaryLine(): String {
        val running = ProxyForegroundService.State.running
        val logs = ProxyForegroundService.State.recentLogs().size
        return "Сервис ${if (running) "работает" else "остановлен"}. Доступно записей диагностики: $logs."
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
            .setTitle(SettingsUiText.RESET_SECRET_DIALOG_TITLE)
            .setMessage(SettingsUiText.RESET_SECRET_DIALOG_MESSAGE)
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

    private fun openAutostartSettings() {
        val config = AppConfigStore.from(applicationContext).loadConfig()
        AppConfigStore.from(applicationContext).saveConfig(config.copy(autostart = true))
        ProxyRuntimeConfig.initialize(applicationContext)
        openBatterySettings()
        Toast.makeText(this, "Проверьте автозапуск в настройках устройства", Toast.LENGTH_LONG).show()
        refreshState()
    }

    private fun toggleTelemetry() {
        val store = AppConfigStore.from(applicationContext)
        val enabled = !store.loadConfig().telemetryEnabled
        store.saveConfig(store.loadConfig().copy(telemetryEnabled = enabled))
        ProxyRuntimeConfig.initialize(applicationContext)
        ProxyForegroundService.State.addLog("telemetry_enabled changed to $enabled", LogSeverity.INFO, "ui")
        Toast.makeText(this, if (enabled) "Анонимная диагностика включена" else "Анонимная диагностика выключена", Toast.LENGTH_SHORT).show()
        refreshState()
    }

    private fun showThemeDialog() {
        val options = Appearance.entries
        val labels = options.map(::appearanceLabel).toTypedArray()
        val current = ProxyRuntimeConfig.appConfig(applicationContext).appearance
        AlertDialog.Builder(this)
            .setTitle(SettingsUiText.THEME_TITLE)
            .setSingleChoiceItems(labels, options.indexOf(current).coerceAtLeast(0)) { dialog, index ->
                val store = AppConfigStore.from(applicationContext)
                store.saveConfig(store.loadConfig().copy(appearance = options[index]))
                ProxyRuntimeConfig.initialize(applicationContext)
                refreshState()
                dialog.dismiss()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun appearanceLabel(appearance: Appearance): String = when (appearance) {
        Appearance.AUTO -> "Авто"
        Appearance.LIGHT -> "Светлая"
        Appearance.DARK -> "Тёмная"
    }

    private fun openNotificationSettingsFlow() {
        if (notificationPermissionMissing()) {
            requestNotificationPermissionIfNeeded()
            return
        }
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        }
        if (!tryStartActivity(intent)) {
            Toast.makeText(this, "Не удалось открыть настройки уведомлений", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestQuickSettingsTile() {
        if (isFinishing || isDestroyed) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val statusBarManager = getSystemService(StatusBarManager::class.java)
            if (statusBarManager == null) {
                showQuickSettingsTileHelp()
                return
            }
            val component = ComponentName(this, ProxyQuickSettingsTileService::class.java)
            try {
                statusBarManager.requestAddTileService(
                    component,
                    "TG Proxy",
                    Icon.createWithResource(this, R.drawable.ic_qs_tg_proxy),
                    mainExecutor,
                ) { result ->
                    if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
                        result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
                    ) {
                        prefs.edit().putBoolean(PREF_RECOMMENDATION_QS_DONE, true).apply()
                    }
                    if (!isFinishing && !isDestroyed) refreshState()
                }
            } catch (_: Throwable) {
                showQuickSettingsTileHelp()
            }
        } else {
            showQuickSettingsTileHelp()
            prefs.edit().putBoolean(PREF_RECOMMENDATION_QS_DONE, true).apply()
        }
    }

    private fun enableTelemetryFromRecommendation() {
        val store = AppConfigStore.from(applicationContext)
        store.saveConfig(store.loadConfig().copy(telemetryEnabled = true))
        ProxyRuntimeConfig.initialize(applicationContext)
        ProxyForegroundService.State.addLog("telemetry_enabled changed to true", LogSeverity.INFO, "ui")
        Toast.makeText(this, "Анонимная диагностика включена", Toast.LENGTH_SHORT).show()
        refreshState()
    }

    private fun showQuickSettingsTileHelp() {
        AlertDialog.Builder(this)
            .setTitle(SettingsUiText.QS_TILE_HELP_TITLE)
            .setMessage(SettingsUiText.QS_TILE_HELP_MESSAGE)
            .setPositiveButton("Понятно", null)
            .show()
    }

    private fun sendTestTelemetry() {
        val config = AppConfigStore.from(applicationContext).loadConfig()
        if (!config.telemetryEnabled) {
            Toast.makeText(this, "Сначала включите анонимную диагностику", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Отправка тестовой телеметрии...", Toast.LENGTH_SHORT).show()
        thread(name = "test-telemetry", isDaemon = true) {
            val sent = Telemetry.sendTestEvent(applicationContext, config)
            ProxyForegroundService.State.addLog("test telemetry send result=$sent", if (sent) LogSeverity.INFO else LogSeverity.WARN, "ui")
            handler.post {
                if (!isFinishing && !isDestroyed) {
                    Toast.makeText(this, if (sent) "Тестовая телеметрия отправлена" else "Не удалось отправить тестовую телеметрию", Toast.LENGTH_SHORT).show()
                    refreshState()
                }
            }
        }
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

    private fun SettingsSection(title: String, subtitle: String? = null, body: LinearLayout.() -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.WHITE)
            cornerRadius = 16 * resources.displayMetrics.density
            setStroke((1 * resources.displayMetrics.density).toInt().coerceAtLeast(1), COLOR_CARD_STROKE)
        }
        val innerPadding = (16 * resources.displayMetrics.density).toInt()
        setPadding(innerPadding, innerPadding, innerPadding, innerPadding)
        if (title.isNotBlank()) {
            addView(createSectionTitle(title), matchWrapParams())
            if (subtitle != null) {
                addView(createValueText().apply {
                    text = subtitle
                    setTextColor(COLOR_TEXT_SECONDARY)
                }, matchWrapParams(topMargin = (4 * resources.displayMetrics.density).toInt(), bottomMargin = (8 * resources.displayMetrics.density).toInt()))
            } else {
                addView(View(this@MainActivity), LinearLayout.LayoutParams(1, (8 * resources.displayMetrics.density).toInt()))
            }
        }
        body()
    }

    private fun createSectionTitle(title: String): TextView = TextView(this).apply {
        text = title
        textSize = 17f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(COLOR_TEXT_PRIMARY)
    }

    private fun createButton(label: String, onClick: (View) -> Unit = {}): Button = createFilledButton(label, onClick)

    private fun createFilledButton(label: String, onClick: (View) -> Unit = {}): Button = MaterialButton(this).apply {
        text = label
        isAllCaps = false
        backgroundTintList = ColorStateList.valueOf(COLOR_ACCENT)
        setTextColor(Color.WHITE)
        setOnClickListener(onClick)
    }

    private fun createOutlinedButton(label: String, onClick: (View) -> Unit = {}): Button = MaterialButton(this).apply {
        text = label
        isAllCaps = false
        backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        strokeColor = ColorStateList.valueOf(COLOR_ACCENT)
        strokeWidth = (1 * resources.displayMetrics.density).toInt().coerceAtLeast(1)
        setTextColor(COLOR_ACCENT)
        setOnClickListener(onClick)
    }

    private fun createNavigationBar(): BottomNavigationView = BottomNavigationView(this).apply {
        setBackgroundColor(Color.WHITE)
        labelVisibilityMode = com.google.android.material.navigation.NavigationBarView.LABEL_VISIBILITY_LABELED
        menu.add(0, Screen.HOME.itemId, 0, "Главная")
        menu.add(0, Screen.SETTINGS.itemId, 1, "Настройки")
        menu.add(0, Screen.DIAGNOSTICS.itemId, 2, "Диагностика")
        selectedItemId = currentScreen.itemId
        setOnItemSelectedListener { item ->
            val selected = Screen.fromItemId(item.itemId) ?: return@setOnItemSelectedListener false
            if (selected != currentScreen) {
                showScreen(selected)
            }
            true
        }
        setOnItemReselectedListener { }
    }

    private fun SettingsRow(label: String, value: TextView): LinearLayout = createTextRow(label, value)

    private fun SettingsValueRow(title: String, description: String, value: TextView, onClick: (() -> Unit)? = null): LinearLayout =
        SettingsBaseRow(title, description, value, null, onClick)

    private fun SettingsStatusRow(title: String, description: String, value: TextView, badge: TextView? = null, onClick: () -> Unit): LinearLayout =
        SettingsBaseRow(title, description, value, badge, onClick)

    private fun SettingsActionRow(title: String, description: String, onClick: () -> Unit): LinearLayout =
        SettingsBaseRow(title, description, createValueText().apply { text = "›"; textSize = 22f }, null, onClick)

    private fun SettingsSwitchRow(title: String, description: String, status: TextView, badge: TextView? = null, onClick: () -> Unit): LinearLayout {
        val switch = Switch(this).apply { isClickable = false; isFocusable = false }
        telemetrySwitch = switch
        status.visibility = View.GONE
        return SettingsBaseRow(title, description, switch, badge, onClick)
    }

    private fun SettingsBaseRow(
        title: String,
        description: String,
        trailing: View,
        badge: TextView? = null,
        onClick: (() -> Unit)? = null,
    ): LinearLayout {
        val density = resources.displayMetrics.density
        val vertical = (10 * density).toInt()
        val horizontal = (4 * density).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(horizontal, vertical, horizontal, vertical)
            if (onClick != null) {
                isClickable = true
                isFocusable = true
                foreground = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).use { attrs -> attrs.getDrawable(0) }
                setOnClickListener { onClick() }
            }
            val left = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                val titleRow = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(TextView(this@MainActivity).apply {
                        text = title
                        textSize = 15f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(COLOR_TEXT_PRIMARY)
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    if (badge != null) addView(badge, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                }
                addView(titleRow, matchWrapParams())
                addView(createValueText().apply {
                    text = description
                    setTextColor(COLOR_TEXT_SECONDARY)
                }, matchWrapParams(topMargin = (3 * density).toInt()))
            }
            addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(trailing.apply {
                if (this is TextView) {
                    textSize = 14f
                    setTextColor(COLOR_TEXT_PRIMARY)
                    typeface = Typeface.DEFAULT_BOLD
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = (12 * density).toInt()
            })
        }
    }

    private fun ConnectionModeSelector(): LinearLayout {
        val config = ProxyRuntimeConfig.appConfig(applicationContext)
        val current = UserRouteModes.normalOptions.firstOrNull { it.routeMode == config.routeMode }
            ?: UserRouteModes.normalOptions.first()
        return SettingsValueRow(
            title = SettingsUiText.CONNECTION_MODE_TITLE,
            description = UserRouteModes.helperFor(current.routeMode),
            value = createValueText().apply { text = current.title },
            onClick = { showConnectionModeDialog() },
        )
    }

    private fun StatusChip(text: String): TextView = Badge(text, COLOR_ACCENT, Color.WHITE)

    private fun Badge(text: String, backgroundColor: Int = COLOR_BACKGROUND_ALT, textColor: Int = COLOR_TEXT_PRIMARY): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(textColor)
        val horizontal = (12 * resources.displayMetrics.density).toInt()
        val vertical = (6 * resources.displayMetrics.density).toInt()
        setPadding(horizontal, vertical, horizontal, vertical)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 999 * resources.displayMetrics.density
            setColor(backgroundColor)
        }
    }

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

    private enum class Screen(val itemId: Int) {
        HOME(1),
        SETTINGS(2),
        DIAGNOSTICS(3);

        companion object {
            fun fromItemId(itemId: Int): Screen? = entries.firstOrNull { it.itemId == itemId }
        }
    }
    private enum class TransitionStatus { NONE, STARTING, STOPPING }

    private data class RecommendationCardModel(
        val id: String,
        val title: String,
        val subtitle: String,
        val actionLabel: String,
        val onClick: (View) -> Unit,
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
        private const val PREF_RECOMMENDATION_QS_DONE = "recommendation_quick_settings_done"
        private const val RECOMMENDATION_NOTIFICATIONS = "notifications"
        private const val RECOMMENDATION_BATTERY = "battery"
        private const val RECOMMENDATION_AUTOSTART = "autostart"
        private const val RECOMMENDATION_QS = "quick_settings"
        private const val RECOMMENDATION_TELEMETRY = "telemetry"
        private const val RECOMMENDATION_COOLDOWN_MS = 7L * 24L * 60L * 60L * 1000L
        private const val MAX_HINTS = 2
        private const val MAX_VISIBLE_LOG_LINES = 12
        private const val COLOR_BACKGROUND = 0xFFF6F7FB.toInt()
        private const val COLOR_BACKGROUND_ALT = 0xFFEFF6FF.toInt()
        private const val COLOR_CARD_STROKE = 0xFFE5E7EB.toInt()
        private const val COLOR_TEXT_PRIMARY = 0xFF111827.toInt()
        private const val COLOR_TEXT_SECONDARY = 0xFF4B5563.toInt()
        private const val COLOR_TEXT_MUTED = 0xFF6B7280.toInt()
        private const val COLOR_WARNING = 0xFFB45309.toInt()
        private const val COLOR_ACCENT = 0xFF2563EB.toInt()
        private const val COLOR_SUCCESS = 0xFF047857.toInt()
    }
}
