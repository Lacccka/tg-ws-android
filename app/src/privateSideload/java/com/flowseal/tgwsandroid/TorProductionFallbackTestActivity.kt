package com.flowseal.tgwsandroid

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.flowseal.tgwsandroid.proxy.NetworkRouteMode
import com.flowseal.tgwsandroid.service.ProxyForegroundService
import com.flowseal.tgwsandroid.service.ProxyRuntimeConfig

/**
 * Private-sideload controller for the decisive production fallback test.
 *
 * It does not run a second ProxyServer implementation. Instead it restarts the
 * ordinary ProxyForegroundService with a private-only outbound connector that
 * fails direct/CF connects on mobile. The unchanged production routing logic
 * must then reach the independently managed Tor/Snowflake connector.
 */
class TorProductionFallbackTestActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusView: TextView
    private val refresh = object : Runnable {
        override fun run() {
            renderStatus()
            handler.postDelayed(this, 750L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Production AUTO → Tor"

        val density = resources.displayMetrics.density
        val padding = (16 * density).toInt()
        val gap = (10 * density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)

            addView(TextView(this@TorProductionFallbackTestActivity).apply {
                text = "Production AUTO → Tor fallback"
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
            }, matchWrap())

            addView(TextView(this@TorProductionFallbackTestActivity).apply {
                text = "Только privateSideload. На мобильной сети обычный direct/CF connector будет намеренно возвращать ConnectException. Сам ProxyServer, AUTO routing, локальный endpoint и Tor runtime остаются production-кодом. После запуска дождитесь Tor ready=true / bootstrap=100, затем подключите Telegram."
            }, matchWrap(gap))

            statusView = TextView(this@TorProductionFallbackTestActivity).apply {
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
            }
            addView(statusView, matchWrap(gap))

            addView(Button(this@TorProductionFallbackTestActivity).apply {
                text = "Запустить production fallback test"
                isAllCaps = false
                setOnClickListener { startForcedFallbackTest() }
            }, matchWrap(gap))

            addView(Button(this@TorProductionFallbackTestActivity).apply {
                text = "Подключить Telegram"
                isAllCaps = false
                setOnClickListener { openTelegramProxyLink() }
            }, matchWrap(gap))

            addView(Button(this@TorProductionFallbackTestActivity).apply {
                text = "Вернуть обычную маршрутизацию"
                isAllCaps = false
                setOnClickListener { restoreOrdinaryRouting() }
            }, matchWrap(gap))

            addView(TextView(this@TorProductionFallbackTestActivity).apply {
                text = "Ожидаемый успешный результат: testOverride=true, Tor ready=true, torSnowflakeSuccesses > 0 и lastRoute=tor-snowflake. При переходе на Wi‑Fi forced failure автоматически не применяется, но тестовый флаг останется виден до обычного restart/restore."
            }, matchWrap(gap))
        }

        setContentView(ScrollView(this).apply {
            addView(content, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun startForcedFallbackTest() {
        ProxyRuntimeConfig.initialize(applicationContext)
        val config = ProxyRuntimeConfig.appConfig(applicationContext)
        if (config.routeMode != NetworkRouteMode.AUTO) {
            Toast.makeText(this, "Для этого теста выберите режим Авто (AUTO)", Toast.LENGTH_LONG).show()
            return
        }
        if (!ProxyForegroundService.State.networkStatus.equals("mobile", ignoreCase = true)) {
            Toast.makeText(this, "Выключите Wi‑Fi и оставьте мобильную сеть", Toast.LENGTH_LONG).show()
            return
        }
        startProxyService(ProxyForegroundService.torFallbackProductionTestRestartIntent(this, enabled = true))
        Toast.makeText(this, "Тест запущен. Дождитесь Tor ready=true", Toast.LENGTH_LONG).show()
    }

    private fun restoreOrdinaryRouting() {
        startProxyService(ProxyForegroundService.torFallbackProductionTestRestartIntent(this, enabled = false))
        Toast.makeText(this, "Обычная маршрутизация восстанавливается", Toast.LENGTH_LONG).show()
    }

    private fun startProxyService(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun openTelegramProxyLink() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(ProxyRuntimeConfig.telegramProxyUri(this)))
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Не удалось открыть Telegram", Toast.LENGTH_LONG).show()
        }
    }

    private fun renderStatus() {
        val runtime = ProxyForegroundService.State.currentTorFallbackRuntimeSnapshot()
        val stats = ProxyForegroundService.State.stats()
        statusView.text = buildString {
            appendLine("proxyRunning=${ProxyForegroundService.State.running}")
            appendLine("network=${ProxyForegroundService.State.networkStatus}")
            appendLine("testOverride=${ProxyForegroundService.State.isTorFallbackTestOverrideActive()}")
            appendLine("torAvailable=${runtime != null}")
            appendLine("torDesired=${runtime?.desired ?: false}")
            appendLine("torRunning=${runtime?.running ?: false}")
            appendLine("torReady=${runtime?.ready ?: false}")
            appendLine("torBootstrap=${runtime?.bootstrapProgress ?: 0}")
            appendLine("torPhase=${runtime?.phase ?: "unknown"}")
            appendLine("torLastError=${runtime?.lastError ?: "none"}")
            appendLine("torRoutes=${stats?.torSnowflakeSuccesses ?: 0}/${stats?.torSnowflakeAttempts ?: 0}/${stats?.torSnowflakeFailures ?: 0}")
            appendLine("torUnavailable=${stats?.torSnowflakeUnavailable ?: 0}")
            appendLine("lastRoute=${stats?.lastRouteUsed ?: "none"}")
            appendLine("cf=${stats?.cfProxyConnections ?: 0}/${stats?.cfProxyErrors ?: 0}")
        }.trimEnd()
    }

    private fun matchWrap(topMargin: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { this.topMargin = topMargin }
}
