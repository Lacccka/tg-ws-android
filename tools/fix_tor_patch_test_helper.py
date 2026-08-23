from pathlib import Path

path = Path("app/src/test/java/com/flowseal/tgwsandroid/proxy/ProxyServerTest.kt")
text = path.read_text()
old = '''        connector: RawWebSocketConnector = RecordingConnector(FakeWebSocketBinaryStream()),
        torSnowflakeConnector: RawWebSocketConnector? = null,
        runner: ProxyBridgeRunner = ProxyBridgeRunner { _, _, _, _, _ -> },
        config: ProxyServerConfig = baseConfig(),
        logger: ProxyLogger = ProxyLogger {},
        cfDomainHealth: CfDomainHealth = CfDomainHealth(config.cfProxyDomains),
'''
new = '''        connector: RawWebSocketConnector = RecordingConnector(FakeWebSocketBinaryStream()),
        runner: ProxyBridgeRunner = ProxyBridgeRunner { _, _, _, _, _ -> },
        config: ProxyServerConfig = baseConfig(),
        logger: ProxyLogger = ProxyLogger {},
        cfDomainHealth: CfDomainHealth = CfDomainHealth(config.cfProxyDomains),
        torSnowflakeConnector: RawWebSocketConnector? = null,
'''
if text.count(old) != 1:
    raise SystemExit(f"test helper compatibility: expected one match, got {text.count(old)}")
path.write_text(text.replace(old, new, 1))
