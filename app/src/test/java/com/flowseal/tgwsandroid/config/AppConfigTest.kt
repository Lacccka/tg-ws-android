package com.flowseal.tgwsandroid.config

import com.flowseal.tgwsandroid.proxy.NetworkRouteMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Parity smoke tests for upstream `utils/default_config.py::default_tray_config`
 * field names consumed by `utils/tray_common.py::apply_proxy_config`.
 */
class AppConfigTest {
    @Test
    fun parsesRuntimeConfigKeys() {
        val config =
            AppConfig.fromJson(
                JSONObject(
                    """
                    {
                      "host": "127.0.0.1",
                      "port": 1443,
                      "secret": "0123456789abcdef0123456789abcdef",
                      "dc_ip": ["2:149.154.167.220", "3:149.154.167.220", "4:149.154.167.220"],
                      "verbose": true,
                      "autostart": true,
                      "buf_kb": 512,
                      "pool_size": 8,
                      "log_max_mb": 6.5,
                      "check_updates": false,
                      "cfproxy": false,
                      "cfproxy_user_domain": "one.example, two.example",
                      "cfproxy_worker_domain": ["worker.example"],
                      "appearance": "dark",
                      "route_mode": "cf_first"
                    }
                    """.trimIndent(),
                ),
            )

        assertEquals("127.0.0.1", config.host)
        assertEquals(1443, config.port)
        assertEquals("0123456789abcdef0123456789abcdef", config.secret)
        assertEquals(listOf("2:149.154.167.220", "3:149.154.167.220", "4:149.154.167.220"), config.dcIp)
        assertEquals(true, config.verbose)
        assertEquals(true, config.autostart)
        assertEquals(512, config.bufKb)
        assertEquals(8, config.poolSize)
        assertEquals(6.5, config.logMaxMb, 0.0)
        assertEquals(false, config.checkUpdates)
        assertEquals(false, config.cfproxy)
        assertEquals(listOf("one.example", "two.example"), config.cfproxyUserDomain)
        assertEquals(listOf("worker.example"), config.cfproxyWorkerDomain)
        assertEquals(Appearance.DARK, config.appearance)
        assertEquals(NetworkRouteMode.CF_FIRST, config.routeMode)
    }

    @Test
    fun invalidRouteModeFallsBackToAuto() {
        val config = AppConfig.fromJson(JSONObject("""{"route_mode":"bad"}"""))

        assertEquals(NetworkRouteMode.AUTO, config.routeMode)
    }
}
