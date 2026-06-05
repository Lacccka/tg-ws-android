package com.flowseal.tgwsandroid.proxy

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketException
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
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
            assertTrue(logs.any { it.contains("session ended") && it.contains("reason=exception: SocketException: Broken pipe") })
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
            assertTrue(logs.any { it.contains("session ended") && it.contains("reason=exception: SocketException: Broken pipe") })
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
    fun autoRouteModeResolvesWifiToDirectFirst() {
        val config = baseConfig().copy(routeMode = NetworkRouteMode.AUTO, networkStatus = "Wi-Fi")

        assertEquals(NetworkRouteMode.DIRECT_FIRST, config.effectiveRouteMode)
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

        assertEquals(listOf("kws2.cf.example", "kws2.web.telegram.org", "kws2-1.web.telegram.org"), connector.domains)
        assertEquals(listOf(RawWebSocket.DEFAULT_CONNECT_TIMEOUT_MS, 1_500, 1_500), connector.timeouts)
        assertEquals(2L, proxy.stats().directTimeouts)
        assertEquals(0L, proxy.stats().poolMisses)
    }

    @Test
    fun protoTagsMapToExpectedSplitterProtoInts() {
        assertEquals(MsgSplitter.PROTO_ABRIDGED_INT, ProxyServer.protoIntForProtoTag(RelayInit.PROTO_TAG_ABRIDGED))
        assertEquals(MsgSplitter.PROTO_INTERMEDIATE_INT, ProxyServer.protoIntForProtoTag(RelayInit.PROTO_TAG_INTERMEDIATE))
        assertEquals(MsgSplitter.PROTO_PADDED_INTERMEDIATE_INT, ProxyServer.protoIntForProtoTag(RelayInit.PROTO_TAG_SECURE))
    }

    private fun newProxy(
        server: FakeTcpServerTransport,
        connector: RawWebSocketConnector = RecordingConnector(FakeWebSocketBinaryStream()),
        runner: ProxyBridgeRunner = ProxyBridgeRunner { _, _, _, _, _ -> },
        config: ProxyServerConfig = baseConfig(),
        logger: ProxyLogger = ProxyLogger {},
    ): ProxyServer =
        ProxyServer(
            config = config,
            serverTransport = server,
            webSocketConnector = connector,
            bridgeRunner = runner,
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

    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (predicate()) return
            Thread.sleep(10)
        }
        throw AssertionError("Timed out waiting for condition")
    }

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
        val targetHosts = mutableListOf<String>()
        val domains = mutableListOf<String>()
        val paths = mutableListOf<String>()

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
