package com.flowseal.tgwsandroid

import IPtProxy.Controller
import IPtProxy.IPtProxy
import IPtProxy.OnTransportEvents
import android.content.Context
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-wide owner of the gomobile-backed IPtProxy Controller.
 *
 * IPtProxy explicitly requires consumers to instantiate Controller only once and
 * retain it. Recreating Controller after a proxy/service restart can re-run global
 * Go transport/log initialization and, when NewController returns nil, surface as
 * gomobile's "trackGoRef called with Java refnum 41" error (41 is Seq's null ref).
 *
 * Different private diagnostics may lease Snowflake sequentially, but never run it
 * concurrently. The underlying Controller remains alive for the Android process.
 */
internal object SharedSnowflakeController {
    private const val STATE_DIR_NAME = "snowflake-pt-shared"

    private val lock = Any()
    private val eventSink = AtomicReference<OnTransportEvents?>(null)

    @Volatile
    private var controller: Controller? = null

    @Volatile
    private var activeOwner: String? = null

    fun start(
        context: Context,
        owner: String,
        events: OnTransportEvents,
        configure: (Controller) -> Unit,
    ): Int = synchronized(lock) {
        require(owner.isNotBlank()) { "Snowflake owner must not be blank" }

        val currentOwner = activeOwner
        check(currentOwner == null || currentOwner == owner) {
            "Snowflake transport is already owned by $currentOwner"
        }

        val activeController = controller ?: createController(context.applicationContext).also {
            controller = it
        }

        val existingPort = activeController.port(IPtProxy.Snowflake).toInt()
        if (currentOwner == owner && existingPort in 1..65535) {
            eventSink.set(events)
            return@synchronized existingPort
        }

        eventSink.set(events)
        configure(activeController)

        try {
            // IPtProxy's API uses an empty string for "no upstream proxy".
            // Do not pass null through gomobile bindings.
            activeController.start(IPtProxy.Snowflake, "")
            val port = activeController.port(IPtProxy.Snowflake).toInt()
            check(port in 1..65535) { "Snowflake listener returned invalid port: $port" }
            activeOwner = owner
            port
        } catch (error: Throwable) {
            runCatching { activeController.stop(IPtProxy.Snowflake) }
            activeOwner = null
            eventSink.compareAndSet(events, null)
            throw error
        }
    }

    fun stop(owner: String): Boolean = synchronized(lock) {
        if (activeOwner != owner) return@synchronized false

        try {
            controller?.stop(IPtProxy.Snowflake)
        } finally {
            activeOwner = null
            eventSink.set(null)
        }
        true
    }

    fun owner(): String? = activeOwner

    private fun createController(context: Context): Controller {
        val stateDir = File(context.noBackupFilesDir, STATE_DIR_NAME).apply { mkdirs() }
        return Controller(
            stateDir.absolutePath,
            true,
            false,
            "INFO",
            object : OnTransportEvents {
                override fun connected(name: String?) {
                    eventSink.get()?.let { sink -> runCatching { sink.connected(name) } }
                }

                override fun error(name: String?, error: Exception?) {
                    eventSink.get()?.let { sink -> runCatching { sink.error(name, error) } }
                }

                override fun stopped(name: String?, error: Exception?) {
                    eventSink.get()?.let { sink -> runCatching { sink.stopped(name, error) } }
                }
            },
        )
    }
}
