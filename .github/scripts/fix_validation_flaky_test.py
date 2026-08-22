from pathlib import Path

path = Path("app/src/test/java/com/flowseal/tgwsandroid/proxy/ProxyServerTest.kt")
text = path.read_text(encoding="utf-8")
old = '        waitUntil({ invalidHandshakeStormStatsMessage(proxy) }) { proxy.stats().recentInvalidHandshakeCount >= 100L }'
new = '''        waitUntil({ invalidHandshakeStormStatsMessage(proxy) }) {
            val stats = proxy.stats()
            stats.connectionsBad == 120L && stats.recentInvalidHandshakeCount >= 100L
        }'''
count = text.count(old)
if count != 1:
    raise SystemExit(f"Expected exactly one flaky-test wait, got {count}")
path.write_text(text.replace(old, new, 1), encoding="utf-8")
print("Updated invalid-handshake storm test to wait for all enqueued bad connections")
