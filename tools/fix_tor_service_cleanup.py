from pathlib import Path

path = Path("app/src/main/java/com/flowseal/tgwsandroid/service/ProxyForegroundService.kt")
text = path.read_text()

def replace_once(old: str, new: str, label: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, got {count}")
    text = text.replace(old, new, 1)

replace_once(
'''                proxyServer = null
                State.setLiveStatsProvider(null)
''',
'''                proxyServer = null
                torFallbackRuntime = null
                runCatching { runtime?.stop() }
                    .onFailure { State.addLog("Tor/Snowflake cleanup after start failure failed: ${it.javaClass.simpleName}: ${it.message.orEmpty()}", LogSeverity.WARN, "service") }
                State.setLiveStatsProvider(null)
''',
"partial start cleanup",
)

replace_once(
'''        val torRuntime = synchronized(lock) {
            torFallbackRuntime.also { torFallbackRuntime = null }
        }
        torRuntime?.stop()
        if (server != null) {
            try {
                server.stop()
                State.updateStats(server.stats())
            } catch (error: Throwable) {
                State.addLog("Proxy stop failed: ${error.message ?: error::class.java.simpleName}", LogSeverity.WARN, "service")
            }
        }
''',
'''        val torRuntime = synchronized(lock) {
            torFallbackRuntime.also { torFallbackRuntime = null }
        }
        if (server != null) {
            try {
                server.stop()
                State.updateStats(server.stats())
            } catch (error: Throwable) {
                State.addLog("Proxy stop failed: ${error.message ?: error::class.java.simpleName}", LogSeverity.WARN, "service")
            }
        }
        runCatching { torRuntime?.stop() }
            .onFailure { State.addLog("Tor/Snowflake stop failed: ${it.javaClass.simpleName}: ${it.message.orEmpty()}", LogSeverity.WARN, "service") }
''',
"shutdown order",
)

replace_once(
'''            "cfPoolLastDomainByKey=${compactMap(stats.cfPoolLastDomainByKey)} " +
            "directHealth=${stats.directHealthState} route=${stats.effectiveRouteMode} lastRoute=${stats.lastRouteUsed ?: "none"}"
''',
'''            "cfPoolLastDomainByKey=${compactMap(stats.cfPoolLastDomainByKey)} " +
            "torSnowflake=${stats.torSnowflakeSuccesses}/${stats.torSnowflakeAttempts}/${stats.torSnowflakeFailures} " +
            "torUnavailable=${stats.torSnowflakeUnavailable} " +
            "directHealth=${stats.directHealthState} route=${stats.effectiveRouteMode} lastRoute=${stats.lastRouteUsed ?: "none"}"
''',
"compact Tor stats",
)

path.write_text(text)
