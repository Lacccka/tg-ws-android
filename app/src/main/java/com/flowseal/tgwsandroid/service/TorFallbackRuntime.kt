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
 *
 * C Tor cannot be safely torn down and started again inside the same Android
 * process because libtor keeps process-global native state. Keep exactly one
 * private runtime instance for the process lifetime once it has been created.
 */
object TorFallbackRuntimeLoader {
    private const val IMPLEMENTATION_CLASS = "com.flowseal.tgwsandroid.SnowflakeTorFallbackRuntime"
    private val lock = Any()

    @Volatile
    private var sharedRuntime: TorFallbackRuntime? = null

    fun create(context: Context, logger: ProxyLogger): TorFallbackRuntime? {
        if (!BuildConfig.SNOWFLAKE_TOR_PACKAGED) return null
        sharedRuntime?.let { return it }

        return synchronized(lock) {
            sharedRuntime?.let { return@synchronized it }
            runCatching {
                val clazz = Class.forName(IMPLEMENTATION_CLASS)
                val constructor = clazz.getConstructor(Context::class.java, ProxyLogger::class.java)
                (constructor.newInstance(context.applicationContext, logger) as TorFallbackRuntime).also {
                    sharedRuntime = it
                }
            }.onFailure {
                logger.log("Tor/Snowflake fallback runtime unavailable: ${it.javaClass.simpleName}: ${it.message.orEmpty()}")
            }.getOrNull()
        }
    }
}
