package com.flowseal.tgwsandroid.service

import java.io.File
import java.time.Clock
import java.time.Instant
import java.util.Properties
import java.util.UUID

data class PreviousRunCheck(
    val hadMarker: Boolean,
    val wasRunning: Boolean,
    val wasUnexpected: Boolean,
    val runId: String?,
    val startedAt: String?,
    val lastStopReason: String?,
)

class ProxyRunMarker(
    private val markerFile: File,
    private val clock: Clock = Clock.systemUTC(),
    private val newRunId: () -> String = { UUID.randomUUID().toString() },
) {
    fun inspectPreviousRun(): PreviousRunCheck {
        val props = readProperties()
        val hadMarker = props.isNotEmpty()
        val wasRunning = props.getProperty(KEY_RUNNING)?.toBooleanStrictOrNull() == true
        return PreviousRunCheck(
            hadMarker = hadMarker,
            wasRunning = wasRunning,
            wasUnexpected = wasRunning,
            runId = props.getProperty(KEY_RUN_ID),
            startedAt = props.getProperty(KEY_STARTED_AT),
            lastStopReason = props.getProperty(KEY_LAST_STOP_REASON),
        )
    }

    fun markStarted(): String {
        val runId = newRunId()
        writeProperties(
            mapOf(
                KEY_RUNNING to "true",
                KEY_RUN_ID to runId,
                KEY_STARTED_AT to Instant.now(clock).toString(),
                KEY_LAST_KNOWN_STATUS to "running",
            ),
        )
        return runId
    }

    fun markStopped(reason: String) {
        val existing = readProperties()
        existing.setProperty(KEY_RUNNING, "false")
        existing.setProperty(KEY_LAST_KNOWN_STATUS, "stopped")
        existing.setProperty(KEY_LAST_STOP_REASON, reason.ifBlank { "unknown" })
        existing.setProperty(KEY_STOPPED_AT, Instant.now(clock).toString())
        writeProperties(existing.entries.associate { it.key.toString() to it.value.toString() })
    }

    private fun readProperties(): Properties = Properties().also { props ->
        runCatching {
            if (markerFile.exists()) markerFile.inputStream().use(props::load)
        }
    }

    private fun writeProperties(values: Map<String, String>) {
        runCatching {
            markerFile.parentFile?.mkdirs()
            val props = Properties()
            values.forEach { (key, value) -> props.setProperty(key, value) }
            markerFile.outputStream().use { props.store(it, "TG WS Android proxy run marker") }
        }
    }

    companion object {
        private const val KEY_RUNNING = "proxy_running"
        private const val KEY_RUN_ID = "run_id"
        private const val KEY_STARTED_AT = "started_at"
        private const val KEY_LAST_KNOWN_STATUS = "last_known_status"
        private const val KEY_LAST_STOP_REASON = "last_stop_reason"
        private const val KEY_STOPPED_AT = "stopped_at"
    }
}
