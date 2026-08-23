from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected exactly one match, got {count}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


# Production Tor fallback runtime: keep the gomobile Controller process-wide.
runtime = "app/src/privateSideload/java/com/flowseal/tgwsandroid/SnowflakeTorFallbackRuntime.kt"
replace_once(runtime, "import IPtProxy.Controller\n", "")
replace_once(runtime, "import IPtProxy.IPtProxy\n", "")
replace_once(runtime, "import java.io.File\n", "")
replace_once(
    runtime,
    "    @Volatile private var controller: Controller? = null\n",
    "    private val snowflakeTransportOwned = AtomicBoolean(false)\n",
)
replace_once(
    runtime,
    "        running = wanted.get() && (readyConnector.get() != null || task?.isDone == false || controller != null || torConnection != null),\n",
    "        running = wanted.get() && (readyConnector.get() != null || task?.isDone == false || snowflakeTransportOwned.get() || torConnection != null),\n",
)
replace_once(
    runtime,
    '        val stateDir = File(appContext.noBackupFilesDir, "snowflake-pt-fallback").apply { mkdirs() }\n',
    "",
)
replace_once(
    runtime,
    '''        val newController = Controller(stateDir.absolutePath, true, false, "INFO", transportEvents).also {\n            it.snowflakeBrokerUrl = SNOWFLAKE_BROKER_URL\n            it.snowflakeFrontDomains = SNOWFLAKE_FRONT_DOMAINS\n            it.snowflakeIceServers = SNOWFLAKE_ICE_SERVERS\n            it.snowflakeAmpCacheUrl = ""\n            it.snowflakeSqsUrl = ""\n            it.snowflakeSqsCreds = ""\n        }\n        controller = newController\n        newController.start(IPtProxy.Snowflake, null)\n        val ptPort = newController.port(IPtProxy.Snowflake).toInt()\n        check(ptPort in 1..65535) { "Snowflake listener returned invalid port: $ptPort" }\n        logger.log("Tor/Snowflake PT ready on 127.0.0.1:$ptPort")\n''',
    '''        val ptPort = SharedSnowflakeController.start(\n            context = appContext,\n            owner = SNOWFLAKE_OWNER,\n            events = transportEvents,\n        ) { sharedController ->\n            sharedController.snowflakeBrokerUrl = SNOWFLAKE_BROKER_URL\n            sharedController.snowflakeFrontDomains = SNOWFLAKE_FRONT_DOMAINS\n            sharedController.snowflakeIceServers = SNOWFLAKE_ICE_SERVERS\n            sharedController.snowflakeAmpCacheUrl = ""\n            sharedController.snowflakeSqsUrl = ""\n            sharedController.snowflakeSqsCreds = ""\n        }\n        snowflakeTransportOwned.set(true)\n        logger.log("Tor/Snowflake PT ready on 127.0.0.1:$ptPort using shared process controller")\n''',
)
replace_once(
    runtime,
    '''        val activeController = controller\n        controller = null\n        if (activeController != null) runCatching { activeController.stop(IPtProxy.Snowflake) }\n''',
    '''        if (snowflakeTransportOwned.getAndSet(false)) {\n            runCatching { SharedSnowflakeController.stop(SNOWFLAKE_OWNER) }\n                .onFailure { logger.log("Tor/Snowflake shared controller stop failed: ${it.javaClass.simpleName}: ${sanitize(it.message)}") }\n        }\n''',
)
replace_once(
    runtime,
    "    companion object {\n        private const val TOR_SERVICE_BIND_TIMEOUT_SECONDS = 10L\n",
    "    companion object {\n        private const val SNOWFLAKE_OWNER = \"production-fallback\"\n        private const val TOR_SERVICE_BIND_TIMEOUT_SECONDS = 10L\n",
)


# Real Telegram proof service: use the same process-wide Controller instead of creating another one.
service = "app/src/privateSideload/java/com/flowseal/tgwsandroid/SnowflakeTorProxyService.kt"
replace_once(service, "import IPtProxy.Controller\n", "")
replace_once(service, "import IPtProxy.IPtProxy\n", "")
replace_once(service, "import java.io.File\n", "")
replace_once(
    service,
    "    private var controller: Controller? = null\n",
    "    private var snowflakeTransportOwned: Boolean = false\n",
)
replace_once(
    service,
    '            val stateDir = File(noBackupFilesDir, "snowflake-pt-proxy").apply { mkdirs() }\n',
    "",
)
replace_once(
    service,
    '''            val newController = Controller(\n                stateDir.absolutePath,\n                true,\n                false,\n                "INFO",\n                transportEvents,\n            ).also {\n                it.snowflakeBrokerUrl = SNOWFLAKE_BROKER_URL\n                it.snowflakeFrontDomains = SNOWFLAKE_FRONT_DOMAINS\n                it.snowflakeIceServers = SNOWFLAKE_ICE_SERVERS\n                it.snowflakeAmpCacheUrl = ""\n                it.snowflakeSqsUrl = ""\n                it.snowflakeSqsCreds = ""\n            }\n            controller = newController\n            newController.start(IPtProxy.Snowflake, null)\n            val ptPort = newController.port(IPtProxy.Snowflake).toInt()\n            check(ptPort in 1..65535) { "Snowflake listener returned invalid port: $ptPort" }\n''',
    '''            val ptPort = SharedSnowflakeController.start(\n                context = applicationContext,\n                owner = SNOWFLAKE_OWNER,\n                events = transportEvents,\n            ) { sharedController ->\n                sharedController.snowflakeBrokerUrl = SNOWFLAKE_BROKER_URL\n                sharedController.snowflakeFrontDomains = SNOWFLAKE_FRONT_DOMAINS\n                sharedController.snowflakeIceServers = SNOWFLAKE_ICE_SERVERS\n                sharedController.snowflakeAmpCacheUrl = ""\n                sharedController.snowflakeSqsUrl = ""\n                sharedController.snowflakeSqsCreds = ""\n            }\n            snowflakeTransportOwned = true\n''',
)
replace_once(
    service,
    '''        val activeController = controller\n        controller = null\n        if (activeController != null) runCatching { activeController.stop(IPtProxy.Snowflake) }\n''',
    '''        if (snowflakeTransportOwned) {\n            snowflakeTransportOwned = false\n            runCatching { SharedSnowflakeController.stop(SNOWFLAKE_OWNER) }\n                .onFailure { SnowflakeTorProxyTestStatus.log("Shared Snowflake stop failed: ${it.javaClass.simpleName}: ${sanitize(it.message)}") }\n        }\n''',
)
replace_once(
    service,
    "    companion object {\n        const val ACTION_START = \"com.flowseal.tgwsandroid.action.START_SNOWFLAKE_TOR_PROXY_TEST\"\n",
    "    companion object {\n        private const val SNOWFLAKE_OWNER = \"real-telegram-proof\"\n        const val ACTION_START = \"com.flowseal.tgwsandroid.action.START_SNOWFLAKE_TOR_PROXY_TEST\"\n",
)


# Standalone E2E diagnostic: sequential lease of the same Controller.
e2e = "app/src/privateSideload/java/com/flowseal/tgwsandroid/SnowflakeTorE2eActivity.kt"
replace_once(e2e, "import IPtProxy.Controller\n", "")
replace_once(e2e, "import IPtProxy.IPtProxy\n", "")
replace_once(e2e, "import java.io.File\n", "")
replace_once(
    e2e,
    '''    @Volatile\n    private var activeController: Controller? = null\n''',
    '''    @Volatile\n    private var activeSnowflakeTransport: Boolean = false\n''',
)
replace_once(e2e, "            var controller: Controller? = null\n", "            var snowflakeTransportOwned = false\n")
replace_once(
    e2e,
    '                val stateDir = File(noBackupFilesDir, "snowflake-pt").apply { mkdirs() }\n',
    "",
)
replace_once(
    e2e,
    '''                controller = Controller(\n                    stateDir.absolutePath,\n                    true,\n                    false,\n                    "INFO",\n                    transportEvents,\n                ).also {\n                    it.snowflakeBrokerUrl = SNOWFLAKE_BROKER_URL\n                    it.snowflakeFrontDomains = SNOWFLAKE_FRONT_DOMAINS\n                    it.snowflakeIceServers = SNOWFLAKE_ICE_SERVERS\n                    it.snowflakeAmpCacheUrl = ""\n                    it.snowflakeSqsUrl = ""\n                    it.snowflakeSqsCreds = ""\n                }\n                activeController = controller\n\n                val ptStartMs = measureTimeMillis {\n                    controller.start(IPtProxy.Snowflake, null)\n                }\n                val ptPort = controller.port(IPtProxy.Snowflake).toInt()\n                check(ptPort in 1..65535) { "Snowflake listener returned invalid port: $ptPort" }\n''',
    '''                var ptPort = 0\n                val ptStartMs = measureTimeMillis {\n                    ptPort = SharedSnowflakeController.start(\n                        context = applicationContext,\n                        owner = SNOWFLAKE_OWNER,\n                        events = transportEvents,\n                    ) { sharedController ->\n                        sharedController.snowflakeBrokerUrl = SNOWFLAKE_BROKER_URL\n                        sharedController.snowflakeFrontDomains = SNOWFLAKE_FRONT_DOMAINS\n                        sharedController.snowflakeIceServers = SNOWFLAKE_ICE_SERVERS\n                        sharedController.snowflakeAmpCacheUrl = ""\n                        sharedController.snowflakeSqsUrl = ""\n                        sharedController.snowflakeSqsCreds = ""\n                    }\n                }\n                snowflakeTransportOwned = true\n                activeSnowflakeTransport = true\n''',
)
replace_once(
    e2e,
    '''                cleanupTransport(connection, controller)\n                activeConnection = null\n                activeController = null\n''',
    '''                cleanupTransport(connection, snowflakeTransportOwned)\n                activeConnection = null\n                activeSnowflakeTransport = false\n''',
)
replace_once(
    e2e,
    '''    private fun cleanupActiveTransport() {\n        val connection = activeConnection\n        val controller = activeController\n        activeConnection = null\n        activeController = null\n        cleanupTransport(connection, controller)\n    }\n\n    private fun cleanupTransport(connection: ServiceConnection?, controller: Controller?) {\n        if (connection != null) {\n            runCatching { unbindService(connection) }\n        }\n        runCatching { stopService(Intent(this, TorService::class.java)) }\n        if (controller != null) {\n            runCatching { controller.stop(IPtProxy.Snowflake) }\n        }\n    }\n''',
    '''    private fun cleanupActiveTransport() {\n        val connection = activeConnection\n        val snowflakeOwned = activeSnowflakeTransport\n        activeConnection = null\n        activeSnowflakeTransport = false\n        cleanupTransport(connection, snowflakeOwned)\n    }\n\n    private fun cleanupTransport(connection: ServiceConnection?, snowflakeOwned: Boolean) {\n        if (connection != null) {\n            runCatching { unbindService(connection) }\n        }\n        runCatching { stopService(Intent(this, TorService::class.java)) }\n        if (snowflakeOwned) {\n            runCatching { SharedSnowflakeController.stop(SNOWFLAKE_OWNER) }\n        }\n    }\n''',
)
replace_once(
    e2e,
    "    companion object {\n        private const val SNOWFLAKE_BROKER_URL = \"https://1098762253.rsc.cdn77.org/\"\n",
    "    companion object {\n        private const val SNOWFLAKE_OWNER = \"snowflake-tor-e2e\"\n        private const val SNOWFLAKE_BROKER_URL = \"https://1098762253.rsc.cdn77.org/\"\n",
)

print("Applied process-wide SharedSnowflakeController migration")
