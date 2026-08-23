package com.flowseal.tgwsandroid

import org.json.JSONObject
import java.lang.reflect.InvocationTargetException

/**
 * Reflection bridge for the optional private-sideload libXray.aar.
 *
 * The pinned Android artifact is XTLS/libXray v26.7.28. That release still uses
 * Invoke apiVersion=1 and runXrayFromJson/configJSON. The repository's current
 * main branch has already moved to a different Invoke contract, so keep this
 * adapter explicitly versioned instead of following README main implicitly.
 */
internal object LibXrayCompat {
    const val PINNED_TAG = "v26.7.28"
    private const val API_VERSION = 1
    private const val CLASS_NAME = "libXray.LibXray"
    private val invokeLock = Any()

    data class Response(
        val success: Boolean,
        val data: Any?,
        val error: String,
        val raw: String,
    )

    fun isAvailable(): Boolean = runCatching {
        resolveInvokeMethod()
    }.isSuccess

    fun version(): String? = runCatching {
        val response = invoke("xrayVersion", JSONObject())
        if (!response.success) return@runCatching null
        (response.data as? JSONObject)?.optString("version")?.takeIf { it.isNotBlank() }
    }.getOrNull()

    fun convertShareLink(link: String): JSONObject {
        val response = invoke(
            method = "convertShareLinksToXrayJson",
            payload = JSONObject().put("text", link),
        )
        checkSuccess(response, "convertShareLinksToXrayJson")
        return response.data as? JSONObject
            ?: throw IllegalStateException("libXray returned non-object config data")
    }

    fun runFromJson(config: JSONObject) {
        val response = invoke(
            method = "runXrayFromJson",
            payload = JSONObject().put("configJSON", config.toString()),
        )
        checkSuccess(response, "runXrayFromJson")
    }

    fun stopBestEffort(): String? = runCatching {
        invoke("stopXray", JSONObject()).error.takeIf { it.isNotBlank() }
    }.getOrNull()

    fun runningState(): Boolean? = runCatching {
        val response = invoke("getXrayState", JSONObject())
        if (!response.success) return@runCatching null
        (response.data as? JSONObject)?.optBoolean("running")
    }.getOrNull()

    private fun invoke(method: String, payload: JSONObject): Response = synchronized(invokeLock) {
        val request = JSONObject()
            .put("apiVersion", API_VERSION)
            .put("method", method)
            .put("payload", payload)
            .toString()

        val reflectMethod = resolveInvokeMethod()
        val raw = try {
            reflectMethod.invoke(null, request) as? String
                ?: throw IllegalStateException("libXray Invoke returned a non-string result")
        } catch (error: InvocationTargetException) {
            throw error.targetException ?: error
        }

        val json = JSONObject(raw)
        Response(
            success = json.optBoolean("success", false),
            data = if (json.isNull("data")) null else json.opt("data"),
            error = json.optString("error"),
            raw = raw,
        )
    }

    private fun resolveInvokeMethod() =
        Class.forName(CLASS_NAME).getMethod("invoke", String::class.java)

    private fun checkSuccess(response: Response, method: String) {
        if (response.success) return
        val detail = response.error.ifBlank { "unknown libXray error" }
        throw IllegalStateException("$method failed: $detail")
    }
}
