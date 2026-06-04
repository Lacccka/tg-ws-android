# tg-ws-android

Android/Kotlin porting workspace for `tg-ws-proxy` logic.

## Windows setup and checks

The repository includes the Gradle Wrapper so Windows contributors do not need a
system Gradle installation. Keep Android SDK paths in `local.properties`; this
file is ignored and must not be committed.

From PowerShell or Command Prompt, run the parity maintenance scripts and unit
tests with:

```bat
python tools/check_upstream.py
python tools/generate_handshake_vectors.py
.\gradlew.bat testDebugUnitTest
```

`gradlew.bat` uses the checked-in wrapper configuration under `gradle/wrapper/`.
The wrapper currently downloads Gradle 8.9, which is compatible with the Android
Gradle Plugin declared in `build.gradle.kts`.

## First Android smoke test

This milestone runs the already-ported `ProxyServer` from a minimal Android
`ForegroundService` and native `MainActivity` UI. It uses a fixed debug runtime
configuration for the first device smoke test:

- Local endpoint: `127.0.0.1:1443`
- Core secret: `4014e15dd34e4b05c42413eab68c3da8`
- Telegram MTProto secret: `dd4014e15dd34e4b05c42413eab68c3da8`
- Android 15 edge-to-edge safe areas: the native UI applies system bar and
  display cutout insets so the title, buttons, and logs are not hidden behind
  the status or navigation bars.
- DC redirects: `2:149.154.167.220`, `3:149.154.167.220`, `4:149.154.167.220`
- Android currently uses this minimal direct WebSocket redirect mapping instead
  of the full upstream `DC_DEFAULT_IPS` list. DC3 is included because Telegram
  may select DC3 on mobile networks; direct WebSocket attempts to other
  official DC IPs may timeout and are intentionally not enabled yet.
- Buffer: `256 KiB`
- Pool size setting: `4`; direct WebSocket pool/warmup is enabled for configured DC redirects and both media modes.
- Cloudflare-proxy setting: enabled in config; bundled CF fallback remains available when direct routes fail.

Smoke-test steps:

1. Build the debug APK with `./gradlew assembleDebug` on macOS/Linux or
   `.\gradlew.bat assembleDebug` on Windows.
2. Install `app/build/outputs/apk/debug/app-debug.apk` on an Android device or
   emulator.
3. Open **TG WS Android**.
4. Tap **Start proxy** and grant notification permission on Android 13+ if
   prompted.
5. Tap **Connect in Telegram** to open the Telegram proxy deep link on the same
   Android device where this proxy app is running. If Telegram cannot handle the
   direct `tg://` link, the app falls back to the `https://t.me/proxy` link.
6. If needed, manually add an MTProto proxy in Telegram on that same Android
   device with:
   - Server: `127.0.0.1`
   - Port: `1443`
   - Secret: `dd4014e15dd34e4b05c42413eab68c3da8`
7. Tap **Stop proxy** in the app or the foreground notification when finished.

Current runtime limitations:

- Bundled CF-proxy fallback is now supported when a direct WebSocket route fails
  or a selected DC has no direct redirect configured. Remote CF domain refresh
  is not implemented yet; only bundled/default or configured domains are used.
- CF worker fallback is not implemented yet.
- Direct WebSocket pool/warmup is implemented for the fixed DC2/DC3/DC4 direct
  redirects and is expected to reduce cold-connect latency on Wi-Fi. Mobile
  networks may still timeout direct WebSocket routes; CF fallback handles those
  failures when enabled.
- No fake TLS yet.
- No autostart yet.
- No settings editor or editable DC mapping UI yet. Direct runtime DC mapping
  remains minimal: DC2/DC3/DC4 -> `149.154.167.220`; missing or failing direct
  DCs can use CF fallback when enabled.
- The UI is intentionally minimal: start, stop, Connect in Telegram, status,
  fixed config summary, compact battery/network diagnostics, proxy stats, and
  recent in-memory service logs.
- Runtime logs are kept in a bounded in-memory diagnostics buffer. Use
  **Clear logs** before reproducing a bug, then use **Copy logs** or
  **Share logs** after reproducing it and paste the text into ChatGPT or an
  issue. Screenshots are no longer required for normal log sharing. The copied
  report includes status, endpoint, partial secret, DC summary, battery
  optimization status, network status, stats, and recent log lines.
- Android and MIUI/Xiaomi battery or background-network restrictions may stop or
  throttle long-running local proxy services after several minutes. Keep the
  foreground notification visible while the proxy runs and allow unrestricted
  battery/background activity for this app where the device exposes that option
  (MIUI may call it **No restrictions**). The **Battery settings** button opens
  the platform battery optimization flow or falls back to the system list.
- While the proxy is running, the service registers network callbacks and logs
  available/lost/capability changes without automatically restarting the proxy.
  A lightweight watchdog emits a compact stats/battery/network line about every
  45 seconds.
- The app holds a non-reference-counted partial WakeLock only while the proxy is
  running, and releases it on proxy stop or service destruction. This helps CPU
  continuity but does not keep the screen on and does not bypass OEM background
  policy.
- The local endpoint uses `127.0.0.1`, which works only inside the same Android
  device where the proxy app is running; links or settings using this server are
  not useful when shared with other devices.
