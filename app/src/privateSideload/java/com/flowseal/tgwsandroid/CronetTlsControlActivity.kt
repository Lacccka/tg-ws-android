package com.flowseal.tgwsandroid

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.flowseal.tgwsandroid.proxy.TracedTlsDiagnosticTimeoutException
import com.flowseal.tgwsandroid.proxy.TracedTrustAllTlsTransportFactory
import org.chromium.net.CronetEngine
import org.chromium.net.CronetException
import org.chromium.net.CronetProvider
import org.chromium.net.NetworkException
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.system.measureTimeMillis

/**
 * Private-sideload transport control comparing:
 *  1. Android SSLSocket over conventional TCP/TLS,
 *  2. native Chromium/Cronet over conventional TCP/TLS/HTTP/1.1,
 *  3. native Chromium/Cronet with QUIC enabled and explicit QUIC hints.
 *
 * Production proxy routing is not modified by this activity.
 */
class CronetTlsControlActivity : Activity() {
    private lateinit var networkText: TextView
    private lateinit var resultText: TextView
    private lateinit var runButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "SE Cronet Transport Control"

        networkText = TextView(this).apply { text = "Сеть: ${currentNetworkLabel()}" }
        resultText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            text = "Сравнивает Android SSLSocket, Chromium TCP/TLS и Chromium QUIC/HTTP3.\n\n" +
                "Production routing не меняется."
        }
        runButton = Button(this).apply {
            text = "Проверить TLS и QUIC"
            isAllCaps = false
            setOnClickListener { runProbe() }
        }
        val copyButton = Button(this).apply {
            text = "Копировать результат"
            isAllCaps = false
            setOnClickListener { copyResult() }
        }

        val density = resources.displayMetrics.density
        val padding = (16 * density).toInt()
        val gap = (10 * density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            addView(TextView(this@CronetTlsControlActivity).apply {
                text = "Chromium/Cronet transport control"
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
            }, matchWrap())
            addView(TextView(this@CronetTlsControlActivity).apply {
                text = "Сначала повторяет conventional TCP/TLS control, затем отдельно ищет настоящий HTTP/3 (h3) через QUIC/UDP:443."
            }, matchWrap(gap))
            addView(networkText, matchWrap(gap))
            addView(runButton, matchWrap(gap))
            addView(copyButton, matchWrap(gap))
            addView(resultText, matchWrap(gap))
        }
        setContentView(ScrollView(this).apply {
            addView(content, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
    }

    override fun onResume() {
        super.onResume()
        networkText.text = "Сеть: ${currentNetworkLabel()}"
    }

    private fun runProbe() {
        val network = currentNetworkLabel()
        networkText.text = "Сеть: $network"
        runButton.isEnabled = false

        thread(name = "CronetTransportControl") {
            val lines = mutableListOf(
                "SE Chromium/Cronet TLS + QUIC control diagnostics",
                "Network: $network",
                "Android baseline: production/upstream trust-all SSLSocket with normal hostname DNS",
                "Cronet transport: app-packaged native Cronet/Chromium",
                "Conventional Cronet: QUIC disabled, HTTP/2 disabled, HTTP cache disabled",
                "QUIC Cronet: QUIC enabled, HTTP/2 disabled, explicit :443 QUIC hints",
                "Cronet certificate verification: Chromium default validation",
                "TLS/request timeout: ${TLS_TIMEOUT_MS}ms / ${REQUEST_WATCHDOG_MS}ms watchdog",
                "QUIC success criterion: HTTP headers with negotiated protocol h3*/quic*; HTTP/1.1 is counted only as TCP fallback",
                "Goal: determine whether UDP/443 + QUIC remains usable when conventional Cloudflare TCP/TLS stalls",
                "",
            )

            var engine: CronetEngine? = null
            val executor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "CronetTransportCallback")
            }

            try {
                val rawCloudflareOk = runRawCloudflareBaseline(lines)
                publish(lines)

                val providers = CronetProvider.getAllProviders(applicationContext)
                lines += ""
                lines += "=== Cronet providers ==="
                providers.forEach { provider ->
                    val version = runCatching { provider.version }.getOrElse { "<error:${it::class.java.simpleName}>" }
                    val enabled = runCatching { provider.isEnabled }.getOrDefault(false)
                    lines += "provider name=${provider.name} version=$version enabled=$enabled class=${provider::class.java.name}"
                }

                val packaged = providers.firstOrNull {
                    it.name == CronetProvider.PROVIDER_NAME_APP_PACKAGED && runCatching { it.isEnabled }.getOrDefault(false)
                }
                if (packaged == null) {
                    lines += ""
                    lines += "RESULT: APP-PACKAGED NATIVE CRONET PROVIDER NOT AVAILABLE"
                    lines += "VERDICT: diagnostic cannot compare native Chromium transports."
                    return@thread
                }

                lines += "Selected provider: ${packaged.name} ${runCatching { packaged.version }.getOrDefault("unknown")}"
                publish(lines)

                engine = packaged.createBuilder()
                    .enableQuic(false)
                    .enableHttp2(false)
                    .enableHttpCache(CronetEngine.Builder.HTTP_CACHE_DISABLED, 0)
                    .build()

                val conventional = runTargets(
                    lines = lines,
                    engine = engine,
                    executor = executor,
                    sectionTitle = "CONVENTIONAL TCP/TLS/HTTP1",
                    targets = CONVENTIONAL_TARGETS,
                )

                runCatching { engine.shutdown() }
                engine = null

                lines += ""
                lines += "=== QUIC / HTTP3 CONTROL ==="
                lines += "QUIC hints force an immediate QUIC attempt; only negotiated h3*/quic* is accepted as proof that UDP/443 works."
                publish(lines)

                engine = packaged.createBuilder()
                    .enableQuic(true)
                    .enableHttp2(false)
                    .enableHttpCache(CronetEngine.Builder.HTTP_CACHE_DISABLED, 0)
                    .addQuicHint("cloudflare-quic.com", 443, 443)
                    .addQuicHint("cloudflare.com", 443, 443)
                    .addQuicHint("kws2.pclead.co.uk", 443, 443)
                    .build()

                val quic = runTargets(
                    lines = lines,
                    engine = engine,
                    executor = executor,
                    sectionTitle = "QUIC ENABLED",
                    targets = QUIC_TARGETS,
                )

                val quicProof = quic.h3HeadersReached > 0
                val benignQuicProof = quic.h3Labels.any { it == TargetKind.QUIC_REFERENCE || it == TargetKind.BENIGN_CLOUDFLARE }
                val proxyQuicProof = quic.h3Labels.any { it == TargetKind.PROXY_HOST }

                lines += ""
                lines += "RESULT: CHROMIUM/CRONET TRANSPORT CONTROL COMPLETE"
                lines += "Android SSLSocket cloudflare.com normal-DNS TLS: ${if (rawCloudflareOk) "OK" else "FAIL"}"
                lines += "Conventional Cronet HTTP headers: ${conventional.headersReached}/${CONVENTIONAL_TARGETS.size}"
                lines += "Conventional Cronet watchdog timeouts: ${conventional.watchdogTimeouts}"
                lines += "QUIC-enabled HTTP headers: ${quic.headersReached}/${QUIC_TARGETS.size}"
                lines += "Confirmed h3/quic responses: ${quic.h3HeadersReached}/${QUIC_TARGETS.size}"
                lines += "QUIC TCP-fallback responses: ${quic.tcpFallbackHeadersReached}"
                lines += "QUIC failures: ${quic.failures}; watchdog timeouts: ${quic.watchdogTimeouts}"
                lines += "Benign/reference QUIC proof: ${if (benignQuicProof) "YES" else "NO"}"
                lines += "Proxy-host QUIC proof: ${if (proxyQuicProof) "YES" else "NO"}"
                lines += when {
                    !rawCloudflareOk && quicProof -> "VERDICT: conventional TCP/TLS is unusable on this mobile path, but QUIC/UDP:443 works. Stop TLS/SNI rotation; investigate a QUIC-capable tunnel transport. This does NOT make the existing WebSocket route work by itself."
                    rawCloudflareOk && quicProof -> "VERDICT: both conventional TLS and QUIC are usable in at least one clean control; failures are hostname/service-specific rather than a blanket Cloudflare transport failure."
                    !quicProof && !rawCloudflareOk -> "VERDICT: neither conventional Cloudflare TCP/TLS nor a confirmed QUIC/HTTP3 path was obtained. Further Cloudflare TLS-stack/domain rotation has low value; move to a different tunnel architecture such as a known-working VLESS/REALITY path."
                    else -> "VERDICT: no confirmed HTTP/3 transport was obtained. Use per-target protocol/error results before changing production routing."
                }
            } catch (error: Throwable) {
                lines += ""
                lines += "RESULT: TEST ERROR: ${errorSummary(error)}"
            } finally {
                runCatching { engine?.shutdown() }
                executor.shutdownNow()
                finish(lines)
            }
        }
    }

    private fun runTargets(
        lines: MutableList<String>,
        engine: CronetEngine,
        executor: java.util.concurrent.Executor,
        sectionTitle: String,
        targets: List<Target>,
    ): SectionResult {
        lines += ""
        lines += "=== $sectionTitle ==="
        publish(lines)

        var headersReached = 0
        var failures = 0
        var watchdogTimeouts = 0
        var h3HeadersReached = 0
        var tcpFallbackHeadersReached = 0
        val h3Labels = mutableSetOf<TargetKind>()

        for (target in targets) {
            lines += ""
            lines += "--- ${target.label} ---"
            lines += "URL: ${target.url}"
            publish(lines)

            val outcome = executeRequest(engine, executor, target.url)
            when (outcome.kind) {
                OutcomeKind.HTTP_HEADERS -> {
                    headersReached += 1
                    val isQuic = isQuicProtocol(outcome.protocol)
                    if (isQuic) {
                        h3HeadersReached += 1
                        h3Labels += target.kind
                    } else if (sectionTitle == "QUIC ENABLED") {
                        tcpFallbackHeadersReached += 1
                    }
                    lines += "OK   Chromium reached HTTP headers (${outcome.elapsedMs}ms)"
                    lines += "status=${outcome.statusCode} protocol=${outcome.protocol.ifBlank { "unknown" }} transport=${if (isQuic) "QUIC_CONFIRMED" else "TCP_OR_UNKNOWN"} proxy=${outcome.proxy.ifBlank { "none" }}"
                    outcome.detail.takeIf { it.isNotBlank() }?.let { lines += it }
                }

                OutcomeKind.FAILURE -> {
                    failures += 1
                    lines += "FAIL Chromium request (${outcome.elapsedMs}ms): ${outcome.detail}"
                }

                OutcomeKind.WATCHDOG_TIMEOUT -> {
                    watchdogTimeouts += 1
                    lines += "FAIL Chromium watchdog timeout after ${outcome.elapsedMs}ms before HTTP headers"
                }
            }
            publish(lines)
        }

        return SectionResult(
            headersReached = headersReached,
            failures = failures,
            watchdogTimeouts = watchdogTimeouts,
            h3HeadersReached = h3HeadersReached,
            tcpFallbackHeadersReached = tcpFallbackHeadersReached,
            h3Labels = h3Labels,
        )
    }

    private fun runRawCloudflareBaseline(lines: MutableList<String>): Boolean {
        lines += "=== Android SSLSocket benign normal-DNS baseline ==="
        lines += "host=cloudflare.com:443 sni=cloudflare.com (no edge-IP pinning)"
        val trace = mutableListOf<String>()
        val factory = TracedTrustAllTlsTransportFactory.traced { event ->
            synchronized(trace) { trace += event }
        }

        return try {
            val elapsed = measureTimeMillis {
                val transport = factory.connect(
                    "cloudflare.com",
                    443,
                    "cloudflare.com",
                    TLS_TIMEOUT_MS,
                )
                transport.close()
            }
            synchronized(trace) { trace.forEach { lines += "TRACE $it" } }
            lines += "OK   Android SSLSocket TLS completed (${elapsed}ms)"
            true
        } catch (error: TracedTlsDiagnosticTimeoutException) {
            synchronized(trace) { trace.forEach { lines += "TRACE $it" } }
            lines += "FAIL Android SSLSocket [${error.stage}]: ${errorSummary(error)}"
            false
        } catch (error: Throwable) {
            synchronized(trace) { trace.forEach { lines += "TRACE $it" } }
            lines += "FAIL Android SSLSocket: ${errorSummary(error)}"
            false
        }
    }

    private fun executeRequest(
        engine: CronetEngine,
        executor: java.util.concurrent.Executor,
        url: String,
    ): Outcome {
        val latch = CountDownLatch(1)
        val outcome = AtomicReference<Outcome?>()
        val startedAt = System.nanoTime()

        fun elapsedMs(): Long = (System.nanoTime() - startedAt) / 1_000_000L
        fun complete(value: Outcome) {
            if (outcome.compareAndSet(null, value)) latch.countDown()
        }

        val callback = CronetDiagnosticCallback(
            object : CronetDiagnosticCallback.Listener {
                override fun onHeaders(
                    request: UrlRequest,
                    info: UrlResponseInfo,
                    detail: String,
                ) {
                    complete(
                        Outcome(
                            kind = OutcomeKind.HTTP_HEADERS,
                            elapsedMs = elapsedMs(),
                            statusCode = info.httpStatusCode,
                            protocol = info.negotiatedProtocol.orEmpty(),
                            proxy = info.proxyServer.orEmpty(),
                            detail = detail,
                        ),
                    )
                }

                override fun onFailure(
                    request: UrlRequest,
                    info: UrlResponseInfo?,
                    error: CronetException,
                ) {
                    val networkDetail = if (error is NetworkException) {
                        " errorCode=${error.errorCode} internalErrorCode=${error.cronetInternalErrorCode} retryable=${error.immediatelyRetryable()}"
                    } else {
                        ""
                    }
                    complete(
                        Outcome(
                            kind = OutcomeKind.FAILURE,
                            elapsedMs = elapsedMs(),
                            statusCode = info?.httpStatusCode,
                            protocol = info?.negotiatedProtocol.orEmpty(),
                            proxy = info?.proxyServer.orEmpty(),
                            detail = "${error::class.java.simpleName}: ${error.message.orEmpty()}$networkDetail",
                        ),
                    )
                }
            },
        )

        val request = engine.newUrlRequestBuilder(url, callback, executor)
            .setHttpMethod("GET")
            .build()
        request.start()

        if (!latch.await(REQUEST_WATCHDOG_MS, TimeUnit.MILLISECONDS)) {
            request.cancel()
            return Outcome(
                kind = OutcomeKind.WATCHDOG_TIMEOUT,
                elapsedMs = elapsedMs(),
                detail = "no callback before watchdog",
            )
        }
        return outcome.get() ?: Outcome(
            kind = OutcomeKind.FAILURE,
            elapsedMs = elapsedMs(),
            detail = "callback completed without outcome",
        )
    }

    private fun isQuicProtocol(protocol: String): Boolean {
        val normalized = protocol.trim().lowercase()
        return normalized.startsWith("h3") || normalized.contains("quic")
    }

    private fun finish(lines: List<String>) {
        publish(lines)
        runOnUiThread {
            runButton.isEnabled = true
            networkText.text = "Сеть: ${currentNetworkLabel()}"
        }
    }

    private fun publish(lines: List<String>) {
        val snapshot = lines.toList()
        runOnUiThread { resultText.text = snapshot.joinToString("\n") }
    }

    private fun copyResult() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Chromium Cronet transport control diagnostics", resultText.text))
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

    private fun errorSummary(error: Throwable): String {
        val root = generateSequence(error) { it.cause }.last()
        val message = root.message?.replace('\n', ' ')?.take(400).orEmpty()
        return if (message.isBlank()) root::class.java.simpleName else "${root::class.java.simpleName}: $message"
    }

    private fun matchWrap(topMargin: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { this.topMargin = topMargin }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private data class Target(
        val label: String,
        val url: String,
        val kind: TargetKind,
    )

    private enum class TargetKind {
        BENIGN_CLOUDFLARE,
        ZONE_ROOT,
        PROXY_HOST,
        QUIC_REFERENCE,
    }

    private data class Outcome(
        val kind: OutcomeKind,
        val elapsedMs: Long,
        val statusCode: Int? = null,
        val protocol: String = "",
        val proxy: String = "",
        val detail: String = "",
    )

    private data class SectionResult(
        val headersReached: Int,
        val failures: Int,
        val watchdogTimeouts: Int,
        val h3HeadersReached: Int,
        val tcpFallbackHeadersReached: Int,
        val h3Labels: Set<TargetKind>,
    )

    private enum class OutcomeKind {
        HTTP_HEADERS,
        FAILURE,
        WATCHDOG_TIMEOUT,
    }

    companion object {
        private const val TLS_TIMEOUT_MS = 10_000
        private const val REQUEST_WATCHDOG_MS = 12_000L

        private val CONVENTIONAL_TARGETS = listOf(
            Target("benign Cloudflare", "https://cloudflare.com/", TargetKind.BENIGN_CLOUDFLARE),
            Target("CF proxy zone root", "https://pclead.co.uk/", TargetKind.ZONE_ROOT),
            Target("CF proxy Telegram hostname", "https://kws2.pclead.co.uk/apiws", TargetKind.PROXY_HOST),
        )

        private val QUIC_TARGETS = listOf(
            Target("Cloudflare QUIC reference", "https://cloudflare-quic.com/", TargetKind.QUIC_REFERENCE),
            Target("benign Cloudflare", "https://cloudflare.com/", TargetKind.BENIGN_CLOUDFLARE),
            Target("CF proxy Telegram hostname", "https://kws2.pclead.co.uk/apiws", TargetKind.PROXY_HOST),
        )
    }
}
