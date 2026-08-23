package com.flowseal.tgwsandroid.service

import android.content.Context
import com.flowseal.tgwsandroid.BuildConfig
import com.flowseal.tgwsandroid.proxy.ProxyLogger
import com.flowseal.tgwsandroid.proxy.RawWebSocketConnector

/** Runtime state exposed without linking normal build types against Tor/IPtProxy classes. */
data class TorFallbackRuntimeSnapshot(
    val desired: Boolean = false,
    val running: Boolean = false,
    val ready: Boolean = false,
    val bootstrapProgress: Int = 0,
    val phase: String = "disabled",
    val lastError: String? = null,
)

/** Private-build implementation owns Snowflake + embedded Tor lifecycle. */
interface TorFallbackRuntime {
    val connector: RawWebSocketConnector
    fun onNetworkChanged(networkStatus: String)
    fun snapshot(): TorFallbackRuntimeSnapshot
    fun stop()
}

/**
 * Keeps main/debug/release/sideload source free of compile-time Tor dependencies.
 * The implementation class only exists in the privateSideload source set.
 */
object TorFallbackRuntimeLoader {
    private const val IMPLEMENTATION_CLASS = "com.flowseal.tgwsandroid.SnowflakeTorFallbackRuntime"

    fun create(context: Context, logger: ProxyLogger): TorFallbackRuntime? {
        if (!BuildConfig.SNOWFLAKE_TOR_PACKAGED) return null
        return runCatching {
            val clazz = Class.forName(IMPLEMENTATION_CLASS)
            val constructor = clazz.getConstructor(Context::class.java, ProxyLogger::class.java)
            constructor.newInstance(context.applicationContext, logger) as TorFallbackRuntime
        }.onFailure {
            logger.log("Tor/Snowflake fallback runtime unavailable: ${it.javaClass.simpleName}: ${it.message.orEmpty()}")
        }.getOrNull()
    }
}
