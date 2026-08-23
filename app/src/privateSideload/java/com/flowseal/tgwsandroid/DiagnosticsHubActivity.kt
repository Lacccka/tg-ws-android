package com.flowseal.tgwsandroid

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Single private-sideload entry point for experimental network diagnostics.
 *
 * Individual probes intentionally remain separate activities so their code and
 * old reproduction paths stay available, but they are no longer exposed as
 * launcher icons. This keeps the device launcher clean while preserving useful
 * regression tools.
 */
class DiagnosticsHubActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "SE Diagnostics"

        val density = resources.displayMetrics.density
        val padding = (16 * density).toInt()
        val gap = (10 * density).toInt()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)

            addView(TextView(this@DiagnosticsHubActivity).apply {
                text = "TG WS — диагностика"
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
            }, matchWrap())

            addView(TextView(this@DiagnosticsHubActivity).apply {
                text = "Один вход для private-sideload сетевых экспериментов. Активный transport prototype не требует собственного сервера или VPN."
            }, matchWrap(gap))

            addSection("Актуальные", gap)
            addProbeButton(
                label = "Snowflake/Tor — реальный Telegram",
                description = "Следующий интеграционный тест: настоящий локальный ProxyServer → Tor SOCKS → Snowflake. Foreground service остаётся активным, пока вы открываете Telegram и проверяете реальные чаты/медиа.",
                target = SnowflakeTorProxyActivity::class.java,
                gap = gap,
            )
            addProbeButton(
                label = "Snowflake/Tor tunnel E2E",
                description = "Уже подтверждённый zero-config probe: публичный Snowflake → embedded Tor SOCKS → SocksRawWebSocketConnector → Telegram WebSocket HTTP 101. Без VPS, VLESS, пользовательского bridge и Android VPN.",
                target = SnowflakeTorE2eActivity::class.java,
                gap = gap,
            )

            addSection("Архивные / точечные", gap)
            addProbeButton(
                label = "Chromium/Cronet transport control",
                description = "Закрытый Cloudflare control: обычный TCP/TLS и QUIC/HTTP3 через native Chromium/Cronet. На исследованной мобильной сети оба пути не дали HTTP headers.",
                target = CronetTlsControlActivity::class.java,
                gap = gap,
            )
            addProbeButton(
                label = "CF proxy matrix",
                description = "Проверяет CF-proxy IPv4/IPv6, TLS stage и same-edge SNI controls.",
                target = CfProxyMatrixActivity::class.java,
                gap = gap,
            )
            addProbeButton(
                label = "Direct fronting E2E",
                description = "Проверяет direct Telegram WebSocket и альтернативный TLS SNI. На сети с заблокированным Telegram TCP ожидаемо остановится до TLS.",
                target = DirectFrontingE2eActivity::class.java,
                gap = gap,
            )
            addProbeButton(
                label = "Worker TLS matrix",
                description = "Сравнивает normal SNI, fragmented SNI и no-SNI на одном Cloudflare edge.",
                target = WorkerTlsMatrixActivity::class.java,
                gap = gap,
            )
            addProbeButton(
                label = "Worker basic E2E",
                description = "Базовая Worker/DNS/TCP/WebSocket диагностика.",
                target = WorkerDiagnosticsActivity::class.java,
                gap = gap,
            )
            addProbeButton(
                label = "Cloudflare edge",
                description = "Проверка выбранных Cloudflare edge IP.",
                target = WorkerPreferredEdgeActivity::class.java,
                gap = gap,
            )
            addProbeButton(
                label = "TLS fragmentation layout",
                description = "Низкоуровневая проверка нарезки ClientHello.",
                target = WorkerTlsFragmentActivity::class.java,
                gap = gap,
            )
            addProbeButton(
                label = "Fragmented Worker E2E",
                description = "Полный Worker E2E через fragmented ClientHello.",
                target = WorkerFragmentedE2eActivity::class.java,
                gap = gap,
            )
            addProbeButton(
                label = "Worker no-SNI E2E",
                description = "Эксперимент TLS без SNI; диагностический, не production transport.",
                target = WorkerNoSniE2eActivity::class.java,
                gap = gap,
            )
        }

        setContentView(ScrollView(this).apply {
            addView(content, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
    }

    private fun LinearLayout.addSection(label: String, topMargin: Int) {
        addView(TextView(this@DiagnosticsHubActivity).apply {
            text = label
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
        }, matchWrap(topMargin))
    }

    private fun LinearLayout.addProbeButton(
        label: String,
        description: String,
        target: Class<out Activity>,
        gap: Int,
    ) {
        addView(Button(this@DiagnosticsHubActivity).apply {
            text = label
            isAllCaps = false
            setOnClickListener {
                startActivity(Intent(this@DiagnosticsHubActivity, target))
            }
        }, matchWrap(gap))
        addView(TextView(this@DiagnosticsHubActivity).apply {
            text = description
        }, matchWrap())
    }

    private fun matchWrap(topMargin: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { this.topMargin = topMargin }
}
