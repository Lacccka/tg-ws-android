package com.flowseal.tgwsandroid.service

import java.time.Clock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class LogSeverity {
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

data class RuntimeLogEntry(
    val timestamp: LocalDateTime,
    val severity: LogSeverity,
    val source: String,
    val message: String,
) {
    fun formatLine(timeFormatter: DateTimeFormatter = RuntimeLogStore.TIME_FORMATTER): String =
        "${timestamp.format(timeFormatter)} ${severity.name} $source $message"
}

/** Thread-safe bounded in-memory runtime log store for Android service diagnostics. */
class RuntimeLogStore(
    private val maxLines: Int = DEFAULT_MAX_LINES,
    private val maxChars: Int = DEFAULT_MAX_CHARS,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    init {
        require(maxLines > 0) { "maxLines must be positive" }
        require(maxChars > 0) { "maxChars must be positive" }
    }

    private val lock = Any()
    private val entries: ArrayDeque<RuntimeLogEntry> = ArrayDeque()
    private var currentChars: Int = 0

    fun append(
        message: String,
        severity: LogSeverity = classifySeverity(message),
        source: String = "service",
    ): RuntimeLogEntry {
        val entry = RuntimeLogEntry(
            timestamp = LocalDateTime.now(clock),
            severity = severity,
            source = source.ifBlank { "unknown" },
            message = message,
        )
        synchronized(lock) {
            entries.addLast(entry)
            currentChars += entry.formatLine().length
            trimLocked()
        }
        return entry
    }

    fun appendProxy(message: String): RuntimeLogEntry = append(
        message = message,
        severity = classifySeverity(message),
        source = "proxy",
    )

    fun appendSeparator(message: String): RuntimeLogEntry = append(
        message = message,
        severity = LogSeverity.INFO,
        source = "service",
    )

    fun clear() {
        synchronized(lock) {
            entries.clear()
            currentChars = 0
        }
    }

    fun snapshot(): List<RuntimeLogEntry> = synchronized(lock) { entries.toList() }

    fun lines(): List<String> = snapshot().map { it.formatLine() }

    fun isEmpty(): Boolean = synchronized(lock) { entries.isEmpty() }

    private fun trimLocked() {
        while (entries.size > maxLines || currentChars > maxChars) {
            val removed = entries.removeFirstOrNull() ?: break
            currentChars -= removed.formatLine().length
        }
        if (currentChars < 0) currentChars = entries.sumOf { it.formatLine().length }
    }

    companion object {
        const val DEFAULT_MAX_LINES = 400
        const val DEFAULT_MAX_CHARS = 80_000
        val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)

        fun classifySeverity(message: String): LogSeverity {
            val lower = message.lowercase(Locale.US)
            return when {
                lower.contains("proxy start failed") ||
                    lower.contains("start foreground failed") ||
                    lower.contains("service failed to start foreground") ||
                    lower.contains("unhandled exception") ||
                    lower.contains("all routes failed") ||
                    lower.contains("cf fallback all failed") ||
                    lower.contains("cf proxy connect failed after all attempts") -> LogSeverity.ERROR

                lower.contains("websocket connect failed") ||
                    lower.contains("sockettimeoutexception") ||
                    lower.contains("cf proxy failed") ||
                    lower.contains("network lost") ||
                    lower.contains("battery optimization enabled") ||
                    lower.contains("unsupported dc") ||
                    lower.contains("no route") ||
                    (lower.contains("direct") && lower.contains("failed") && lower.contains("fallback")) ||
                    lower.contains("attempt via") && lower.contains("failed") ||
                    lower.contains("accept failed") ||
                    lower.contains("stop failed") -> LogSeverity.WARN

                lower.contains("service created") ||
                    lower.contains("service destroyed") ||
                    lower.contains("start command received") ||
                    lower.contains("stop command received") ||
                    lower.contains("proxy started") ||
                    lower.contains("proxy stopped") ||
                    lower.contains("proxyserver listening") ||
                    lower.contains("listening") ||
                    lower.contains("handshake accepted") ||
                    lower.contains("websocket connected") ||
                    lower.contains("cf proxy connected") ||
                    lower.contains("pool warmup started") ||
                    lower.contains("pool hit") ||
                    lower.contains("pool miss") ||
                    lower.contains("logs cleared") -> LogSeverity.INFO

                else -> LogSeverity.DEBUG
            }
        }
    }
}
