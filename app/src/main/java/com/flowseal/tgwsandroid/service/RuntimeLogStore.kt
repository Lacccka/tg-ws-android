package com.flowseal.tgwsandroid.service

import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
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

data class RuntimeLogMetadata(
    val oldestLogTimeMs: Long?,
    val newestLogTimeMs: Long?,
    val logCoverageDurationMs: Long?,
    val currentLogEntryCount: Int,
    val currentLogApproxChars: Int,
    val maxLogLines: Int,
    val maxLogChars: Int,
    val totalLogEntriesAccepted: Long,
    val totalLogEntriesDroppedDueToLimit: Long,
    val restoredLogEntries: Long,
)

/** Thread-safe bounded in-memory runtime log store for Android service diagnostics. */
class RuntimeLogStore(
    private val maxLines: Int = DEFAULT_MAX_LINES,
    private val maxChars: Int = DEFAULT_MAX_CHARS,
    private val clock: Clock = Clock.systemDefaultZone(),
    private var persistence: RuntimeLogPersistence? = null,
) {
    init {
        require(maxLines > 0) { "maxLines must be positive" }
        require(maxChars > 0) { "maxChars must be positive" }
    }

    private val lock = Any()
    private val entries: ArrayDeque<RuntimeLogEntry> = ArrayDeque()
    private var currentChars: Int = 0
    private var totalLogEntriesAccepted: Long = 0
    private var totalLogEntriesDroppedDueToLimit: Long = 0
    private var restoredLogEntries: Long = 0

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
        val line = entry.formatLine()
        synchronized(lock) {
            totalLogEntriesAccepted += 1
            entries.addLast(entry)
            currentChars += line.length
            trimLocked()
        }
        runCatching { persistence?.appendLine(line) }
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

    fun configurePersistence(newPersistence: RuntimeLogPersistence): Int {
        val restoredLines = newPersistence.readTailLines()
        synchronized(lock) {
            persistence = newPersistence
            entries.clear()
            currentChars = 0
            for (line in restoredLines) {
                entries.addLast(parsePersistedLine(line))
                currentChars += line.length
            }
            restoredLogEntries += restoredLines.size.toLong()
            trimLocked()
        }
        return restoredLines.size
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            currentChars = 0
        }
        runCatching { persistence?.clear() }
    }

    fun snapshot(): List<RuntimeLogEntry> = synchronized(lock) { entries.toList() }

    fun metadataSnapshot(): RuntimeLogMetadata = synchronized(lock) { metadataLocked() }

    fun snapshotWithMetadata(): Pair<List<RuntimeLogEntry>, RuntimeLogMetadata> = synchronized(lock) {
        entries.toList() to metadataLocked()
    }

    private fun metadataLocked(): RuntimeLogMetadata {
        val oldestLogTimeMs = entries.firstOrNull()?.timestamp?.atZone(clock.zone)?.toInstant()?.toEpochMilli()
        val newestLogTimeMs = entries.lastOrNull()?.timestamp?.atZone(clock.zone)?.toInstant()?.toEpochMilli()
        return RuntimeLogMetadata(
            oldestLogTimeMs = oldestLogTimeMs,
            newestLogTimeMs = newestLogTimeMs,
            logCoverageDurationMs = if (oldestLogTimeMs != null && newestLogTimeMs != null && entries.size >= 2) {
                (newestLogTimeMs - oldestLogTimeMs).coerceAtLeast(0L)
            } else {
                null
            },
            currentLogEntryCount = entries.size,
            currentLogApproxChars = currentChars,
            maxLogLines = maxLines,
            maxLogChars = maxChars,
            totalLogEntriesAccepted = totalLogEntriesAccepted,
            totalLogEntriesDroppedDueToLimit = totalLogEntriesDroppedDueToLimit,
            restoredLogEntries = restoredLogEntries,
        )
    }

    fun lines(): List<String> = snapshot().map { it.formatLine() }

    fun isEmpty(): Boolean = synchronized(lock) { entries.isEmpty() }

    private fun trimLocked() {
        while (entries.size > maxLines || currentChars > maxChars) {
            val removed = entries.removeFirstOrNull() ?: break
            currentChars -= removed.formatLine().length
            totalLogEntriesDroppedDueToLimit += 1
        }
        if (currentChars < 0) currentChars = entries.sumOf { it.formatLine().length }
    }

    companion object {
        const val DEFAULT_MAX_LINES = 400
        const val DEFAULT_MAX_CHARS = 80_000
        val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)

        private fun parsePersistedLine(line: String): RuntimeLogEntry {
            val parts = line.split(' ', limit = 4)
            if (parts.size == 4) {
                val severity = runCatching { LogSeverity.valueOf(parts[1]) }.getOrNull()
                val time = runCatching { LocalTime.parse(parts[0], TIME_FORMATTER) }.getOrNull()
                if (severity != null && time != null) {
                    return RuntimeLogEntry(
                        timestamp = LocalDateTime.of(LocalDate.now(), time),
                        severity = severity,
                        source = parts[2].ifBlank { "restored" },
                        message = parts[3],
                    )
                }
            }
            return RuntimeLogEntry(LocalDateTime.now(), LogSeverity.INFO, "restored", line)
        }

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
                    lower.contains("stop failed") ||
                    lower.contains("ended unexpectedly") -> LogSeverity.WARN

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
                    lower.contains("logs cleared") ||
                    lower.contains("app process diagnostics initialized") ||
                    lower.contains("process started") ||
                    lower.contains("app opened") ||
                    lower.contains("previous run marker loaded") ||
                    lower.contains("previous proxy run was graceful") ||
                    lower.contains("restored") && lower.contains("persisted log lines") -> LogSeverity.INFO

                else -> LogSeverity.DEBUG
            }
        }
    }
}
