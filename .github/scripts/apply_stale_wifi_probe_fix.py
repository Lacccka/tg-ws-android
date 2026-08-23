from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected exactly one match, got {count}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


proxy = "app/src/main/java/com/flowseal/tgwsandroid/proxy/ProxyServer.kt"

replace_once(
    proxy,
    '''    private val routeGeneration = AtomicLong(0)\n    private val networkStateMonitor = Object()\n''',
    '''    private val routeGeneration = AtomicLong(0)\n    /** Serializes network-route commits with asynchronous direct-health promotions. */\n    private val routePolicyMonitor = Object()\n    private val networkStateMonitor = Object()\n''',
)

replace_once(
    proxy,
    '''    private fun applyNetworkRoute(networkStatus: String, immediate: Boolean): RouteChangeResult {\n        val normalized = networkStatus.ifBlank { "unknown" }\n''',
    '''    private fun applyNetworkRoute(networkStatus: String, immediate: Boolean): RouteChangeResult = synchronized(routePolicyMonitor) {\n        val normalized = networkStatus.ifBlank { "unknown" }\n''',
)

replace_once(
    proxy,
    '''        maybeStartAutoWifiDirectProbe(normalized, previousNetworkStatus)\n        return result\n    }\n\n    private fun isMobileGenerationRecoveryTransition''',
    '''        maybeStartAutoWifiDirectProbe(normalized, previousNetworkStatus)\n        result\n    }\n\n    private fun isMobileGenerationRecoveryTransition''',
)

old_promote = '''            onPromote = {\n                val before = effectiveRouteMode()\n                val result = applyEffectiveRouteMode(\n                    NetworkRouteMode.DIRECT_FIRST,\n                    "direct health probe success",\n                    currentNetworkStatus,\n                    source = "direct-health",\n                )\n                if (result.changed) {\n                    directRouteHealth.recordPromotion()\n                    logger.log("direct promoted: ${before.configValue} -> ${NetworkRouteMode.DIRECT_FIRST.configValue}")\n                }\n            },\n'''
new_promote = '''            onPromote = {\n                commitAutoWifiDirectPromotionIfStillValid()\n            },\n'''
replace_once(proxy, old_promote, new_promote)

anchor = '''    private fun maybeStartAutoWifiDirectProbe(\n        networkStatus: String,\n        previousNetworkStatus: String = "unknown",\n    ) {\n'''
helper = '''    /**\n     * Commits an asynchronous Wi-Fi health promotion only if the route policy is\n     * still the same one for which the probe was started. The network callback\n     * path uses the same monitor, closing the canPromote -> onPromote race where\n     * a Wi-Fi probe could otherwise repromote DIRECT_FIRST after MOBILE arrived.\n     */\n    private fun commitAutoWifiDirectPromotionIfStillValid(): RouteChangeResult? = synchronized(routePolicyMonitor) {\n        val network = currentNetworkStatus\n        if (\n            !running.get() ||\n            routeState.configuredRouteMode != NetworkRouteMode.AUTO ||\n            !isWifi(network) ||\n            effectiveRouteMode() != NetworkRouteMode.CF_FIRST\n        ) {\n            logger.log(\n                "direct promotion discarded: stale Wi-Fi health probe " +\n                    "network=$network route=${effectiveRouteMode().configValue}",\n            )\n            return@synchronized null\n        }\n\n        val before = effectiveRouteMode()\n        val result = applyEffectiveRouteMode(\n            NetworkRouteMode.DIRECT_FIRST,\n            "direct health probe success",\n            network,\n            source = "direct-health",\n        )\n        if (result.changed) {\n            directRouteHealth.recordPromotion()\n            logger.log("direct promoted: ${before.configValue} -> ${NetworkRouteMode.DIRECT_FIRST.configValue}")\n        }\n        result\n    }\n\n    private fun maybeStartAutoWifiDirectProbe(\n        networkStatus: String,\n        previousNetworkStatus: String = "unknown",\n    ) {\n'''
replace_once(proxy, anchor, helper)


test = "app/src/test/java/com/flowseal/tgwsandroid/proxy/ProxyServerTest.kt"
anchor_test = '''    @Test\n    fun networkChangeWifiToMobileUpdatesEffectiveRouteToCfFirst() {\n'''
insert_test = '''    @Test\n    fun staleWifiHealthPromotionCallbackCannotRepromoteAfterMobileTransition() {\n        val server = FakeTcpServerTransport()\n        val logs = CopyOnWriteArrayList<String>()\n        val proxy = newProxy(\n            server = server,\n            config = baseConfig().copy(routeMode = NetworkRouteMode.AUTO, networkStatus = "mobile", poolSize = 0),\n            logger = ProxyLogger { logs.add(it) },\n        )\n\n        proxy.start()\n        proxy.applyEffectiveRouteMode(NetworkRouteMode.DIRECT_FIRST, "test promoted", "Wi-Fi")\n        proxy.applyNetworkRouteImmediately("mobile")\n        assertEquals(NetworkRouteMode.CF_FIRST.configValue, proxy.stats().effectiveRouteMode)\n\n        val method = ProxyServer::class.java.getDeclaredMethod("commitAutoWifiDirectPromotionIfStillValid")\n        method.isAccessible = true\n        val staleResult = method.invoke(proxy)\n        proxy.stop()\n\n        assertNull("stale Wi-Fi health callback must be discarded on mobile", staleResult)\n        assertEquals(NetworkRouteMode.CF_FIRST.configValue, proxy.stats().effectiveRouteMode)\n        assertTrue(logs.any { it.contains("direct promotion discarded: stale Wi-Fi health probe") && it.contains("network=mobile") })\n        assertFalse(logs.dropWhile { !it.contains("network=mobile") }.any { it.contains("direct promoted:") })\n    }\n\n    @Test\n    fun networkChangeWifiToMobileUpdatesEffectiveRouteToCfFirst() {\n'''
replace_once(test, anchor_test, insert_test)
