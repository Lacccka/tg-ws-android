plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun gitCommitSha(): String = runCatching {
    val process = ProcessBuilder("git", "rev-parse", "--short=12", "HEAD")
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    if (process.waitFor() == 0 && output.isNotBlank()) output else "unknown"
}.getOrElse { "unknown" }

android {
    namespace = "com.flowseal.tgwsandroid"
    // Keep the product toolchain unchanged. tor-android 0.4.8.19 is the latest
    // release we verified from source as compileSdk/targetSdk 35 before the
    // newer Guardian Project publication moved to the API-37 toolchain.
    compileSdk = 35

    defaultConfig {
        applicationId = "com.flowseal.tgwsandroid"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        manifestPlaceholders["proxyForegroundServiceType"] = "dataSync"
        buildConfigField("String", "DECLARED_FOREGROUND_SERVICE_STRATEGY", "\"dataSync\"")
        buildConfigField("String", "GIT_COMMIT_SHA", "\"${gitCommitSha()}\"")
        buildConfigField("String", "BUILD_FLAVOR_NAME", "\"none\"")
        buildConfigField("String", "TELEMETRY_ENDPOINT", "\"https://d5dqfsreu76tk91ifakc.xxg4zr82.apigw.yandexcloud.net/telemetry\"")
        buildConfigField("String", "TELEMETRY_TOKEN", "\"${providers.gradleProperty("TELEMETRY_TOKEN").orNull ?: ""}\"")
        buildConfigField("Boolean", "ENABLE_TEST_TELEMETRY_BUTTON", "false")
        buildConfigField("Boolean", "LIBXRAY_AAR_PACKAGED", "false")
        buildConfigField("Boolean", "SNOWFLAKE_TOR_PACKAGED", "false")
    }

    buildTypes {
        getByName("debug") {
            manifestPlaceholders["proxyForegroundServiceType"] = "dataSync"
            buildConfigField("String", "DECLARED_FOREGROUND_SERVICE_STRATEGY", "\"dataSync\"")
            buildConfigField("Boolean", "ENABLE_TEST_TELEMETRY_BUTTON", "false")
            buildConfigField("Boolean", "LIBXRAY_AAR_PACKAGED", "false")
            buildConfigField("Boolean", "SNOWFLAKE_TOR_PACKAGED", "false")
        }

        getByName("release") {
            manifestPlaceholders["proxyForegroundServiceType"] = "dataSync"
            buildConfigField("String", "DECLARED_FOREROUND_SERVICE_STRATEGY", "\"dataSync\"")
            buildConfigField("Boolean", "ENABLE_TEST_TELEMETRY_BUTTON", "false")
            buildConfigField("Boolean", "LIBXRAY_AAR_PACKAGED", "false")
            buildConfigField("Boolean", "SNOWFLAKE_TOR_PACKAGED", "false")
        }

        create("sideload") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("debug")
            manifestPlaceholders["proxyForegroundServiceType"] = "specialUse"
            buildConfigField("String", "DECLARED_FOREGROUND_SERVICE_STRATEGY", "\"specialUse\"")
            buildConfigField("Boolean", "ENABLE_TEST_TELEMETRY_BUTTON", "false")
            buildConfigField("Boolean", "LIBXRAY_AAR_PACKAGED", "false")
            buildConfigField("Boolean", "SNOWFLAKE_TOR_PACKAGED", "false")
        }

        create("privateSideload") {
            initWith(getByName("sideload"))
            matchingFallbacks += listOf("sideload", "debug")
            manifestPlaceholders["proxyForegroundServiceType"] = "specialUse"
            buildConfigField("String", "DECLARED_FOREGROUND_SERVICE_STRATEGY", "\"specialUse\"")
            buildConfigField("String", "BUILD_FLAVOR_NAME", "\"privateSideload\"")
            buildConfigField("Boolean", "ENABLE_TEST_TELEMETRY_BUTTON", "true")
            buildConfigField("Boolean", "LIBXRAY_AAR_PACKAGED", "false")
            buildConfigField("Boolean", "SNOWFLAKE_TOR_PACKAGED", "true")

            // The private diagnostic APK targets the physical ARM64 test phone.
            // Restricting ABI keeps native Cronet, tor-android and IPtProxy from
            // multiplying APK size with unused emulator/32-bit binaries.
            ndk {
                abiFilters.clear()
                abiFilters += "arm64-v8a"
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.android.material:material:1.12.0")

    // Private diagnostic only: native Chromium remains available for the closed
    // Cloudflare control, but is not included in normal builds.
    add("privateSideloadImplementation", "org.chromium.net:cronet-bundled:500.0.1")

    // Zero-config censorship-circumvention PoC. tor-android 0.4.8.19 is pinned
    // to the latest source-verified API-35 publication. It exposes the same
    // TorService/SOCKS control surface while keeping the app toolchain unchanged.
    // Snowflake itself stays current through IPtProxy 5.5.1 (Snowflake 2.14.1).
    add("privateSideloadImplementation", "info.guardianproject:tor-android:0.4.8.19")
    add("privateSideloadImplementation", "info.guardianproject:jtorctl:0.4.5.7")
    add("privateSideloadImplementation", "com.netzarchitekten:IPtProxy:5.5.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
