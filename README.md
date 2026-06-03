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
- DC redirects: `2:149.154.167.220`, `4:149.154.167.220`
- Buffer: `256 KiB`
- Pool size setting: `4` (reserved; pooling is not implemented yet)
- Cloudflare-proxy setting: enabled in config (fallback is not implemented yet)

Smoke-test steps:

1. Build the debug APK with `./gradlew assembleDebug` on macOS/Linux or
   `.\gradlew.bat assembleDebug` on Windows.
2. Install `app/build/outputs/apk/debug/app-debug.apk` on an Android device or
   emulator.
3. Open **TG WS Android**.
4. Tap **Start proxy** and grant notification permission on Android 13+ if
   prompted.
5. In Telegram, add an MTProto proxy with:
   - Server: `127.0.0.1`
   - Port: `1443`
   - Secret: `dd4014e15dd34e4b05c42413eab68c3da8`
6. Tap **Stop proxy** in the app or the foreground notification when finished.

Current runtime limitations:

- No cfproxy fallback or refresh yet.
- No ws_pool / connection pooling yet.
- No fake TLS yet.
- No autostart yet.
- No settings editor yet.
- The UI is intentionally minimal: start, stop, status, fixed config summary,
  and recent in-memory service logs only.
