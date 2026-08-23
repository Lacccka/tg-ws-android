package com.flowseal.tgwsandroid

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import com.flowseal.tgwsandroid.service.ProxyRuntimeConfig

/** UI for the private real-Telegram Snowflake/Tor ProxyServer integration test. */
class SnowflakeTorProxyActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var networkText: TextView
    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var telegramButton: Button

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshStatus()
            mainHandler.postDelayed(this, STATUS_REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "SE Snowflake/Tor real proxy"
        ProxyRuntimeConfig.initialize(applicationContext)

        val density = resources.displayMetrics.density
        val padding = (16 * density).toInt()
        val gap = (10 * density).toInt()

        networkText = TextView(this).apply {
            text = "Сеть: ${currentNetworkLabel()}"
        }
        statusText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        startButton = Button(this).apply {
            text = "Запустить Snowflake/Tor прокси"
            isAllCaps = false
            setOnClickListener { startTestService() }
        }
        stopButton = Button(this).apply {
            text = "Остановить тест"
            isAllCaps = false
            setOnClickListener { stopTestService() }
        }
        telegramButton = Button(this).apply {
            text = "Подключить / открыть Telegram"
            isAllCaps = false
            setOnClickListener { openTelegramProxy() }
        }
        val copyButton = Button(this).apply {
            text = "Копировать результат"
            isAllCaps = false
            setOnClickListener { copyResult() }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            addView(TextView(this@SnowflakeTorProxyActivity).apply {
                text = "Реальный Telegram через Snowflake/Tor"
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
            }, matchWrap())
            addView(TextView(this@SnowflakeTorProxyActivity).apply {
                text = "Этот private-only тест запускает настоящий локальный ProxyServer, но его WebSocket-соединения идут через Tor SOCKS и Snowflake. Обычный ProxyForegroundService будет остановлен, чтобы освободить тот же локальный порт."
            }, matchWrap(gap))
            addView(TextView(this@SnowflakeTorProxyActivity).apply {
                text = "После READY откройте Telegram этой кнопкой, дождитесь подключения прокси, загрузите чаты/медиа и отправьте сообщение. Затем вернитесь сюда и скопируйте результат. Тестовый foreground service продолжает работать, пока открыт Telegram."
            }, matchWrap(gap))
            addView(networkText, matchWrap(gap))
            addView(startButton, matchWrap(gap))
            addView(telegramButton, matchWrap(gap))
            addView(stopButton, matchWrap(gap))
            addView(copyButton, matchWrap(gap))
            addView(statusText, matchWrap(gap))
        }

        setContentView(ScrollView(this).apply {
            addView(content, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        networkText.text = "Сеть: ${currentNetworkLabel()}"
        mainHandler.removeCallbacks(refreshRunnable)
        mainHandler.post(refreshRunnable)
    }

    override fun onPause() {
        mainHandler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    private fun startTestService() {
        if (!BuildConfig.SNOWFLAKE_TOR_PACKAGED) {
            toast("Snowflake/Tor не упакован в эту сборку")
            return
        }
        val intent = SnowflakeTorProxyService.startIntent(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        toast("Запуск Snowflake/Tor")
        refreshStatus()
    }

    private fun stopTestService() {
        runCatching { startService(SnowflakeTorProxyService.stopIntent(this)) }
            .onFailure { stopService(Intent(this, SnowflakeTorProxyService::class.java)) }
        refreshStatus()
    }

    private fun openTelegramProxy() {
        val snapshot = SnowflakeTorProxyTestStatus.snapshot()
        if (!snapshot.ready) {
            toast("Сначала дождитесь READY")
            return
        }
        val uri = Uri.parse(ProxyRuntimeConfig.telegramProxyUri(this))
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (error: Throwable) {
            toast("Не удалось открыть Telegram: ${error.javaClass.simpleName}")
        }
    }

    private fun refreshStatus() {
        val snapshot = SnowflakeTorProxyTestStatus.snapshot()
        startButton.isEnabled = !snapshot.running
        stopButton.isEnabled = snapshot.running || snapshot.ready
        telegramButton.isEnabled = snapshot.ready

        statusText.text = buildString {
            appendLine("SE REAL TELEGRAM SNOWFLAKE/TOR TEST")
            appendLine("Network: ${currentNetworkLabel()}")
            appendLine("Phase: ${snapshot.phase}")
            appendLine("Detail: ${snapshot.detail}")
            appendLine("Tor bootstrap: ${snapshot.torBootstrapProgress}%")
            appendLine("Local endpoint: ${snapshot.endpoint ?: ProxyRuntimeConfig.endpointSummary(this@SnowflakeTorProxyActivity)}")
            appendLine("Tor SOCKS: ${snapshot.torSocksPort?.let { "127.0.0.1:$it" } ?: "not ready"}")
            appendLine("Route: forced direct_first through SocksRawWebSocketConnector")
            appendLine("CF fallback: off")
            appendLine("WebSocket pools: off")
            appendLine("VpnService: not used")
            appendLine()
            appendLine("ProxyServer stats:")
            appendLine(snapshot.statsLine)
            appendLine()
            appendLine("Runtime log:")
            append(snapshot.logText)
        }
    }

    private fun copyResult() {
        refreshStatus()
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Snowflake Tor real Telegram proxy test", statusText.text))
        toast("Результат скопирован")
    }

    private fun currentNetworkLabel(): String = try {
        val manager = getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
        when {
            capabilities == null -> "Нет сети"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Мобильная сеть"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Другая сеть"
        }
    } catch (_: Throwable) {
        "Неизвестно"
    }

    private fun matchWrap(topMargin: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { this.topMargin = topMargin }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val STATUS_REFRESH_MS = 500L
    }
}
