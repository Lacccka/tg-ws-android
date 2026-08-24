package com.flowseal.tgwsandroid.proxy

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class ProxyServerTest {
    @Test
    fun validHandshakeConnectsToDcSendsRelayInitThenRunsBridge() {
        val vector = handshakeVector("abridged_dc2")
        val client = FakeTcpClientTransport(vector.getString("handshake_hex").hexToBytes())
        val server = FakeTcpServerTransport()
        val events = CopyOnWriteArrayList<String>()
        val webSocket = FakeWebSocketBinaryStream(events)
        val connector = RecordingConnector(webSocket)
        val runner =
            ProxyBridgeRunner { _, _, cryptoContext, splitter, counters ->
                assertTrue(cryptoContext.decryptFromClient(ByteArray(0)).isEmpty())
                assertTrue(splitter.split(ByteArray(0)).isEmpty())
                counters.recordUp(7)
                counters.recordDown(11)
                events.add("bridge")
            }
        val proxy = newProxy(server, connector, runner)

        proxy.start()
        server.enqueue(client)
        waitUntil { events.contains("bridge") }
        waitUntil { client.closed }
        proxy.stop()

        assertEquals(listOf("203.0.113.2"), connector.targetHosts)
        assertEquals(listOf("kws2.web.telegram.org"), connector.domains)
        assertEquals(listOf(ProxyServer.DEFAULT_WS_PATH), connector.paths)
        assertEquals(listOf("send", "bridge", "close"), events.toList())
        assertEquals(MtprotoHandshake.HANDSHAKE_LEN, webSocket.sent.single().size)
        assertArrayEquals(RelayInit.PROTO_TAG_ABRIDGED, relayInitPlainTail(webSocket.sent.single()).copyOfRange(0, 4))
        assertTrue(client.closed)
        assertEquals(1L, proxy.stats().connectionsTotal)
        assertEquals(0, proxy.stats().connectionsActive)
        assertEquals(0L, proxy.stats().connectionsBad)
        assertEquals(7L, proxy.stats().bytesUp)
        assertEquals(11L, proxy.stats().bytesDown)
    }

    @Test
    fun bridgeCompletionLogsSessionEndRouteAndByteCounters() {
        val client = FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes())
        val server = FakeTcpServerTransport()
        val logs = CopyOnWriteArrayList<String>()
        val proxy = newProxy(
            server = server,
            connector = RecordingConnector(FakeWebSocketBinaryStream()),
            runner = ProxyBridgeRunner { _, _, _, _, counters ->
                counters.recordUp(13)
                counters.recordDown(17)
                counters.finish("completed")
            },
            logger = ProxyLogger { logs.add(it) },
        )

        proxy.start()
        server.enqueue(client)
        waitUntil { logs.any { it.contains("session ended") } }
        proxy.stop()

        val endLog = logs.first { it.contains("session ended") }
        assertTrue(endLog.contains("fake-client session ended: DC2"))
        assertTrue(endLog.contains("media=false"))
        assertTrue(endLog.contains("route=direct-cold"))
        assertTrue(endLog.contains("bytesUp=13"))
        assertTrue(endLog.contains("bytesDown=17"))
        assertTrue(endLog.contains("reason=completed"))
    }


    @Test
    fun eofExceptionSessionEndIncrementsRemoteEofButNotUnexpectedErrors() {
        val client = FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes())
        val server = FakeTcpServerTransport()
        val logs = CopyOnWriteArrayList<String>()
        val proxy = newProxy(
            server = server,
            runner = ProxyBridgeRunner { _, _, _, _, _ -> throw EOFException("unexpected end of WebSocket frame") },
            logger = ProxyLogger { logs.add(it) },
        )

        proxy.start()
        server.enqueue(client)
        waitUntil { client.closed }
        proxy.stop()

        val stats = proxy.stats()
        assertEquals(1L, stats.sessionEof)
        assertEquals(1L, stats.sessionRemoteEof)
        assertEquals(1L, stats.sessionRemoteEofShort)
        assertEquals(0L, stats.sessionRemoteIdleEof)
        assertEquals(0L, stats.sessionUnexpectedErrors)
        assertEquals("direct-cold", stats.lastRemoteEofRoute)
        assertEquals(2, stats.lastRemoteEofDc)
        assertFalse(stats.lastRemoteEofMedia ?: true)
        assertTrue(stats.lastRemoteEofTimeMs > 0L)
        assertTrue(logs.any { it.contains("session ended") && it.contains("reason=remote_eof") })
        assertFalse(logs.any { it.contains("session ended") && it.contains("reason=exception: EOFException") })
    }

    @Test
    fun eofExceptionAtIdleThresholdIsClassifiedAsRemoteIdleEof() {
        val classification = classifySessionEnd(
            rawReason = "exception: EOFException: unexpected end of WebSocket frame",
            error = EOFException("unexpected end of WebSocket frame"),
            durationMs = SESSION_REMOTE_IDLE_EOF_MIN_DURATION_MS,
        )

        assertEquals("remote_idle_eof", classification.reason)
        assertTrue(classification.remoteEof)
        assertTrue(classification.remoteIdleEof)
    }

    @Test
    fun shortEofExceptionIsClassifiedAsRemoteEof() {
        val classification = classifySessionEnd(
            rawReason = "exception: EOFException: unexpected end of WebSocket frame",
            error = EOFException("unexpected end of WebSocket frame"),
            durationMs = SESSION_REMOTE_IDLE_EOF_MIN_DURATION_MS - 1,
        )

        assertEquals("remote_eof", classification.reason)
        assertTrue(classification.remoteEof)
        assertFalse(classification.remoteIdleEof)
    }

    @Test
    fun clientClosedSessionEndIsNotRemoteEof() {
        val client = FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes())
        val server = FakeTcpServerTransport()
        val proxy = newProxy(
            server = server,
            runner = ProxyBridgeRunner { _, _, _, _, counters -> counters.finish("client closed") },
        )

        proxy.start()
        server.enqueue(client)
        waitUntil { client.closed }
        proxy.stop()

        val stats = proxy.stats()
        assertEquals(1L, stats.sessionClientClosed)
        assertEquals(0L, stats.sessionEof)
        assertEquals(0L, stats.sessionRemoteEof)
        assertEquals(0L, stats.sessionUnexpectedErrors)
    }

    @Test
    fun timeoutSessionEndIsNotRemoteEof() {
        val client = FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes())
        val server = FakeTcpServerTransport()
        val proxy = newProxy(
            server = server,
            runner = ProxyBridgeRunner { _, _, _, _, counters -> counters.finish("exception: SocketTimeoutException: read timed out") },
        )

        proxy.start()
        server.enqueue(client)
        waitUntil { client.closed }
        proxy.stop()

        val stats = proxy.stats()
        assertEquals(1L, stats.sessionTimeouts)
        assertEquals(0L, stats.sessionEof)
        assertEquals(0L, stats.sessionRemoteEof)
        assertEquals(0L, stats.sessionUnexpectedErrors)
    }

    @Test
    fun connectionResetSessionEndIsNotUnexpectedError() {
        val client = FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes())
        val server = FakeTcpServerTransport()
        val logs = CopyOnWriteArrayList<String>()
        val proxy = newProxy(
            server = server,
            runner = ProxyBridgeRunner { _, _, _, _, _ -> throw SocketException("Connection reset") },
            logger = ProxyLogger { logs.add(it) },
        )

        proxy.start()
        server.enqueue(client)
        waitUntil { client.closed }
        proxy.stop()

        val stats = proxy.stats()
        assertEquals(1L, stats.sessionEndDiagnostics.connectionReset.count)
        assertEquals(0L, stats.sessionEndDiagnostics.connectionTimedOut.count)
        assertEquals(0L, stats.sessionUnexpectedErrors)
        assertEquals("direct-cold", stats.sessionEndDiagnostics.connectionReset.lastRoute)
        assertEquals(2, stats.sessionEndDiagnostics.connectionReset.lastDc)
        assertFalse(stats.sessionEndDiagnostics.connectionReset.lastMedia ?: true)
        assertTrue(stats.sessionEndDiagnostics.connectionReset.lastTimeMs > 0L)
        assertTrue(logs.any { it.contains("session ended") && it.contains("reason=connection_reset") && it.contains("detail=exception: SocketException: Connection reset") })
        assertFalse(logs.any { it.contains("session ended") && it.contains("reason=unexpected_error") })
    }

    @Test
    fun socketConnectionTimedOutSessionEndIsNotSessionTimeoutOrUnexpectedError() {
        val client = FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes())
        val server = FakeTcpServerTransport()
        val logs = CopyOnWriteArrayList<String>()
        val proxy = newProxy(
            server = server,
            runner = ProxyBridgeRunner { _, _, _, _, _ -> throw SocketException("Connection timed out") },
            logger = ProxyLogger { logs.add(it) },
        )

        proxy.start()
        server.enqueue(client)
        waitUntil { client.closed }
        proxy.stop()

        val stats = proxy.stats()
        assertEquals(1L, stats.sessionEndDiagnostics.connectionTimedOut.count)
        assertEquals(0L, stats.sessionEndDiagnostics.connectionReset.count)
        assertEquals(0L, stats.sessionTimeouts)
        assertEquals(0L, stats.sessionUnexpectedErrors)
        assertEquals("direct-cold", stats.sessionEndDiagnostics.connectionTimedOut.lastRoute)
        assertEquals(2, stats.sessionEndDiagnostics.connectionTimedOut.lastDc)
        assertFalse(stats.sessionEndDiagnostics.connectionTimedOut.lastMedia ?: true)
        assertTrue(stats.sessionEndDiagnostics.connectionTimedOut.lastTimeMs > 0L)
        assertTrue(logs.any { it.contains("session ended") && it.contains("reason=connection_timed_out") && it.contains("detail=exception: SocketException: Connection timed out") })
        assertFalse(logs.any { it.contains("session ended") && it.contains("reason=unexpected_error") })
    }

    @Test
    fun invalidHandshakeVectorsAreRejectedByParserWithBaseSecret() {
        val invalidWrongSecret = handshakeVector("invalid_wrong_secret").getString("handshake_hex").hexToBytes()
        assertNull(MtprotoHandshake.parse(invalidWrongSecret, baseConfig().secretHex))

        val invalidProtoTag = handshakeVector("invalid_proto_tag").getString("handshake_hex").hexToBytes()
        assertNull(MtprotoHandshake.parse(invalidProtoTag, baseConfig().secretHex))
    }

    @Test
    fun invalidHandshakeClosesClientAndIncrementsBadCounter() {
        val invalid = handshakeVector("invalid_wrong_secret")
        val client = FakeTcpClientTransport(invalid.getString("handshake_hex").hexToBytes())
        val server = FakeTcpServerTransport()
        val logs = CopyOnWriteArrayList<String>()
        val config = baseConfig().copy(secretHex = invalid.getString("secret_hex"))
        val proxy = newProxy(server, config = config, logger = ProxyLogger { logs.add(it) })

        proxy.start()
        server.enqueue(client)
        waitUntil { client.closed }
        proxy.stop()

        assertEquals(1L, proxy.stats().connectionsBad)
        assertTrue(logs.any { it.contains("Invalid MTProto handshake") })
    }


    @Test
    fun invalidHandshakeLogsAreAggregatedButStatsStayExact() {
        val invalid = handshakeVector("invalid_wrong_secret")
        val server = FakeTcpServerTransport()
        val logs = CopyOnWriteArrayList<String>()
        val config = baseConfig().copy(secretHex = invalid.getString("secret_hex"))
        val proxy = newProxy(server, config = config, logger = ProxyLogger { logs.add(it) })

        proxy.start()
        repeat(20) { server.enqueue(FakeTcpClientTransport(invalid.getString("handshake_hex").hexToBytes())) }
        waitUntil { proxy.stats().connectionsBad == 20L }
        proxy.stop()

        assertEquals(20L, proxy.stats().connectionsTotal)
        assertEquals(20L, proxy.stats().connectionsBad)
        assertTrue(logs.count { it.contains("Invalid MTProto handshake from") } <= 5)
        assertTrue(logs.any { it.contains("Invalid MTProto handshake repeated") })
        assertTrue(logs.any { it.contains("classified=") && it.contains("severity=debug") })
    }

    @Test
    fun unknownDcClosesClientAndLogsUnsupportedDc() {
        val vector = handshakeVector("abridged_dc2")
        val client = FakeTcpClientTransport(vector.getString("handshake_hex").hexToBytes())
        val server = FakeTcpServerTransport()
        val logs = CopyOnWriteArrayList<String>()
        val config = baseConfig().copy(dcRedirects = mapOf(4 to "203.0.113.4"), cfproxyEnabled = false)
        val proxy = newProxy(server, config = config, logger = ProxyLogger { logs.add(it) })

        proxy.start()
        server.enqueue(client)
        waitUntil { client.closed }
        proxy.stop()

        assertEquals(1L, proxy.stats().connectionsBad)
        assertTrue(logs.any { it.contains("Unsupported DC 2") })
    }

    @Test
    fun stopClosesServerAndActiveClients() {
        val client = FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes())
        val server = FakeTcpServerTransport()
        val connector = RecordingConnector(FakeWebSocketBinaryStream())
        val proxy =
            newProxy(
                server = server,
                connector = connector,
                runner =
                    ProxyBridgeRunner { _, _, _, _, _ ->
                        while (!client.closed) Thread.sleep(10)
                    },
            )

        proxy.start()
        server.enqueue(client)
        waitUntil { proxy.stats().connectionsActive == 1 }
        proxy.stop()

        assertTrue(server.closed)
        assertTrue(client.closed)
        waitUntil { proxy.stats().connectionsActive == 0 }
        assertFalse(proxy.isRunning)
    }

    @Test
    fun acceptLoopHandlesMultipleClients() {
        val vector = handshakeVector("abridged_dc2")
        val server = FakeTcpServerTransport()
        val bridgeCount = AtomicInteger(0)
        val proxy =
            newProxy(
                server = server,
                connector = RecordingConnector(FakeWebSocketBinaryStream()),
                runner = ProxyBridgeRunner { _, _, _, _, _ -> bridgeCount.incrementAndGet() },
            )

        proxy.start()
        server.enqueue(FakeTcpClientTransport(vector.getString("handshake_hex").hexToBytes()))
        server.enqueue(FakeTcpClientTransport(vector.getString("handshake_hex").hexToBytes()))
        waitUntil { bridgeCount.get() == 2 }
        waitUntil { proxy.stats().connectionsActive == 0 }
        proxy.stop()

        assertEquals(2L, proxy.stats().connectionsTotal)
        assertEquals(0L, proxy.stats().connectionsBad)
    }

    @Test
    fun websocketConnectFailureIncrementsCounterAndClosesClient() {
        val client = FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes())
        val server = FakeTcpServerTransport()
        val proxy =
            newProxy(
                server = server,
                connector = RawWebSocketConnector { _, _, _, _ -> throw IOException("ws boom") },
                config = baseConfig().copy(cfproxyEnabled = false),
            )

        proxy.start()
        server.enqueue(client)
        waitUntil { client.closed }
        proxy.stop()

        assertEquals(2L, proxy.stats().wsConnectErrors)
        assertEquals(0L, proxy.stats().connectionsBad)
    }

    @Test
    fun wsDomainsMatchesUpstreamOrdering() {
        assertEquals(
            listOf("kws2.web.telegram.org", "kws2-1.web.telegram.org"),
            wsDomains(2, isMedia = false),
        )
        assertEquals(
            listOf("kws2-1.web.telegram.org", "kws2.web.telegram.org"),
            wsDomains(2, isMedia = true),
        )
        assertEquals(
            listOf("kws2.web.telegram.org", "kws2-1.web.telegram.org"),
            wsDomains(203, isMedia = false),
        )
    }

    @Test
    fun proxyTriesNormalDcDomainsInUpstreamOrder() {
        val client = FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes())
        val server = FakeTcpServerTransport()
        val connector = RecordingConnector(FakeWebSocketBinaryStream())
        val proxy = newProxy(server = server, connector = connector)

        proxy.start()
        server.enqueue(client)
        waitUntil { client.closed }
        proxy.stop()

        assertEquals(listOf("203.0.113.2"), connector.targetHosts)
        assertEquals(listOf("kws2.web.telegram.org"), connector.domains)
        assertEquals(listOf(ProxyServer.DEFAULT_WS_PATH), connector.paths)
    }

    @Test
    fun proxyTriesMediaDcDomainsInUpstreamOrder() {
        val client = FakeTcpClientTransport(handshakeVector("abridged_media_dc2").getString("handshake_hex").hexToBytes())
        val server = FakeTcpServerTransport()
        val connector = RecordingConnector(FakeWebSocketBinaryStream())
        val proxy = newProxy(server = server, connector = connector)

        proxy.start()
        server.enqueue(client)
        waitUntil { client.closed }
        proxy.stop()

        assertEquals(listOf("203.0.113.2"), connector.targetHosts)
        assertEquals(listOf("kws2-1.web.telegram.org"), connector.domains)
        assertEquals(listOf(ProxyServer.DEFAULT_WS_PATH), connector.paths)
    }

    @Test
    fun proxyFallsBackToSecondDomainAndLogsFailureThenSuccess() {
        val client = FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes())
        val server = FakeTcpServerTransport()
        val logs = CopyOnWriteArrayList<String>()
        val events = CopyOnWriteArrayList<String>()
        val connector = FailingThenRecordingConnector(FakeWebSocketBinaryStream(events), failCount = 1)
        val proxy =
            newProxy(
                server = server,
                connector = connector,
                runner = ProxyBridgeRunner { _, _, _, _, _ -> events.add("bridge") },
                logger = ProxyLogger { logs.add(it) },
            )

        proxy.start()
        server.enqueue(client)
        waitUntil { events.contains("bridge") }
        proxy.stop()

        assertEquals(
            listOf("kws2.web.telegram.org", "kws2-1.web.telegram.org"),
            connector.domains,
        )
        assertEquals(1L, proxy.stats().wsConnectErrors)
        assertTrue(logs.any { it.contains("wss://kws2.web.telegram.org/apiws via 203.0.113.2") })
        assertTrue(logs.any { it.contains("kws2.web.telegram.org failed: IOException: planned failure 1") })
        assertTrue(logs.any { it.contains("DC2 WebSocket connected via kws2-1.web.telegram.org") })
    }

    @Test
    fun allWebSocketDomainsFailClosesClientIncrementsCounterAndLogsAttempts() {
        val client = FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes())
        val server = FakeTcpServerTransport()
        val logs = CopyOnWriteArrayList<String>()
        val connector = RawWebSocketConnector { _, domain, _, _ -> throw IOException("boom for $domain") }
        val proxy = newProxy(
            server = server,
            connector = connector,
            config = baseConfig().copy(cfproxyEnabled = false),
            logger = ProxyLogger { logs.add(it) },
        )

        proxy.start()
        server.enqueue(client)
        waitUntil { client.closed }
        proxy.stop()

        assertEquals(2L, proxy.stats().wsConnectErrors)
        assertEquals(0L, proxy.stats().connectionsBad)
        assertTrue(logs.any { it.contains("wss://kws2.web.telegram.org/apiws via 203.0.113.2") })
        assertTrue(logs.any { it.contains("wss://kws2-1.web.telegram.org/apiws via 203.0.113.2") })
        assertTrue(logs.any { it.contains("IOException: boom for kws2.web.telegram.org") })
        assertTrue(logs.any { it.contains("connect failed after attempts") })
    }


    @Test
    fun transientNetworkNoneFollowedByMobileWithinSettlingWindowResumesRouteAttempt() {
        val client = FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes())
        val server = FakeTcpServerTransport()
        val events = CopyOnWriteArrayList<String>()
        val logs = CopyOnWriteArrayList<String>()
        val connector = RecordingConnector(FakeWebSocketBinaryStream(events))
        val proxy = newProxy(
            server = server,
            connector = connector,
            config = baseConfig().copy(
                routeMode = NetworkRouteMode.AUTO,
                networkStatus = "mobile",
                cfPoolEnabled = false,
                cfProxyDomains = listOf("cf.example"),
            ),
            runner = ProxyBridgeRunner { _, _, _, _, _ -> events.add("bridge") },
            logger = ProxyLogger { logs.add(it) },
        )

        proxy.start()
        proxy.applyNetworkRouteImmediately("none")
        val generationAtNone = proxy.stats().networkGeneration
        server.enqueue(client)
        waitUntil { proxy.stats().networkSettlingWaits == 1L }
        proxy.applyNetworkRouteImmediately("mobile")
        waitUntil(
            message = {
                transientNetworkRecoveryDiagnostics(
                    generationAtNone = generationAtNone,
                    stats = proxy.stats(),
                    events = events,
                    connectorDomains = connector.domains,
                    logs = logs,
                )
            },
        ) {
            events.contains("bridge") && connector.domains.contains("kws2.cf.example")
        }

        val stats = proxy.stats()
        proxy.stop()

        val connectorDomains = connector.domains.toList()
        assertTrue(
            "Expected exactly one resumed CF route attempt after MOBILE within settling window, " +
                "but connectorDomains=$connectorDomains. " +
                transientNetworkRecoveryDiagnostics(generationAtNone, stats, events, connectorDomains, logs),
            connectorDomains == listOf("kws2.cf.example"),
        )
        assertTrue(
            "Expected MOBILE transition to advance network generation beyond $generationAtNone. " +
                transientNetworkRecoveryDiagnostics(generationAtNone, stats, events, connectorDomains, logs),
            stats.networkGeneration > generationAtNone,
        )
        assertEquals(1L, stats.networkSettlingResumedAfterAvailable)
        assertEquals(1L, stats.networkSettlingStaleAttemptsIgnored)
        assertEquals(0L, stats.networkSettlingControlledFailures)
        assertTrue(stats.lastNetworkLostAtMs > 0L)
        assertTrue(stats.lastNetworkAvailableAtMs > 0L)
        assertTrue(
            "Expected network-lost settling log. " +
                transientNetworkRecoveryDiagnostics(generationAtNone, stats, events, connectorDomains, logs),
            logs.any { it.contains("network settling started after network lost") },
        )
        assertTrue(
            "Expected route wait log. " +
                transientNetworkRecoveryDiagnostics(generationAtNone, stats, events, connectorDomains, logs),
            logs.any { it.contains("client route waits") },
        )
        assertTrue(
            "Expected resumed-after-network-available log. " +
                transientNetworkRecoveryDiagnostics(generationAtNone, stats, events, connectorDomains, logs),
            logs.any { it.contains("network appeared during settling") },
        )
    }

    @Test
    fun networkNoneBeyondSettlingWindowReturnsControlledFailureWithoutConnects() {
        val client = FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes())
        val server = FakeTcpServerTransport()
        val logs = CopyOnWriteArrayList<String>()
        val connector = RecordingConnector(FakeWebSocketBinaryStream())
        val proxy = newProxy(
            server = server,
            connector = connector,
            config = baseConfig().copy(routeMode = NetworkRouteMode.AUTO, networkStatus = "mobile"),
            logger = ProxyLogger { logs.add(it) },
        )

        proxy.start()
        proxy.applyNetworkRouteImmediately("none")
        Thread.sleep(1_650L)
        server.enqueue(client)
        waitUntil { client.closed }
        proxy.stop()

        assertTrue(connector.domains.isEmpty())
        assertEquals(1L, proxy.stats().networkSettlingControlledFailures)
        assertTrue(logs.any { it.contains("network=none outside settling window; controlled no-route failure") })
    }

    @Test
    fun routeAttemptDuringNetworkSettlingDoesNotStartCfConnectWhileNetworkIsNone() {
        val client = FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes())
        val server = FakeTcpServerTransport()
        val events = CopyOnWriteArrayList<String>()
        val connector = RecordingConnector(FakeWebSocketBinaryStream(events))
        val proxy = newProxy(
            server = server,
            connector = connector,
            config = baseConfig().copy(
                routeMode = NetworkRouteMode.CF_ONLY,
                networkStatus = "mobile",
                cfProxyDomains = listOf("cf.example"),
            ),
            runner = ProxyBridgeRunner { _, _, _, _, _ -> events.add("bridge") },
        )

        proxy.start()
        proxy.applyNetworkRouteImmediately("none")
        server.enqueue(client)
        waitUntil { proxy.stats().networkSettlingWaits == 1L }
        Thread.sleep(150L)
        assertTrue(connector.domains.isEmpty())
        proxy.applyNetworkRouteImmediately("mobile")
        waitUntil { events.contains("bridge") }
        proxy.stop()

        assertEquals(listOf("kws2.cf.example"), connector.domains)
    }

    @Test
    fun routeAttemptDuringNetworkSettlingDoesNotStartDirectConnectWhileNetworkIsNone() {
        val client = FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes())
        val server = FakeTcpServerTransport()
        val connector = RecordingConnector(FakeWebSocketBinaryStream())
        val proxy = newProxy(
            server = server,
            connector = connector,
            config = baseConfig().copy(routeMode = NetworkRouteMode.DIRECT_FIRST, networkStatus = "Wi-Fi", cfproxyEnabled = false),
        )

        proxy.start()
        proxy.applyNetworkRouteImmediately("none")
        server.enqueue(client)
        waitUntil { proxy.stats().networkSettlingWaits == 1L }
        Thread.sleep(150L)
        assertTrue(connector.domains.isEmpty())
        proxy.applyNetworkRouteImmediately("Wi-Fi")
        waitUntil { client.closed }
        proxy.stop()

        assertEquals(listOf("kws2.web.telegram.org"), connector.domains)
    }

    @Test
    fun missingDirectRedirectWithCfEnabledAttemptsCfProxyDomain() {
        val client = FakeTcpClientTransport(buildClientHandshake(dcIdx = 5, protoTag = RelayInit.PROTO_TAG_ABRIDGED))
        val server = FakeTcpServerTransport()
        val events = CopyOnWriteArrayList<String>()
        val logs = CopyOnWriteArrayList<String>()
        val connector = RecordingConnector(FakeWebSocketBinaryStream(events))
        val proxy = newProxy(
            server = server,
            connector = connector,
            config = baseConfig().copy(dcRedirects = emptyMap(), cfProxyDomains = listOf("cf.example")),
            runner = ProxyBridgeRunner { _, _, _, _, _ -> events.add("bridge") },
            logger = ProxyLogger { logs.add(it) },
        )

        proxy.start()
        server.enqueue(client)
        waitUntil { events.contains("bridge") }
        proxy.stop()

        assertEquals(listOf("kws5.cf.example"), connector.targetHosts)
        assertEquals(listOf("kws5.cf.example"), connector.domains)
        assertEquals(listOf(ProxyServer.DEFAULT_WS_PATH), connector.paths)
        assertEquals(1L, proxy.stats().cfProxyConnections)
        assertTrue(logs.any { it.contains("DC5 has no direct redirect configured; trying CF fallback") })
        assertTrue(logs.any { it.contains("DC5 -> trying CF proxy wss://kws5.cf.example/apiws") })
        assertTrue(logs.any { it.contains("DC5 CF proxy connected via kws5.cf.example") })
    }

    @Test
    fun missingDc3DirectRedirectWithCfEnabledAttemptsCfProxyDomain() {
        val client = FakeTcpClientTransport(buildClientHandshake(dcIdx = 3, protoTag = RelayInit.PROTO_TAG_ABRIDGED))
        val server = FakeTcpServerTransport()
        val events = CopyOnWriteArrayList<String>()
        val logs = CopyOnWriteArrayList<String>()
        val connector = RecordingConnector(FakeWebSocketBinaryStream(events))
        val proxy = newProxy(
            server = server,
            connector = connector,
            config = baseConfig().copy(cfProxyDomains = listOf("cf.example")),
            runner = ProxyBridgeRunner { _, _, _, _, _ -> events.add("bridge") },
            logger = ProxyLogger { logs.add(it) },
        )

        proxy.start()
        server.enqueue(client)
        waitUntil { events.contains("bridge") }
        proxy.stop()

        assertEquals(listOf("kws3.cf.example"), connector.targetHosts)
        assertEquals(listOf("kws3.cf.example"), connector.domains)
        assertEquals(1L, proxy.stats().cfProxyConnections)
        assertTrue(logs.any { it.contains("DC3 has no direct redirect configured; trying CF fallback") })
        assertTrue(logs.any { it.contains("DC3 -> trying CF proxy wss://kws3.cf.example/apiws") })
        assertTrue(logs.any { it.contains("DC3 CF proxy connected via kws3.cf.example") })
    }

    @Test
    fun directWebSocketFailureFallsBackToCfProxyAndStartsBridge() {
        val client = FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes())
        val server = FakeTcpServerTransport()
        val events = CopyOnWriteArrayList<String>()
        val logs = CopyOnWriteArrayList<String>()
        val connector = FailingDirectThenCfConnector(FakeWebSocketBinaryStream(events))
        val proxy = newProxy(
            server = server,
            connector = connector,
            config = baseConfig().copy(cfProxyDomains = listOf("cf.example")),
            runner = ProxyBridgeRunner { _, _, _, _, _ -> events.add("bridge") },
            logger = ProxyLogger { logs.add(it) },
        )

        proxy.start()
        server.enqueue(client)
        waitUntil { events.contains("bridge") }
        proxy.stop()

        assertEquals(listOf("kws2.web.telegram.org", "kws2-1.web.telegram.org", "kws2.cf.example"), connector.domains)
        assertEquals("kws2.cf.example", connector.targetHosts.last())
        assertEquals(2L, proxy.stats().wsConnectErrors)
        assertEquals(1L, proxy.stats().cfProxyConnections)
        assertTrue(logs.any { it.contains("DC2 -> trying CF proxy wss://kws2.cf.example/apiws") })
    }

    @Test
    fun directWebSocketSuccessDoesNotAttemptCfFallback() {
        val client = FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes())
        val server = FakeTcpServerTransport()
        val events = CopyOnWriteArrayList<String>()
        val connector = RecordingConnector(FakeWebSocketBinaryStream(events))
        val proxy = newProxy(
            server = server,
            connector = connector,
            config = baseConfig().copy(cfProxyDomains = listOf("cf.example")),
            runner = ProxyBridgeRunner { _, _, _, _, _ -> events.add("bridge") },
        )

        proxy.start()
        server.enqueue(client)
        waitUntil { events.contains("bridge") }
        proxy.stop()

        assertEquals(listOf("kws2.web.telegram.org"), connector.domains)
        assertEquals(0L, proxy.stats().cfProxyConnections)
    }

    @Test
    fun cfDisabledAndMissingDirectRedirectKeepsUnsupportedDcCloseBehavior() {
        val client = FakeTcpClientTransport(buildClientHandshake(dcIdx = 5, protoTag = RelayInit.PROTO_TAG_ABRIDGED))
        val server = FakeTcpServerTransport()
        val logs = CopyOnWriteArrayList<String>()
        val connector = RecordingConnector(FakeWebSocketBinaryStream())
        val proxy = newProxy(
            server = server,
            connector = connector,
            config = baseConfig().copy(dcRedirects = emptyMap(), cfproxyEnabled = false, cfProxyDomains = listOf("cf.example")),
            logger = ProxyLogger { logs.add(it) },
        )

        proxy.start()
        server.enqueue(client)
        waitUntil { client.closed }
        proxy.stop()

        assertTrue(connector.domains.isEmpty())
        assertEquals(1L, proxy.stats().connectionsBad)
        assertTrue(logs.any { it.contains("Unsupported DC 5") })
    }

    @Test
    fun allCfDomainsFailClosesClientAndLogsCfFailure() {
        val client = FakeTcpClientTransport(buildClientHandshake(dcIdx = 5, protoTag = RelayInit.PROTO_TAG_ABRIDGED))
        val server = FakeTcpServerTransport()
        val logs = CopyOnWriteArrayList<String>()
        val connector = RawWebSocketConnector { _, domain, _, _ -> throw IOException("cf boom for $domain") }
        val proxy = newProxy(
            server = server,
            connector = connector,
            config = baseConfig().copy(dcRedirects = emptyMap(), cfProxyDomains = listOf("one.example", "two.example")),
            logger = ProxyLogger { logs.add(it) },
        )

        proxy.start()
        server.enqueue(client)
        waitUntil { client.closed }
        proxy.stop()

        assertEquals(2L, proxy.stats().cfProxyErrors)
        assertEquals(1L, proxy.stats().connectionsBad)
        assertTrue(logs.any { it.contains("DC5 CF proxy failed via one.example: IOException: cf boom for kws5.one.example") })
        assertTrue(logs.any { it.contains("DC5 all CF proxy fallback attempts failed") })
    }

    @Test
    fun cfPoolRefillUsesSameConnectTupleAsNormalCfFallback() {
        val server = FakeTcpServerTransport()
        val connector = TupleRecordingConnector()
        val proxy = newProxy(
            server = server,
            connector = connector,
            config = baseConfig().copy(
                routeMode = NetworkRouteMode.CF_FIRST,
                networkStatus = "mobile",
                cfProxyDomains = listOf("cf.example"),
            ),
            runner = ProxyBridgeRunner { _, _, _, _, counters -> counters.finish("completed") },
        )

        proxy.start()
        server.enqueue(FakeTcpClientTransport(buildClientHandshake(dcIdx = 2, protoTag = RelayInit.PROTO_TAG_ABRIDGED)))
        waitUntil { proxy.stats().cfProxyConnections == 1L }
        waitUntil { proxy.stats().cfPoolRefillSuccesses == 1L }
        proxy.stop()

        val expected = ConnectTuple(
            targetHost = "kws2.cf.example",
            domain = "kws2.cf.example",
            path = ProxyServer.DEFAULT_WS_PATH,
            timeoutMs = RawWebSocket.DEFAULT_CONNECT_TIMEOUT_MS,
        )
        assertTrue("expected normal CF connect and CF pool refill tuples, got ${connector.tuples}", connector.tuples.size >= 2)
        assertEquals(expected, connector.tuples[0])
        assertEquals(expected, connector.tuples[1])
    }

    @Test
    fun retrySafeStaleCfPoolFallsThroughToNormalCfFlow() {
        val server = FakeTcpServerTransport()
        val connector = SequencedConnector(
            FakeWebSocketBinaryStream(),
            FakeWebSocketBinaryStream(sendError = EOFException("stale pool")),
            FakeWebSocketBinaryStream(),
        )
        val bridgedRoutes = CopyOnWriteArrayList<String>()
        val proxy = newProxy(
            server = server,
            connector = connector,
            config = baseConfig().copy(
                routeMode = NetworkRouteMode.CF_FIRST,
                networkStatus = "mobile",
                cfProxyDomains = listOf("cf.example"),
            ),
            runner = ProxyBridgeRunner { _, webSocket, _, _, counters ->
                bridgedRoutes.add(if (webSocket === connector.sockets[0]) "first-cf" else "retry-cf")
                counters.finish("completed")
            },
        )

        proxy.start()
        server.enqueue(FakeTcpClientTransport(buildClientHandshake(dcIdx = 2, protoTag = RelayInit.PROTO_TAG_ABRIDGED)))
        waitUntil { proxy.stats().cfPoolRefillSuccesses == 1L }
        server.enqueue(FakeTcpClientTransport(buildClientHandshake(dcIdx = 2, protoTag = RelayInit.PROTO_TAG_ABRIDGED)))
        waitUntil { proxy.stats().cfPoolStale == 1L && proxy.stats().cfProxyConnections >= 2L }
        proxy.stop()

        assertEquals(1L, proxy.stats().cfPoolHits)
        assertEquals(1L, proxy.stats().cfPoolStale)
        assertEquals(2L, proxy.stats().cfProxyConnections)
        assertTrue("normal CF retry should bridge after retry-safe stale pool", bridgedRoutes.contains("retry-cf"))
    }

    @Test
    fun nonRetrySafeStaleCfPoolIsHandledWithoutReplay() {
        val server = FakeTcpServerTransport()
        val connector = SequencedConnector(
            FakeWebSocketBinaryStream(),
            FakeWebSocketBinaryStream(),
        )
        val bridgeCalls = AtomicInteger(0)
        val proxy = newProxy(
            server = server,
            connector = connector,
            config = baseConfig().copy(
                routeMode = NetworkRouteMode.CF_FIRST,
                networkStatus = "mobile",
                cfProxyDomains = listOf("cf.example"),
            ),
            runner = ProxyBridgeRunner { _, _, _, _, counters ->
                if (bridgeCalls.incrementAndGet() == 2) {
                    counters.recordDown(1)
                    throw EOFException("partial frame")
                }
                counters.finish("completed")
            },
        )

        proxy.start()
        server.enqueue(FakeTcpClientTransport(buildClientHandshake(dcIdx = 2, protoTag = RelayInit.PROTO_TAG_ABRIDGED)))
        waitUntil { proxy.stats().cfPoolRefillSuccesses == 1L }
        server.enqueue(FakeTcpClientTransport(buildClientHandshake(dcIdx = 2, protoTag = RelayInit.PROTO_TAG_ABRIDGED)))
        waitUntil { proxy.stats().cfPoolStale == 1L }
        proxy.stop()

        assertEquals(1L, proxy.stats().cfPoolHits)
        assertEquals(1L, proxy.stats().cfPoolStale)
        assertEquals("non-retry-safe stale pool must not open another CF socket", 2, connector.tuples.size)
        assertEquals(1L, proxy.stats().cfProxyConnections)
    }

    @Test
    fun websocketPoolWarmupCreatesEntriesForConfiguredDcsAndMediaModes() {
        val logs = CopyOnWriteArrayList<String>()
        val connector = MultiSocketRecordingConnector()
        val pool = WebSocketPool(
            poolSize = 1,
            connector = connector,
            logger = ProxyLogger { logs.add(it) },
        )

        pool.warmup(
            mapOf(2 to "203.0.113.2", 3 to "203.0.113.3", 4 to "203.0.113.4"),
            ::wsDomains,
        )
        waitUntil { connector.domains.size == 6 }

        for (dc in listOf(2, 3, 4)) {
            waitUntil { pool.readyCount(dc, isMedia = false) == 1 && pool.readyCount(dc, isMedia = true) == 1 }
        }
        assertTrue(logs.any { it.contains("WS pool warmup started for 3 DC(s)") })
        pool.closeAll()
    }


    @Test
    fun clientThreadContainsRelayInitBrokenPipeWithoutUncaughtException() {
        val client = FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes())
        val server = FakeTcpServerTransport()
        val logs = CopyOnWriteArrayList<String>()
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> uncaught.add(error) }
        try {
            val proxy = newProxy(
                server = server,
                connector = RecordingConnector(FakeWebSocketBinaryStream(sendError = SocketException("Broken pipe"))),
                config = baseConfig().copy(cfproxyEnabled = false),
                logger = ProxyLogger { logs.add(it) },
            )

            proxy.start()
            server.enqueue(client)
            waitUntil { client.closed }
            proxy.stop()

            assertEquals(0, proxy.stats().connectionsActive)
            assertEquals(1L, proxy.stats().sessionUnexpectedErrors)
            assertTrue(logs.any { it.contains("DC2 direct-cold route failed before bridge: SocketException: Broken pipe") })
            assertTrue(logs.any { it.contains("session ended") && it.contains("reason=unexpected_error") })
            assertTrue(logs.none { it.contains("client handler failed") })
            assertTrue(uncaught.isEmpty())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
    }

    @Test
    fun bridgeRunnerBrokenPipeDoesNotEscapeClientThread() {
        val client = FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes())
        val server = FakeTcpServerTransport()
        val logs = CopyOnWriteArrayList<String>()
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> uncaught.add(error) }
        try {
            val proxy = newProxy(
                server = server,
                connector = RecordingConnector(FakeWebSocketBinaryStream()),
                config = baseConfig().copy(cfproxyEnabled = false),
                runner = ProxyBridgeRunner { _, _, _, _, _ -> throw SocketException("Broken pipe") },
                logger = ProxyLogger { logs.add(it) },
            )

            proxy.start()
            server.enqueue(client)
            waitUntil { client.closed }
            proxy.stop()

            assertEquals(0, proxy.stats().connectionsActive)
            assertEquals(1L, proxy.stats().sessionUnexpectedErrors)
            assertTrue(logs.any { it.contains("DC2 direct-cold route failed: SocketException: Broken pipe") })
            assertTrue(logs.any { it.contains("session ended") && it.contains("reason=unexpected_error") })
            assertTrue(uncaught.isEmpty())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
    }

    @Test
    fun pooledWebSocketHitRunsBridgeWithoutColdDirectConnect() {
        val client = FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes())
        val server = FakeTcpServerTransport()
        val logs = CopyOnWriteArrayList<String>()
        val events = CopyOnWriteArrayList<String>()
        val warmSocket = FakeWebSocketBinaryStream(events)
        val attempts = AtomicInteger(0)
        val connector = RawWebSocketConnector { _, domain, _, _ ->
            val attempt = attempts.incrementAndGet()
            if (attempt == 1 && domain == "kws2.web.telegram.org") warmSocket else throw IOException("cold direct blocked")
        }
        val proxy = newProxy(
            server = server,
            connector = connector,
            config = baseConfig().copy(poolSize = 1, cfproxyEnabled = false, dcRedirects = mapOf(2 to "203.0.113.2")),
            runner = ProxyBridgeRunner { _, webSocket, _, _, _ ->
                assertTrue(webSocket === warmSocket)
                events.add("bridge")
            },
            logger = ProxyLogger { logs.add(it) },
        )

        proxy.start()
        waitUntil { logs.any { it.contains("WS pool refilled DC2: 1 ready") } }
        server.enqueue(client)
        waitUntil { events.contains("bridge") }
        proxy.stop()

        assertEquals(1L, proxy.stats().poolHits)
        assertEquals(0L, proxy.stats().poolMisses)
        assertTrue(logs.any { it.contains("DC2 direct WS pool hit") })
        assertTrue(events.contains("send"))
    }

    @Test
    fun poolMissFallsBackToColdDirectConnection() {
        val client = FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes())
        val server = FakeTcpServerTransport()
        val logs = CopyOnWriteArrayList<String>()
        val events = CopyOnWriteArrayList<String>()
        val attempts = AtomicInteger(0)
        val coldSocket = FakeWebSocketBinaryStream(events)
        val connector = RawWebSocketConnector { _, domain, _, _ ->
            val attempt = attempts.incrementAndGet()
            if (attempt <= 4) throw IOException("warmup failed $attempt")
            if (!Thread.currentThread().name.startsWith("ProxyServer-client")) throw IOException("refill blocked $attempt")
            if (domain == "kws2.web.telegram.org") coldSocket else throw IOException("unexpected $domain")
        }
        val proxy = newProxy(
            server = server,
            connector = connector,
            config = baseConfig().copy(poolSize = 1, cfproxyEnabled = false, dcRedirects = mapOf(2 to "203.0.113.2")),
            runner = ProxyBridgeRunner { _, webSocket, _, _, _ ->
                assertTrue(webSocket === coldSocket)
                events.add("bridge")
            },
            logger = ProxyLogger { logs.add(it) },
        )

        proxy.start()
        waitUntil { logs.count { it.contains("direct WS pool refill failed") } >= 4 }
        server.enqueue(client)
        waitUntil { events.contains("bridge") }
        proxy.stop()

        assertEquals(0L, proxy.stats().poolHits)
        assertEquals(1L, proxy.stats().poolMisses)
        assertTrue(logs.any { it.contains("DC2 direct WS pool miss") })
        assertTrue(logs.any { it.contains("DC2 WebSocket connected via kws2.web.telegram.org") })
    }


    @Test
    fun stalePooledSocketBrokenPipeRetriesColdDirect() {
        val client = FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes())
        val server = FakeTcpServerTransport()
        val logs = CopyOnWriteArrayList<String>()
        val events = CopyOnWriteArrayList<String>()
        val staleSocket = FakeWebSocketBinaryStream(events, sendError = SocketException("Broken pipe"))
        val coldSocket = FakeWebSocketBinaryStream(events)
        val attempts = AtomicInteger(0)
        val connector = RawWebSocketConnector { _, domain, _, _ ->
            val attempt = attempts.incrementAndGet()
            when {
                attempt == 1 && domain == "kws2.web.telegram.org" -> staleSocket
                Thread.currentThread().name.startsWith("WebSocketPool") -> throw IOException("refill blocked $attempt")
                Thread.currentThread().name.startsWith("ProxyServer-client") && domain == "kws2.web.telegram.org" -> coldSocket
                else -> throw IOException("unexpected $domain attempt $attempt")
            }
        }
        val proxy = newProxy(
            server = server,
            connector = connector,
            config = baseConfig().copy(poolSize = 1, cfproxyEnabled = false, dcRedirects = mapOf(2 to "203.0.113.2")),
            runner = ProxyBridgeRunner { _, webSocket, _, _, _ ->
                assertTrue(webSocket === coldSocket)
                events.add("bridge")
            },
            logger = ProxyLogger { logs.add(it) },
        )

        proxy.start()
        waitUntil { logs.any { it.contains("WS pool refilled DC2: 1 ready") } }
        server.enqueue(client)
        waitUntil { events.contains("bridge") }
        proxy.stop()

        assertTrue(staleSocket.closed)
        assertTrue(coldSocket.closed)
        assertEquals(1L, proxy.stats().poolHits)
        assertEquals(0L, proxy.stats().poolMisses)
        assertEquals(1L, proxy.stats().poolStale)
        assertEquals(1L, proxy.stats().sessionUnexpectedErrors)
        assertTrue(logs.any { it.contains("DC2 direct-pool route failed before bridge: SocketException: Broken pipe") })
        assertTrue(logs.any { it.contains("DC2 retrying with cold direct route after stale pool") })
        assertTrue(logs.any { it.contains("route=direct-cold") && it.contains("reason=completed") })
    }

    @Test
    fun stalePooledSocketFallsBackToCfWhenColdDirectFails() {
        val client = FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes())
        val server = FakeTcpServerTransport()
        val logs = CopyOnWriteArrayList<String>()
        val events = CopyOnWriteArrayList<String>()
        val staleSocket = FakeWebSocketBinaryStream(events, sendError = SocketException("Broken pipe"))
        val cfSocket = FakeWebSocketBinaryStream(events)
        val attempts = AtomicInteger(0)
        val connector = RawWebSocketConnector { _, domain, _, _ ->
            val attempt = attempts.incrementAndGet()
            when {
                attempt == 1 && domain == "kws2.web.telegram.org" -> staleSocket
                domain.endsWith(".web.telegram.org") -> throw IOException("cold direct down $domain")
                domain == "kws2.cf.example" -> cfSocket
                else -> throw IOException("unexpected $domain attempt $attempt")
            }
        }
        val proxy = newProxy(
            server = server,
            connector = connector,
            config = baseConfig().copy(poolSize = 1, cfProxyDomains = listOf("cf.example"), dcRedirects = mapOf(2 to "203.0.113.2")),
            runner = ProxyBridgeRunner { _, webSocket, _, _, _ ->
                assertTrue(webSocket === cfSocket)
                events.add("bridge")
            },
            logger = ProxyLogger { logs.add(it) },
        )

        proxy.start()
        waitUntil { logs.any { it.contains("WS pool refilled DC2: 1 ready") } }
        server.enqueue(client)
        waitUntil { events.contains("bridge") }
        proxy.stop()

        assertEquals(1L, proxy.stats().poolHits)
        assertEquals(1L, proxy.stats().cfProxyConnections)
        assertTrue(logs.any { it.contains("DC2 retrying with cold direct route after stale pool") })
        assertTrue(logs.any { it.contains("DC2 -> trying CF proxy wss://kws2.cf.example/apiws") })
        assertTrue(logs.any { it.contains("route=cf") && it.contains("reason=completed") })
    }

    @Test
    fun stalePooledSocketImmediateEofIncrementsCounterAndRetriesColdDirect() {
        val client = FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes())
        val server = FakeTcpServerTransport()
        val logs = CopyOnWriteArrayList<String>()
        val events = CopyOnWriteArrayList<String>()
        val staleSocket = FakeWebSocketBinaryStream(events)
        val coldSocket = FakeWebSocketBinaryStream(events)
        val attempts = AtomicInteger(0)
        val connector = RawWebSocketConnector { _, domain, _, _ ->
            val attempt = attempts.incrementAndGet()
            when {
                attempt == 1 && domain == "kws2.web.telegram.org" -> staleSocket
                Thread.currentThread().name.startsWith("WebSocketPool") -> throw IOException("refill blocked $attempt")
                Thread.currentThread().name.startsWith("ProxyServer-client") && domain == "kws2.web.telegram.org" -> coldSocket
                else -> throw IOException("unexpected $domain attempt $attempt")
            }
        }
        val proxy = newProxy(
            server = server,
            connector = connector,
            config = baseConfig().copy(poolSize = 1, cfproxyEnabled = false, dcRedirects = mapOf(2 to "203.0.113.2")),
            runner = ProxyBridgeRunner { _, webSocket, _, _, counters ->
                if (webSocket === staleSocket) {
                    counters.finish("exception: EOFException: no frame")
                } else {
                    assertTrue(webSocket === coldSocket)
                    events.add("bridge")
                }
            },
            logger = ProxyLogger { logs.add(it) },
        )

        proxy.start()
        waitUntil { logs.any { it.contains("WS pool refilled DC2: 1 ready") } }
        server.enqueue(client)
        waitUntil { events.contains("bridge") }
        proxy.stop()

        assertEquals(1L, proxy.stats().poolHits)
        assertEquals(1L, proxy.stats().poolStale)
        assertTrue(logs.any { it.contains("direct-pool stale route detected") && it.contains("bytesDown=0") })
        assertTrue(logs.any { it.contains("DC2 retrying with cold direct route after stale pool") })
    }

    @Test
    fun nonStaleClientClosedPooledSessionIsNotRetried() {
        val client = FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes())
        val server = FakeTcpServerTransport()
        val logs = CopyOnWriteArrayList<String>()
        val events = CopyOnWriteArrayList<String>()
        val pooledSocket = FakeWebSocketBinaryStream(events)
        val attempts = AtomicInteger(0)
        val connector = RawWebSocketConnector { _, domain, _, _ ->
            val attempt = attempts.incrementAndGet()
            if (attempt == 1 && domain == "kws2.web.telegram.org") pooledSocket else throw IOException("unexpected retry $attempt $domain")
        }
        val proxy = newProxy(
            server = server,
            connector = connector,
            config = baseConfig().copy(poolSize = 1, cfproxyEnabled = false, dcRedirects = mapOf(2 to "203.0.113.2")),
            runner = ProxyBridgeRunner { _, webSocket, _, _, counters ->
                assertTrue(webSocket === pooledSocket)
                counters.finish("client closed")
                events.add("bridge")
            },
            logger = ProxyLogger { logs.add(it) },
        )

        proxy.start()
        waitUntil { logs.any { it.contains("WS pool refilled DC2: 1 ready") } }
        server.enqueue(client)
        waitUntil { events.contains("bridge") }
        proxy.stop()

        assertEquals(1L, proxy.stats().poolHits)
        assertEquals(0L, proxy.stats().poolStale)
        assertFalse(logs.any { it.contains("retrying with cold direct route after stale pool") })
    }

    @Test
    fun expiredPoolEntriesAreClosedAndNotReused() {
        var now = 1_000L
        val socket = FakeWebSocketBinaryStream()
        val connector = MultiSocketRecordingConnector(socket)
        val pool = WebSocketPool(
            poolSize = 1,
            connector = connector,
            maxAgeMs = 10,
            nowMs = { now },
        )

        pool.warmup(mapOf(2 to "203.0.113.2"), ::wsDomains)
        waitUntil { pool.readyCount(2, isMedia = false) == 1 }
        now += 11
        val pooled = pool.get(2, isMedia = false, "203.0.113.2", wsDomains(2, false))

        assertEquals(null, pooled)
        assertTrue(socket.closed)
        pool.closeAll()
    }

    @Test
    fun failedPoolRefillDoesNotCrashAndLogsFailure() {
        val logs = CopyOnWriteArrayList<String>()
        val pool = WebSocketPool(
            poolSize = 1,
            connector = RawWebSocketConnector { _, domain, _, _ -> throw IOException("refill boom for $domain") },
            logger = ProxyLogger { logs.add(it) },
        )

        pool.warmup(mapOf(2 to "203.0.113.2"), ::wsDomains)
        waitUntil { logs.any { it.contains("DC2 direct WS pool refill failed via kws2.web.telegram.org") } }

        assertTrue(logs.any { it.contains("IOException: refill boom for kws2.web.telegram.org") })
        pool.closeAll()
    }

    @Test
    fun directPoolHitIncrementsPerKeyHitCounter() {
        val client = FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes())
        val server = FakeTcpServerTransport()
        val logs = CopyOnWriteArrayList<String>()
        val warmSocket = FakeWebSocketBinaryStream()
        val attempts = AtomicInteger(0)
        val proxy = newProxy(
            server = server,
            connector = RawWebSocketConnector { _, domain, _, _ ->
                if (attempts.incrementAndGet() == 1 && domain == "kws2.web.telegram.org") warmSocket else throw IOException("blocked")
            },
            config = baseConfig().copy(poolSize = 1, cfproxyEnabled = false, dcRedirects = mapOf(2 to "203.0.113.2")),
            runner = ProxyBridgeRunner { _, _, _, _, _ -> },
            logger = ProxyLogger { logs.add(it) },
        )
        proxy.start()
        waitUntil { logs.any { it.contains("WS pool refilled DC2: 1 ready") } }
        server.enqueue(client)
        waitUntil { client.closed }
        proxy.stop()
        assertEquals(1L, proxy.stats().poolHits)
        assertEquals(1L, proxy.stats().directPoolDiagnostics.hitsByKey["DC2.normal"])
        assertTrue((proxy.stats().directPoolDiagnostics.lastHitTimeMsByKey["DC2.normal"] ?: 0L) > 0L)
    }

    @Test
    fun directPoolMissIncrementsPerKeyMissCounter() {
        val client = FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes())
        val server = FakeTcpServerTransport()
        val logs = CopyOnWriteArrayList<String>()
        val proxy = newProxy(
            server = server,
            connector = RawWebSocketConnector { _, domain, _, _ ->
                if (Thread.currentThread().name.startsWith("ProxyServer-client") && domain == "kws2.web.telegram.org") FakeWebSocketBinaryStream() else throw IOException("blocked")
            },
            config = baseConfig().copy(poolSize = 1, cfproxyEnabled = false, dcRedirects = mapOf(2 to "203.0.113.2")),
            runner = ProxyBridgeRunner { _, _, _, _, _ -> },
            logger = ProxyLogger { logs.add(it) },
        )
        proxy.start()
        waitUntil { logs.count { it.contains("direct WS pool refill failed") } >= 4 }
        server.enqueue(client)
        waitUntil { client.closed }
        proxy.stop()
        assertEquals(1L, proxy.stats().poolMisses)
        assertEquals(1L, proxy.stats().directPoolDiagnostics.missesByKey["DC2.normal"])
        assertTrue((proxy.stats().directPoolDiagnostics.lastMissTimeMsByKey["DC2.normal"] ?: 0L) > 0L)
    }

    @Test
    fun directPoolRefillErrorIncrementsPerKeyErrorCounter() {
        val logs = CopyOnWriteArrayList<String>()
        val proxy = newProxy(
            server = FakeTcpServerTransport(),
            connector = RawWebSocketConnector { _, _, _, _ -> throw IOException("refill timeout") },
            config = baseConfig().copy(poolSize = 1, cfproxyEnabled = false, dcRedirects = mapOf(2 to "203.0.113.2")),
            logger = ProxyLogger { logs.add(it) },
        )
        proxy.start()
        waitUntil { logs.any { it.contains("DC2 direct WS pool refill failed") } }
        proxy.stop()
        val stats = proxy.stats()
        assertTrue(stats.poolRefillErrors > 0L)
        assertTrue((stats.directPoolDiagnostics.refillErrorsByKey["DC2.normal.normal"] ?: 0L) > 0L)
        assertTrue(stats.directPoolDiagnostics.lastRefillErrorByKey["DC2.normal.normal"]?.contains("refill timeout") == true)
    }

    @Test
    fun staleDirectPoolEntryIncrementsPerKeyStaleCounter() {
        val client = FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes())
        val server = FakeTcpServerTransport()
        val logs = CopyOnWriteArrayList<String>()
        val attempts = AtomicInteger(0)
        val proxy = newProxy(
            server = server,
            connector = RawWebSocketConnector { _, domain, _, _ ->
                if (attempts.incrementAndGet() == 1 && domain == "kws2.web.telegram.org") FakeWebSocketBinaryStream(sendError = SocketException("Broken pipe")) else FakeWebSocketBinaryStream()
            },
            config = baseConfig().copy(poolSize = 1, cfproxyEnabled = false, dcRedirects = mapOf(2 to "203.0.113.2")),
            runner = ProxyBridgeRunner { _, _, _, _, _ -> },
            logger = ProxyLogger { logs.add(it) },
        )
        proxy.start()
        waitUntil { logs.any { it.contains("WS pool refilled DC2: 1 ready") } }
        server.enqueue(client)
        waitUntil { client.closed }
        proxy.stop()
        assertEquals(1L, proxy.stats().poolStale)
        assertEquals(1L, proxy.stats().directPoolDiagnostics.staleByKey["DC2.normal"])
    }

    @Test
    fun idleMaintenanceCountersAreAttributedToMaintenanceSource() {
        val attempts = CopyOnWriteArrayList<String>()
        val pool = WebSocketPool(
            poolSize = 1,
            connector = RawWebSocketConnector { _, _, _, _ -> FakeWebSocketBinaryStream() },
            onRefillAttempt = { key, source -> attempts.add("DC${key.dc}.${if (key.isMedia) "media" else "normal"}.$source") },
        )
        pool.ensureMinReadyForDc(2, "203.0.113.2", 1, ::wsDomains)
        waitUntil { attempts.contains("DC2.normal.maintenance") && attempts.contains("DC2.media.maintenance") }
        assertEquals(1, pool.readySnapshot()[WebSocketPool.Key(2, false)])
        pool.closeAll()
    }

    @Test
    fun wakeBurstPrewarmCountersAreAttributedToWakePrewarmSource() {
        val attempts = CopyOnWriteArrayList<String>()
        val pool = WebSocketPool(
            poolSize = 1,
            connector = RawWebSocketConnector { _, _, _, _ -> FakeWebSocketBinaryStream() },
            onRefillAttempt = { key, source -> attempts.add("DC${key.dc}.${if (key.isMedia) "media" else "normal"}.$source") },
        )
        pool.prewarmDc(2, "203.0.113.2", ::wsDomains)
        waitUntil { attempts.contains("DC2.normal.wake-prewarm") && attempts.contains("DC2.media.wake-prewarm") }
        pool.closeAll()
    }

    @Test
    fun aggregateCountersRemainBackwardCompatible() {
        val stats = ProxyServerStats().apply {
            connectionsTotal = 0
            connectionsActive = 0
            connectionsBad = 0
            wsConnectErrors = 0
            cfProxyConnections = 0
            cfProxyErrors = 0
            bytesUp = 0
            bytesDown = 0
            poolHits = 1
            poolMisses = 2
            poolRefillErrors = 3
            poolStale = 4
        }
        assertEquals(1L, stats.poolHits)
        assertEquals(2L, stats.poolMisses)
        assertEquals(3L, stats.poolRefillErrors)
        assertEquals(4L, stats.poolStale)
        assertTrue(stats.directPoolDiagnostics.hitsByKey.isEmpty())
    }


    @Test
    fun stopClosesIdlePooledSockets() {
        val server = FakeTcpServerTransport()
        val logs = CopyOnWriteArrayList<String>()
        val sockets = CopyOnWriteArrayList<FakeWebSocketBinaryStream>()
        val connector = RawWebSocketConnector { _, _, _, _ ->
            FakeWebSocketBinaryStream().also { sockets.add(it) }
        }
        val proxy = newProxy(
            server = server,
            connector = connector,
            config = baseConfig().copy(poolSize = 1, dcRedirects = mapOf(2 to "203.0.113.2")),
            logger = ProxyLogger { logs.add(it) },
        )

        proxy.start()
        waitUntil { logs.count { it.contains("WS pool refilled DC2") } >= 2 }
        proxy.stop()

        waitUntil { sockets.all { it.closed } }
    }

    @Test
    fun directPoolAndColdDirectFailureStillRunsCfFallback() {
        val client = FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes())
        val server = FakeTcpServerTransport()
        val events = CopyOnWriteArrayList<String>()
        val logs = CopyOnWriteArrayList<String>()
        val connector = FailingDirectThenCfConnector(FakeWebSocketBinaryStream(events))
        val proxy = newProxy(
            server = server,
            connector = connector,
            config = baseConfig().copy(poolSize = 1, cfProxyDomains = listOf("cf.example"), dcRedirects = mapOf(2 to "203.0.113.2")),
            runner = ProxyBridgeRunner { _, _, _, _, _ -> events.add("bridge") },
            logger = ProxyLogger { logs.add(it) },
        )

        proxy.start()
        waitUntil { logs.any { it.contains("direct WS pool refill failed via kws2.web.telegram.org") } }
        server.enqueue(client)
        waitUntil { events.contains("bridge") }
        proxy.stop()

        assertTrue(connector.domains.contains("kws2.cf.example"))
        assertEquals(1L, proxy.stats().cfProxyConnections)
        assertTrue(logs.any { it.contains("DC2 direct WS pool miss") })
        assertTrue(logs.any { it.contains("DC2 -> trying CF proxy wss://kws2.cf.example/apiws") })
    }

    @Test
    fun bridgeRunnerReceivesMsgSplitterAndCryptoContext() {
        val client = FakeTcpClientTransport(handshakeVector("intermediate_dc4").getString("handshake_hex").hexToBytes())
        val server = FakeTcpServerTransport()
        val sawCrypto = AtomicBoolean(false)
        val sawSplitter = AtomicBoolean(false)
        val proxy =
            newProxy(
                server = server,
                connector = RecordingConnector(FakeWebSocketBinaryStream()),
                runner =
                    ProxyBridgeRunner { _, _, cryptoContext, splitter, _ ->
                        sawCrypto.set(cryptoContext.encryptToTelegram(ByteArray(0)).isEmpty())
                        sawSplitter.set(splitter.flush().isEmpty())
                    },
            )

        proxy.start()
        server.enqueue(client)
        waitUntil { sawCrypto.get() && sawSplitter.get() }
        proxy.stop()

        assertTrue(sawCrypto.get())
        assertTrue(sawSplitter.get())
    }


    @Test
    fun autoRouteModeResolvesWifiToCfFirstBeforeDirectHealthPromotion() {
        val config = baseConfig().copy(routeMode = NetworkRouteMode.AUTO, networkStatus = "Wi-Fi")

        assertEquals(NetworkRouteMode.CF_FIRST, config.effectiveRouteMode)
    }

    @Test
    fun autoRouteModeResolvesMobileToCfFirst() {
        val config = baseConfig().copy(routeMode = NetworkRouteMode.AUTO, networkStatus = "mobile")

        assertEquals(NetworkRouteMode.CF_FIRST, config.effectiveRouteMode)
    }

    @Test
    fun cfOnlyDoesNotUseDirectConnectorOrPoolPath() {
        val client = FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes())
        val server = FakeTcpServerTransport()
        val connector = RecordingConnector(FakeWebSocketBinaryStream())
        val proxy = newProxy(
            server = server,
            connector = connector,
            config = baseConfig().copy(
                poolSize = 1,
                routeMode = NetworkRouteMode.CF_ONLY,
                cfProxyDomains = listOf("cf.example"),
            ),
        )

        proxy.start()
        server.enqueue(client)
        waitUntil { client.closed }
        proxy.stop()

        assertEquals(listOf("kws2.cf.example"), connector.targetHosts)
        assertEquals(0L, proxy.stats().poolHits)
        assertEquals(0L, proxy.stats().poolMisses)
        assertEquals(NetworkRouteMode.CF_ONLY.configValue, proxy.stats().effectiveRouteMode)
    }

    @Test
    fun directFirstKeepsDirectBeforeCfFallbackBehavior() {
        val client = FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes())
        val server = FakeTcpServerTransport()
        val connector = FailingDirectThenCfConnector(FakeWebSocketBinaryStream())
        val proxy = newProxy(
            server = server,
            connector = connector,
            config = baseConfig().copy(routeMode = NetworkRouteMode.DIRECT_FIRST, cfProxyDomains = listOf("cf.example")),
        )

        proxy.start()
        server.enqueue(client)
        waitUntil { client.closed }
        proxy.stop()

        assertTrue(connector.domains.first().endsWith(".web.telegram.org"))
        assertTrue(connector.domains.last().endsWith(".cf.example"))
        assertEquals(NetworkRouteMode.DIRECT_FIRST.configValue, proxy.stats().effectiveRouteMode)
    }

    @Test
    fun cfFirstUsesShortDirectTimeoutOnlyAfterCfFailure() {
        val client = FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes())
        val server = FakeTcpServerTransport()
        val connector = TimeoutRecordingConnector()
        val proxy = newProxy(
            server = server,
            connector = connector,
            config = baseConfig().copy(
                routeMode = NetworkRouteMode.CF_FIRST,
                cfProxyDomains = listOf("cf.example"),
                directFallbackTimeoutMs = 1_500,
            ),
        )

        proxy.start()
        server.enqueue(client)
        waitUntil { client.closed }
        proxy.stop()

        assertEquals(listOf("kws2.cf.example", "kws2.web.telegram.org"), connector.domains)
        assertEquals(listOf(RawWebSocket.DEFAULT_CONNECT_TIMEOUT_MS, 1_500), connector.timeouts)
        assertEquals(1L, proxy.stats().directTimeouts)
        assertEquals(0L, proxy.stats().poolMisses)
    }




    @Test
    fun saturatedCfPressureSuppressesCfButKeepsAllowedDirectFallback() {
        val server = FakeTcpServerTransport()
        val logs = CopyOnWriteArrayList<String>()
        val domains = CopyOnWriteArrayList<String>()
        val health = CfDomainHealth(listOf("one.example", "two.example"), jitterRatio = { 0.0 })
        repeat(8) { index ->
            health.recordFailure(2, false, if (index % 2 == 0) "one.example" else "two.example", RuntimeException("HTTP 429"), "mobile", false)
        }
        val connector = RawWebSocketConnector { _, domain, _, _ ->
            domains.add(domain)
            if (domain.endsWith(".example")) throw IOException("HTTP 429 planned for $domain")
            FakeWebSocketBinaryStream()
        }
        val proxy = newProxy(
            server = server,
            connector = connector,
            config = baseConfig().copy(
                routeMode = NetworkRouteMode.CF_FIRST,
                networkStatus = "Wi-Fi",
                cfProxyDomains = listOf("one.example", "two.example"),
            ),
            logger = ProxyLogger { logs.add(it) },
            cfDomainHealth = health,
        )

        proxy.start()
        server.enqueue(FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes()))
        waitUntil { domains.any { it.endsWith(".web.telegram.org") } }
        server.enqueue(FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes()))
        waitUntil { domains.count { it.endsWith(".web.telegram.org") } == 2 }
        proxy.stop()

        assertEquals("only the saturated probe should start a CF connect", 1, domains.count { it.startsWith("kws2.") && it.endsWith(".example") })
        assertEquals("direct fallback policy must remain available under CF pressure", 2, domains.count { it.endsWith(".web.telegram.org") })
        assertTrue("expected second saturated request to suppress a probe inside the probe window", proxy.stats().cfPressureProbeSuppressed >= 1L)
        assertTrue("expected suppressed saturated probe to be counted as a pressure controlled failure", proxy.stats().cfPressureControlledFailures >= 1L)
        assertTrue("expected pressure suppression log", logs.any { it.contains("CF pressure saturated: probe suppressed") })
    }

    @Test
    fun saturatedCfPressureDoesNotUseDirectFallbackWhenCfOnlyForbidsIt() {
        val server = FakeTcpServerTransport()
        val logs = CopyOnWriteArrayList<String>()
        val domains = CopyOnWriteArrayList<String>()
        val health = CfDomainHealth(listOf("one.example", "two.example"), jitterRatio = { 0.0 })
        repeat(8) { index ->
            health.recordFailure(2, false, if (index % 2 == 0) "one.example" else "two.example", RuntimeException("HTTP 429"), "mobile", false)
        }
        val connector = RawWebSocketConnector { _, domain, _, _ ->
            domains.add(domain)
            throw IOException("HTTP 429 planned for $domain")
        }
        val proxy = newProxy(
            server = server,
            connector = connector,
            config = baseConfig().copy(
                routeMode = NetworkRouteMode.CF_ONLY,
                networkStatus = "Wi-Fi",
                cfProxyDomains = listOf("one.example", "two.example"),
            ),
            logger = ProxyLogger { logs.add(it) },
            cfDomainHealth = health,
        )

        proxy.start()
        server.enqueue(FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes()))
        waitUntil { logs.any { it.contains("no route available after CF-only attempts") } }
        server.enqueue(FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes()))
        waitUntil { logs.count { it.contains("no route available after CF-only attempts") } == 2 }
        proxy.stop()

        assertEquals("CF_ONLY should allow exactly one saturated CF probe in the probe window", 1, domains.count { it.startsWith("kws2.") && it.endsWith(".example") })
        assertEquals("CF_ONLY must not use direct fallback when saturated pressure suppresses CF", 0, domains.count { it.endsWith(".web.telegram.org") })
        assertTrue("expected saturated CF_ONLY request to suppress second probe", proxy.stats().cfPressureProbeSuppressed >= 1L)
        assertTrue("expected saturated CF_ONLY suppression to be a pressure controlled failure", proxy.stats().cfPressureControlledFailures >= 1L)
        assertTrue("expected pressure suppression log", logs.any { it.contains("CF pressure saturated: probe suppressed") })
    }

    @Test
    fun cfAllCooldownCircuitSuppressesRepeatedCfConnectButKeepsDirectFallback() {
        val server = FakeTcpServerTransport()
        val logs = CopyOnWriteArrayList<String>()
        val domains = CopyOnWriteArrayList<String>()
        val connector = RawWebSocketConnector { _, domain, _, _ ->
            domains.add(domain)
            if (domain.endsWith(".cf.example")) throw IOException("HTTP 429 planned for $domain")
            FakeWebSocketBinaryStream()
        }
        val proxy = newProxy(
            server = server,
            connector = connector,
            config = baseConfig().copy(
                routeMode = NetworkRouteMode.CF_FIRST,
                cfProxyDomains = listOf("cf.example"),
            ),
            logger = ProxyLogger { logs.add(it) },
        )

        proxy.start()
        server.enqueue(FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes()))
        waitUntil { domains.count { it == "kws2.cf.example" } == 1 && domains.any { it == "kws2.web.telegram.org" } }

        server.enqueue(FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes()))
        waitUntil {
            domains.count { it == "kws2.cf.example" } == 2 &&
                domains.count { it == "kws2.web.telegram.org" } == 2
        }

        server.enqueue(FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes()))
        waitUntil { proxy.stats().connectionsTotal == 3L && domains.count { it == "kws2.web.telegram.org" } == 3 }
        proxy.stop()

        assertEquals(
            "normal CF failure plus one allowed all-cooldown least-bad attempt are expected; suppressed retry must not start a third CF connect",
            2,
            domains.count { it == "kws2.cf.example" },
        )
        assertEquals(
            "CF_FIRST direct fallback must still run after normal, allowed all-cooldown, and suppressed all-cooldown CF failures",
            3,
            domains.count { it == "kws2.web.telegram.org" },
        )
        assertEquals("exactly one repeated all-cooldown attempt should be suppressed", 1L, proxy.stats().cfAllCooldownAttemptsSuppressed)
        assertEquals("suppression should be counted as a controlled failure", 1L, proxy.stats().cfAllCooldownControlledFailures)
        assertTrue(logs.any { it.contains("CF all-cooldown circuit suppressed single least-bad attempt for DC2") })
    }

    @Test
    fun cfAllCooldownCircuitSuppressionFailsWhenNoFallbackRouteAllowed() {
        val server = FakeTcpServerTransport()
        val logs = CopyOnWriteArrayList<String>()
        val domains = CopyOnWriteArrayList<String>()
        val connector = RawWebSocketConnector { _, domain, _, _ ->
            domains.add(domain)
            throw IOException("HTTP 429 planned for $domain")
        }
        val proxy = newProxy(
            server = server,
            connector = connector,
            config = baseConfig().copy(
                routeMode = NetworkRouteMode.CF_ONLY,
                cfProxyDomains = listOf("cf.example"),
            ),
            logger = ProxyLogger { logs.add(it) },
        )

        proxy.start()
        server.enqueue(FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes()))
        waitUntil { domains.count { it == "kws2.cf.example" } == 1 }

        server.enqueue(FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes()))
        waitUntil { domains.count { it == "kws2.cf.example" } == 2 }

        server.enqueue(FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes()))
        waitUntil { proxy.stats().connectionsTotal == 3L && logs.count { it.contains("no route available after CF-only attempts") } == 3 }
        proxy.stop()

        assertEquals(
            "CF_ONLY should perform the initial CF failure and one allowed all-cooldown attempt, but suppression must not open another CF connect",
            2,
            domains.count { it == "kws2.cf.example" },
        )
        assertEquals("CF_ONLY must not use direct fallback", 0, domains.count { it.endsWith(".web.telegram.org") })
        assertEquals("third CF-only all-cooldown attempt should be suppressed", 1L, proxy.stats().cfAllCooldownAttemptsSuppressed)
        assertTrue(logs.any { it.contains("CF all-cooldown controlled failure for DC2 instead of starting another connect") })
    }



    @Test
    fun autoMobileCfFirstCfExhaustedAllowsBoundedDirectRescueWithoutPool() {
        val server = FakeTcpServerTransport()
        val logs = CopyOnWriteArrayList<String>()
        val domains = CopyOnWriteArrayList<String>()
        val connector = RawWebSocketConnector { _, domain, _, _ ->
            domains.add(domain)
            if (domain.endsWith(".cf.example")) throw IOException("HTTP 429 planned for $domain")
            FakeWebSocketBinaryStream()
        }
        val proxy = newProxy(
            server = server,
            connector = connector,
            config = baseConfig().copy(
                routeMode = NetworkRouteMode.AUTO,
                networkStatus = "mobile",
                poolSize = 1,
                cfPoolEnabled = false,
                cfProxyDomains = listOf("cf.example"),
            ),
            logger = ProxyLogger { logs.add(it) },
        )

        proxy.start()
        server.enqueue(FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes()))
        waitUntil { domains.any { it == "kws2.web.telegram.org" } }
        proxy.stop()

        val stats = proxy.stats()
        assertEquals(NetworkRouteMode.CF_FIRST.configValue, stats.effectiveRouteMode)
        assertEquals(1L, stats.mobileDirectRescueAttempts)
        assertEquals(1L, stats.mobileDirectRescueSuccesses)
        assertEquals(0L, stats.poolHits)
        assertEquals(0L, stats.poolMisses)
        assertTrue(domains.contains("kws2.cf.example"))
        assertTrue(domains.contains("kws2.web.telegram.org"))
        assertTrue(logs.any { it.contains("mobile direct rescue allowed because CF exhausted") })
        assertTrue(logs.any { it.contains("mobile direct rescue success") })
    }

    @Test
    fun autoMobileDirectRescueFailureSetsCooldownAndSuppressesRepeat() {
        val server = FakeTcpServerTransport()
        val logs = CopyOnWriteArrayList<String>()
        val domains = CopyOnWriteArrayList<String>()
        val connector = RawWebSocketConnector { _, domain, _, _ ->
            domains.add(domain)
            if (domain.endsWith(".cf.example")) throw IOException("HTTP 429 planned for $domain")
            throw IOException("planned direct rescue failure for $domain")
        }
        val proxy = newProxy(
            server = server,
            connector = connector,
            config = baseConfig().copy(
                routeMode = NetworkRouteMode.AUTO,
                networkStatus = "mobile",
                cfProxyDomains = listOf("cf.example"),
            ),
            logger = ProxyLogger { logs.add(it) },
        )

        proxy.start()
        server.enqueue(FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes()))
        waitUntil { logs.any { it.contains("mobile direct rescue failed") } }
        assertEquals("first exhausted mobile request should make one direct rescue attempt", 1, domains.count { it == "kws2.web.telegram.org" })
        server.enqueue(FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes()))
        waitUntil { proxy.stats().mobileDirectRescueSuppressed >= 1L }
        proxy.stop()

        val stats = proxy.stats()
        assertEquals("only the first request may start mobile direct rescue", 1L, stats.mobileDirectRescueAttempts)
        assertEquals("failed direct rescue must be counted", 1L, stats.mobileDirectRescueFailures)
        assertTrue("failed direct rescue must set a per-DC cooldown", stats.mobileDirectRescueCooldownUntil[2] ?: 0L > 0L)
        assertTrue("last rescue error should be the direct failure", stats.mobileDirectRescueLastError[2]?.contains("planned direct rescue failure") == true)
        assertEquals("rescue cooldown must prevent a second mobile direct attempt", 1, domains.count { it == "kws2.web.telegram.org" })
        assertEquals("mobile rescue must not use the direct pool", 0L, stats.poolHits + stats.poolMisses)
        assertTrue("second request should suppress rescue inside cooldown", logs.any { it.contains("mobile direct rescue suppressed because cooldown") })
    }


    @Test
    fun autoRouteReevaluatesMobileCfExhaustedAttemptAfterWifiArrives() {
        val server = FakeTcpServerTransport()
        val logs = CopyOnWriteArrayList<String>()
        val domains = CopyOnWriteArrayList<String>()
        var proxy: ProxyServer? = null
        val connector = RawWebSocketConnector { _, domain, _, _ ->
            domains.add(domain)
            if (domain.endsWith(".cf.example")) {
                proxy!!.applyNetworkRoute("Wi-Fi")
                throw IOException("HTTP 429 planned for $domain")
            }
            FakeWebSocketBinaryStream()
        }
        proxy = newProxy(
            server = server,
            connector = connector,
            config = baseConfig().copy(
                routeMode = NetworkRouteMode.AUTO,
                networkStatus = "mobile",
                cfProxyDomains = listOf("cf.example"),
            ),
            logger = ProxyLogger { logs.add(it) },
        )

        proxy.start()
        server.enqueue(FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes()))
        waitUntil { proxy!!.stats().lastRouteUsed?.startsWith("direct") == true }
        proxy.stop()

        val stats = proxy.stats()
        assertEquals(NetworkRouteMode.DIRECT_FIRST.configValue, stats.effectiveRouteMode)
        assertTrue("route attempt should observe generation change and re-evaluate", stats.routeAttemptNetworkChangedBeforeSelection >= 1L)
        assertEquals("Wi-Fi re-evaluation must not run mobile rescue", 0L, stats.mobileDirectRescueAttempts)
        assertEquals("Wi-Fi re-evaluation must not inherit mobile rescue cooldown", 0L, stats.mobileDirectRescueSuppressed)
        assertTrue(domains.contains("kws2.cf.example"))
        assertTrue(domains.any { it == "kws2.web.telegram.org" })
        assertTrue(logs.any { it.contains("re-evaluating route policy") })
        assertFalse(logs.any { it.contains("CF exhausted on mobile") })
    }

    @Test
    fun autoWifiProbeInProgressAllowsColdDirectInsteadOfCfPressureNoRoute() {
        val server = FakeTcpServerTransport()
        val logs = CopyOnWriteArrayList<String>()
        val domains = CopyOnWriteArrayList<String>()
        val probeEntered = CountDownLatch(1)
        val releaseProbe = CountDownLatch(1)
        val probeAttempts = AtomicInteger(0)
        val health = CfDomainHealth(listOf("one.example", "two.example"), jitterRatio = { 0.0 })
        repeat(8) { index ->
            health.recordFailure(2, false, if (index % 2 == 0) "one.example" else "two.example", RuntimeException("HTTP 429"), "mobile", false)
        }
        val connector = RawWebSocketConnector { _, domain, _, _ ->
            domains.add(domain)
            if (domain.endsWith(".example") && !domain.endsWith(".web.telegram.org")) throw IOException("HTTP 429 planned for $domain")
            if (domain == "kws2.web.telegram.org" && probeAttempts.getAndIncrement() == 0) {
                probeEntered.countDown()
                releaseProbe.await(2, TimeUnit.SECONDS)
            }
            FakeWebSocketBinaryStream()
        }
        val proxy = newProxy(
            server = server,
            connector = connector,
            config = baseConfig().copy(
                routeMode = NetworkRouteMode.AUTO,
                networkStatus = "mobile",
                cfProxyDomains = listOf("one.example", "two.example"),
            ),
            logger = ProxyLogger { logs.add(it) },
            cfDomainHealth = health,
        )

        proxy.start()
        proxy.applyNetworkRoute("Wi-Fi")
        waitUntil { probeEntered.count == 0L }
        server.enqueue(FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes()))
        waitUntil { proxy.stats().wifiDirectRecoverySuccesses == 1L }
        releaseProbe.countDown()
        proxy.stop()

        val stats = proxy.stats()
        assertEquals("direct-cold", stats.lastRouteUsed)
        assertEquals(1L, stats.wifiDirectRecoveryAttempts)
        assertEquals(1L, stats.wifiDirectRecoverySuccesses)
        assertEquals(0L, stats.mobileDirectRescueAttempts)
        assertEquals("CF pressure exhaustion from mobile must not block Wi-Fi direct recovery", 0L, stats.cfPressureProbeSuppressed)
        assertTrue(domains.any { it == "kws2.web.telegram.org" })
        assertFalse(domains.any { it == "kws2.one.example" || it == "kws2.two.example" })
        assertFalse(logs.any { it.contains("no route available after CF-first attempts") })
        assertFalse(logs.any { it.contains("CF exhausted on mobile") })
    }

    @Test
    fun mobileDirectRescueCooldownDoesNotSuppressWifiDirectAttempts() {
        val server = FakeTcpServerTransport()
        val logs = CopyOnWriteArrayList<String>()
        val domains = CopyOnWriteArrayList<String>()
        var failDirectOnMobile = true
        val connector = RawWebSocketConnector { _, domain, _, _ ->
            domains.add(domain)
            if (domain.endsWith(".cf.example")) throw IOException("HTTP 429 planned for $domain")
            if (failDirectOnMobile) throw IOException("planned mobile direct rescue failure for $domain")
            FakeWebSocketBinaryStream()
        }
        val proxy = newProxy(
            server = server,
            connector = connector,
            config = baseConfig().copy(
                routeMode = NetworkRouteMode.AUTO,
                networkStatus = "mobile",
                cfProxyDomains = listOf("cf.example"),
            ),
            logger = ProxyLogger { logs.add(it) },
        )

        proxy.start()
        server.enqueue(FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes()))
        waitUntil { proxy.stats().mobileDirectRescueFailures == 1L }
        failDirectOnMobile = false
        proxy.applyNetworkRoute("Wi-Fi")
        server.enqueue(FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes()))
        waitUntil { proxy.stats().lastRouteUsed?.startsWith("direct") == true && proxy.stats().directAttempts >= 2L }
        proxy.stop()

        val stats = proxy.stats()
        assertTrue("mobile rescue failure should leave cooldown for mobile", stats.mobileDirectRescueCooldownUntil[2] ?: 0L > 0L)
        assertEquals("Wi-Fi route must not add another mobile rescue attempt", 1L, stats.mobileDirectRescueAttempts)
        assertTrue("Wi-Fi direct should be attempted despite mobile rescue cooldown", domains.count { it == "kws2.web.telegram.org" } >= 2)
        assertFalse(logs.dropWhile { !it.contains("network changed from mobile to Wi-Fi") }.any { it.contains("mobile direct rescue suppressed because cooldown") })
    }

    @Test
    fun cfOnlyMobileNeverAllowsDirectRescueWhenCfExhausted() {
        val server = FakeTcpServerTransport()
        val logs = CopyOnWriteArrayList<String>()
        val domains = CopyOnWriteArrayList<String>()
        val connector = RawWebSocketConnector { _, domain, _, _ ->
            domains.add(domain)
            throw IOException("HTTP 429 planned for $domain")
        }
        val proxy = newProxy(
            server = server,
            connector = connector,
            config = baseConfig().copy(
                routeMode = NetworkRouteMode.CF_ONLY,
                networkStatus = "mobile",
                cfProxyDomains = listOf("cf.example"),
            ),
            logger = ProxyLogger { logs.add(it) },
        )

        proxy.start()
        server.enqueue(FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes()))
        waitUntil { logs.any { it.contains("no route available after CF-only attempts") } }
        proxy.stop()

        assertEquals(0L, proxy.stats().mobileDirectRescueAttempts)
        assertEquals(0, domains.count { it.endsWith(".web.telegram.org") })
        assertTrue(logs.any { it.contains("CF exhausted but CF_ONLY forbids direct rescue") })
    }

    @Test
    fun networkChangeMobileToWifiKeepsCfFirstUntilDirectHealthSucceeds() {
        val server = FakeTcpServerTransport()
        val logs = CopyOnWriteArrayList<String>()
        val proxy = newProxy(
            server = server,
            config = baseConfig().copy(routeMode = NetworkRouteMode.AUTO, networkStatus = "mobile"),
            connector = RawWebSocketConnector { _, domain, _, _ -> throw IOException("probe blocked for $domain") },
            logger = ProxyLogger { logs.add(it) },
        )

        proxy.start()
        val before = proxy
        val result = proxy.applyNetworkRoute("Wi-Fi")
        waitUntil { proxy.stats().directHealthFailures > 0L }
        proxy.stop()

        assertFalse(result.changed)
        assertTrue(before === proxy)
        assertEquals(NetworkRouteMode.CF_FIRST.configValue, proxy.stats().effectiveRouteMode)
        assertNull(proxy.stats().previousEffectiveRouteMode)
        assertTrue(logs.any { it.contains("direct health probe failed") })
    }

    @Test
    fun staleWifiHealthPromotionCallbackCannotRepromoteAfterMobileTransition() {
        val server = FakeTcpServerTransport()
        val logs = CopyOnWriteArrayList<String>()
        val proxy = newProxy(
            server = server,
            config = baseConfig().copy(routeMode = NetworkRouteMode.AUTO, networkStatus = "mobile", poolSize = 0),
            logger = ProxyLogger { logs.add(it) },
        )

        proxy.start()
        proxy.applyEffectiveRouteMode(NetworkRouteMode.DIRECT_FIRST, "test promoted", "Wi-Fi")
        proxy.applyNetworkRouteImmediately("mobile")
        assertEquals(NetworkRouteMode.CF_FIRST.configValue, proxy.stats().effectiveRouteMode)

        val method = ProxyServer::class.java.getDeclaredMethod("commitAutoWifiDirectPromotionIfStillValid")
        method.isAccessible = true
        val staleResult = method.invoke(proxy)
        proxy.stop()

        assertNull("stale Wi-Fi health callback must be discarded on mobile", staleResult)
        assertEquals(NetworkRouteMode.CF_FIRST.configValue, proxy.stats().effectiveRouteMode)
        assertTrue(logs.any { it.contains("direct promotion discarded: stale Wi-Fi health probe") && it.contains("network=mobile") })
        assertFalse(logs.dropWhile { !it.contains("network=mobile") }.any { it.contains("direct promoted:") })
    }

    @Test
    fun networkChangeWifiToMobileUpdatesEffectiveRouteToCfFirst() {
        val server = FakeTcpServerTransport()
        val logs = CopyOnWriteArrayList<String>()
        val proxy = newProxy(
            server = server,
            config = baseConfig().copy(routeMode = NetworkRouteMode.AUTO, networkStatus = "Wi-Fi"),
            logger = ProxyLogger { logs.add(it) },
        )

        proxy.start()
        proxy.applyEffectiveRouteMode(NetworkRouteMode.DIRECT_FIRST, "test promoted", "Wi-Fi")
        val result = proxy.applyNetworkRoute("mobile")
        proxy.stop()

        assertTrue(result.changed)
        assertEquals(NetworkRouteMode.CF_FIRST.configValue, proxy.stats().effectiveRouteMode)
        assertEquals(NetworkRouteMode.DIRECT_FIRST.configValue, proxy.stats().previousEffectiveRouteMode)
        assertTrue(logs.any { it.contains("effective route changed: direct_first -> cf_first because network=mobile") })
    }

    @Test
    fun routeSwitchToCfFirstDisablesAndSkipsPoolForNewSessions() {
        val client = FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes())
        val server = FakeTcpServerTransport()
        val logs = CopyOnWriteArrayList<String>()
        val connector = RecordingConnector(FakeWebSocketBinaryStream())
        val proxy = newProxy(
            server = server,
            connector = connector,
            config = baseConfig().copy(
                poolSize = 3,
                routeMode = NetworkRouteMode.AUTO,
                networkStatus = "Wi-Fi",
                cfProxyDomains = listOf("cf.example"),
            ),
            logger = ProxyLogger { logs.add(it) },
        )

        proxy.start()
        proxy.applyNetworkRoute("mobile")
        server.enqueue(client)
        waitUntil { client.closed }
        proxy.stop()

        assertEquals(NetworkRouteMode.CF_FIRST.configValue, proxy.stats().effectiveRouteMode)
        assertEquals(0L, proxy.stats().poolHits)
        assertEquals(0L, proxy.stats().poolMisses)
        assertTrue(connector.domains.any { it == "kws2.cf.example" })
        assertTrue(logs.any { it.contains("Direct WS pool warmup skipped because effective route mode cf_first") })
    }

    @Test
    fun routeSwitchToDirectFirstAllowsPoolWarmup() {
        val server = FakeTcpServerTransport()
        val logs = CopyOnWriteArrayList<String>()
        val connector = RawWebSocketConnector { _, _, _, _ -> FakeWebSocketBinaryStream() }
        val proxy = newProxy(
            server = server,
            connector = connector,
            config = baseConfig().copy(
                poolSize = 1,
                routeMode = NetworkRouteMode.AUTO,
                networkStatus = "mobile",
                dcRedirects = mapOf(2 to "203.0.113.2"),
            ),
            logger = ProxyLogger { logs.add(it) },
        )

        proxy.start()
        proxy.applyNetworkRoute("Wi-Fi")
        waitUntil { proxy.stats().directPromotions > 0L }
        proxy.stop()

        assertEquals(NetworkRouteMode.DIRECT_FIRST.configValue, proxy.stats().effectiveRouteMode)
        assertTrue(logs.any { it.contains("direct promoted: cf_first -> direct_first") })
        assertTrue(logs.any { it.contains("WS pool warmup started") })
    }


    @Test
    fun networkLostBypassesDebounceAndAppliesSafeRouteImmediately() {
        val server = FakeTcpServerTransport()
        val logs = CopyOnWriteArrayList<String>()
        val proxy = newProxy(
            server = server,
            config = baseConfig().copy(routeMode = NetworkRouteMode.AUTO, networkStatus = "Wi-Fi"),
            logger = ProxyLogger { logs.add(it) },
        )

        proxy.start()
        proxy.applyEffectiveRouteMode(NetworkRouteMode.DIRECT_FIRST, "test promoted", "Wi-Fi")
        val result = proxy.applyNetworkRouteImmediately("none")
        proxy.stop()

        assertTrue(result.changed)
        assertEquals("immediate", result.source)
        assertEquals(NetworkRouteMode.CF_FIRST.configValue, proxy.stats().effectiveRouteMode)
        assertEquals(1L, proxy.stats().routeChangesImmediate)
        assertEquals(1L, proxy.stats().networkNoneEvents)
        assertTrue(logs.any { it.contains("network lost: applying safe route immediately") })
        assertTrue(logs.any { it.contains("effective route changed: direct_first -> cf_first because network=none") })
    }

    @Test
    fun wifiToNoneClearsAndDisablesPoolImmediately() {
        val server = FakeTcpServerTransport()
        val logs = CopyOnWriteArrayList<String>()
        val connector = MultiSocketRecordingConnector()
        val proxy = newProxy(
            server = server,
            connector = connector,
            config = baseConfig().copy(poolSize = 1, routeMode = NetworkRouteMode.AUTO, networkStatus = "Wi-Fi"),
            logger = ProxyLogger { logs.add(it) },
        )

        proxy.start()
        proxy.applyEffectiveRouteMode(NetworkRouteMode.DIRECT_FIRST, "test promoted", "Wi-Fi")
        waitUntil { proxy.stats().directAttempts >= 4L }
        val result = proxy.applyNetworkRouteImmediately("none")
        proxy.stop()

        assertTrue(result.changed)
        assertEquals(NetworkRouteMode.CF_FIRST.configValue, proxy.stats().effectiveRouteMode)
        assertTrue(connector.sockets.all { it.closed })
        assertTrue(logs.any { it.contains("Direct WS pool warmup skipped because effective route mode cf_first") })
    }

    @Test
    fun inFlightPoolRefillResultIsDiscardedAfterRouteChangesToCfFirst() {
        val server = FakeTcpServerTransport()
        val connectorStarted = CountDownLatch(1)
        val allowConnect = CountDownLatch(1)
        val connector = RawWebSocketConnector { _, _, _, _ ->
            connectorStarted.countDown()
            assertTrue(allowConnect.await(2, TimeUnit.SECONDS))
            FakeWebSocketBinaryStream()
        }
        val proxy = newProxy(
            server = server,
            connector = connector,
            config = baseConfig().copy(
                poolSize = 1,
                routeMode = NetworkRouteMode.AUTO,
                networkStatus = "mobile",
                dcRedirects = mapOf(2 to "203.0.113.2"),
            ),
        )

        proxy.start()
        proxy.applyEffectiveRouteMode(NetworkRouteMode.DIRECT_FIRST, "test promoted", "Wi-Fi")
        assertTrue(connectorStarted.await(2, TimeUnit.SECONDS))
        proxy.applyNetworkRouteImmediately("none")
        allowConnect.countDown()
        waitUntil { proxy.stats().poolResultsDiscardedAfterRouteChange > 0L }
        proxy.stop()

        assertTrue(proxy.stats().poolRefillsCancelled > 0L)
        assertTrue(proxy.stats().poolResultsDiscardedAfterRouteChange > 0L)
    }

    @Test
    fun afterRouteDirectFirstToCfFirstNewSessionsDoNotUsePool() {
        val client = FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes())
        val server = FakeTcpServerTransport()
        val connector = MultiSocketRecordingConnector()
        val proxy = newProxy(
            server = server,
            connector = connector,
            config = baseConfig().copy(
                poolSize = 1,
                routeMode = NetworkRouteMode.AUTO,
                networkStatus = "Wi-Fi",
                cfPoolEnabled = false,
                cfProxyDomains = listOf("cf.example"),
            ),
        )

        proxy.start()
        proxy.applyEffectiveRouteMode(NetworkRouteMode.DIRECT_FIRST, "test promoted", "Wi-Fi")
        waitUntil("direct pool warmup connections recorded before route transition") {
            connector.domains.count { it.endsWith(".web.telegram.org") } >= 4
        }
        val directDomainsBefore = connector.domains.count { it.endsWith(".web.telegram.org") }
        proxy.applyEffectiveRouteMode(NetworkRouteMode.CF_FIRST, "test downgraded", "Wi-Fi")
        waitUntil("effective route is cf_first before new session") {
            proxy.stats().effectiveRouteMode == NetworkRouteMode.CF_FIRST.configValue
        }
        val beforeSessionStats = proxy.stats()
        server.enqueue(client)
        waitUntil { client.closed }
        waitUntil("new session used CF route") { proxy.stats().lastRouteUsed == "cf" }
        val afterSessionStats = proxy.stats()
        proxy.stop()

        val directDomainsAfter = connector.domains.count { it.endsWith(".web.telegram.org") }
        assertEquals(
            "New sessions after direct_first -> cf_first must not open additional direct domains; " +
                "configured=${afterSessionStats.routeMode}, effective=${afterSessionStats.effectiveRouteMode}, " +
                "reason=${afterSessionStats.lastRouteChangeReason}, directDomainsBefore=$directDomainsBefore, " +
                "directDomainsAfter=$directDomainsAfter, poolHitsBefore=${beforeSessionStats.poolHits}, " +
                "poolHitsAfter=${afterSessionStats.poolHits}, lastRouteUsed=${afterSessionStats.lastRouteUsed}",
            directDomainsBefore,
            directDomainsAfter,
        )
        assertEquals(
            "cf_first sessions must not use the old direct pool; " +
                "configured=${afterSessionStats.routeMode}, effective=${afterSessionStats.effectiveRouteMode}, " +
                "reason=${afterSessionStats.lastRouteChangeReason}, poolHitsBefore=${beforeSessionStats.poolHits}, " +
                "poolHitsAfter=${afterSessionStats.poolHits}, lastRouteUsed=${afterSessionStats.lastRouteUsed}",
            beforeSessionStats.poolHits,
            afterSessionStats.poolHits,
        )
        assertEquals(
            "New session after direct_first -> cf_first must use CF route; " +
                "configured=${afterSessionStats.routeMode}, effective=${afterSessionStats.effectiveRouteMode}, " +
                "reason=${afterSessionStats.lastRouteChangeReason}, lastRouteUsed=${afterSessionStats.lastRouteUsed}",
            "cf",
            afterSessionStats.lastRouteUsed,
        )
        assertTrue(
            "New session after direct_first -> cf_first must attempt CF domain; " +
                "domains=${connector.domains}, configured=${afterSessionStats.routeMode}, " +
                "effective=${afterSessionStats.effectiveRouteMode}, reason=${afterSessionStats.lastRouteChangeReason}, " +
                "lastRouteUsed=${afterSessionStats.lastRouteUsed}",
            connector.domains.any { it == "kws2.cf.example" },
        )
    }




    @Test
    fun poolStaleThresholdDowngradesAutoDirectFirstToCfFirst() {
        val server = FakeTcpServerTransport()
        val logs = CopyOnWriteArrayList<String>()
        val proxy = newProxy(
            server = server,
            connector = MultiSocketRecordingConnector(),
            config = baseConfig().copy(
                poolSize = 3,
                routeMode = NetworkRouteMode.AUTO,
                networkStatus = "mobile",
                dcRedirects = mapOf(2 to "203.0.113.2"),
            ),
            runner = ProxyBridgeRunner { _, _, _, _, counters ->
                counters.finish("exception: EOFException: no frame")
            },
            logger = ProxyLogger { logs.add(it) },
        )

        proxy.start()
        proxy.applyEffectiveRouteMode(NetworkRouteMode.DIRECT_FIRST, "test promoted", "Wi-Fi")
        waitUntil { proxy.stats().directAttempts >= 6L }
        repeat(3) {
            val client = FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes())
            server.enqueue(client)
            waitUntil { client.closed }
        }
        waitUntil { proxy.stats().directDowngrades > 0L }
        proxy.stop()

        assertEquals(NetworkRouteMode.CF_FIRST.configValue, proxy.stats().effectiveRouteMode)
        assertTrue(proxy.stats().poolStale >= 1L)
        assertEquals(DirectHealthState.COOLDOWN.configValue, proxy.stats().directHealthState)
        assertTrue(
            logs.any { it.contains("direct route downgraded to cf_first because health degraded") } ||
                logs.any { it.contains("direct route downgraded to cf_first because client experience degraded") },
        )
    }


    @Test
    fun autoWifiDirectFirstDowngradesToCfFirstWhenShortRemoteEofThresholdReached() {
        val server = FakeTcpServerTransport()
        val logs = CopyOnWriteArrayList<String>()
        val proxy = newProxy(
            server = server,
            config = baseConfig().copy(routeMode = NetworkRouteMode.AUTO, networkStatus = "Wi-Fi"),
            logger = ProxyLogger { logs.add(it) },
        )

        proxy.applyEffectiveRouteMode(NetworkRouteMode.DIRECT_FIRST, "test promoted", "Wi-Fi")
        addClientExperienceEvents(proxy, "recentShortRemoteEofTimes", 2)
        invokeClientExperienceDowngrade(proxy)

        val stats = proxy.stats()
        assertEquals(NetworkRouteMode.CF_FIRST.configValue, stats.effectiveRouteMode)
        assertEquals(1L, stats.clientExperience.clientExperienceDirectDowngrades)
        assertTrue(stats.clientExperience.lastClientExperienceDirectDowngradeReason!!.contains("recentShortRemoteEofSessions"))
        assertTrue(logs.any { it.contains("direct route downgraded to cf_first because client experience degraded") })
    }

    @Test
    fun autoWifiDirectFirstDowngradesToCfFirstWhenPoolStaleAndDirectTimeoutThresholdReached() {
        val server = FakeTcpServerTransport()
        val proxy = newProxy(
            server = server,
            config = baseConfig().copy(routeMode = NetworkRouteMode.AUTO, networkStatus = "Wi-Fi"),
        )

        proxy.applyEffectiveRouteMode(NetworkRouteMode.DIRECT_FIRST, "test promoted", "Wi-Fi")
        addClientExperienceEvents(proxy, "recentPoolStaleTimes", 1)
        addClientExperienceEvents(proxy, "recentDirectTimeoutTimes", 1)
        invokeClientExperienceDowngrade(proxy)

        val stats = proxy.stats()
        assertEquals(NetworkRouteMode.CF_FIRST.configValue, stats.effectiveRouteMode)
        assertEquals(1L, stats.clientExperience.clientExperienceDirectDowngrades)
        assertTrue(stats.clientExperience.lastClientExperienceDirectDowngradeReason!!.contains("recentPoolStale"))
    }

    @Test
    fun explicitDirectFirstDoesNotDowngradeForClientExperienceDegradation() {
        val server = FakeTcpServerTransport()
        val proxy = newProxy(
            server = server,
            config = baseConfig().copy(routeMode = NetworkRouteMode.DIRECT_FIRST, networkStatus = "Wi-Fi"),
        )

        addClientExperienceEvents(proxy, "recentShortRemoteEofTimes", 2)
        invokeClientExperienceDowngrade(proxy)

        val stats = proxy.stats()
        assertEquals(NetworkRouteMode.DIRECT_FIRST.configValue, stats.effectiveRouteMode)
        assertEquals(0L, stats.clientExperience.clientExperienceDirectDowngrades)
    }

    @Test
    fun alreadyCfFirstDoesNotRepeatClientExperienceDowngrade() {
        val server = FakeTcpServerTransport()
        val logs = CopyOnWriteArrayList<String>()
        val proxy = newProxy(
            server = server,
            config = baseConfig().copy(routeMode = NetworkRouteMode.AUTO, networkStatus = "Wi-Fi"),
            logger = ProxyLogger { logs.add(it) },
        )

        addClientExperienceEvents(proxy, "recentShortRemoteEofTimes", 2)
        invokeClientExperienceDowngrade(proxy)

        val stats = proxy.stats()
        assertEquals(NetworkRouteMode.CF_FIRST.configValue, stats.effectiveRouteMode)
        assertEquals(0L, stats.clientExperience.clientExperienceDirectDowngrades)
        assertFalse(logs.any { it.contains("direct route downgraded to cf_first because client experience degraded") })
    }

    @Test
    fun isolatedSingleRemoteIdleEofDoesNotClientExperienceDowngrade() {
        val server = FakeTcpServerTransport()
        val proxy = newProxy(
            server = server,
            config = baseConfig().copy(routeMode = NetworkRouteMode.AUTO, networkStatus = "Wi-Fi"),
        )

        proxy.applyEffectiveRouteMode(NetworkRouteMode.DIRECT_FIRST, "test promoted", "Wi-Fi")
        addClientExperienceEvents(proxy, "recentShortRemoteEofTimes", 1)
        invokeClientExperienceDowngrade(proxy)

        val stats = proxy.stats()
        assertEquals(NetworkRouteMode.DIRECT_FIRST.configValue, stats.effectiveRouteMode)
        assertEquals(0L, stats.clientExperience.clientExperienceDirectDowngrades)
    }

    @Test
    fun autoWifiPromotesToDirectFirstAfterRequiredSuccessfulHealthProbes() {
        val server = FakeTcpServerTransport()
        val logs = CopyOnWriteArrayList<String>()
        val attempts = AtomicInteger(0)
        val proxy = newProxy(
            server = server,
            connector = RawWebSocketConnector { _, _, _, _ ->
                attempts.incrementAndGet()
                FakeWebSocketBinaryStream()
            },
            config = baseConfig().copy(routeMode = NetworkRouteMode.AUTO, networkStatus = "Wi-Fi", poolSize = 0),
            logger = ProxyLogger { logs.add(it) },
        )

        proxy.start()
        waitUntil { proxy.stats().directPromotions == 1L }
        proxy.stop()

        assertTrue(attempts.get() >= 2)
        assertEquals(NetworkRouteMode.DIRECT_FIRST.configValue, proxy.stats().effectiveRouteMode)
        assertEquals(2L, proxy.stats().directHealthSuccesses)
        assertTrue(logs.any { it.contains("direct promoted: cf_first -> direct_first") })
    }


    @Test
    fun autoDirectPromotionAllowsColdDirectDuringRouteSettling() {
        val client = FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes())
        val server = FakeTcpServerTransport()
        val logs = CopyOnWriteArrayList<String>()
        val connector = MultiSocketRecordingConnector()
        val proxy = newProxy(
            server = server,
            connector = connector,
            config = baseConfig().copy(
                routeMode = NetworkRouteMode.AUTO,
                networkStatus = "mobile",
                poolSize = 1,
                cfProxyDomains = listOf("cf.example"),
            ),
            logger = ProxyLogger { logs.add(it) },
        )

        proxy.start()
        proxy.applyNetworkRoute("Wi-Fi")
        waitUntil { proxy.stats().directPromotions == 1L }
        assertEquals(NetworkRouteMode.DIRECT_FIRST.configValue, proxy.stats().effectiveRouteMode)
        assertEquals(NetworkRouteMode.CF_FIRST.configValue, proxy.stats().previousEffectiveRouteMode)
        assertTrue(proxy.stats().routeSettlingUntil > System.currentTimeMillis())

        server.enqueue(client)
        waitUntil { client.closed }
        proxy.stop()

        assertEquals(NetworkRouteMode.DIRECT_FIRST.configValue, proxy.stats().effectiveRouteMode)
        assertEquals("direct-cold", proxy.stats().lastRouteUsed)
        assertEquals(0L, proxy.stats().directDowngrades)
        assertTrue(connector.domains.any { it == "kws2.web.telegram.org" })
        assertFalse("CF must not be the only usable route immediately after direct probe success", connector.domains.any { it == "kws2.cf.example" })
        assertTrue(logs.any { it.contains("direct promoted: cf_first -> direct_first") })
        assertTrue(logs.any { it.contains("route settling prohibits direct pool") && it.contains("allows cold direct") })
        assertTrue(logs.any { it.contains("client uses cold direct during route settling") })
        assertFalse(logs.any { it.contains("direct route skipped because route settling") })
        assertFalse(logs.any { it.contains("no route available after direct WebSocket attempts") })
        assertFalse(logs.any { it.contains("direct route downgraded to cf_first because health degraded") })
    }

    @Test
    fun failedAutoWifiHealthProbeKeepsCfFirst() {
        val server = FakeTcpServerTransport()
        val proxy = newProxy(
            server = server,
            connector = RawWebSocketConnector { _, domain, _, _ -> throw IOException("direct unavailable $domain") },
            config = baseConfig().copy(routeMode = NetworkRouteMode.AUTO, networkStatus = "Wi-Fi", poolSize = 0),
        )

        proxy.start()
        waitUntil { proxy.stats().directHealthFailures > 0L }
        proxy.stop()

        assertEquals(NetworkRouteMode.CF_FIRST.configValue, proxy.stats().effectiveRouteMode)
        assertEquals(0L, proxy.stats().directPromotions)
        assertEquals(DirectHealthState.UNHEALTHY.configValue, proxy.stats().directHealthState)
    }

    @Test
    fun mobileAutoRouteRemainsCfFirstAndNeverPromotesDirect() {
        val server = FakeTcpServerTransport()
        val attempts = AtomicInteger(0)
        val proxy = newProxy(
            server = server,
            connector = RawWebSocketConnector { _, _, _, _ ->
                attempts.incrementAndGet()
                FakeWebSocketBinaryStream()
            },
            config = baseConfig().copy(routeMode = NetworkRouteMode.AUTO, networkStatus = "mobile"),
        )

        proxy.start()
        Thread.sleep(100)
        proxy.stop()

        assertEquals(NetworkRouteMode.CF_FIRST.configValue, proxy.stats().effectiveRouteMode)
        assertEquals(0L, proxy.stats().directPromotions)
        assertEquals(0, attempts.get())
    }


    @Test
    fun stalePoolRetrySkipsColdDirectAfterRouteGenerationChanges() {
        val client = FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes())
        val server = FakeTcpServerTransport()
        val logs = CopyOnWriteArrayList<String>()
        var proxy: ProxyServer? = null
        proxy = newProxy(
            server = server,
            connector = MultiSocketRecordingConnector(),
            config = baseConfig().copy(
                poolSize = 1,
                routeMode = NetworkRouteMode.AUTO,
                networkStatus = "mobile",
                dcRedirects = mapOf(2 to "203.0.113.2"),
                cfproxyEnabled = false,
            ),
            runner = ProxyBridgeRunner { _, _, _, _, counters ->
                counters.finish("exception: EOFException: no frame")
                proxy!!.applyNetworkRouteImmediately("none")
            },
            logger = ProxyLogger { logs.add(it) },
        )

        proxy!!.start()
        proxy!!.applyEffectiveRouteMode(NetworkRouteMode.DIRECT_FIRST, "test promoted", "Wi-Fi")
        waitUntil { logs.any { it.contains("WS pool refilled DC2: 1 ready") } }
        server.enqueue(client)
        waitUntil { client.closed }
        proxy!!.stop()

        assertEquals(NetworkRouteMode.CF_FIRST.configValue, proxy!!.stats().effectiveRouteMode)
        assertEquals(1L, proxy!!.stats().poolStale)
        assertTrue(proxy!!.stats().directAttemptsSkippedBecauseRoute > 0L)
        assertTrue(logs.any { it.contains("cold direct retry skipped after stale pool because route/network changed") })
        assertFalse(logs.any { it.contains("retrying with cold direct route after stale pool") })
    }

    @Test
    fun networkNoneDisablesDirectAndPreventsColdDirectRetry() {
        val client = FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes())
        val server = FakeTcpServerTransport()
        val logs = CopyOnWriteArrayList<String>()
        val connector = RecordingConnector(FakeWebSocketBinaryStream())
        val proxy = newProxy(
            server = server,
            connector = connector,
            config = baseConfig().copy(
                routeMode = NetworkRouteMode.AUTO,
                networkStatus = "mobile",
                cfproxyEnabled = true,
                cfProxyDomains = listOf("cf.example"),
            ),
            logger = ProxyLogger { logs.add(it) },
        )

        proxy.start()
        proxy.applyEffectiveRouteMode(NetworkRouteMode.DIRECT_FIRST, "test promoted", "Wi-Fi")
        proxy.applyNetworkRouteImmediately("none")
        server.enqueue(client)
        waitUntil { client.closed }
        proxy.stop()

        val stats = proxy.stats()
        assertEquals("persistent network=none should settle back to the safe CF-first route", NetworkRouteMode.CF_FIRST.configValue, stats.effectiveRouteMode)
        assertEquals("persistent network=none must not start direct or CF connects", 0, connector.domains.size)
        assertEquals("settling gate should wait once for the transient network=none window", 1L, stats.networkSettlingWaits)
        assertEquals("persistent network=none should be reported as a controlled settling failure", 1L, stats.networkSettlingControlledFailures)
        assertEquals("route selection is suppressed before direct skip accounting when network=none persists", 0L, stats.directAttemptsSkippedBecauseRoute)
        assertTrue(
            "expected controlled no-route log after bounded settling wait",
            logs.any { it.contains("network still none after settling wait; controlled no-route failure") },
        )
        assertFalse(
            "persistent network=none must not run stale-pool cold direct retry",
            logs.any { it.contains("retrying with cold direct route after stale pool") },
        )
        assertFalse(
            "persistent network=none must not enter CF/direct route selection",
            logs.any { it.contains("no route available after CF-first attempts") || it.contains("direct fallback skipped") },
        )
    }


    @Test
    fun wifiCapabilityEventWhileDirectFirstHealthyKeepsDirectFirst() {
        val server = FakeTcpServerTransport()
        val logs = CopyOnWriteArrayList<String>()
        val proxy = newProxy(
            server = server,
            connector = RawWebSocketConnector { _, _, _, _ -> FakeWebSocketBinaryStream() },
            config = baseConfig().copy(routeMode = NetworkRouteMode.AUTO, networkStatus = "Wi-Fi", poolSize = 0),
            logger = ProxyLogger { logs.add(it) },
        )

        proxy.start()
        waitUntil { proxy.stats().directPromotions == 1L }
        val promotionsBefore = proxy.stats().directPromotions
        val successesBefore = proxy.stats().directHealthSuccesses
        val result = proxy.applyNetworkRoute("Wi-Fi")
        proxy.stop()

        assertFalse(result.changed)
        assertEquals(NetworkRouteMode.DIRECT_FIRST.configValue, proxy.stats().effectiveRouteMode)
        val stats = proxy.stats()
        assertEquals(promotionsBefore, stats.directPromotions)
        assertEquals(successesBefore, stats.directHealthSuccesses)
        assertEquals("direct health probe success", stats.lastRouteChangeReason)
        assertEquals("Wi-Fi capabilities changed; direct route already healthy", stats.lastRouteEvaluationReason)
        assertEquals(1L, stats.wifiCapabilityEventsIgnored)
        assertEquals(1L, stats.routeChurnAvoided)
        assertTrue(stats.routeEvaluations >= 2L)
        assertTrue(stats.routeNoopEvaluations >= 1L)
        assertTrue(logs.any { it.contains("direct route already healthy") })
        assertTrue(logs.any { it.contains("route evaluation noop") && it.contains("direct route already healthy") })
        assertFalse(logs.any { it.contains("effective route changed: direct_first -> cf_first because network=Wi-Fi") })
    }

    @Test
    fun repeatedWifiCapabilityEventsDoNotRepromoteOrRepeatWarmup() {
        val server = FakeTcpServerTransport()
        val logs = CopyOnWriteArrayList<String>()
        val proxy = newProxy(
            server = server,
            connector = RawWebSocketConnector { _, _, _, _ -> FakeWebSocketBinaryStream() },
            config = baseConfig().copy(routeMode = NetworkRouteMode.AUTO, networkStatus = "Wi-Fi", poolSize = 1),
            logger = ProxyLogger { logs.add(it) },
        )

        proxy.start()
        waitUntil { proxy.stats().directPromotions == 1L }
        waitUntil { logs.count { it.contains("Direct WS pool warmup started because effective route mode direct_first") } == 1 }
        repeat(5) { proxy.applyNetworkRoute("Wi-Fi") }
        Thread.sleep(100)
        proxy.stop()

        assertEquals(NetworkRouteMode.DIRECT_FIRST.configValue, proxy.stats().effectiveRouteMode)
        assertEquals(1L, proxy.stats().directPromotions)
        assertEquals(5L, proxy.stats().wifiCapabilityEventsIgnored)
        assertEquals(5L, proxy.stats().routeChurnAvoided)
        assertEquals(1, logs.count { it.contains("Direct WS pool warmup started because effective route mode direct_first") })
    }

    @Test
    fun healthProbeIsThrottledWhenDirectAlreadyHealthy() {
        val server = FakeTcpServerTransport()
        val attempts = AtomicInteger(0)
        val proxy = newProxy(
            server = server,
            connector = RawWebSocketConnector { _, _, _, _ ->
                attempts.incrementAndGet()
                FakeWebSocketBinaryStream()
            },
            config = baseConfig().copy(routeMode = NetworkRouteMode.AUTO, networkStatus = "Wi-Fi", poolSize = 0),
        )

        proxy.start()
        waitUntil { proxy.stats().directPromotions == 1L }
        val attemptsBefore = attempts.get()
        val throttleUntil = proxy.stats().directProbeThrottleUntil
        repeat(3) { proxy.applyNetworkRoute("Wi-Fi") }
        Thread.sleep(100)
        proxy.stop()

        assertEquals(attemptsBefore, attempts.get())
        assertTrue(throttleUntil > System.currentTimeMillis())
        assertTrue(proxy.stats().directProbeSkippedBecauseAlreadyHealthy >= 3L)
    }

    @Test
    fun directFirstDoesNotSkipBecauseDirectFirstAndUsesDirectPath() {
        val client = FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes())
        val server = FakeTcpServerTransport()
        val logs = CopyOnWriteArrayList<String>()
        val connector = RecordingConnector(FakeWebSocketBinaryStream())
        val proxy = newProxy(
            server = server,
            connector = connector,
            config = baseConfig().copy(routeMode = NetworkRouteMode.AUTO, networkStatus = "Wi-Fi", poolSize = 0),
            logger = ProxyLogger { logs.add(it) },
        )

        proxy.start()
        waitUntil { proxy.stats().directPromotions == 1L }
        server.enqueue(client)
        waitUntil { client.closed }
        proxy.stop()

        assertEquals(NetworkRouteMode.DIRECT_FIRST.configValue, proxy.stats().effectiveRouteMode)
        assertTrue(connector.domains.any { it == "kws2.web.telegram.org" })
        assertEquals("direct-cold", proxy.stats().lastRouteUsed)
        assertFalse(logs.any { it.contains("direct route skipped because effective route mode direct_first") })
        assertFalse(logs.any { it.contains("direct connect skipped because effective route mode direct_first") })
    }

    @Test
    fun directFirstDowngradesOnMobileButNotWifiCapabilityEvent() {
        val server = FakeTcpServerTransport()
        val proxy = newProxy(
            server = server,
            connector = RawWebSocketConnector { _, _, _, _ -> FakeWebSocketBinaryStream() },
            config = baseConfig().copy(routeMode = NetworkRouteMode.AUTO, networkStatus = "Wi-Fi", poolSize = 0),
        )

        proxy.start()
        waitUntil { proxy.stats().directPromotions == 1L }
        proxy.applyNetworkRoute("Wi-Fi")
        assertEquals(NetworkRouteMode.DIRECT_FIRST.configValue, proxy.stats().effectiveRouteMode)
        proxy.applyNetworkRouteImmediately("mobile")
        proxy.stop()

        assertEquals(NetworkRouteMode.CF_FIRST.configValue, proxy.stats().effectiveRouteMode)
    }


    @Test
    fun wifiDirectRecoveryCountersAndLastRouteUpdateAfterSuccessfulDirectConnect() {
        val client = FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes())
        val server = FakeTcpServerTransport()
        val logs = CopyOnWriteArrayList<String>()
        val attempts = AtomicInteger(0)
        val connector = RawWebSocketConnector { _, _, _, _ ->
            when (attempts.incrementAndGet()) {
                // First direct-health probe succeeds and keeps a recent Wi-Fi direct success.
                // The second probe must fail across all direct domains so AUTO stays on CF_FIRST
                // and the client route exercises the Wi-Fi direct recovery path instead of a
                // regular direct_first route.
                in 2..5 -> throw SocketException("probe failed after first success")
                else -> FakeWebSocketBinaryStream()
            }
        }
        val proxy = newProxy(
            server = server,
            connector = connector,
            config = baseConfig().copy(
                routeMode = NetworkRouteMode.AUTO,
                networkStatus = "mobile",
                poolSize = 0,
                cfproxyEnabled = false,
            ),
            logger = ProxyLogger { logs.add(it) },
        )

        proxy.start()
        proxy.applyNetworkRoute("Wi-Fi")
        waitUntil("expected one successful direct-health probe followed by one failed probe") {
            val stats = proxy.stats()
            stats.directHealthSuccesses == 1L && stats.directHealthFailures == 1L
        }
        assertEquals(
            "expected AUTO to remain cf_first so the client route uses Wi-Fi direct recovery",
            NetworkRouteMode.CF_FIRST.configValue,
            proxy.stats().effectiveRouteMode,
        )

        server.enqueue(client)
        waitUntil("expected Wi-Fi direct recovery session to close the fake client") { client.closed }
        proxy.stop()

        val stats = proxy.stats()
        assertEquals("expected wifiDirectRecoveryAttempts", 1L, stats.wifiDirectRecoveryAttempts)
        assertEquals("expected wifiDirectRecoverySuccesses", 1L, stats.wifiDirectRecoverySuccesses)
        assertEquals("expected wifiDirectRecoveryFailures", 0L, stats.wifiDirectRecoveryFailures)
        assertEquals("expected wifiCfFirstRecoveryAttempts", 1L, stats.recoveryDiagnostics.cfFirst.wifiAttempts)
        assertEquals("expected wifiCfFirstRecoverySuccesses", 1L, stats.recoveryDiagnostics.cfFirst.wifiSuccesses)
        assertEquals("expected cfFirstRecoveryAttempts", 1L, stats.recoveryDiagnostics.cfFirst.attempts)
        assertEquals("expected direct promotion after recovery", NetworkRouteMode.DIRECT_FIRST.configValue, stats.effectiveRouteMode)
        assertEquals("expected lastRouteUsed", "direct-cold", stats.lastRouteUsed)
        val lastRouteUsedUpdateTimeMs = requireNotNull(stats.lastRouteUsedUpdateTimeMs) {
            "Expected lastRouteUsedUpdateTimeMs to be set"
        }
        assertTrue(
            "expected lastRouteUsedUpdateTimeMs > 0",
            lastRouteUsedUpdateTimeMs > 0L,
        )
        assertEquals("expected direct connector calls", 6, attempts.get())
        assertTrue(
            "expected Wi-Fi direct recovery allowed log",
            logs.any { it.contains("Wi-Fi direct recovery allowed") },
        )
        assertTrue(
            "expected successful direct WebSocket connect log",
            logs.any { it.contains("WebSocket connected via kws2.web.telegram.org") },
        )
    }

    @Test
    fun emergencyDirectFallbackIsSuppressedDuringDirectCooldown() {
        val firstClient = FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes())
        val secondClient = FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes())
        val server = FakeTcpServerTransport()
        val proxy = newProxy(
            server = server,
            connector = RawWebSocketConnector { _, _, _, _ -> throw SocketException("direct timeout") },
            config = baseConfig().copy(routeMode = NetworkRouteMode.AUTO, networkStatus = "Wi-Fi", poolSize = 0, cfproxyEnabled = false),
        )

        proxy.applyEffectiveRouteMode(NetworkRouteMode.DIRECT_FIRST, "test promoted", "Wi-Fi")
        proxy.start()
        server.enqueue(firstClient)
        waitUntil { firstClient.closed && proxy.stats().directCooldownUntil > System.currentTimeMillis() }
        server.enqueue(secondClient)
        waitUntil { secondClient.closed }
        proxy.stop()

        val stats = proxy.stats()
        assertEquals(NetworkRouteMode.CF_FIRST.configValue, stats.effectiveRouteMode)
        assertEquals(0L, stats.recoveryDiagnostics.emergencyDirectFallback.attempts)
        assertTrue(stats.recoveryDiagnostics.emergencyDirectFallback.suppressed > 0L)
        assertEquals("direct cooldown", stats.recoveryDiagnostics.emergencyDirectFallback.lastReason)
    }

    @Test
    fun statsExposeDirectFirstAfterEffectiveRoutePromotionLogEvent() {
        val server = FakeTcpServerTransport()
        val logs = CopyOnWriteArrayList<String>()
        val proxy = newProxy(
            server = server,
            connector = RawWebSocketConnector { _, _, _, _ -> FakeWebSocketBinaryStream() },
            config = baseConfig().copy(routeMode = NetworkRouteMode.AUTO, networkStatus = "mobile", poolSize = 0),
            logger = ProxyLogger { logs.add(it) },
        )

        proxy.start()
        proxy.applyEffectiveRouteMode(NetworkRouteMode.DIRECT_FIRST, "direct health probe success", "Wi-Fi", source = "direct-health")
        waitUntil { logs.any { it.contains("effective route changed: cf_first -> direct_first because direct health probe success") } }
        proxy.stop()

        val stats = proxy.stats()
        assertEquals(NetworkRouteMode.DIRECT_FIRST.configValue, stats.effectiveRouteMode)
        assertEquals(NetworkRouteMode.CF_FIRST.configValue, stats.previousEffectiveRouteMode)
        val lastEffectiveRouteModeUpdateTimeMs = requireNotNull(stats.lastEffectiveRouteModeUpdateTimeMs) {
            "Expected lastEffectiveRouteModeUpdateTimeMs to be set"
        }
        assertTrue(lastEffectiveRouteModeUpdateTimeMs > 0L)
    }


    @Test
    fun reconnectBurstAfterIdleWithPoolMissesMarksClientExperienceBurst() {
        val server = FakeTcpServerTransport()
        val proxy = newProxy(
            server = server,
            connector = RawWebSocketConnector { _, _, _, _ -> throw IOException("direct unavailable") },
            config = baseConfig().copy(poolSize = 1, cfproxyEnabled = false),
        )

        proxy.start()
        repeat(4) {
            server.enqueue(FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes()))
        }
        waitUntil { proxy.stats().recentAcceptedHandshakeCount >= 4L && proxy.stats().poolMisses >= 4L }
        proxy.stop()

        val experience = proxy.stats().clientExperience
        assertTrue(experience.likelyReconnectBurst)
        assertTrue(experience.recentPoolMisses >= 4L)
        assertEquals(0L, proxy.stats().recentInvalidHandshakeCount)
    }

    @Test
    fun validHandshakeAfterIdleTriggersDirectPoolPrewarm() {
        val attempts = java.util.concurrent.atomic.AtomicInteger(0)
        val server = FakeTcpServerTransport()
        val proxy = newProxy(
            server = server,
            connector = RawWebSocketConnector { _, _, _, _ ->
                attempts.incrementAndGet()
                FakeWebSocketBinaryStream()
            },
            config = baseConfig().copy(poolSize = 1, cfproxyEnabled = false),
        )

        proxy.start()
        server.enqueue(FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes()))
        waitUntil { proxy.stats().clientExperience.wakeBurstPrewarmTriggers == 1L }
        waitUntil { attempts.get() > 0 }
        proxy.stop()

        val experience = proxy.stats().clientExperience
        assertEquals(1L, experience.wakeBurstPrewarmTriggers)
        assertEquals(1L, experience.wakeBurstPrewarmAttempts)
        assertEquals(2, experience.lastWakeBurstPrewarmDc)
    }

    @Test
    fun invalidHandshakeAfterIdleDoesNotTriggerPrewarm() {
        val invalid = handshakeVector("invalid_proto_tag").getString("handshake_hex").hexToBytes()
        val server = FakeTcpServerTransport()
        val proxy = newProxy(server = server, config = baseConfig().copy(poolSize = 1))

        proxy.start()
        server.enqueue(FakeTcpClientTransport(invalid))
        waitUntil { proxy.stats().recentInvalidHandshakeCount == 1L }
        proxy.stop()

        val stats = proxy.stats()
        assertEquals(1L, stats.recentInvalidHandshakeCount)
        assertEquals(0L, stats.clientExperience.wakeBurstPrewarmTriggers)
        assertEquals(0L, stats.clientExperience.recentAcceptedHandshakes)
    }

    @Test
    fun repeatedBurstHandshakesRespectPrewarmCooldown() {
        val server = FakeTcpServerTransport()
        val proxy = newProxy(
            server = server,
            connector = RawWebSocketConnector { _, _, _, _ -> FakeWebSocketBinaryStream() },
            config = baseConfig().copy(poolSize = 1, cfproxyEnabled = false),
        )

        proxy.start()
        repeat(6) {
            server.enqueue(FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes()))
        }
        waitUntil { proxy.stats().recentAcceptedHandshakeCount >= 6L }
        proxy.stop()

        val experience = proxy.stats().clientExperience
        assertEquals(1L, experience.wakeBurstPrewarmTriggers)
        assertEquals(1L, experience.wakeBurstPrewarmAttempts)
    }

    @Test
    fun unsupportedDcDoesNotPrewarmDirectPool() {
        val attempts = java.util.concurrent.atomic.AtomicInteger(0)
        val server = FakeTcpServerTransport()
        val proxy = newProxy(
            server = server,
            connector = RawWebSocketConnector { _, _, _, _ ->
                attempts.incrementAndGet()
                FakeWebSocketBinaryStream()
            },
            config = baseConfig().copy(poolSize = 1, dcRedirects = mapOf(2 to "203.0.113.2", 4 to "203.0.113.4"), cfproxyEnabled = false),
        )

        proxy.start()
        server.enqueue(FakeTcpClientTransport(buildClientHandshake(5, RelayInit.PROTO_TAG_ABRIDGED)))
        waitUntil { proxy.stats().clientExperience.wakeBurstPrewarmSkippedNoDirectRedirect == 1L }
        proxy.stop()

        val stats = proxy.stats()
        assertEquals(1L, stats.clientExperience.wakeBurstPrewarmSkippedNoDirectRedirect)
        assertEquals(0L, stats.clientExperience.wakeBurstPrewarmTriggers)
        assertEquals(0L, stats.clientExperience.wakeBurstPrewarmAttempts)
        assertEquals(0L, stats.clientExperience.wakeBurstPrewarmSuccesses)
        assertEquals(0L, stats.clientExperience.wakeBurstPrewarmFailures)
    }

    @Test
    fun idleDirectPoolMaintenanceWarmsConfiguredDirectDc() {
        val attempts = AtomicInteger(0)
        val domains = CopyOnWriteArrayList<String>()
        val server = FakeTcpServerTransport()
        val proxy = newProxy(
            server = server,
            connector = RawWebSocketConnector { _, domain, _, _ ->
                attempts.incrementAndGet()
                domains.add(domain)
                FakeWebSocketBinaryStream()
            },
            config = baseConfig().copy(poolSize = 1, cfproxyEnabled = false),
        )

        proxy.start()
        waitUntil { proxy.stats().clientExperience.idlePoolMaintenanceRuns >= 1L }
        waitUntil { attempts.get() >= 4 }
        proxy.stop()

        val experience = proxy.stats().clientExperience
        assertTrue(experience.idlePoolMaintenanceAttempts >= 2L)
        assertTrue(experience.idlePoolMaintenanceSuccesses >= 2L)
        assertTrue(domains.any { it == "kws2.web.telegram.org" })
        assertTrue(domains.any { it == "kws4.web.telegram.org" })
    }

    @Test
    fun idleDirectPoolMaintenanceSkipsUnsupportedDc() {
        val domains = CopyOnWriteArrayList<String>()
        val server = FakeTcpServerTransport()
        val proxy = newProxy(
            server = server,
            connector = RawWebSocketConnector { _, domain, _, _ ->
                domains.add(domain)
                FakeWebSocketBinaryStream()
            },
            config = baseConfig().copy(poolSize = 1, dcRedirects = mapOf(2 to "203.0.113.2"), cfproxyEnabled = false),
        )

        proxy.start()
        waitUntil { proxy.stats().clientExperience.idlePoolMaintenanceRuns >= 1L }
        proxy.stop()

        assertTrue(domains.any { it == "kws2.web.telegram.org" })
        assertFalse(domains.any { it.contains("kws5.") })
    }

    @Test
    fun idleDirectPoolMaintenanceSkipsWhenNetworkUnavailable() {
        val server = FakeTcpServerTransport()
        val proxy = newProxy(server = server, config = baseConfig().copy(poolSize = 1, networkStatus = "none"))

        proxy.start()
        waitUntil { proxy.stats().clientExperience.idlePoolMaintenanceSkippedNetwork >= 1L }
        proxy.stop()

        assertEquals(0L, proxy.stats().clientExperience.idlePoolMaintenanceRuns)
    }

    @Test
    fun idleDirectPoolMaintenanceSkipsWhenDirectHealthUnhealthy() {
        val server = FakeTcpServerTransport()
        val proxy = newProxy(server = server, config = baseConfig().copy(poolSize = 1, cfproxyEnabled = false))

        proxy.directRouteHealthForTest().startCooldown(ProxyServer.IDLE_POOL_MAINTENANCE_COOLDOWN_MS, "test unhealthy")
        proxy.start()
        waitUntil { proxy.stats().clientExperience.idlePoolMaintenanceSkippedDirectHealth >= 1L }
        proxy.stop()
    }

    @Test
    fun idleDirectPoolMaintenanceSkipsWhenClientSessionsActive() {
        val bridgeEntered = CountDownLatch(1)
        val releaseBridge = CountDownLatch(1)
        val server = FakeTcpServerTransport()
        val proxy = newProxy(
            server = server,
            connector = RawWebSocketConnector { _, _, _, _ -> FakeWebSocketBinaryStream() },
            runner = ProxyBridgeRunner { _, _, _, _, _ ->
                bridgeEntered.countDown()
                releaseBridge.await(5, TimeUnit.SECONDS)
            },
            config = baseConfig().copy(poolSize = 1, cfproxyEnabled = false),
        )

        proxy.start()
        server.enqueue(FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes()))
        assertTrue(bridgeEntered.await(5, TimeUnit.SECONDS))
        waitUntil { proxy.stats().clientExperience.idlePoolMaintenanceSkippedActiveSessions >= 1L }
        releaseBridge.countDown()
        proxy.stop()
    }

    @Test
    fun idleDirectPoolMaintenanceDoesNotChangeRouteSelection() {
        val server = FakeTcpServerTransport()
        val proxy = newProxy(server = server, config = baseConfig().copy(poolSize = 1, routeMode = NetworkRouteMode.CF_ONLY))

        proxy.start()
        val routeBefore = proxy.stats().effectiveRouteMode
        waitUntil { proxy.stats().clientExperience.idlePoolMaintenanceSkippedRoute >= 1L }
        val stats = proxy.stats()
        proxy.stop()

        assertEquals(routeBefore, stats.effectiveRouteMode)
        assertEquals(1L, stats.clientExperience.idlePoolMaintenanceSkippedRoute)
        assertEquals(0L, stats.clientExperience.idlePoolMaintenanceAttempts)
    }

    @Test
    fun invalidHandshakesDoNotTriggerIdleMaintenance() {
        val invalid = handshakeVector("invalid_proto_tag").getString("handshake_hex").hexToBytes()
        val server = FakeTcpServerTransport()
        val proxy = newProxy(server = server, config = baseConfig().copy(poolSize = 0))

        proxy.start()
        server.enqueue(FakeTcpClientTransport(invalid))
        waitUntil { proxy.stats().recentInvalidHandshakeCount == 1L }
        proxy.stop()

        assertEquals(0L, proxy.stats().clientExperience.idlePoolMaintenanceRuns)
        assertEquals(0L, proxy.stats().clientExperience.idlePoolMaintenanceAttempts)
    }

    @Test
    fun normalActiveSessionDoesNotRepeatedlyPrewarm() {
        val bridgeEntered = java.util.concurrent.CountDownLatch(1)
        val releaseBridge = java.util.concurrent.CountDownLatch(1)
        val server = FakeTcpServerTransport()
        val proxy = newProxy(
            server = server,
            connector = RawWebSocketConnector { _, _, _, _ -> FakeWebSocketBinaryStream() },
            runner = ProxyBridgeRunner { _, _, _, _, _ ->
                bridgeEntered.countDown()
                releaseBridge.await(5, TimeUnit.SECONDS)
            },
            config = baseConfig().copy(poolSize = 1, cfproxyEnabled = false),
        )

        proxy.start()
        server.enqueue(FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes()))
        assertTrue(bridgeEntered.await(5, TimeUnit.SECONDS))
        server.enqueue(FakeTcpClientTransport(handshakeVector("intermediate_dc4").getString("handshake_hex").hexToBytes()))
        waitUntil { proxy.stats().recentAcceptedHandshakeCount >= 2L }
        releaseBridge.countDown()
        proxy.stop()

        assertEquals(1L, proxy.stats().clientExperience.wakeBurstPrewarmTriggers)
    }


    @Test
    fun directTimeoutStopsDirectDomainLoopAndFallsBackToCf() {
        val client = FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes())
        val server = FakeTcpServerTransport()
        val logs = CopyOnWriteArrayList<String>()
        val connector = DirectTimeoutThenCfConnector(FakeWebSocketBinaryStream())
        val proxy = newProxy(
            server = server,
            connector = connector,
            config = baseConfig().copy(dcRedirects = mapOf(2 to "203.0.113.2"), cfProxyDomains = listOf("cf.example")),
            logger = ProxyLogger { logs.add(it) },
        )

        proxy.start()
        server.enqueue(client)
        waitUntil { client.closed }
        proxy.stop()

        assertEquals(listOf("kws2.web.telegram.org", "kws2.cf.example"), connector.domains.toList())
        val stats = proxy.stats()
        assertTrue(proxy.directTargetIpCooldownSnapshotForTest().containsKey("203.0.113.2"))
        assertTrue(stats.directTargetIpCooldownSets >= 1L)
        assertEquals("203.0.113.2", stats.lastDirectTargetIpCooldownTarget)
        assertTrue(logs.any { it.contains("direct target 203.0.113.2 cooldown set for DC2 because SocketTimeoutException: connect timed out") })
    }

    @Test
    fun nextSessionSkipsDirectWhileTargetIpCooldownIsActive() {
        val server = FakeTcpServerTransport()
        val logs = CopyOnWriteArrayList<String>()
        val connector = RecordingConnector(FakeWebSocketBinaryStream())
        val proxy = newProxy(
            server = server,
            connector = connector,
            config = baseConfig().copy(dcRedirects = mapOf(2 to "203.0.113.2"), cfProxyDomains = listOf("cf.example")),
            logger = ProxyLogger { logs.add(it) },
        )
        proxy.setDirectTargetIpCooldownForTest("203.0.113.2", System.currentTimeMillis() + ProxyServer.IP_FAIL_COOLDOWN_MS, "test")

        proxy.start()
        server.enqueue(FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes()))
        waitUntil { connector.domains.contains("kws2.cf.example") }
        waitUntil { proxy.stats().directAttemptsSkippedBecauseTargetIpCooldown == 1L }
        proxy.stop()

        assertFalse(connector.domains.any { it.endsWith(".web.telegram.org") })
        assertEquals("kws2.cf.example", connector.domains.first())
        val stats = proxy.stats()
        assertEquals(1L, stats.directAttemptsSkippedBecauseTargetIpCooldown)
        assertEquals(1L, stats.directTargetIpCooldownHits)
        assertTrue(logs.any { it.contains("DC2 direct target 203.0.113.2 in cooldown; trying CF fallback") })
    }

    @Test
    fun targetIpCooldownIsSharedAcrossDcs() {
        val server = FakeTcpServerTransport()
        val logs = CopyOnWriteArrayList<String>()
        val connector = RecordingConnector(FakeWebSocketBinaryStream())
        val proxy = newProxy(
            server = server,
            connector = connector,
            config = baseConfig().copy(dcRedirects = mapOf(2 to "203.0.113.2", 4 to "203.0.113.2"), cfProxyDomains = listOf("cf.example")),
            logger = ProxyLogger { logs.add(it) },
        )
        proxy.setDirectTargetIpCooldownForTest("203.0.113.2", System.currentTimeMillis() + ProxyServer.IP_FAIL_COOLDOWN_MS, "test")

        proxy.start()
        server.enqueue(FakeTcpClientTransport(handshakeVector("intermediate_dc4").getString("handshake_hex").hexToBytes()))
        waitUntil { connector.domains.contains("kws4.cf.example") }
        proxy.stop()

        assertFalse(connector.domains.any { it.endsWith(".web.telegram.org") })
        assertEquals("203.0.113.2", proxy.stats().lastDirectTargetIpCooldownTarget)
        assertTrue(logs.any { it.contains("DC4 direct target 203.0.113.2 in cooldown; trying CF fallback") })
    }

    @Test
    fun targetIpCooldownDoesNotSuppressDirectWhenCfFallbackDisabled() {
        val server = FakeTcpServerTransport()
        val connector = RecordingConnector(FakeWebSocketBinaryStream())
        val proxy = newProxy(
            server = server,
            connector = connector,
            config = baseConfig().copy(dcRedirects = mapOf(2 to "203.0.113.2"), cfproxyEnabled = false),
        )
        proxy.setDirectTargetIpCooldownForTest("203.0.113.2", System.currentTimeMillis() + ProxyServer.IP_FAIL_COOLDOWN_MS, "test")

        proxy.start()
        server.enqueue(FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes()))
        waitUntil { connector.domains.any { it.endsWith(".web.telegram.org") } }
        proxy.stop()

        assertEquals(0L, proxy.stats().directAttemptsSkippedBecauseTargetIpCooldown)
    }

    @Test
    fun successfulDirectRouteClearsTargetIpCooldown() {
        val server = FakeTcpServerTransport()
        val logs = CopyOnWriteArrayList<String>()
        val connector = RecordingConnector(FakeWebSocketBinaryStream())
        val proxy = newProxy(
            server = server,
            connector = connector,
            config = baseConfig().copy(dcRedirects = mapOf(2 to "203.0.113.2"), cfproxyEnabled = false),
            logger = ProxyLogger { logs.add(it) },
        )
        proxy.setDirectTargetIpCooldownForTest("203.0.113.2", System.currentTimeMillis() + ProxyServer.IP_FAIL_COOLDOWN_MS, "test")

        proxy.start()
        server.enqueue(FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes()))
        waitUntil { clientClosedOrDirectAttempted(connector) }
        proxy.stop()

        assertFalse(proxy.directTargetIpCooldownSnapshotForTest().containsKey("203.0.113.2"))
        assertEquals(1L, proxy.stats().directTargetIpCooldownClears)
        assertTrue(logs.any { it.contains("direct target 203.0.113.2 cooldown cleared after successful direct route") })
    }

    @Test
    fun directPoolWarmupAndUseRespectTargetIpCooldown() {
        val server = FakeTcpServerTransport()
        val connector = RecordingConnector(FakeWebSocketBinaryStream())
        val proxy = newProxy(
            server = server,
            connector = connector,
            config = baseConfig().copy(poolSize = 1, dcRedirects = mapOf(2 to "203.0.113.2"), cfProxyDomains = listOf("cf.example")),
        )
        proxy.setDirectTargetIpCooldownForTest("203.0.113.2", System.currentTimeMillis() + ProxyServer.IP_FAIL_COOLDOWN_MS, "test")

        proxy.start()
        Thread.sleep(100)
        server.enqueue(FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes()))
        waitUntil { connector.domains.contains("kws2.cf.example") }
        proxy.stop()

        assertFalse(connector.domains.any { it.endsWith(".web.telegram.org") })
        assertTrue(proxy.stats().directPoolSkippedBecauseTargetIpCooldown > 0L)
    }

    private fun clientClosedOrDirectAttempted(connector: RecordingConnector): Boolean =
        connector.domains.any { it.endsWith(".web.telegram.org") }

    @Test
    fun dcWithoutDirectRedirectAndCfDisabledRecordsUnsupportedAndNoRouteByDc() {
        val client = FakeTcpClientTransport(buildClientHandshake(5, RelayInit.PROTO_TAG_ABRIDGED))
        val server = FakeTcpServerTransport()
        val proxy = newProxy(
            server = server,
            config = baseConfig().copy(dcRedirects = mapOf(2 to "203.0.113.2", 4 to "203.0.113.4"), cfproxyEnabled = false),
        )

        proxy.start()
        server.enqueue(client)
        waitUntil { client.closed }
        proxy.stop()

        val experience = proxy.stats().clientExperience
        assertEquals(1L, experience.recentUnsupportedDcByDc[5])
        assertEquals(1L, experience.recentNoRouteByDc[5])
    }

    @Test
    fun manyConnectionResetsAfterAcceptedHandshakesCanMarkTelegramDisabledProxy() {
        val server = FakeTcpServerTransport()
        val proxy = newProxy(
            server = server,
            connector = RecordingConnector(FakeWebSocketBinaryStream(sendError = SocketException("Connection reset"))),
            config = baseConfig().copy(cfproxyEnabled = false),
        )

        proxy.start()
        repeat(4) {
            server.enqueue(FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes()))
        }
        waitUntil { proxy.stats().clientExperience.likelyTelegramDisabledProxy }
        val experience = proxy.stats().clientExperience
        proxy.stop()

        assertTrue(experience.likelyTelegramDisabledProxy)
        assertFalse(proxy.stats().badHandshakeStormRecent)
    }

    @Test
    fun normalSuccessfulDirectRouteDoesNotMarkReconnectBurst() {
        val client = FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes())
        val server = FakeTcpServerTransport()
        val proxy = newProxy(server = server, connector = RecordingConnector(FakeWebSocketBinaryStream()))

        proxy.start()
        server.enqueue(client)
        waitUntil { client.closed }
        proxy.stop()

        assertFalse(proxy.stats().clientExperience.likelyReconnectBurst)
    }

    @Test
    fun invalidHandshakeStormRemainsSeparateFromClientExperienceDiagnostics() {
        val invalid = handshakeVector("invalid_proto_tag").getString("handshake_hex").hexToBytes()
        assertNull(MtprotoHandshake.parse(invalid, baseConfig().secretHex))

        val server = FakeTcpServerTransport()
        val proxy = newProxy(server = server)

        proxy.start()
        repeat(120) { server.enqueue(FakeTcpClientTransport(invalid)) }
        waitUntil({ invalidHandshakeStormStatsMessage(proxy) }) {
            val stats = proxy.stats()
            stats.connectionsBad == 120L && stats.recentInvalidHandshakeCount >= 100L
        }
        proxy.stop()

        val stats = proxy.stats()
        val experience = stats.clientExperience
        assertTrue("invalid handshakes should increment recent invalid handshakes", stats.recentInvalidHandshakeCount >= 100L)
        assertEquals("invalid handshakes should increment bad connections", 120L, stats.connectionsBad)
        assertTrue("invalid handshakes should still trigger bad-handshake storm diagnostics", stats.badHandshakeStormRecent)
        assertFalse("likelyReconnectBurst leaked invalid handshakes", experience.likelyReconnectBurst)
        assertFalse("likelyTelegramDisabledProxy leaked invalid handshakes", experience.likelyTelegramDisabledProxy)
        assertEquals("recentAcceptedHandshakes leaked invalid handshakes", 0L, experience.recentAcceptedHandshakes)
        assertEquals("recentClientClosedSessions leaked invalid handshakes", 0L, experience.recentClientClosedSessions)
        assertEquals("recentVeryShortClientClosedSessions leaked invalid handshakes", 0L, experience.recentVeryShortClientClosedSessions)
        assertEquals("recentShortRemoteEofSessions leaked invalid handshakes", 0L, experience.recentShortRemoteEofSessions)
        assertEquals("recentConnectionResetSessions leaked invalid handshakes", 0L, experience.recentConnectionResetSessions)
        assertEquals("recentDirectTimeouts leaked invalid handshakes", 0L, experience.recentDirectTimeouts)
        assertEquals("recentPoolMisses leaked invalid handshakes", 0L, experience.recentPoolMisses)
        assertEquals("recentPoolRefillErrors leaked invalid handshakes", 0L, experience.recentPoolRefillErrors)
        assertEquals("recentPoolStale leaked invalid handshakes", 0L, experience.recentPoolStale)
        assertTrue("recentUnsupportedDcByDc leaked invalid handshakes: ${experience.recentUnsupportedDcByDc}", experience.recentUnsupportedDcByDc.isEmpty())
        assertTrue("recentNoRouteByDc leaked invalid handshakes: ${experience.recentNoRouteByDc}", experience.recentNoRouteByDc.isEmpty())
        assertEquals("recentCfQueueControlledFailures leaked invalid handshakes", 0L, experience.recentCfQueueControlledFailures)
        assertEquals("recentCfConnectQueueTimeouts leaked invalid handshakes", 0L, experience.recentCfConnectQueueTimeouts)
        assertNull("idle-wave recovery timing leaked invalid handshakes", experience.timeToFirstSuccessfulRouteAfterIdleMs)
    }

    @Test
    fun autoMobileForcedOrdinaryConnectorFailuresReachTorThroughProductionRouting() {
        val server = FakeTcpServerTransport()
        val ordinaryDomains = CopyOnWriteArrayList<String>()
        val ordinary = RawWebSocketConnector { _, domain, _, _ ->
            ordinaryDomains.add(domain)
            throw IOException("private test ordinary route unavailable for $domain")
        }
        val tor = RecordingConnector(FakeWebSocketBinaryStream())
        val proxy = newProxy(
            server = server,
            connector = ordinary,
            torSnowflakeConnector = tor,
            config = baseConfig().copy(
                routeMode = NetworkRouteMode.AUTO,
                networkStatus = "mobile",
                cfproxyEnabled = true,
                cfPoolEnabled = false,
                cfProxyDomains = listOf("cf.example"),
                torSnowflakeFallbackEnabled = true,
            ),
        )

        proxy.start()
        server.enqueue(FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes()))
        waitUntil("forced ordinary route failure should reach Tor/Snowflake") {
            proxy.stats().lastRouteUsed == TOR_SNOWFLAKE_ROUTE_TYPE
        }
        proxy.stop()

        val stats = proxy.stats()
        assertTrue("production CF path should be attempted before Tor", ordinaryDomains.contains("kws2.cf.example"))
        assertEquals(1L, stats.cfProxyErrors)
        assertEquals(1L, stats.torSnowflakeAttempts)
        assertEquals(1L, stats.torSnowflakeSuccesses)
        assertEquals(0L, stats.torSnowflakeFailures)
        assertEquals(TOR_SNOWFLAKE_ROUTE_TYPE, stats.lastRouteUsed)
        assertEquals(listOf("kws2.web.telegram.org"), tor.domains)
    }

    @Test
    fun autoMobileUsesTorSnowflakeAfterOrdinaryRoutesAreUnavailable() {
        val server = FakeTcpServerTransport()
        val direct = RecordingConnector(FakeWebSocketBinaryStream())
        val tor = RecordingConnector(FakeWebSocketBinaryStream())
        val proxy = newProxy(
            server = server,
            connector = direct,
            torSnowflakeConnector = tor,
            config = baseConfig().copy(
                routeMode = NetworkRouteMode.AUTO,
                networkStatus = "mobile",
                cfproxyEnabled = false,
                torSnowflakeFallbackEnabled = true,
            ),
        )

        proxy.start()
        server.enqueue(FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes()))
        waitUntil("Tor/Snowflake route") { proxy.stats().lastRouteUsed == TOR_SNOWFLAKE_ROUTE_TYPE }
        proxy.stop()

        val stats = proxy.stats()
        assertEquals(TOR_SNOWFLAKE_ROUTE_TYPE, stats.lastRouteUsed)
        assertEquals(0L, stats.directAttempts)
        assertEquals(1L, stats.torSnowflakeAttempts)
        assertEquals(1L, stats.torSnowflakeSuccesses)
        assertEquals(0L, stats.torSnowflakeFailures)
        assertTrue(direct.domains.isEmpty())
        assertEquals(listOf("kws2.web.telegram.org"), tor.domains)
    }

    @Test
    fun unknownDirectDcFallsBackToTorUsingTunnelDnsAfterCfFailure() {
        val server = FakeTcpServerTransport()
        val cfAttempts = CopyOnWriteArrayList<String>()
        val cfConnector = RawWebSocketConnector { _, domain, _, _ ->
            cfAttempts.add(domain)
            throw IOException("planned CF failure for $domain")
        }
        val tor = RecordingConnector(FakeWebSocketBinaryStream())
        val proxy = newProxy(
            server = server,
            connector = cfConnector,
            torSnowflakeConnector = tor,
            config = baseConfig().copy(
                dcRedirects = emptyMap(),
                routeMode = NetworkRouteMode.AUTO,
                networkStatus = "mobile",
                cfproxyEnabled = true,
                cfPoolEnabled = false,
                cfProxyDomains = listOf("cf.example"),
                torSnowflakeFallbackEnabled = true,
            ),
        )

        proxy.start()
        server.enqueue(FakeTcpClientTransport(buildClientHandshake(dcIdx = -5, protoTag = RelayInit.PROTO_TAG_SECURE)))
        waitUntil("DC5 media Tor/Snowflake route") { proxy.stats().lastRouteUsed == TOR_SNOWFLAKE_ROUTE_TYPE }
        proxy.stop()

        val stats = proxy.stats()
        assertEquals(listOf("kws5.cf.example"), cfAttempts.toList())
        assertEquals(listOf("kws5-1.web.telegram.org"), tor.targetHosts.toList())
        assertEquals(listOf("kws5-1.web.telegram.org"), tor.domains.toList())
        assertEquals(TOR_SNOWFLAKE_ROUTE_TYPE, stats.lastRouteUsed)
        assertEquals(1L, stats.torSnowflakeAttempts)
        assertEquals(1L, stats.torSnowflakeSuccesses)
        assertEquals(0L, stats.torSnowflakeFailures)
        assertEquals(0L, stats.unsupportedDc)
        assertEquals(0L, stats.connectionsBad)
    }

    @Test
    fun cfOnlyUnknownDirectDcDoesNotUseTorFallback() {
        val server = FakeTcpServerTransport()
        val tor = RecordingConnector(FakeWebSocketBinaryStream())
        val proxy = newProxy(
            server = server,
            torSnowflakeConnector = tor,
            config = baseConfig().copy(
                dcRedirects = emptyMap(),
                routeMode = NetworkRouteMode.CF_ONLY,
                networkStatus = "mobile",
                cfproxyEnabled = false,
                torSnowflakeFallbackEnabled = true,
            ),
        )

        proxy.start()
        val client = FakeTcpClientTransport(buildClientHandshake(dcIdx = -5, protoTag = RelayInit.PROTO_TAG_SECURE))
        server.enqueue(client)
        waitUntil("CF_ONLY unknown DC client closes") { client.closed }
        proxy.stop()

        assertTrue(tor.domains.isEmpty())
        assertEquals(0L, proxy.stats().torSnowflakeAttempts)
        assertEquals(1L, proxy.stats().unsupportedDc)
        assertEquals(1L, proxy.stats().connectionsBad)
    }

    @Test
    fun warmingTorFallbackDoesNotCountAsTorNetworkFailure() {
        val server = FakeTcpServerTransport()
        val proxy = newProxy(
            server = server,
            torSnowflakeConnector = RawWebSocketConnector { _, _, _, _ ->
                throw TorSnowflakeUnavailableException("bootstrap=72%")
            },
            config = baseConfig().copy(
                routeMode = NetworkRouteMode.AUTO,
                networkStatus = "mobile",
                cfproxyEnabled = false,
                torSnowflakeFallbackEnabled = true,
            ),
        )

        proxy.start()
        val client = FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes())
        server.enqueue(client)
        waitUntil("client closes after warming Tor fallback") { client.closed }
        proxy.stop()

        val stats = proxy.stats()
        assertEquals(1L, stats.torSnowflakeAttempts)
        assertEquals(0L, stats.torSnowflakeSuccesses)
        assertEquals(0L, stats.torSnowflakeFailures)
        assertEquals(1L, stats.torSnowflakeUnavailable)
        assertTrue(stats.lastTorSnowflakeError.orEmpty().contains("72%"))
    }

    @Test
    fun serviceReadinessGateSkipsColdTorConnectorButSignalsOrdinaryExhaustion() {
        val server = FakeTcpServerTransport()
        val signals = CopyOnWriteArrayList<String>()
        val tor = RecordingConnector(FakeWebSocketBinaryStream())
        val proxy = newProxy(
            server = server,
            torSnowflakeConnector = tor,
            onOrdinaryRoutesExhausted = { dcId, isMedia, reason -> signals.add("$dcId/$isMedia/$reason") },
            torSnowflakeReadyProvider = { false },
            torSnowflakeWaitUntilReady = { false },
            config = baseConfig().copy(
                routeMode = NetworkRouteMode.AUTO,
                networkStatus = "mobile",
                cfproxyEnabled = false,
                torSnowflakeFallbackEnabled = true,
            ),
        )

        proxy.start()
        val client = FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes())
        server.enqueue(client)
        waitUntil("cold Tor client closes") { client.closed }
        proxy.stop()

        assertEquals(1, signals.size)
        assertTrue(signals.first().contains("CF-first ordinary routes exhausted"))
        assertTrue(tor.domains.isEmpty())
        assertEquals(0L, proxy.stats().torSnowflakeAttempts)
        assertEquals(1L, proxy.stats().torSnowflakeUnavailable)
    }

    @Test
    fun serviceReadinessGateCanWaitForWarmTorWithoutTelegramReconnect() {
        val server = FakeTcpServerTransport()
        val tor = RecordingConnector(FakeWebSocketBinaryStream())
        var ready = false
        var waits = 0
        val proxy = newProxy(
            server = server,
            torSnowflakeConnector = tor,
            onOrdinaryRoutesExhausted = { _, _, _ -> },
            torSnowflakeReadyProvider = { ready },
            torSnowflakeWaitUntilReady = {
                waits += 1
                ready = true
                true
            },
            config = baseConfig().copy(
                routeMode = NetworkRouteMode.AUTO,
                networkStatus = "mobile",
                cfproxyEnabled = false,
                torSnowflakeFallbackEnabled = true,
            ),
        )

        proxy.start()
        server.enqueue(FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes()))
        waitUntil("warming wait should continue same client through Tor") { proxy.stats().lastRouteUsed == TOR_SNOWFLAKE_ROUTE_TYPE }
        proxy.stop()

        assertEquals(1, waits)
        assertEquals(1L, proxy.stats().torSnowflakeAttempts)
        assertEquals(1L, proxy.stats().torSnowflakeSuccesses)
        assertEquals(0L, proxy.stats().torSnowflakeUnavailable)
    }

    @Test
    fun protoTagsMapToExpectedSplitterProtoInts() {
        assertEquals(MsgSplitter.PROTO_ABRIDGED_INT, ProxyServer.protoIntForProtoTag(RelayInit.PROTO_TAG_ABRIDGED))
        assertEquals(MsgSplitter.PROTO_INTERMEDIATE_INT, ProxyServer.protoIntForProtoTag(RelayInit.PROTO_TAG_INTERMEDIATE))
        assertEquals(MsgSplitter.PROTO_PADDED_INTERMEDIATE_INT, ProxyServer.protoIntForProtoTag(RelayInit.PROTO_TAG_SECURE))
    }


    private fun addClientExperienceEvents(proxy: ProxyServer, fieldName: String, count: Int) {
        val field = ProxyServer::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val deque = field.get(proxy) as java.util.concurrent.ConcurrentLinkedDeque<Long>
        repeat(count) { deque.addLast(System.currentTimeMillis()) }
    }

    private fun invokeClientExperienceDowngrade(proxy: ProxyServer) {
        val method = ProxyServer::class.java.getDeclaredMethod("maybeDowngradeDirectRouteForClientExperience", java.lang.Long.TYPE)
        method.isAccessible = true
        method.invoke(proxy, System.currentTimeMillis())
    }

    private fun newProxy(
        server: FakeTcpServerTransport,
        connector: RawWebSocketConnector = RecordingConnector(FakeWebSocketBinaryStream()),
        runner: ProxyBridgeRunner = ProxyBridgeRunner { _, _, _, _, _ -> },
        config: ProxyServerConfig = baseConfig(),
        logger: ProxyLogger = ProxyLogger {},
        cfDomainHealth: CfDomainHealth = CfDomainHealth(config.cfProxyDomains),
        torSnowflakeConnector: RawWebSocketConnector? = null,
        onOrdinaryRoutesExhausted: ((Int, Boolean, String) -> Unit)? = null,
        torSnowflakeReadyProvider: (() -> Boolean)? = null,
        torSnowflakeWaitUntilReady: ((Long) -> Boolean)? = null,
    ): ProxyServer =
        ProxyServer(
            config = config,
            serverTransport = server,
            webSocketConnector = connector,
            torSnowflakeConnector = torSnowflakeConnector,
            onOrdinaryRoutesExhausted = onOrdinaryRoutesExhausted,
            torSnowflakeReadyProvider = torSnowflakeReadyProvider,
            torSnowflakeWaitUntilReady = torSnowflakeWaitUntilReady,
            bridgeRunner = runner,
            cfDomainHealth = cfDomainHealth,
            randomBytes = DeterministicRandomBytes,
            logger = logger,
        )

    private fun baseConfig(): ProxyServerConfig =
        ProxyServerConfig(
            host = "127.0.0.1",
            port = 1443,
            secretHex = "0123456789abcdeffedcba9876543210",
            dcRedirects = mapOf(2 to "203.0.113.2", 4 to "203.0.113.4"),
            bufferSizeBytes = 4096,
            poolSize = 0,
            cfproxyEnabled = true,
            routeMode = NetworkRouteMode.DIRECT_FIRST,
            networkStatus = "Wi-Fi",
        )

    private fun handshakeVector(name: String): JSONObject {
        val stream = javaClass.classLoader!!.getResourceAsStream("handshake_vectors.json")
        val vectors = JSONObject(stream.reader(Charsets.UTF_8).readText()).getJSONArray("vectors")
        for (index in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(index)
            if (vector.getString("name") == name) return vector
        }
        throw AssertionError("Missing handshake vector $name")
    }

    private fun relayInitPlainTail(relayInit: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(relayInit.copyOfRange(MtprotoHandshake.SKIP_LEN, MtprotoHandshake.SKIP_LEN + 32), "AES"),
            IvParameterSpec(relayInit.copyOfRange(MtprotoHandshake.SKIP_LEN + 32, MtprotoHandshake.SKIP_LEN + 32 + 16)),
        )
        return cipher.doFinal(relayInit).copyOfRange(MtprotoHandshake.PROTO_TAG_POS, MtprotoHandshake.HANDSHAKE_LEN)
    }


    private fun buildClientHandshake(
        dcIdx: Int,
        protoTag: ByteArray,
        secret: ByteArray = "0123456789abcdeffedcba9876543210".hexToBytes(),
    ): ByteArray {
        val handshake = ByteArray(MtprotoHandshake.HANDSHAKE_LEN) { index -> (index * 17 + 3).toByte() }
        handshake[0] = 0x11
        val decPrekey = handshake.copyOfRange(MtprotoHandshake.SKIP_LEN, MtprotoHandshake.SKIP_LEN + MtprotoHandshake.PREKEY_LEN)
        val decIv = handshake.copyOfRange(
            MtprotoHandshake.SKIP_LEN + MtprotoHandshake.PREKEY_LEN,
            MtprotoHandshake.SKIP_LEN + MtprotoHandshake.PREKEY_LEN + MtprotoHandshake.IV_LEN,
        )
        val decKey = MessageDigest.getInstance("SHA-256").digest(decPrekey + secret)
        val stream = aesCtr(decKey, decIv, handshake).mapIndexed { index, byte ->
            (byte.toInt() xor handshake[index].toInt()).toByte()
        }.toByteArray()
        val tailPlain = protoTag + byteArrayOf((dcIdx and 0xff).toByte(), ((dcIdx shr 8) and 0xff).toByte(), 0x55, 0x66)
        for (offset in tailPlain.indices) {
            handshake[MtprotoHandshake.PROTO_TAG_POS + offset] =
                (tailPlain[offset].toInt() xor stream[MtprotoHandshake.PROTO_TAG_POS + offset].toInt()).toByte()
        }
        return handshake
    }

    private fun aesCtr(key: ByteArray, iv: ByteArray, input: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(input)
    }

    private fun invalidHandshakeStormStatsMessage(proxy: ProxyServer): String {
        val stats = proxy.stats()
        val experience = stats.clientExperience
        return "invalid handshake storm diagnostics; last stats snapshot: " +
            "connectionsBad=${stats.connectionsBad}, " +
            "recentInvalidHandshakeCount=${stats.recentInvalidHandshakeCount}, " +
            "badHandshakeStormRecent=${stats.badHandshakeStormRecent}, " +
            "badHandshakeStormCumulative=${stats.badHandshakeStormCumulative}, " +
            "clientExperience.recentAcceptedHandshakes=${experience.recentAcceptedHandshakes}, " +
            "clientExperienceActiveSessions=${proxy.clientExperienceActiveSessionsForTest()}, " +
            "clientExperience.likelyReconnectBurst=${experience.likelyReconnectBurst}, " +
            "clientExperience.likelyTelegramDisabledProxy=${experience.likelyTelegramDisabledProxy}"
    }

    private fun ProxyServer.clientExperienceActiveSessionsForTest(): Int {
        val field = ProxyServer::class.java.getDeclaredField("clientExperienceActiveSessions")
        field.isAccessible = true
        val counter = field.get(this) as java.util.concurrent.atomic.AtomicInteger
        return counter.get()
    }

    private fun ProxyServer.directRouteHealthForTest(): DirectRouteHealth {
        val field = ProxyServer::class.java.getDeclaredField("directRouteHealth")
        field.isAccessible = true
        return field.get(this) as DirectRouteHealth
    }

    private fun waitUntil(message: String = "condition", predicate: () -> Boolean) {
        waitUntil({ message }, predicate)
    }

    private fun waitUntil(message: () -> String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (predicate()) return
            Thread.sleep(10)
        }
        throw AssertionError("Timed out waiting for ${message()}")
    }

    private fun transientNetworkRecoveryDiagnostics(
        generationAtNone: Long,
        stats: ProxyServerStats,
        events: List<String>,
        connectorDomains: List<String>,
        logs: List<String>,
    ): String =
        "generationAtNone=$generationAtNone, currentGeneration=${stats.networkGeneration}, " +
            "settlingWaits=${stats.networkSettlingWaits}, settlingWaitMs=${stats.networkSettlingWaitMs}, " +
            "resumedAfterAvailable=${stats.networkSettlingResumedAfterAvailable}, " +
            "staleAttemptsIgnored=${stats.networkSettlingStaleAttemptsIgnored}, " +
            "controlledFailures=${stats.networkSettlingControlledFailures}, " +
            "settlingUntilMs=${stats.networkSettlingUntilMs}, " +
            "lastNetworkLostAtMs=${stats.lastNetworkLostAtMs}, " +
            "lastNetworkAvailableAtMs=${stats.lastNetworkAvailableAtMs}, " +
            "effectiveRoute=${stats.effectiveRouteMode}, lastRouteUsed=${stats.lastRouteUsed}, " +
            "events=$events, connectorDomains=$connectorDomains, logs=$logs"

    private object DeterministicRandomBytes : RelayInit.RandomBytes {
        override fun nextBytes(length: Int): ByteArray = ByteArray(length) { index -> (index + 1).toByte() }
    }

    private class FakeTcpServerTransport : TcpServerTransport {
        private val queue = LinkedBlockingQueue<TcpClientTransport>()

        @Volatile var closed: Boolean = false
        var boundHost: String? = null
        var boundPort: Int? = null

        override fun bind(
            host: String,
            port: Int,
        ) {
            boundHost = host
            boundPort = port
        }

        override fun accept(): TcpClientTransport? = queue.poll(50, TimeUnit.MILLISECONDS)

        override fun close() {
            closed = true
        }

        fun enqueue(client: TcpClientTransport) {
            queue.put(client)
        }
    }

    private class FakeTcpClientTransport(
        private val input: ByteArray,
    ) : TcpClientTransport {
        private var offset = 0
        val writes = mutableListOf<ByteArray>()

        @Volatile var closed: Boolean = false
        override val remoteLabel: String = "fake-client"

        override fun readExact(byteCount: Int): ByteArray? {
            if (offset + byteCount > input.size) return null
            return input.copyOfRange(offset, offset + byteCount).also { offset += byteCount }
        }

        override fun read(bufferSize: Int): ByteArray? = null

        override fun write(data: ByteArray) {
            writes.add(data)
        }

        override fun close() {
            closed = true
        }
    }

    private class FakeWebSocketBinaryStream(
        private val events: MutableList<String> = CopyOnWriteArrayList(),
        private val sendError: Throwable? = null,
    ) : WebSocketBinaryStream {
        val sent = mutableListOf<ByteArray>()
        var closed = false

        override fun send(data: ByteArray) {
            events.add("send")
            sendError?.let { throw it }
            sent.add(data)
        }

        override fun sendBatch(parts: List<ByteArray>) {
            events.add("batch")
            sent.addAll(parts)
        }

            override fun recv(): ByteArray? = null

        override fun close() {
            events.add("close")
            closed = true
        }
    }

    private class FailingThenRecordingConnector(
        private val webSocket: FakeWebSocketBinaryStream,
        private val failCount: Int,
    ) : RawWebSocketConnector {
        val domains = mutableListOf<String>()
        private var attempts = 0

        override fun connect(
            targetHost: String,
            domain: String,
            path: String,
            timeoutMs: Int,
        ): WebSocketBinaryStream {
            attempts += 1
            domains.add(domain)
            if (attempts <= failCount) throw IOException("planned failure $attempts")
            return webSocket
        }
    }


    private class FailingDirectThenCfConnector(
        private val webSocket: FakeWebSocketBinaryStream,
    ) : RawWebSocketConnector {
        val targetHosts = mutableListOf<String>()
        val domains = mutableListOf<String>()

        override fun connect(
            targetHost: String,
            domain: String,
            path: String,
            timeoutMs: Int,
        ): WebSocketBinaryStream {
            targetHosts.add(targetHost)
            domains.add(domain)
            if (domain.endsWith(".web.telegram.org")) throw IOException("direct down for $domain")
            return webSocket
        }
    }

    private class DirectTimeoutThenCfConnector(
        private val webSocket: FakeWebSocketBinaryStream,
    ) : RawWebSocketConnector {
        val domains = CopyOnWriteArrayList<String>()

        override fun connect(
            targetHost: String,
            domain: String,
            path: String,
            timeoutMs: Int,
        ): WebSocketBinaryStream {
            domains.add(domain)
            if (domain.endsWith(".web.telegram.org")) throw SocketTimeoutException("connect timed out")
            return webSocket
        }
    }

    private class MultiSocketRecordingConnector(
        private val fixedSocket: FakeWebSocketBinaryStream? = null,
    ) : RawWebSocketConnector {
        val targetHosts = CopyOnWriteArrayList<String>()
        val domains = CopyOnWriteArrayList<String>()
        val paths = CopyOnWriteArrayList<String>()
        val sockets = CopyOnWriteArrayList<FakeWebSocketBinaryStream>()

        override fun connect(
            targetHost: String,
            domain: String,
            path: String,
            timeoutMs: Int,
        ): WebSocketBinaryStream {
            targetHosts.add(targetHost)
            domains.add(domain)
            paths.add(path)
            return fixedSocket ?: FakeWebSocketBinaryStream().also { sockets.add(it) }
        }
    }

    private data class ConnectTuple(
        val targetHost: String,
        val domain: String,
        val path: String,
        val timeoutMs: Int,
    )

    private class TupleRecordingConnector : RawWebSocketConnector {
        val tuples = CopyOnWriteArrayList<ConnectTuple>()

        override fun connect(
            targetHost: String,
            domain: String,
            path: String,
            timeoutMs: Int,
        ): WebSocketBinaryStream {
            tuples.add(ConnectTuple(targetHost, domain, path, timeoutMs))
            return FakeWebSocketBinaryStream()
        }
    }

    private class SequencedConnector(
        vararg webSockets: FakeWebSocketBinaryStream,
    ) : RawWebSocketConnector {
        val tuples = CopyOnWriteArrayList<ConnectTuple>()
        val sockets = webSockets.toList()
        private val index = AtomicInteger(0)

        override fun connect(
            targetHost: String,
            domain: String,
            path: String,
            timeoutMs: Int,
        ): WebSocketBinaryStream {
            tuples.add(ConnectTuple(targetHost, domain, path, timeoutMs))
            return sockets.getOrElse(index.getAndIncrement()) { FakeWebSocketBinaryStream() }
        }
    }


    private class TimeoutRecordingConnector : RawWebSocketConnector {
        val domains = mutableListOf<String>()
        val timeouts = mutableListOf<Int>()

        override fun connect(
            targetHost: String,
            domain: String,
            path: String,
            timeoutMs: Int,
        ): WebSocketBinaryStream {
            domains.add(domain)
            timeouts.add(timeoutMs)
            throw java.net.SocketTimeoutException("timeout after $timeoutMs")
        }
    }

    private class RecordingConnector(
        private val webSocket: FakeWebSocketBinaryStream,
    ) : RawWebSocketConnector {
        val targetHosts = CopyOnWriteArrayList<String>()
        val domains = CopyOnWriteArrayList<String>()
        val paths = CopyOnWriteArrayList<String>()

        override fun connect(
            targetHost: String,
            domain: String,
            path: String,
            timeoutMs: Int,
        ): WebSocketBinaryStream {
            targetHosts.add(targetHost)
            domains.add(domain)
            paths.add(path)
            return webSocket
        }
    }
}
