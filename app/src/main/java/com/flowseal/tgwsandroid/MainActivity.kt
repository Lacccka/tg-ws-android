package com.flowseal.tgwsandroid

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.flowseal.tgwsandroid.service.ProxyForegroundService
import com.flowseal.tgwsandroid.service.ProxyRuntimeConfig

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusText: TextView
    private lateinit var logsText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var connectTelegramButton: Button
    private lateinit var copyProxyLinkButton: Button

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
        copyProxyLinkButton.setOnClickListener {
            copyProxyLink()
            Toast.makeText(this, "Proxy link copied", Toast.LENGTH_SHORT).show()
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
                appendLine("Hint: tap Start proxy before connecting Telegram.")
            }
            setPadding(0, 0, 0, smallPadding)
        }

        startButton = Button(this).apply { text = "Start proxy" }
        stopButton = Button(this).apply { text = "Stop proxy" }
        connectTelegramButton = Button(this).apply { text = "Connect in Telegram" }
        copyProxyLinkButton = Button(this).apply { text = "Copy proxy link" }

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
            addView(startButton, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(stopButton, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(connectTelegramButton, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(copyProxyLinkButton, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(TextView(this@MainActivity).apply {
                text = "Recent logs"
                textSize = 16f
                setPadding(0, smallPadding, 0, smallPadding)
            })
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

    private fun refreshState() {
        val running = ProxyForegroundService.State.running
        statusText.text = "Status: ${ProxyForegroundService.State.lastStatus}"
        startButton.isEnabled = !running
        stopButton.isEnabled = running
        connectTelegramButton.isEnabled = true
        copyProxyLinkButton.isEnabled = true
        logsText.text = ProxyForegroundService.State.recentLogs().takeIf { it.isNotEmpty() }?.joinToString("\n") ?: "No logs yet"
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
