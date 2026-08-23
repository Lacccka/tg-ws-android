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
 * Private-sideload control comparing Android SSLSocket with app-packaged native
 * Chromium/Cronet on the same mobile network. QUIC and HTTP/2 are disabled so a
 * successful Cronet response proves a conventional TCP -> TLS -> HTTP/1.1 path.
 */
class CronetTlsControlActivity : Activity() {
    private lateinit var networkText: TextView
    private lateinit var resultText: TextView
    private lateinit var runButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "SE Cronet TLS Control"

        networkText = TextView(this).apply { text = "Сеть: ${currentNetworkLabel()}" }
        resultText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            text = "Сравнивает normal-DNS Android SSLSocket baseline с native Chromium/Cronet.\n\n" +
                "QUIC выключен, HTTP/2 выключен, production routing не меняется."
        }
        runButton = Button(this).apply {
            text = "Проверить Chromium/Cronet TLS"
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
                text = "Chromium/Cronet TLS control"
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
            }, matchWrap())
            addView(TextView(this@CronetTlsControlActivity).apply {
                text = "Сначала проверяет cloudflare.com через обычный Android SSLSocket и собственный DNS домена, затем те же классы hostname через native Cronet."
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

        thread(name = "CronetTlsControl") {
            val lines = mutableListOf(
                "SE Chromium/Cronet TLS control diagnostics",
                "Network: $network",
                "Android baseline: production/upstream trust-all SSLSocket with normal hostname DNS",
                "Cronet transport: app-packaged native Cronet/Chromium",
                "Cronet QUIC: disabled",
                "Cronet HTTP/2: disabled",
                "Cronet HTTP cache: disabled",
                "Cronet certificate verification: Chromium default validation",
                "TLS/request timeout: ${TLS_TIMEOUT_MS}ms / ${REQUEST_WATCHDOG_MS}ms watchdog",
                "Goal: separate cross-zone IP pinning, SNI/hostname effects, and TLS/network-stack differences",
                "",
            )

            var engine: CronetEngine? = null
            val executor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "CronetTlsCallback")
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
                    lines += "VERDICT: diagnostic cannot compare Chromium native TLS; do not treat Java fallback as a Chromium-stack result."
                    return@thread
                }

                lines += "Selected provider: ${packaged.name} ${runCatching { packaged.version }.getOrDefault("unknown")}"
                publish(lines)

                engine = packaged.createBuilder()
                    .enableQuic(false)
                    .enableHttp2(false)
                    .enableHttpCache(CronetEngine.Builder.HTTP_CACHE_DISABLED, 0)
                    .build()

                var headersReached = 0
                var failures = 0
                var watchdogTimeouts = 0
                var cronetCloudflareOk = false
                var cronetProxyHostOk = false

                for (target in TARGETS) {
                    lines += ""
                    lines += "=== ${target.label} ==="
                    lines += "URL: ${target.url}"
                    publish(lines)

                    val outcome = executeRequest(engine, executor, target.url)
                    when (outcome.kind) {
                        OutcomeKind.HTTP_HEADERS -> {
                            headersReached += 1
                            if (target.kind == TargetKind.BENIGN_CLOUDFLARE) cronetCloudflareOk = true
                            if (target.kind == TargetKind.PROXY_HOST) cronetProxyHostOk = true
                            lines += "OK   Chromium reached HTTP headers (${outcome.elapsedMs}ms)"
                            lines += "status=${outcome.statusCode} protocol=${outcome.protocol.ifBlank { "unknown" }} proxy=${outcome.proxy.ifBlank { "none" }}"
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

                lines += ""
                lines += "RESULT: CHROMIUM/CRONET TLS CONTROL COMPLETE"
                lines += "Android SSLSocket cloudflare.com normal-DNS TLS: ${if (rawCloudflareOk) "OK" else "FAIL"}"
                lines += "Cronet cloudflare.com HTTP headers: ${if (cronetCloudflareOk) "OK" else "FAIL"}"
                lines += "Cronet proxy-host HTTP headers: ${if (cronetProxyHostOk) "OK" else "FAIL"}"
                lines += "Cronet HTTP headers reached: $headersReached/${TARGETS.size}"
                lines += "Cronet failures: $failures"
                lines += "Cronet watchdog timeouts: $watchdogTimeouts"
                lines += when {
                    !rawCloudflareOk && cronetCloudflareOk -> "VERDICT: clean normal-DNS control isolates a network-stack difference: Android SSLSocket cannot complete benign Cloudflare TLS while native Chromium/Cronet can. Chromium TLS behavior is a concrete transport candidate; do not rotate SNI domains again."
                    rawCloudflareOk && !cronetProxyHostOk -> "VERDICT: Android SSLSocket can complete benign Cloudflare TLS with cloudflare.com's own DNS. Proxy/Worker hostname behavior remains the differentiator."
                    rawCloudflareOk && cronetProxyHostOk -> "VERDICT: both benign Android TLS and the proxy hostname work through the clean/native controls. Investigate reusing the successful Chromium path for WebSocket/MTProto."
                    !rawCloudflareOk && !cronetCloudflareOk -> "VERDICT: both Android SSLSocket and native Chromium fail to reach benign Cloudflare over conventional TCP/TLS on this network. A simple TLS-library swap is unlikely to solve the mobile path."
                    else -> "VERDICT: mixed result; use the per-target Cronet error/internal codes before choosing a production transport."
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
        clipboard.setPrimaryClip(ClipData.newPlainText("Chromium Cronet TLS control diagnostics", resultText.text))
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
    }

    private data class Outcome(
        val kind: OutcomeKind,
        val elapsedMs: Long,
        val statusCode: Int? = null,
        val protocol: String = "",
        val proxy: String = "",
        val detail: String = "",
    )

    private enum class OutcomeKind {
        HTTP_HEADERS,
        FAILURE,
        WATCHDOG_TIMEOUT,
    }

    companion object {
        private const val TLS_TIMEOUT_MS = 10_000
        private const val REQUEST_WATCHDOG_MS = 12_000L
        private val TARGETS = listOf(
            Target("benign Cloudflare", "https://cloudflare.com/", TargetKind.BENIGN_CLOUDFLARE),
            Target("CF proxy zone root", "https://pclead.co.uk/", TargetKind.ZONE_ROOT),
            Target("CF proxy Telegram hostname", "https://kws2.pclead.co.uk/apiws", TargetKind.PROXY_HOST),
        )
    }
}
