# Upstream Porting Map

This project keeps `third_party/tg-ws-proxy` as a read-only reference to the
original Python implementation. Android/Kotlin files should be ported in small,
reviewable slices that preserve a direct relationship to the upstream file and
function names below.

## Critical upstream modules

| Upstream Python reference | Critical functions/classes | Android/Kotlin target | Porting status |
| --- | --- | --- | --- |
| `proxy/config.py` | `ProxyConfig`, `coerce_domain_list`, `parse_dc_ip_list`, CF-proxy domain normalization and refresh helpers | `app/src/main/java/com/flowseal/tgwsandroid/config/AppConfig.kt`, future `proxy/ProxyConfig.kt`, `proxy/CfProxyDomains.kt` | `AppConfig` model started; proxy runtime mapping pending. |
| `utils/default_config.py` | `default_tray_config` | `app/src/main/java/com/flowseal/tgwsandroid/config/AppConfig.kt`, future Android preferences/default config repository | `AppConfig` defaults started. |
| `utils/tray_common.py` | `apply_proxy_config`, config-to-runtime coercion | future `app/src/main/java/com/flowseal/tgwsandroid/config/ConfigRepository.kt` and `proxy/ProxyRuntimeConfig.kt` | Pending. |
| `proxy/tg_ws_proxy.py` | `_try_handshake`, `_generate_relay_init`, `_read_client_init`, `_build_crypto_ctx`, `_handle_client`, `_run`, `run_proxy` | `app/src/main/java/com/flowseal/tgwsandroid/proxy/MtprotoHandshake.kt`, `app/src/main/java/com/flowseal/tgwsandroid/proxy/RelayInit.kt`, `app/src/main/java/com/flowseal/tgwsandroid/proxy/CryptoContext.kt`, future `proxy/TgWsProxy.kt`, Android service entrypoint | `_try_handshake`, `_generate_relay_init`, and `_build_crypto_ctx` ported and covered by generated parity vectors; networking code pending. |
| `proxy/bridge.py` | `CryptoCtx`, `MsgSplitter`, fallback functions, TCP/WebSocket re-encryption bridge | `app/src/main/java/com/flowseal/tgwsandroid/proxy/CryptoContext.kt`, `app/src/main/java/com/flowseal/tgwsandroid/proxy/MsgSplitter.kt`, future `proxy/bridge/Bridge.kt`, `proxy/fallback/*` | `CryptoCtx` Kotlin equivalent and `MsgSplitter` ported and parity-tested; bridge and fallback networking pending. |
| `proxy/raw_websocket.py` | `RawWebSocket`, `_xor_mask`, socket option setup, handshake errors | `app/src/main/java/com/flowseal/tgwsandroid/proxy/RawWebSocketCodec.kt`, `app/src/main/java/com/flowseal/tgwsandroid/proxy/RawWebSocket.kt` | Offline frame codec, masking, HTTP Upgrade request/response parsing, and the live RawWebSocket TLS connection/send/recv/close layer are ported and unit-tested with fake transports. Real Telegram/Cloudflare integration, TCP server integration, connection pool, and bridge usage are not tested or ported yet. |
| `proxy/fake_tls.py` | `verify_client_hello`, `build_server_hello`, `wrap_tls_record`, `FakeTlsStream`, masking-domain proxy | future `proxy/tls/FakeTls.kt`, `proxy/tls/FakeTlsStream.kt` | Pending. |
| `proxy/utils.py` | `ws_domains`, `human_bytes`, `get_link_host`, GitHub opener helper | future `proxy/TelegramDcDomains.kt`, `util/Format.kt`, `util/LinkHost.kt` | Pending. |
| `proxy/pool.py` | `_WsPool`, `_CfWorkerPool` | future `proxy/pool/WebSocketPool.kt`, `proxy/pool/CfWorkerPool.kt` | Pending. |
| `proxy/balancer.py` | `_Balancer` | future `proxy/balancer/Balancer.kt` | Pending. |

## Test-vector expectations

When a row above moves from pending to ported, add deterministic vectors produced
from the checked-out Python submodule. The Android test should load the vector,
run the Kotlin implementation, and assert byte-for-byte or field-for-field
parity. This makes future submodule updates inspectable with
`tools/check_upstream.py` before porting code changes. Crypto vectors in
`app/src/test/resources/crypto_vectors.json` must be regenerated with
`tools/generate_crypto_vectors.py` after relevant upstream relay-init or crypto
context changes. Splitter vectors in
`app/src/test/resources/splitter_vectors.json` must be regenerated with
`tools/generate_splitter_vectors.py` after relevant upstream `bridge.MsgSplitter`
or transport-constant changes. WebSocket vectors in
`app/src/test/resources/websocket_vectors.json` must be regenerated with
`tools/generate_websocket_vectors.py` after relevant upstream
`proxy/raw_websocket.py` frame, mask, HTTP Upgrade request, handshake response
parsing, or live RawWebSocket connection behavior changes.
