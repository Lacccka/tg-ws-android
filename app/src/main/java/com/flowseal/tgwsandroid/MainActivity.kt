package com.flowseal.tgwsandroid

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
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
import com.flowseal.tgwsandroid.service.ProxyForegroundService
import com.flowseal.tgwsandroid.service.ProxyRuntimeConfig

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusText: TextView
    private lateinit var logsText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshState()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContentView())
        startButton.setOnClickListener {
            requestNotificationPermissionIfNeeded()
            startProxyService()
        }
        stopButton.setOnClickListener {
            startService(ProxyForegroundService.stopIntent(this))
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
                appendLine("Config")
                appendLine("Endpoint: ${ProxyRuntimeConfig.endpointSummary()}")
                appendLine("Secret: ${ProxyRuntimeConfig.partialSecret()}")
                appendLine("DCs: ${ProxyRuntimeConfig.dcIp.joinToString()}")
            }
            setPadding(0, 0, 0, smallPadding)
        }

        startButton = Button(this).apply { text = "Start proxy" }
        stopButton = Button(this).apply { text = "Stop proxy" }

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
            setPadding(padding, padding, padding, padding)
            addView(statusText, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(configText, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(startButton, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(stopButton, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(TextView(this@MainActivity).apply {
                text = "Recent logs"
                textSize = 16f
                setPadding(0, smallPadding, 0, smallPadding)
            })
            addView(logsScroll)
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

    private fun refreshState() {
        val running = ProxyForegroundService.State.running
        statusText.text = "Status: ${ProxyForegroundService.State.lastStatus}"
        startButton.isEnabled = !running
        stopButton.isEnabled = running
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
