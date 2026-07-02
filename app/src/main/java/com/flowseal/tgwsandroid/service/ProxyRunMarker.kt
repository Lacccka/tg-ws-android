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
    val lastHeartbeatAt: String?,
    val lastServiceEvent: String?,
    val lastServiceEventAt: String? = null,
    val lastForegroundStartedAt: String? = null,
    val lastStopReason: String? = null,
    val stoppedAt: String? = null,
    val hadWakeLockAtLastMarker: Boolean? = null,
    val wasForegroundAtLastMarker: Boolean? = null,
    val networkAtLastMarker: String? = null,
    val routeAtLastMarker: String? = null,
    val lastCrashClass: String? = null,
    val lastCrashMessage: String? = null,
    val lastCrashTopFrame: String? = null,
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
            lastHeartbeatAt = props.getProperty(KEY_LAST_HEARTBEAT_AT),
            lastServiceEvent = props.getProperty(KEY_LAST_SERVICE_EVENT),
            lastServiceEventAt = props.getProperty(KEY_LAST_SERVICE_EVENT_AT),
            lastForegroundStartedAt = props.getProperty(KEY_LAST_FOREGROUND_STARTED_AT),
            lastStopReason = props.getProperty(KEY_LAST_STOP_REASON),
            stoppedAt = props.getProperty(KEY_STOPPED_AT),
            hadWakeLockAtLastMarker = props.getProperty(KEY_WAKE_LOCK_HELD)?.toBooleanStrictOrNull(),
            wasForegroundAtLastMarker = props.getProperty(KEY_FOREGROUND)?.toBooleanStrictOrNull(),
            networkAtLastMarker = props.getProperty(KEY_NETWORK),
            routeAtLastMarker = props.getProperty(KEY_ROUTE),
            lastCrashClass = props.getProperty(KEY_LAST_CRASH_CLASS),
            lastCrashMessage = props.getProperty(KEY_LAST_CRASH_MESSAGE),
            lastCrashTopFrame = props.getProperty(KEY_LAST_CRASH_TOP_FRAME),
        )
    }

    fun markStarted(network: String = "unknown", route: String = "unknown", wakeLockHeld: Boolean = false, foreground: Boolean = false): String {
        val runId = newRunId()
        val now = Instant.now(clock).toString()
        writeProperties(
            mapOf(
                KEY_RUNNING to "true",
                KEY_RUN_ID to runId,
                KEY_STARTED_AT to now,
                KEY_LAST_KNOWN_STATUS to "running",
                KEY_LAST_SERVICE_EVENT to "proxy_started",
                KEY_LAST_SERVICE_EVENT_AT to now,
                KEY_WAKE_LOCK_HELD to wakeLockHeld.toString(),
                KEY_FOREGROUND to foreground.toString(),
                KEY_NETWORK to network.ifBlank { "unknown" },
                KEY_ROUTE to route.ifBlank { "unknown" },
            ),
        )
        return runId
    }

    fun markForegroundStarted() {
        val now = Instant.now(clock).toString()
        updateExisting(
            KEY_LAST_FOREGROUND_STARTED_AT to now,
            KEY_LAST_SERVICE_EVENT to "foreground_started",
            KEY_LAST_SERVICE_EVENT_AT to now,
            KEY_FOREGROUND to "true",
        )
    }

    fun markHeartbeat(network: String = "unknown", route: String = "unknown", wakeLockHeld: Boolean = false, foreground: Boolean = false) {
        val now = Instant.now(clock).toString()
        updateExisting(
            KEY_LAST_HEARTBEAT_AT to now,
            KEY_LAST_SERVICE_EVENT to "watchdog_heartbeat",
            KEY_LAST_SERVICE_EVENT_AT to now,
            KEY_WAKE_LOCK_HELD to wakeLockHeld.toString(),
            KEY_FOREGROUND to foreground.toString(),
            KEY_NETWORK to network.ifBlank { "unknown" },
            KEY_ROUTE to route.ifBlank { "unknown" },
        )
    }

    fun markServiceEvent(
        event: String,
        network: String = "unknown",
        route: String = "unknown",
        wakeLockHeld: Boolean = false,
        foreground: Boolean = false,
    ) {
        updateExisting(
            KEY_LAST_SERVICE_EVENT to event.ifBlank { "unknown" },
            KEY_LAST_SERVICE_EVENT_AT to Instant.now(clock).toString(),
            KEY_WAKE_LOCK_HELD to wakeLockHeld.toString(),
            KEY_FOREGROUND to foreground.toString(),
            KEY_NETWORK to network.ifBlank { "unknown" },
            KEY_ROUTE to route.ifBlank { "unknown" },
        )
    }

    fun markCrash(exceptionClass: String, message: String?, topFrame: String?) {
        markServiceEvent("uncaught_exception")
        updateExisting(
            KEY_LAST_CRASH_CLASS to exceptionClass.ifBlank { "unknown" },
            KEY_LAST_CRASH_MESSAGE to (message?.take(180)?.ifBlank { "no message" } ?: "no message"),
            KEY_LAST_CRASH_TOP_FRAME to (topFrame?.take(240) ?: "unknown"),
        )
    }

    fun markStopped(reason: String) {
        val now = Instant.now(clock).toString()
        updateExisting(
            KEY_RUNNING to "false",
            KEY_LAST_KNOWN_STATUS to "stopped",
            KEY_LAST_SERVICE_EVENT to "stopped",
            KEY_LAST_SERVICE_EVENT_AT to now,
            KEY_LAST_STOP_REASON to reason.ifBlank { "unknown" },
            KEY_STOPPED_AT to now,
            KEY_WAKE_LOCK_HELD to "false",
            KEY_FOREGROUND to "false",
        )
    }

    private fun updateExisting(vararg updates: Pair<String, String>) {
        val existing = readProperties()
        if (existing.isEmpty) return
        updates.forEach { (key, value) -> existing.setProperty(key, value) }
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
            markerFile.outputStream().use { props.store(it, "Siberian Empire Proxy proxy run marker") }
        }
    }

    companion object {
        private const val KEY_RUNNING = "proxy_running"
        private const val KEY_RUN_ID = "run_id"
        private const val KEY_STARTED_AT = "started_at"
        private const val KEY_LAST_KNOWN_STATUS = "last_known_status"
        private const val KEY_LAST_HEARTBEAT_AT = "last_heartbeat_at"
        private const val KEY_LAST_SERVICE_EVENT = "last_service_event"
        private const val KEY_LAST_SERVICE_EVENT_AT = "last_service_event_at"
        private const val KEY_LAST_FOREGROUND_STARTED_AT = "last_foreground_started_at"
        private const val KEY_LAST_STOP_REASON = "last_stop_reason"
        private const val KEY_STOPPED_AT = "stopped_at"
        private const val KEY_WAKE_LOCK_HELD = "wake_lock_held"
        private const val KEY_FOREGROUND = "foreground"
        private const val KEY_NETWORK = "network"
        private const val KEY_ROUTE = "route"
        private const val KEY_LAST_CRASH_CLASS = "last_crash_class"
        private const val KEY_LAST_CRASH_MESSAGE = "last_crash_message"
        private const val KEY_LAST_CRASH_TOP_FRAME = "last_crash_top_frame"
    }
}
