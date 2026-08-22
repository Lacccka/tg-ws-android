package com.flowseal.tgwsandroid.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class DirectFrontingConnectorTest {
    @Test
    fun normalTimeoutFallsBackToFrontingAndLearnsPreference() {
        val calls = mutableListOf<String>()
        val state = DirectFrontingPreferenceState()
        val connector =
            DirectFrontingConnector(
                normalConnect = { _, _, _, _ ->
                    calls += "normal"
                    throw SocketTimeoutException("connect timed out")
                },
                frontedConnect = { _, _, _, _, sni ->
                    calls += "fronted:$sni"
                    FakeWebSocket()
                },
                state = state,
            )

        val result = connector.connect(2, false, TARGET, DOMAIN, PATH, 2_000, 7L)

        assertTrue(result.fronted)
        assertFalse(result.frontingTriedFirst)
        assertEquals(listOf("normal", "fronted:sprinthost.ru"), calls)
        assertTrue(state.shouldTryFrontingFirst(KEY, 7L))
    }

    @Test
    fun learnedPreferenceTriesFrontingFirst() {
        val calls = mutableListOf<String>()
        val state = DirectFrontingPreferenceState().also { it.recordFrontingSuccess(KEY, 3L) }
        val connector =
            DirectFrontingConnector(
                normalConnect = { _, _, _, _ ->
                    calls += "normal"
                    FakeWebSocket()
                },
                frontedConnect = { _, _, _, _, _ ->
                    calls += "fronted"
                    FakeWebSocket()
                },
                state = state,
            )

        val result = connector.connect(2, false, TARGET, DOMAIN, PATH, 2_000, 3L)

        assertTrue(result.fronted)
        assertTrue(result.frontingTriedFirst)
        assertEquals(listOf("fronted"), calls)
    }

    @Test
    fun failedFrontingFirstThenNormalSuccessClearsPreference() {
        val calls = mutableListOf<String>()
        val state = DirectFrontingPreferenceState().also { it.recordFrontingSuccess(KEY, 11L) }
        val connector =
            DirectFrontingConnector(
                normalConnect = { _, _, _, _ ->
                    calls += "normal"
                    FakeWebSocket()
                },
                frontedConnect = { _, _, _, _, _ ->
                    calls += "fronted"
                    throw IOException("fronting blocked")
                },
                state = state,
            )

        val result = connector.connect(2, false, TARGET, DOMAIN, PATH, 2_000, 11L)

        assertFalse(result.fronted)
        assertTrue(result.frontingTriedFirst)
        assertEquals(listOf("fronted", "normal"), calls)
        assertFalse(state.shouldTryFrontingFirst(KEY, 11L))
    }

    @Test
    fun frontingFirstFailureAndNormalTimeoutDoesNotRetryFronting() {
        val calls = mutableListOf<String>()
        val state = DirectFrontingPreferenceState().also { it.recordFrontingSuccess(KEY, 5L) }
        val connector =
            DirectFrontingConnector(
                normalConnect = { _, _, _, _ ->
                    calls += "normal"
                    throw SocketTimeoutException("normal timed out")
                },
                frontedConnect = { _, _, _, _, _ ->
                    calls += "fronted"
                    throw IOException("fronting failed")
                },
                state = state,
            )

        val error = assertThrows<SocketTimeoutException> {
            connector.connect(2, false, TARGET, DOMAIN, PATH, 2_000, 5L)
        }

        assertEquals("normal timed out", error.message)
        assertEquals(listOf("fronted", "normal"), calls)
        assertEquals(1, error.suppressed.size)
    }

    @Test
    fun networkGenerationChangeDropsLearnedPreference() {
        val calls = mutableListOf<String>()
        val state = DirectFrontingPreferenceState().also { it.recordFrontingSuccess(KEY, 20L) }
        val connector =
            DirectFrontingConnector(
                normalConnect = { _, _, _, _ ->
                    calls += "normal"
                    FakeWebSocket()
                },
                frontedConnect = { _, _, _, _, _ ->
                    calls += "fronted"
                    FakeWebSocket()
                },
                state = state,
            )

        val result = connector.connect(2, false, TARGET, DOMAIN, PATH, 2_000, 21L)

        assertFalse(result.fronted)
        assertEquals(listOf("normal"), calls)
        assertFalse(state.shouldTryFrontingFirst(KEY, 21L))
    }

    @Test
    fun preferenceIsScopedToDcMediaAndTarget() {
        val calls = mutableListOf<String>()
        val state = DirectFrontingPreferenceState().also { it.recordFrontingSuccess(KEY, 9L) }
        val connector =
            DirectFrontingConnector(
                normalConnect = { _, _, _, _ ->
                    calls += "normal"
                    FakeWebSocket()
                },
                frontedConnect = { _, _, _, _, _ ->
                    calls += "fronted"
                    FakeWebSocket()
                },
                state = state,
            )

        val result = connector.connect(2, true, TARGET, "kws2-1.web.telegram.org", PATH, 2_000, 9L)

        assertFalse(result.fronted)
        assertEquals(listOf("normal"), calls)
    }

    @Test
    fun nonTimeoutNormalFailureDoesNotTryFronting() {
        val calls = mutableListOf<String>()
        val connector =
            DirectFrontingConnector(
                normalConnect = { _, _, _, _ ->
                    calls += "normal"
                    throw IOException("connection refused")
                },
                frontedConnect = { _, _, _, _, _ ->
                    calls += "fronted"
                    FakeWebSocket()
                },
            )

        assertThrows<IOException> {
            connector.connect(2, false, TARGET, DOMAIN, PATH, 2_000, 1L)
        }

        assertEquals(listOf("normal"), calls)
    }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit): T {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return error
            throw AssertionError("Expected ${T::class.java.simpleName}, got ${error::class.java.simpleName}", error)
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }

    private class FakeWebSocket : WebSocketBinaryStream {
        override fun send(data: ByteArray) = Unit
        override fun sendBatch(parts: List<ByteArray>) = Unit
        override fun recv(): ByteArray? = null
        override fun close() = Unit
    }

    companion object {
        private const val TARGET = "149.154.167.220"
        private const val DOMAIN = "kws2.web.telegram.org"
        private const val PATH = "/apiws"
        private val KEY = DirectFrontingRouteKey(2, false, TARGET)
    }
}
