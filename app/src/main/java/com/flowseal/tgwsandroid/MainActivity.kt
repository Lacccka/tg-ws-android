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
import android.content.res.Configuration
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
import android.app.ActivityManager
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.CompoundButtonCompat
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
    private lateinit var heroProgressBar: ProgressBar
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
    private lateinit var routeModeDescriptionText: TextView
    private lateinit var notificationStatusText: TextView
    private lateinit var autostartStatusText: TextView
    private lateinit var quickSettingsStatusText: TextView
    private lateinit var backgroundDataStatusText: TextView
    private lateinit var telemetryStatusText: TextView
    private lateinit var themeStatusText: TextView
    private lateinit var themeHelperText: TextView
    private var themeToggleGroup: MaterialButtonToggleGroup? = null
    private val themeButtons = mutableMapOf<Appearance, MaterialButton>()
    private var settingsScrollView: ScrollView? = null
    private lateinit var hostStatusText: TextView
    private lateinit var portStatusText: TextView
    private lateinit var secretStateText: TextView
    private lateinit var diagnosticsSummaryText: TextView
    private lateinit var diagnosticsProxyText: TextView
    private lateinit var diagnosticsNetworkText: TextView
    private lateinit var diagnosticsAvailabilityText: TextView
    private lateinit var diagnosticsDurationText: TextView
    private lateinit var diagnosticsLastCheckText: TextView
    private lateinit var diagnosticsGuidanceText: TextView
    private lateinit var diagnosticsRouteText: TextView
    private lateinit var diagnosticsLastConnectionText: TextView
    private lateinit var diagnosticsCheckButton: Button
    private lateinit var telemetryCheckBox: CheckBox
    private lateinit var telemetrySwitch: MaterialSwitch
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
    private var restartRequestedAtMs: Long = 0L
    private var diagnosticsLastCheckAtMs: Long? = null
    private var diagnosticsLastCheckDurationMs: Long? = null
    private var diagnosticsCheckInProgress: Boolean = false

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
        applyAppearanceToWindow(ProxyRuntimeConfig.appConfig(applicationContext).appearance)
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
        refreshLocalDiagnostics()
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
        contentHost = FrameLayout(this).apply { setBackgroundColor(currentColorScheme().background) }
        navigationBar = createNavigationBar()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(currentColorScheme().background)
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


    private fun rebuildCurrentScreenIfSettings() {
        if (::contentHost.isInitialized && currentScreen == Screen.SETTINGS) {
            showScreen(Screen.SETTINGS, forceRefresh = true)
        }
    }

    private fun rebuildRootForAppearanceChange(savedSettingsScrollY: Int = currentSettingsScrollY()) {
        val screen = currentScreen
        setContentView(buildRootView())
        showScreen(screen, forceRefresh = true)
        if (screen == Screen.SETTINGS) restoreSettingsScrollY(savedSettingsScrollY)
    }

    private fun currentSettingsScrollY(): Int = settingsScrollView?.scrollY ?: 0

    private fun restoreSettingsScrollY(scrollY: Int) {
        settingsScrollView?.post { settingsScrollView?.scrollTo(0, scrollY) }
    }

    private fun applyAppearanceToWindow(appearance: Appearance) {
        val dark = isDarkTheme(appearance)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !dark
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = !dark
    }

    private fun isDarkTheme(appearance: Appearance = ProxyRuntimeConfig.appConfig(applicationContext).appearance): Boolean = when (AppearanceUiModels.nightMode(appearance)) {
        ResolvedNightMode.DARK -> true
        ResolvedNightMode.LIGHT -> false
        ResolvedNightMode.FOLLOW_SYSTEM -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    private fun currentThemeStatusText(): String = if (isDarkTheme(Appearance.AUTO)) "Сейчас: тёмная" else "Сейчас: светлая"

    private fun buildHomeScreen(): ScrollView {
        val density = resources.displayMetrics.density
        val padding = (16 * density).toInt()
        val rowGap = (8 * density).toInt()
        val chipGap = (6 * density).toInt()
        statusText = createValueText(textSize = 30f, bold = true)
        heroSubtitleText = createValueText(textSize = 17f).apply { setTextColor(currentColorScheme().onSurfaceVariant) }
        heroProgressBar = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        networkText = Badge("Wi-Fi")
        routeText = Badge("Авто")
        qualityText = Badge("Проверка…")
        restartRequiredText = createValueText().apply {
            text = PendingRestartModel.RESTART_WARNING
            setTextColor(currentColorScheme().warning)
            typeface = Typeface.DEFAULT_BOLD
        }
        telegramCleanupHintText = createValueText().apply {
            text = ""
            setTextColor(currentColorScheme().onSurfaceVariant)
        }
        primaryControlButton = createFilledButton("Включить") { handleMainPrimaryAction() }
        connectTelegramButton = createOutlinedButton("Переподключить") { restartProxyService() }
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
            setBackgroundColor(currentColorScheme().background)
            addHeader()
            addView(SettingsSection("") {
                addView(statusText, matchWrapParams())
                addView(heroSubtitleText, matchWrapParams(topMargin = rowGap))
                addView(heroProgressBar, matchWrapParams(topMargin = rowGap))
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
            setBackgroundColor(currentColorScheme().background)
            addView(content, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun buildSettingsScreen(): ScrollView {
        val density = resources.displayMetrics.density
        val padding = (16 * density).toInt()
        val rowGap = (10 * density).toInt()
        val bottomContentPadding = (96 * density).toInt()
        routeModeValueText = createValueText()
        notificationStatusText = createValueText()
        batteryStatusText = createValueText()
        autostartStatusText = createValueText()
        quickSettingsStatusText = createValueText()
        backgroundDataStatusText = createValueText()
        telemetryStatusText = createValueText()
        themeStatusText = createValueText()
        themeHelperText = createValueText(textSize = 13f)
        themeToggleGroup = null
        themeButtons.clear()
        hostStatusText = createValueText()
        portStatusText = createValueText()
        secretStateText = createValueText().apply { setTextColor(currentColorScheme().success) }
        telemetryCheckBox = CheckBox(this).apply {
            applyCheckBoxStyle(this)
            text = "Отправлять анонимную диагностику"
            isAllCaps = false
            setTextColor(currentColorScheme().onSurface)
            setOnCheckedChangeListener { _, enabled ->
                val store = AppConfigStore.from(applicationContext)
                store.saveConfig(store.loadConfig().copy(telemetryEnabled = enabled))
                ProxyRuntimeConfig.initialize(applicationContext)
                ProxyForegroundService.State.addLog("telemetry_enabled changed to $enabled", LogSeverity.INFO, "ui")
                refreshState()
            }
        }
        developerModeCheckBox = CheckBox(this).apply {
            applyCheckBoxStyle(this)
            text = "Режим разработчика"
            isAllCaps = false
            isChecked = developerModeEnabled()
            setTextColor(currentColorScheme().onSurface)
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
        developerSection = SettingsSection("Для разработчика") {
            addView(createValueText().apply {
                text = "Показывает технические разделы во вкладке «Диагностика»."
                setTextColor(currentColorScheme().onSurfaceVariant)
            }, matchWrapParams())
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, bottomContentPadding)
            setBackgroundColor(currentColorScheme().background)
            addHeader()
            addView(SettingsSection("Важные параметры", "Эти параметры помогают прокси не отключаться в фоне.") {
                addView(SettingsStatusRow("Батарея", "Разрешите работать в фоне, чтобы прокси не отключался.", batteryStatusText, Badge("Важно", currentColorScheme().warningContainer, currentColorScheme().onWarningContainer)) { onRequestBatteryUnrestricted() }, matchWrapParams(topMargin = rowGap))
                addView(SettingsStatusRow("Автозапуск", "Разрешите запуск после перезагрузки телефона.", autostartStatusText, Badge("Важно", currentColorScheme().warningContainer, currentColorScheme().onWarningContainer)) { onOpenAutostartSettings() }, matchWrapParams(topMargin = rowGap))
                addView(SettingsStatusRow("Фоновые данные", "Если фоновые данные запрещены, прокси может работать только при открытом приложении.", backgroundDataStatusText, Badge("Важно", currentColorScheme().warningContainer, currentColorScheme().onWarningContainer)) { onOpenBackgroundDataSettings() }, matchWrapParams(topMargin = rowGap))
                addView(SettingsSwitchRow("Анонимная диагностика", "Помогает улучшать стабильность без личных данных.", telemetryStatusText, Badge("Рекомендуется")) { toggleTelemetry() }, matchWrapParams(topMargin = rowGap))
                addView(SettingsStatusRow("Кнопка в шторке", "Быстрый переключатель, чтобы включать и останавливать прокси без открытия приложения.", quickSettingsStatusText, Badge("Рекомендуется")) { onAddQuickSettingsTile() }, matchWrapParams(topMargin = rowGap))
                addView(SettingsStatusRow("Уведомления", "Показывают состояние подключения.", notificationStatusText, Badge("Рекомендуется")) { openNotificationSettingsFlow() }, matchWrapParams())
            }, cardParams())
            addView(SettingsSection("Подключение") {
                addView(ConnectionModeSelector(), matchWrapParams())
            }, cardParams(topMargin = padding))
            addView(SettingsSection("Telegram MTProto", "Параметры локального подключения Telegram.") {
                addView(SettingsValueRow("IP-адрес", "Адрес локального прокси.", hostStatusText) { showEditHostDialog() }, matchWrapParams())
                addView(SettingsValueRow("Порт", "Порт локального прокси.", portStatusText) { showEditPortDialog() }, matchWrapParams(topMargin = rowGap))
                addView(SettingsValueRow("Secret", "Скрыт в обычном интерфейсе.", secretStateText), matchWrapParams(topMargin = rowGap))
                addView(SettingsActionRow(SettingsUiText.RESET_SECRET_TITLE, "После обновления нужно заново подключить Telegram.") { confirmResetSecret() }, matchWrapParams(topMargin = rowGap))
            }, cardParams(topMargin = padding))
            addView(DcMappingsSection(), cardParams(topMargin = padding))
            addView(SettingsSection("Внешний вид") {
                addView(ThemeSelector(), matchWrapParams())
            }, cardParams(topMargin = padding))
            addView(SettingsSection("Для разработчика") {
                addView(developerModeCheckBox, matchWrapParams())
                developerSection.visibility = if (developerModeEnabled()) View.VISIBLE else View.GONE
                addView(developerSection, matchWrapParams(topMargin = rowGap))
            }, cardParams(topMargin = padding))

        }
        return ScrollView(this).apply {
            settingsScrollView = this
            setBackgroundColor(currentColorScheme().background)
            addView(content, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun LinearLayout.addHeader() {
        val smallPadding = (8 * resources.displayMetrics.density).toInt()
        addView(TextView(this@MainActivity).apply {
            text = "TG WS"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(currentColorScheme().onSurface)
        }, matchWrapParams())
        addView(TextView(this@MainActivity).apply {
            text = "Локальный прокси для Telegram"
            textSize = 15f
            setTextColor(currentColorScheme().onSurfaceVariant)
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
        updateConnectionModeRow(routeMode)
        refreshState()
    }

    private fun showConnectionModeDialog() {
        val options = UserRouteModes.normalOptions
        val labels = options.map { it.title }.toTypedArray()
        val current = ProxyRuntimeConfig.appConfig(applicationContext).routeMode
        showThemeAwareSingleChoiceDialog(
            title = SettingsUiText.CONNECTION_MODE_TITLE,
            labels = labels,
            checkedIndex = options.indexOfFirst { it.routeMode == current }.coerceAtLeast(0),
        ) { index ->
            setRouteMode(options[index].routeMode)
        }
    }


    private fun showThemeAwareSingleChoiceDialog(
        title: String,
        labels: Array<String>,
        checkedIndex: Int,
        onSelected: (Int) -> Unit,
    ) {
        val density = resources.displayMetrics.density
        val colors = currentColorScheme()
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colors.surface)
            setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
        }
        lateinit var dialog: AlertDialog
        labels.forEachIndexed { index, label ->
            val radioButton = android.widget.RadioButton(this).apply {
                text = label
                isChecked = index == checkedIndex
                setTextColor(colors.onSurface)
                textSize = 16f
                applyRadioButtonStyle(this)
                setPadding((16 * density).toInt(), (10 * density).toInt(), (16 * density).toInt(), (10 * density).toInt())
                setOnClickListener {
                    onSelected(index)
                    dialog.dismiss()
                }
            }
            list.addView(radioButton, matchWrapParams())
        }
        dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(list)
            .setNegativeButton("Отмена", null)
            .show()
        dialog.window?.decorView?.setBackgroundColor(colors.surface)
    }

    private fun refreshState() {
        ProxyRuntimeConfig.initialize(applicationContext)
        refreshLocalDiagnostics()
        val running = ProxyForegroundService.State.running
        if (!running && transitionStatus != TransitionStatus.STARTING && transitionStatus != TransitionStatus.RESTARTING) pendingRestartRequired = false
        updateTransitionStatus(running)
        val failed = ProxyForegroundService.State.lastStatus.contains("failed", ignoreCase = true) ||
            ProxyForegroundService.State.lastStatus.contains("error", ignoreCase = true)

        if (::statusText.isInitialized) {
            val stats = ProxyForegroundService.State.stats()
            val starting = transitionStatus == TransitionStatus.STARTING
            val stopping = transitionStatus == TransitionStatus.STOPPING
            val restarting = transitionStatus == TransitionStatus.RESTARTING
            val healthLabel = userHealthLabel(running, stats, starting, failed)
            val telegramConnected = telegramConnected(running, stats)
            val unstable = failed || healthLabel == "Нестабильно" || TelegramStatusUiText.showTelegramReconnectWarning(stats)

            val hero = MainHeroStateMapper.state(
                running = running,
                starting = starting,
                stopping = stopping,
                restarting = restarting,
                failed = failed,
                healthLabel = healthLabel,
                telegramReconnectWarning = TelegramStatusUiText.showTelegramReconnectWarning(stats),
                telegramConnected = telegramConnected,
            )

            statusText.text = hero.title
            heroSubtitleText.text = hero.subtitle
            heroProgressBar.visibility = if (starting || restarting) View.VISIBLE else View.GONE
            networkText.text = userNetworkChipLabel(ProxyForegroundService.State.networkStatus)
            routeText.text = userModeChipLabel()
            qualityText.text = healthLabel

            val actions = MainActionModelMapper.actions(
                running = running,
                starting = starting,
                stopping = stopping,
                restarting = restarting,
                settingsChangedPendingRestart = pendingRestartRequired && running,
            )

            primaryControlButton.text = actions.primaryAction.orEmpty()
            primaryControlButton.visibility = if (actions.primaryAction == null) View.GONE else View.VISIBLE
            primaryControlButton.isEnabled = actions.primaryAction != null

            connectTelegramButton.text = actions.restartAction.orEmpty()
            connectTelegramButton.visibility = if (actions.restartAction == null) View.GONE else View.VISIBLE
            connectTelegramButton.isEnabled = running && !restarting
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
            val powerStatus = currentPowerSetupStatus(config.autostart)
            batteryStatusText.text = PowerSetupUiMapper.batteryStatus(powerStatus.batteryOptimization)
            autostartStatusText.text = PowerSetupUiMapper.autostartStatus(powerStatus.appBootPreference, powerStatus.oemAutostart)
            quickSettingsStatusText.text = PowerSetupUiMapper.quickSettingsStatus(powerStatus.quickSettingsTile)
            backgroundDataStatusText.text = PowerSetupUiMapper.backgroundStatus(powerStatus.backgroundRestriction)
            telemetryStatusText.text = if (config.telemetryEnabled) "Включено" else "Выключено"
            updateConnectionModeRow(config.routeMode)
            updateThemeSelectorState(config.appearance)
            hostStatusText.text = config.host
            portStatusText.text = config.port.toString()
            developerModeCheckBox.isChecked = developerModeEnabled()
            developerSection.visibility = if (developerModeEnabled()) View.VISIBLE else View.GONE
            telemetryCheckBox.isChecked = config.telemetryEnabled
            if (::telemetrySwitch.isInitialized) telemetrySwitch.isChecked = config.telemetryEnabled
            if (::telemetryTestButton.isInitialized) {
                telemetryTestButton.isEnabled = config.telemetryEnabled
                telemetryTestButton.visibility = if (developerModeEnabled()) View.VISIBLE else View.GONE
            }
            secretStateText.text = ProxyRuntimeConfig.partialTelegramSecret(applicationContext)
        }

        if (::diagnosticsSummaryText.isInitialized) {
            updateDiagnosticsDashboard()
            rawRouteDetailsText.text = routeDetailsLine()
            cfDetailsText.text = cfDetailsLine()
            directDetailsText.text = directDetailsLine()
            if (::telemetryStatusText.isInitialized) {
                val enabled = ProxyRuntimeConfig.appConfig(applicationContext).telemetryEnabled
                telemetryStatusText.text = "Анонимная диагностика: ${if (enabled) "включена" else "выключена"}"
            }
            developerSection.visibility = if (developerModeEnabled()) View.VISIBLE else View.GONE
        }
    }

    private fun buildDiagnosticsScreen(): ScrollView {
        val density = resources.displayMetrics.density
        val padding = (16 * density).toInt()
        val rowGap = (8 * density).toInt()
        val bottomContentPadding = (96 * density).toInt()
        diagnosticsSummaryText = createValueText()
        diagnosticsProxyText = createValueText()
        diagnosticsNetworkText = createValueText()
        diagnosticsAvailabilityText = createValueText()
        diagnosticsDurationText = createValueText()
        diagnosticsLastCheckText = createValueText()
        diagnosticsGuidanceText = createValueText()
        diagnosticsRouteText = createValueText()
        diagnosticsLastConnectionText = createValueText()
        diagnosticsCheckButton = createButton("Проверить сейчас") { runDiagnosticsAvailabilityCheck() }
        telemetryStatusText = createValueText()
        telemetryTestButton = createButton("Отправить тестовую телеметрию") { sendTestTelemetry() }
        rawRouteDetailsText = createValueText(textSize = 13f).apply { setTextIsSelectable(true) }
        cfDetailsText = createValueText(textSize = 13f).apply { setTextIsSelectable(true) }
        directDetailsText = createValueText(textSize = 13f).apply { setTextIsSelectable(true) }
        developerSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (developerModeEnabled()) View.VISIBLE else View.GONE
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, bottomContentPadding)
            setBackgroundColor(currentColorScheme().background)
            addHeader()
            addView(SettingsSection("Проверка подключения") {
                addView(diagnosticsSummaryText, matchWrapParams())
                addView(SettingsRow("Прокси", diagnosticsProxyText), matchWrapParams(topMargin = rowGap))
                addView(SettingsRow("Сеть", diagnosticsNetworkText), matchWrapParams(topMargin = rowGap))
                addView(SettingsRow("Доступность", diagnosticsAvailabilityText), matchWrapParams(topMargin = rowGap))
                addView(SettingsRow("Время ответа", diagnosticsDurationText), matchWrapParams(topMargin = rowGap))
                addView(SettingsRow("Последняя проверка", diagnosticsLastCheckText), matchWrapParams(topMargin = rowGap))
                addView(diagnosticsCheckButton, matchWrapParams(topMargin = rowGap))
            }, cardParams())
            addView(SettingsSection("Что можно сделать") {
                addView(diagnosticsGuidanceText, matchWrapParams())
            }, cardParams(topMargin = padding))
            addView(SettingsSection("Сеть и маршрут") {
                addView(SettingsRow("Сеть", createValueText().apply { text = userNetworkLabel(ProxyForegroundService.State.networkStatus) }), matchWrapParams())
                addView(SettingsRow("Режим", diagnosticsRouteText), matchWrapParams(topMargin = rowGap))
                addView(SettingsRow("Последнее подключение", diagnosticsLastConnectionText), matchWrapParams(topMargin = rowGap))
            }, cardParams(topMargin = padding))
            developerSection.addView(SettingsSection("Инструменты разработчика") {
                addView(createButton("Копировать диагностику") { copyDiagnostics() }, matchWrapParams())
                addView(createButton("Поделиться диагностикой") { shareDiagnostics() }, matchWrapParams(topMargin = rowGap))
                addView(createButton("Открыть лог") { showLogsDialog() }, matchWrapParams(topMargin = rowGap))
                addView(createButton("Очистить логи") { confirmClearLogs() }, matchWrapParams(topMargin = rowGap))
                telemetryTestButton.visibility = if (developerModeEnabled()) View.VISIBLE else View.GONE
                addView(telemetryTestButton, matchWrapParams(topMargin = rowGap))
            }, cardParams(topMargin = padding))
            developerSection.addView(CollapsibleSettingsSection("Маршрут: детали", rawRouteDetailsText), cardParams(topMargin = padding))
            developerSection.addView(CollapsibleSettingsSection("Cloudflare: детали", cfDetailsText), cardParams(topMargin = padding))
            developerSection.addView(CollapsibleSettingsSection("Direct/pool: детали", directDetailsText), cardParams(topMargin = padding))
            developerSection.addView(CollapsibleSettingsSection("Handshake: детали", createValueText(textSize = 13f).apply { text = directDetailsLine(); setTextIsSelectable(true) }), cardParams(topMargin = padding))
            developerSection.addView(CollapsibleSettingsSection("Счётчики", createValueText(textSize = 13f).apply { text = developerCountersSummary(); setTextIsSelectable(true) }), cardParams(topMargin = padding))
            addView(developerSection, matchWrapParams())
        }
        updateDiagnosticsDashboard()
        return ScrollView(this).apply {
            setBackgroundColor(currentColorScheme().background)
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
                    onRequestBatteryUnrestricted()
                    coolDownRecommendation(RECOMMENDATION_BATTERY)
                })
            }
            if (!config.autostart && !recommendationInCooldown(RECOMMENDATION_AUTOSTART)) {
                add(recommendation(RECOMMENDATION_AUTOSTART, RecommendationUiText.OPEN_ACTION) {
                    onOpenAutostartSettings()
                    coolDownRecommendation(RECOMMENDATION_AUTOSTART)
                })
            }
            if (!prefs.getBoolean(PREF_RECOMMENDATION_QS_DONE, false) && !recommendationInCooldown(RECOMMENDATION_QS)) {
                add(recommendation(RECOMMENDATION_QS, RecommendationUiText.ADD_ACTION) {
                    onAddQuickSettingsTile()
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
                setTextColor(currentColorScheme().onSurfaceVariant)
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

    private fun lastProblemText(): String {
        val recent = ProxyForegroundService.State.recentLogs().asReversed().firstOrNull { line ->
            line.contains("ERROR", ignoreCase = true) ||
                line.contains("WARN", ignoreCase = true) ||
                line.contains("failed", ignoreCase = true) ||
                line.contains("error", ignoreCase = true)
        } ?: return "Нет недавних ошибок"
        return when {
            recent.contains("network", ignoreCase = true) -> "Сеть была недоступна"
            recent.contains("connect", ignoreCase = true) || recent.contains("failed", ignoreCase = true) -> "Не удалось подключиться"
            recent.contains("closed", ignoreCase = true) || recent.contains("reset", ignoreCase = true) -> "Подключение было прервано"
            else -> "Есть ошибки"
        }
    }


    private fun runDiagnosticsAvailabilityCheck() {
        diagnosticsCheckInProgress = true
        val started = SystemClock.elapsedRealtime()
        updateDiagnosticsDashboard()
        handler.postDelayed({
            diagnosticsLastCheckDurationMs = (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L)
            diagnosticsLastCheckAtMs = System.currentTimeMillis()
            diagnosticsCheckInProgress = false
            if (ProxyForegroundService.State.running) refreshLocalDiagnostics()
            updateDiagnosticsDashboard()
        }, 150L)
    }

    private fun updateDiagnosticsDashboard() {
        val running = ProxyForegroundService.State.running
        val network = ProxyForegroundService.State.networkStatus
        val networkAvailable = !network.equals("none", ignoreCase = true) && !network.equals("unknown", ignoreCase = true) && network.isNotBlank()
        val stats = ProxyForegroundService.State.stats()
        val connected = telegramConnected(running, stats)
        diagnosticsSummaryText.text = when {
            diagnosticsCheckInProgress -> "Проверяем..."
            !running -> "Прокси выключен"
            !networkAvailable -> "Есть проблема с подключением"
            connected -> "Прокси работает"
            diagnosticsLastCheckAtMs == null -> "Проверка не выполнялась"
            else -> "Есть проблема с подключением"
        }
        diagnosticsProxyText.text = if (running) "Работает" else "Выключен"
        diagnosticsNetworkText.text = if (networkAvailable) userNetworkLabel(network) else "Недоступна"
        diagnosticsAvailabilityText.text = when {
            !running -> "Не проверено"
            diagnosticsCheckInProgress -> "Проверяем..."
            diagnosticsLastCheckAtMs == null -> "Не проверено"
            networkAvailable -> if (connected || stats != null) "Доступно" else "Недостаточно данных"
            else -> "Недоступно"
        }
        diagnosticsDurationText.text = diagnosticsLastCheckDurationMs?.let { "$it мс" } ?: "—"
        diagnosticsLastCheckText.text = diagnosticsLastCheckAtMs?.let { relativeCheckTime(it) } ?: "не выполнялась"
        diagnosticsCheckButton.isEnabled = !diagnosticsCheckInProgress
        diagnosticsRouteText.text = userRouteLabel()
        diagnosticsLastConnectionText.text = if (connected) "сейчас" else "Недостаточно данных"
        diagnosticsGuidanceText.text = diagnosticsRecommendations(running, networkAvailable, connected).joinToString("\n") { "• $it" }
    }

    private fun diagnosticsRecommendations(running: Boolean, networkAvailable: Boolean, connected: Boolean): List<String> = buildList {
        if (!running) add("Включите прокси на главном экране.")
        if (!networkAvailable) add("Проверьте Wi-Fi или мобильную сеть.")
        if (running && networkAvailable && !connected) {
            add("Попробуйте переподключить прокси на главном экране.")
            add("Откройте Telegram и подключите прокси заново.")
        }
        if (running && networkAvailable && connected) add("Действия не требуются.")
    }.take(3)

    private fun relativeCheckTime(timeMs: Long): String {
        val seconds = ((System.currentTimeMillis() - timeMs).coerceAtLeast(0L) / 1000L).toInt()
        return when {
            seconds < 30 -> "сейчас"
            seconds < 60 -> "$seconds сек. назад"
            seconds < 3600 -> "${seconds / 60} мин. назад"
            else -> "давно"
        }
    }

    private fun showLogsDialog() {
        val colors = currentColorScheme()
        val logText = ProxyForegroundService.State.recentLogs().takeIf { it.isNotEmpty() }?.joinToString("\n") ?: "Логов пока нет"
        val textView = createValueText(textSize = 13f).apply {
            text = logText
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setTextColor(colors.onSurface)
            setBackgroundColor(colors.surfaceContainer)
            setPadding((16 * resources.displayMetrics.density).toInt(), (16 * resources.displayMetrics.density).toInt(), (16 * resources.displayMetrics.density).toInt(), (16 * resources.displayMetrics.density).toInt())
        }
        val content = ScrollView(this).apply {
            setBackgroundColor(colors.surfaceContainer)
            addView(textView)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Открыть лог")
            .setView(content)
            .setPositiveButton("Закрыть", null)
            .show()
        dialog.window?.decorView?.setBackgroundColor(colors.surface)
    }

    private fun confirmClearLogs() {
        AlertDialog.Builder(this)
            .setTitle("Очистить логи?")
            .setMessage("Последние записи диагностики будут удалены из локального журнала.")
            .setPositiveButton("Очистить") { _, _ ->
                ProxyForegroundService.State.clearLogs()
                refreshState()
            }
            .setNegativeButton("Отмена", null)
            .show()
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


    private fun developerCountersSummary(): String {
        val stats = ProxyForegroundService.State.stats() ?: return "Нет заметных событий."
        val groups = buildList {
            addCounterGroup("Подключения", listOfNotNull(
                "Активные сессии: ${stats.connectionsActive}".takeIf { stats.connectionsActive > 0 },
                "Всего подключений: ${stats.connectionsTotal}".takeIf { stats.connectionsTotal > 0 },
                "Сбои подключений: ${stats.connectionsBad}".takeIf { stats.connectionsBad > 0 },
                "Сбросы соединений: ${stats.sessionEndDiagnostics.connectionReset.count}".takeIf { stats.sessionEndDiagnostics.connectionReset.count > 0 },
            ))
            addCounterGroup("Маршруты", listOfNotNull(
                "Последний маршрут: ${stats.lastRouteUsed}".takeIf { !stats.lastRouteUsed.isNullOrBlank() && !stats.lastRouteUsed.equals("none", ignoreCase = true) },
                "Direct успешно/сбоев: ${stats.directHealthSuccesses}/${stats.directHealthFailures}".takeIf { stats.directHealthSuccesses > 0 || stats.directHealthFailures > 0 },
                "CF успешно/сбоев: ${stats.cfProxyConnections}/${stats.cfProxyErrors}".takeIf { stats.cfProxyConnections > 0 || stats.cfProxyErrors > 0 },
                "Восстановления CF-first: ${stats.recoveryDiagnostics.cfFirst.successes}/${stats.recoveryDiagnostics.cfFirst.failures}".takeIf { stats.recoveryDiagnostics.cfFirst.successes > 0 || stats.recoveryDiagnostics.cfFirst.failures > 0 },
                "Экстренный direct: ${stats.recoveryDiagnostics.emergencyDirectFallback.successes}/${stats.recoveryDiagnostics.emergencyDirectFallback.failures}".takeIf { stats.recoveryDiagnostics.emergencyDirectFallback.successes > 0 || stats.recoveryDiagnostics.emergencyDirectFallback.failures > 0 },
            ))
            addCounterGroup("Сеть", listOfNotNull(
                "Смены сети: ${stats.mobileNetworkGenerationChanges}".takeIf { stats.mobileNetworkGenerationChanges > 0 },
                "События без сети: ${stats.networkNoneEvents}".takeIf { stats.networkNoneEvents > 0 },
                "Mobile восстановление: ${stats.noneToMobileRecoverySuccesses}/${stats.noneToMobileRecoveryAttempts}".takeIf { stats.noneToMobileRecoverySuccesses > 0 || stats.noneToMobileRecoveryAttempts > 0 },
                "Wi‑Fi восстановление: ${stats.wifiDirectRecoverySuccesses}/${stats.wifiDirectRecoveryFailures}".takeIf { stats.wifiDirectRecoverySuccesses > 0 || stats.wifiDirectRecoveryFailures > 0 },
            ))
            addCounterGroup("Handshake", listOfNotNull(
                "Принято: ${stats.recentAcceptedHandshakeCount}".takeIf { stats.recentAcceptedHandshakeCount > 0 },
                "Отклонено: ${stats.recentInvalidHandshakeCount}".takeIf { stats.recentInvalidHandshakeCount > 0 },
                "Всего отклонено: ${stats.connectionsBad}".takeIf { stats.connectionsBad > 0 },
            ))
            addCounterGroup("Очереди и пул", listOfNotNull(
                "Пул попадания/промахи: ${stats.poolHits}/${stats.poolMisses}".takeIf { stats.poolHits > 0 || stats.poolMisses > 0 },
                "Устаревшие записи пула: ${stats.poolStale}".takeIf { stats.poolStale > 0 },
                "Ошибки пополнения пула: ${stats.poolRefillErrors}".takeIf { stats.poolRefillErrors > 0 },
                "CF очередь: ${stats.cfQueueControlledFailures}".takeIf { stats.cfQueueControlledFailures > 0 },
                "CF тайм-ауты очереди: ${stats.cfConnectQueueTimeouts}".takeIf { stats.cfConnectQueueTimeouts > 0 },
            ))
        }
        return groups.takeIf { it.isNotEmpty() }?.joinToString("\n\n") ?: "Нет заметных событий."
    }

    private fun MutableList<String>.addCounterGroup(title: String, rows: List<String>) {
        if (rows.isNotEmpty()) add((listOf(title) + rows.take(5).map { "• $it" }).joinToString("\n"))
    }

    private fun secretStateLine(): String = "Secret: ${ProxyRuntimeConfig.partialTelegramSecret(applicationContext)}"

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
        transitionStatus = TransitionStatus.RESTARTING
        restartRequestedAtMs = SystemClock.elapsedRealtime()
        pendingRestartRequired = false
        startService(ProxyForegroundService.restartIntent(this))
        refreshState()
    }


    private fun updateTransitionStatus(running: Boolean) {
        when (transitionStatus) {
            TransitionStatus.STARTING -> if (running) transitionStatus = TransitionStatus.NONE
            TransitionStatus.STOPPING -> if (!running) transitionStatus = TransitionStatus.NONE
            TransitionStatus.RESTARTING -> {
                val elapsedMs = SystemClock.elapsedRealtime() - restartRequestedAtMs
                when {
                    elapsedMs >= RESTART_UI_TIMEOUT_MS -> {
                        transitionStatus = TransitionStatus.NONE
                        restartRequestedAtMs = 0L
                    }
                    running && elapsedMs >= RESTART_UI_MIN_DURATION_MS -> {
                        transitionStatus = TransitionStatus.NONE
                        restartRequestedAtMs = 0L
                    }
                    running -> handler.postDelayed({
                        if (!isFinishing && !isDestroyed) refreshState()
                    }, (RESTART_UI_MIN_DURATION_MS - elapsedMs).coerceAtLeast(0L))
                }
            }
            TransitionStatus.NONE -> Unit
        }
    }

    private fun showEditHostDialog() {
        val current = ProxyRuntimeConfig.appConfig(applicationContext)
        val input = EditText(this).apply {
            setText(current.host)
            selectAll()
            inputType = InputType.TYPE_CLASS_PHONE
        }
        AlertDialog.Builder(this)
            .setTitle("IP-адрес")
            .setView(input)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить", null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val value = input.text.toString().trim()
                        if (!isValidIpv4(value)) {
                            input.error = "Введите корректный IP-адрес"
                            return@setOnClickListener
                        }
                        saveMtprotoConfig(current.copy(host = value), "host changed")
                        dialog.dismiss()
                    }
                }
            }
            .show()
    }

    private fun showEditPortDialog() {
        val current = ProxyRuntimeConfig.appConfig(applicationContext)
        val input = EditText(this).apply {
            setText(current.port.toString())
            selectAll()
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(this)
            .setTitle("Порт")
            .setView(input)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить", null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val port = input.text.toString().trim().toIntOrNull()
                        if (port == null || port !in 1..65535) {
                            input.error = "Введите корректный порт"
                            return@setOnClickListener
                        }
                        saveMtprotoConfig(current.copy(port = port), "port changed")
                        dialog.dismiss()
                    }
                }
            }
            .show()
    }

    private fun saveMtprotoConfig(config: com.flowseal.tgwsandroid.config.AppConfig, logReason: String) {
        AppConfigStore.from(applicationContext).saveConfig(config)
        ProxyRuntimeConfig.initialize(applicationContext)
        val running = ProxyForegroundService.State.running
        pendingRestartRequired = running
        ProxyForegroundService.State.addLog(
            if (running) "$logReason; reconnect required" else "$logReason; will apply on next proxy start",
            LogSeverity.INFO,
            "ui",
        )
        if (running) Toast.makeText(this, "Настройки изменены — Переподключить", Toast.LENGTH_LONG).show()
        refreshState()
    }

    private fun isValidIpv4(value: String): Boolean {
        return DcMappingEditor.isValidIpv4(value)
    }

    private fun DcMappingsSection(): LinearLayout {
        val density = resources.displayMetrics.density
        val rowGap = (10 * density).toInt()
        val mappings = DcMappingEditor.validMappings(ProxyRuntimeConfig.appConfig(applicationContext).dcIp)
        return SettingsSection(SettingsUiText.DC_SECTION_TITLE, SettingsUiText.DC_SECTION_SUBTITLE) {
            if (mappings.isEmpty()) {
                addView(
                    SettingsAdaptiveRow(SettingsUiText.DC_EMPTY_TITLE, SettingsUiText.DC_EMPTY_DESCRIPTION),
                    matchWrapParams(),
                )
            } else {
                mappings.forEachIndexed { index, mapping ->
                    addView(
                        SettingsActionRow(mapping.rowTitle, mapping.ip) { showDcMappingDialog(mapping) },
                        matchWrapParams(topMargin = if (index == 0) 0 else rowGap),
                    )
                }
            }
            addView(
                SettingsActionRow(SettingsUiText.DC_ADD_TITLE, SettingsUiText.DC_EMPTY_DESCRIPTION) { showDcMappingDialog(null) },
                matchWrapParams(topMargin = rowGap),
            )
        }
    }

    private fun showDcMappingDialog(mapping: DcMapping?) {
        val current = ProxyRuntimeConfig.appConfig(applicationContext)
        val density = resources.displayMetrics.density
        val dcInput = EditText(this).apply {
            setText(mapping?.dc?.toString().orEmpty())
            hint = "2"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val ipInput = EditText(this).apply {
            setText(mapping?.ip.orEmpty())
            hint = "149.154.167.220"
            inputType = InputType.TYPE_CLASS_PHONE
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), 0)
            addView(dcInput, matchWrapParams())
            addView(ipInput, matchWrapParams(topMargin = (8 * density).toInt()))
        }
        AlertDialog.Builder(this)
            .setTitle(if (mapping == null) "Добавить датацентр" else "Изменить датацентр")
            .setView(form)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить", null)
            .apply {
                if (mapping != null) setNeutralButton("Удалить", null)
            }
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val dc = dcInput.text.toString().trim().toIntOrNull()
                        val ip = ipInput.text.toString().trim()
                        if (dc == null || dc <= 0) {
                            dcInput.error = "Введите номер DC"
                            return@setOnClickListener
                        }
                        if (!DcMappingEditor.isValidIpv4(ip)) {
                            ipInput.error = "Введите корректный IP-адрес"
                            return@setOnClickListener
                        }
                        saveDcMapping(current.dcIp, mapping?.dc, DcMapping(dc, ip))
                        dialog.dismiss()
                    }
                    if (mapping != null) {
                        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                            confirmDeleteDcMapping(mapping)
                            dialog.dismiss()
                        }
                    }
                }
            }
            .show()
    }

    private fun saveDcMapping(rawDcIp: List<String>, oldDc: Int?, newMapping: DcMapping) {
        val newRaw = DcMappingEditor.raw(newMapping)
        val updated = buildList {
            var replaced = false
            rawDcIp.forEach { raw ->
                val parsed = DcMappingEditor.parse(raw)
                when {
                    parsed?.dc == oldDc -> {
                        if (!replaced) add(newRaw)
                        replaced = true
                    }
                    parsed?.dc == newMapping.dc -> {
                        if (!replaced) add(newRaw)
                        replaced = true
                    }
                    else -> add(raw)
                }
            }
            if (!replaced) add(newRaw)
        }
        saveMtprotoConfig(ProxyRuntimeConfig.appConfig(applicationContext).copy(dcIp = updated), "dc mapping changed")
        val savedScrollY = currentSettingsScrollY()
        rebuildCurrentScreenIfSettings()
        restoreSettingsScrollY(savedScrollY)
    }

    private fun confirmDeleteDcMapping(mapping: DcMapping) {
        AlertDialog.Builder(this)
            .setTitle("Удалить датацентр?")
            .setMessage("${mapping.rowTitle} / ${mapping.ip}")
            .setPositiveButton("Удалить") { _, _ ->
                val current = ProxyRuntimeConfig.appConfig(applicationContext)
                val updated = current.dcIp.filterNot { DcMappingEditor.parse(it)?.dc == mapping.dc }
                saveMtprotoConfig(current.copy(dcIp = updated), "dc mapping deleted")
                val savedScrollY = currentSettingsScrollY()
                rebuildCurrentScreenIfSettings()
                restoreSettingsScrollY(savedScrollY)
            }
            .setNegativeButton("Отмена", null)
            .show()
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
        pendingRestartRequired = running
        val logMessage = if (running) {
            "proxy secret reset; reconnect required"
        } else {
            "proxy secret reset; will apply on next proxy start"
        }
        ProxyForegroundService.State.addLog(logMessage, LogSeverity.INFO, "ui")
        if (running) Toast.makeText(this, "Настройки изменены — Переподключить", Toast.LENGTH_LONG).show()
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

    private fun openBatterySettings() = onOpenBatterySetupAction()

    private fun onOpenBatterySetupAction() {
        val navigator = PowerSettingsNavigator(this)
        val result = if (detectBatteryOptimizationStatus() == "unrestricted") {
            navigator.openBatteryOptimizationSettings()
        } else {
            navigator.requestIgnoreBatteryOptimizations(this)
        }
        if (result == PowerSettingsOpenResult.Failed) Toast.makeText(this, "Не удалось открыть настройки батареи", Toast.LENGTH_LONG).show()
        refreshState()
    }

    private fun onRequestBatteryUnrestricted() = onOpenBatterySetupAction()

    private fun onOpenAutostartSettings() {
        val result = PowerSettingsNavigator(this).openAutostartSettings()
        prefs.edit().putString(PREF_OEM_AUTOSTART_STATUS, if (result == PowerSettingsOpenResult.Exact) "opened" else "not_supported").apply()
        Toast.makeText(this, if (result == PowerSettingsOpenResult.Failed) "Не удалось открыть автозапуск" else "Проверьте автозапуск вручную", Toast.LENGTH_LONG).show()
        refreshState()
    }

    private fun onOpenBackgroundDataSettings() {
        val result = PowerSettingsNavigator(this).openBackgroundDataSettings()
        if (result == PowerSettingsOpenResult.Failed) Toast.makeText(this, "Не удалось открыть фоновые данные", Toast.LENGTH_LONG).show()
        refreshState()
    }

    private fun onAddQuickSettingsTile() = requestQuickSettingsTile()

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
        val labels = options.map { appearanceDialogText(it) }.toTypedArray()
        val current = ProxyRuntimeConfig.appConfig(applicationContext).appearance
        showThemeAwareSingleChoiceDialog(
            title = SettingsUiText.THEME_TITLE,
            labels = labels,
            checkedIndex = options.indexOf(current).coerceAtLeast(0),
        ) { index ->
            setAppearance(options[index])
        }
    }

    private fun setAppearance(appearance: Appearance) {
        val store = AppConfigStore.from(applicationContext)
        store.saveConfig(store.loadConfig().copy(appearance = appearance))
        ProxyRuntimeConfig.initialize(applicationContext)
        applyAppearanceToWindow(appearance)
        updateThemeSelectorState(appearance)
        rebuildRootForAppearanceChange(currentSettingsScrollY())
    }

    private fun appearanceDialogText(appearance: Appearance): String {
        val model = AppearanceUiModels.uiModel(appearance)
        return "${model.label}\n${model.description}"
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
                    if (QuickSettingsTileAddResultMapper.shouldPersistAdded(result)) {
                        prefs.edit().putBoolean(PREF_RECOMMENDATION_QS_DONE, true).apply()
                    }
                    if (!isFinishing && !isDestroyed) refreshState()
                }
            } catch (_: Throwable) {
                showQuickSettingsTileHelp()
            }
        } else {
            showQuickSettingsTileHelp()
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


    private fun currentPowerSetupStatus(appAutostart: Boolean): PowerSetupStatus = PowerSetupStatus(
        batteryOptimization = when (detectBatteryOptimizationStatus()) {
            "unrestricted" -> BatteryOptimizationStatus.Granted
            "optimized" -> BatteryOptimizationStatus.NeedsAction
            else -> BatteryOptimizationStatus.UnsupportedOrUnknown
        },
        backgroundRestriction = detectBackgroundRestrictionStatus(),
        appBootPreference = if (appAutostart) AppBootPreferenceStatus.EnabledInApp else AppBootPreferenceStatus.DisabledInApp,
        oemAutostart = when (prefs.getString(PREF_OEM_AUTOSTART_STATUS, "unknown")) {
            "opened" -> OemAutostartStatus.OpenedSettingsButNotVerified
            "not_supported" -> OemAutostartStatus.NotSupportedOrUnavailable
            else -> OemAutostartStatus.Unknown
        },
        quickSettingsTile = PowerSetupUiMapper.quickSettingsStatusForSdk(
            Build.VERSION.SDK_INT,
            prefs.getBoolean(PREF_RECOMMENDATION_QS_DONE, false),
        ),
    )

    private fun detectBackgroundRestrictionStatus(): BackgroundRestrictionStatus = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (getSystemService(ActivityManager::class.java)?.isBackgroundRestricted == true) BackgroundRestrictionStatus.Restricted else BackgroundRestrictionStatus.Allowed
        } else {
            BackgroundRestrictionStatus.UnsupportedOrUnknown
        }
    } catch (_: Throwable) {
        BackgroundRestrictionStatus.UnsupportedOrUnknown
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
            setColor(currentColorScheme().surfaceContainer)
            cornerRadius = 16 * resources.displayMetrics.density
            setStroke((1 * resources.displayMetrics.density).toInt().coerceAtLeast(1), currentColorScheme().outline)
        }
        val innerPadding = (16 * resources.displayMetrics.density).toInt()
        setPadding(innerPadding, innerPadding, innerPadding, innerPadding)
        if (title.isNotBlank()) {
            addView(createSectionTitle(title), matchWrapParams())
            if (subtitle != null) {
                addView(createValueText().apply {
                    text = subtitle
                    setTextColor(currentColorScheme().onSurfaceVariant)
                }, matchWrapParams(topMargin = (4 * resources.displayMetrics.density).toInt(), bottomMargin = (8 * resources.displayMetrics.density).toInt()))
            } else {
                addView(View(this@MainActivity), LinearLayout.LayoutParams(1, (8 * resources.displayMetrics.density).toInt()))
            }
        }
        body()
    }


    private fun CollapsibleSettingsSection(title: String, content: TextView): LinearLayout = SettingsSection(title) {
        val details = content.apply {
            visibility = View.GONE
            setTextColor(currentColorScheme().onSurfaceVariant)
        }
        val toggle = createOutlinedButton("Показать") { buttonView ->
            val expanded = details.visibility != View.VISIBLE
            details.visibility = if (expanded) View.VISIBLE else View.GONE
            (buttonView as? Button)?.text = if (expanded) "Скрыть" else "Показать"
        }
        addView(toggle, matchWrapParams())
        addView(details, matchWrapParams(topMargin = (8 * resources.displayMetrics.density).toInt()))
    }
    private fun createSectionTitle(title: String): TextView = TextView(this).apply {
        text = title
        textSize = 17f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(currentColorScheme().onSurface)
    }

    private fun createButton(label: String, onClick: (View) -> Unit = {}): Button = createFilledButton(label, onClick)

    private fun createFilledButton(label: String, onClick: (View) -> Unit = {}): Button = MaterialButton(this).apply {
        text = label
        isAllCaps = false
        backgroundTintList = ColorStateList.valueOf(currentColorScheme().primary)
        setTextColor(currentColorScheme().onPrimary)
        setOnClickListener(onClick)
    }

    private fun createOutlinedButton(label: String, onClick: (View) -> Unit = {}): Button = MaterialButton(this).apply {
        text = label
        isAllCaps = false
        backgroundTintList = ColorStateList.valueOf(currentColorScheme().surface)
        strokeColor = ColorStateList.valueOf(currentColorScheme().primary)
        strokeWidth = (1 * resources.displayMetrics.density).toInt().coerceAtLeast(1)
        setTextColor(currentColorScheme().primary)
        setOnClickListener(onClick)
    }

    private fun createNavigationBar(): BottomNavigationView = BottomNavigationView(this).apply {
        val colors = bottomNavColorModel(currentColorScheme())
        val selectedState = intArrayOf(android.R.attr.state_checked)
        val navItemColors = ColorStateList(arrayOf(selectedState, intArrayOf()), intArrayOf(colors.selected, colors.unselected))
        setBackgroundColor(colors.background)
        itemTextColor = navItemColors
        itemIconTintList = navItemColors
        itemActiveIndicatorColor = ColorStateList.valueOf(colors.activeIndicator)
        labelVisibilityMode = com.google.android.material.navigation.NavigationBarView.LABEL_VISIBILITY_LABELED
        menu.add(0, Screen.HOME.itemId, 0, "Главная").setIcon(R.drawable.ic_nav_home)
        menu.add(0, Screen.SETTINGS.itemId, 1, "Настройки").setIcon(R.drawable.ic_nav_settings)
        menu.add(0, Screen.DIAGNOSTICS.itemId, 2, "Диагностика").setIcon(R.drawable.ic_nav_diagnostics)
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

    private fun SettingsValueRow(title: String, description: String, value: TextView, meta: View? = null, onClick: (() -> Unit)? = null): LinearLayout =
        SettingsAdaptiveRow(title, description, topTrailing = value, meta = meta, onClick = onClick)

    private fun SettingsStatusRow(title: String, description: String, value: TextView, badge: TextView? = null, onClick: () -> Unit): LinearLayout =
        SettingsAdaptiveRow(title, description, topTrailing = null, meta = metaLine(badge, value), onClick = onClick)

    private fun SettingsActionRow(title: String, description: String, onClick: () -> Unit): LinearLayout =
        SettingsAdaptiveRow(title, description, topTrailing = createValueText().apply { text = "›"; textSize = 22f }, meta = null, onClick = onClick)

    private fun SettingsSwitchRow(title: String, description: String, status: TextView, badge: TextView? = null, onClick: () -> Unit): LinearLayout {
        val switch = MaterialSwitch(this).apply {
            isClickable = false
            isFocusable = false
            applySwitchStyle(this)
        }
        telemetrySwitch = switch
        status.visibility = View.GONE
        return SettingsAdaptiveRow(title, description, topTrailing = switch, meta = metaLine(badge, status), onClick = onClick)
    }

    private fun SettingsAdaptiveRow(
        title: String,
        description: String,
        topTrailing: View? = null,
        meta: View? = null,
        onClick: (() -> Unit)? = null,
    ): LinearLayout {
        val density = resources.displayMetrics.density
        val vertical = (12 * density).toInt()
        val horizontal = (4 * density).toInt()
        val gap = (6 * density).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(horizontal, vertical, horizontal, vertical)
            if (onClick != null) {
                isClickable = true
                isFocusable = true
                foreground = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).use { attrs -> attrs.getDrawable(0) }
                setOnClickListener { onClick() }
            }

            val titleView = TextView(this@MainActivity).apply {
                text = title
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(currentColorScheme().onSurface)
                maxLines = 2
            }
            val titleParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            if (topTrailing == null) {
                addView(titleView, matchWrapParams())
            } else {
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    addView(titleView, titleParams)
                    addView(topTrailing.apply {
                        if (this is TextView) {
                            textSize = 14f
                            setTextColor(currentColorScheme().onSurface)
                            typeface = Typeface.DEFAULT_BOLD
                            maxLines = 2
                        }
                    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        leftMargin = (12 * density).toInt()
                    })
                }, matchWrapParams())
            }

            addView(createValueText().apply {
                text = description
                setTextColor(currentColorScheme().onSurfaceVariant)
            }, matchWrapParams(topMargin = (3 * density).toInt()))

            if (meta != null) addView(meta, matchWrapParams(topMargin = gap))
        }
    }

    private fun metaLine(vararg views: View?): LinearLayout? {
        val visibleViews = views.filterNotNull()
        if (visibleViews.isEmpty()) return null
        val density = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            visibleViews.forEachIndexed { index, view ->
                addView(view.apply {
                    if (this is TextView) {
                        textSize = 13f
                        if (typeface == null) typeface = Typeface.DEFAULT_BOLD
                    }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    if (index > 0) leftMargin = (8 * density).toInt()
                })
            }
        }
    }


    private fun ThemeSelector(): LinearLayout {
        val appearance = ProxyRuntimeConfig.appConfig(applicationContext).appearance
        val density = resources.displayMetrics.density
        val vertical = (12 * density).toInt()
        val horizontal = (4 * density).toInt()
        val gap = (8 * density).toInt()
        val group = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        themeToggleGroup = group
        AppearanceUiModels.options.forEachIndexed { index, option ->
            val button = MaterialButton(this).apply {
                id = View.generateViewId()
                text = option.label
                isAllCaps = false
                minWidth = 0
                minimumWidth = 0
                insetTop = 0
                insetBottom = 0
                setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
                tag = option.appearance
                applySegmentedButtonStyle(this, option.appearance == appearance)
            }
            themeButtons[option.appearance] = button
            group.addView(button, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (index > 0) leftMargin = (4 * density).toInt()
            })
        }
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val selected = themeButtons.entries.firstOrNull { it.value.id == checkedId }?.key ?: return@addOnButtonCheckedListener
            if (selected != ProxyRuntimeConfig.appConfig(applicationContext).appearance) setAppearance(selected)
        }
        updateThemeSelectorState(appearance)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(horizontal, vertical, horizontal, vertical)
            addView(TextView(this@MainActivity).apply {
                text = SettingsUiText.THEME_TITLE
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(currentColorScheme().onSurface)
            }, matchWrapParams())
            addView(group, matchWrapParams(topMargin = gap))
            addView(themeHelperText.apply { setTextColor(currentColorScheme().onSurfaceVariant) }, matchWrapParams(topMargin = (4 * density).toInt()))
        }
    }

    private fun updateThemeSelectorState(appearance: Appearance) {
        themeStatusText.text = AppearanceUiModels.label(appearance)
        themeHelperText.text = AppearanceUiModels.compactHelper(appearance)
        themeToggleGroup?.let { group ->
            val buttonId = themeButtons[appearance]?.id ?: return@let
            if (group.checkedButtonId != buttonId) group.check(buttonId)
        }
        themeButtons.forEach { (buttonAppearance, button) ->
            val selected = buttonAppearance == appearance
            applySegmentedButtonStyle(button, selected)
        }
    }

    private fun ConnectionModeSelector(): LinearLayout {
        val config = ProxyRuntimeConfig.appConfig(applicationContext)
        val routeModeUi = UserRouteModes.uiModel(config.routeMode)
        routeModeDescriptionText = createValueText().apply {
            text = routeModeUi.description
            setTextColor(currentColorScheme().onSurfaceVariant)
        }
        val row = SettingsValueRow(
            title = SettingsUiText.CONNECTION_MODE_TITLE,
            description = routeModeUi.description,
            value = routeModeValueText.apply { text = routeModeUi.label },
            onClick = { showConnectionModeDialog() },
        )
        routeModeDescriptionText = (row.getChildAt(1) as? TextView) ?: routeModeDescriptionText
        return row
    }

    private fun updateConnectionModeRow(routeMode: NetworkRouteMode) {
        val routeModeUi = UserRouteModes.uiModel(routeMode)
        routeModeValueText.text = routeModeUi.label
        if (::routeModeDescriptionText.isInitialized) routeModeDescriptionText.text = routeModeUi.description
    }

    private fun StatusChip(text: String): TextView = Badge(text, currentColorScheme().primaryContainer, currentColorScheme().onPrimaryContainer)

    private fun Badge(text: String, backgroundColor: Int = currentColorScheme().surfaceVariant, textColor: Int = currentColorScheme().onSurfaceVariant): TextView = TextView(this).apply {
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
            setTextColor(currentColorScheme().onSurfaceVariant)
            typeface = Typeface.DEFAULT_BOLD
        }, matchWrapParams())
        addView(value, matchWrapParams(topMargin = 2))
    }

    private fun createValueText(textSize: Float = 14f, bold: Boolean = false): TextView = TextView(this).apply {
        this.textSize = textSize
        setTextColor(currentColorScheme().onSurface)
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



    private fun applySwitchStyle(switchView: MaterialSwitch) {
        val colors = ControlTintModels.switchColors(currentColorScheme())
        switchView.minWidth = dp(52)
        switchView.minimumWidth = dp(52)
        switchView.minHeight = dp(32)
        switchView.minimumHeight = dp(32)
        switchView.thumbTintList = switchStateColorList(
            checkedEnabled = colors.checkedThumb,
            uncheckedEnabled = colors.uncheckedThumb,
            checkedDisabled = colors.disabledCheckedThumb,
            uncheckedDisabled = colors.disabledUncheckedThumb,
        )
        switchView.trackTintList = switchStateColorList(
            checkedEnabled = colors.checkedTrack,
            uncheckedEnabled = colors.uncheckedTrack,
            checkedDisabled = colors.disabledCheckedTrack,
            uncheckedDisabled = colors.disabledUncheckedTrack,
        )
        switchView.trackDecorationTintList = switchStateColorList(
            checkedEnabled = colors.checkedTrack,
            uncheckedEnabled = currentColorScheme().outline,
            checkedDisabled = colors.disabledCheckedTrack,
            uncheckedDisabled = currentColorScheme().outline.withAlpha(0.38f),
        )
    }

    private fun applyCheckBoxStyle(checkBox: CheckBox) {
        applyCompoundButtonTint(checkBox)
    }

    @Suppress("unused")
    private fun applyRadioButtonStyle(radioButton: android.widget.RadioButton) {
        applyCompoundButtonTint(radioButton)
    }

    private fun applyCompoundButtonTint(button: CompoundButton) {
        val colors = ControlTintModels.compoundButtonColors(currentColorScheme())
        CompoundButtonCompat.setButtonTintList(button, statefulControlColorList(colors.checked, colors.unchecked, colors.disabled))
    }

    private fun applySegmentedButtonStyle(button: MaterialButton, selected: Boolean) {
        val colors = ControlTintModels.segmentedButtonColors(currentColorScheme(), selected)
        button.backgroundTintList = ColorStateList.valueOf(colors.background)
        button.setTextColor(colors.text)
        button.strokeColor = ColorStateList.valueOf(colors.stroke)
        button.strokeWidth = (1 * resources.displayMetrics.density).toInt().coerceAtLeast(1)
    }

    private fun statefulControlColorList(checked: Int, unchecked: Int, disabled: Int): ColorStateList = ColorStateList(
        arrayOf(
            intArrayOf(android.R.attr.state_enabled, android.R.attr.state_checked),
            intArrayOf(android.R.attr.state_enabled, -android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_enabled),
        ),
        intArrayOf(checked, unchecked, disabled),
    )

    private fun switchStateColorList(
        checkedEnabled: Int,
        uncheckedEnabled: Int,
        checkedDisabled: Int,
        uncheckedDisabled: Int,
    ): ColorStateList = ColorStateList(
        arrayOf(
            intArrayOf(android.R.attr.state_checked, android.R.attr.state_enabled),
            intArrayOf(-android.R.attr.state_checked, android.R.attr.state_enabled),
            intArrayOf(android.R.attr.state_checked, -android.R.attr.state_enabled),
            intArrayOf(-android.R.attr.state_checked, -android.R.attr.state_enabled),
        ),
        intArrayOf(checkedEnabled, uncheckedEnabled, checkedDisabled, uncheckedDisabled),
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun currentColorScheme(): AppColorScheme = if (isDarkTheme()) darkAppColorScheme() else lightAppColorScheme()

    private enum class Screen(val itemId: Int) {
        HOME(1),
        SETTINGS(2),
        DIAGNOSTICS(3);

        companion object {
            fun fromItemId(itemId: Int): Screen? = entries.firstOrNull { it.itemId == itemId }
        }
    }
    private enum class TransitionStatus { NONE, STARTING, STOPPING, RESTARTING }

    private data class RecommendationCardModel(
        val id: String,
        val title: String,
        val subtitle: String,
        val actionLabel: String,
        val onClick: (View) -> Unit,
    )

    companion object {
        private const val REFRESH_MS = 1_000L
        private const val RESTART_UI_MIN_DURATION_MS = 1_000L
        private const val RESTART_UI_TIMEOUT_MS = 10_000L
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
        private const val PREF_OEM_AUTOSTART_STATUS = "oem_autostart_status"
        private const val RECOMMENDATION_TELEMETRY = "telemetry"
        private const val RECOMMENDATION_COOLDOWN_MS = 7L * 24L * 60L * 60L * 1000L
        private const val MAX_HINTS = 2
        private const val MAX_VISIBLE_LOG_LINES = 12
    }
}
