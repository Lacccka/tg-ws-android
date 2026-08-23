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
import org.chromium.net.CronetEngine
import org.chromium.net.CronetException
import org.chromium.net.CronetProvider
import org.chromium.net.NetworkException
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.system.measureTimeMillis

/**
 * Private-sideload control that compares the app's failing Android SSLSocket
 * path with a native app-packaged Chromium/Cronet network stack.
 *
 * QUIC and HTTP/2 are disabled deliberately: a successful response here proves
 * that Chromium can complete a conventional TCP -> TLS -> HTTP/1.1 path on the
 * same mobile network. This activity does not alter production proxy routing.
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
            text = "Сравнивает native Chromium/Cronet с текущим Android SSLSocket path.\n\n" +
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
                text = "Если cloudflare.com отвечает через native Cronet, а SSLSocket на этой же сети зависает в TLS, различие находится в TLS/network stack, а не в TCP-доступности Cloudflare."
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
                "Transport: app-packaged native Cronet/Chromium",
                "QUIC: disabled",
                "HTTP/2: disabled",
                "HTTP cache: disabled",
                "Certificate verification: Chromium default validation",
                "Request watchdog: ${REQUEST_WATCHDOG_MS}ms",
                "Goal: compare Chromium TCP/TLS/HTTP stack with failing Android SSLSocket TLS path",
                "",
            )

            var engine: CronetEngine? = null
            val executor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "CronetTlsCallback")
            }

            try {
                val providers = CronetProvider.getAllProviders(applicationContext)
                lines += "=== Providers ==="
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
                    finish(lines)
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

                for (target in TARGETS) {
                    lines += ""
                    lines += "=== ${target.label} ==="
                    lines += "URL: ${target.url}"
                    publish(lines)

                    val outcome = executeRequest(engine!!, executor, target.url)
                    when (outcome.kind) {
                        OutcomeKind.HTTP_HEADERS -> {
                            headersReached += 1
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
                lines += "HTTP headers reached: $headersReached/${TARGETS.size}"
                lines += "Cronet failures: $failures"
                lines += "Watchdog timeouts: $watchdogTimeouts"
                lines += when {
                    headersReached > 0 -> "VERDICT: native Chromium/Cronet can complete at least one TCP/TLS/HTTP path on this network while the Android SSLSocket Cloudflare controls time out. Investigate Chromium TLS/ECH/fingerprint behavior as a transport candidate instead of rotating SNI domains."
                    failures > 0 -> "VERDICT: native Chromium/Cronet also fails before HTTP headers. Inspect Cronet error/internal codes; changing only the Java TLS implementation is unlikely to be sufficient."
                    else -> "VERDICT: native Chromium/Cronet produced no HTTP headers within the watchdog window."
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

    private fun executeRequest(
        engine: CronetEngine,
        executor: java.util.concurrent.Executor,
        url: String,
    ): Outcome {
        val latch = CountDownLatch(1)
        val outcome = AtomicReference<Outcome?>()
        lateinit var request: UrlRequest
        val startedAt = System.nanoTime()

        fun elapsedMs(): Long = (System.nanoTime() - startedAt) / 1_000_000L
        fun complete(value: Outcome) {
            if (outcome.compareAndSet(null, value)) latch.countDown()
        }

        val callback = object : UrlRequest.Callback() {
            override fun onRedirectReceived(
                request: UrlRequest,
                info: UrlResponseInfo,
                newLocationUrl: String,
            ) {
                complete(
                    Outcome(
                        kind = OutcomeKind.HTTP_HEADERS,
                        elapsedMs = elapsedMs(),
                        statusCode = info.httpStatusCode,
                        protocol = info.negotiatedProtocol.orEmpty(),
                        proxy = info.proxyServer.orEmpty(),
                        detail = "redirect=$newLocationUrl",
                    ),
                )
                request.cancel()
            }

            override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
                complete(
                    Outcome(
                        kind = OutcomeKind.HTTP_HEADERS,
                        elapsedMs = elapsedMs(),
                        statusCode = info.httpStatusCode,
                        protocol = info.negotiatedProtocol.orEmpty(),
                        proxy = info.proxyServer.orEmpty(),
                        detail = "statusText=${info.httpStatusText}",
                    ),
                )
                request.cancel()
            }

            override fun onReadCompleted(
                request: UrlRequest,
                info: UrlResponseInfo,
                byteBuffer: ByteBuffer,
            ) = Unit

            override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) {
                complete(
                    Outcome(
                        kind = OutcomeKind.HTTP_HEADERS,
                        elapsedMs = elapsedMs(),
                        statusCode = info.httpStatusCode,
                        protocol = info.negotiatedProtocol.orEmpty(),
                        proxy = info.proxyServer.orEmpty(),
                        detail = "request completed",
                    ),
                )
            }

            override fun onFailed(
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

            override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) {
                if (outcome.get() == null) {
                    complete(
                        Outcome(
                            kind = OutcomeKind.FAILURE,
                            elapsedMs = elapsedMs(),
                            detail = "request canceled before HTTP headers",
                        ),
                    )
                }
            }
        }

        request = engine.newUrlRequestBuilder(url, callback, executor)
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
    )

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
        private const val REQUEST_WATCHDOG_MS = 12_000L
        private val TARGETS = listOf(
            Target("benign Cloudflare", "https://cloudflare.com/"),
            Target("CF proxy zone root", "https://pclead.co.uk/"),
            Target("CF proxy Telegram hostname", "https://kws2.pclead.co.uk/apiws"),
        )
    }
}
