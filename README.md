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
