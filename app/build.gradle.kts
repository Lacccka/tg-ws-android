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

fun sha256(file: File): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(128 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
}

val privateLibXrayAar = file("libs/libXray.aar")
val pinnedLibXrayTag = "v26.7.28"
val pinnedLibXrayAarSha256 = "4708a361a74f7e955635dbe3661cefb459bdc867423c3b1826a2c5a6ea4ac77d"

android {
    namespace = "com.flowseal.tgwsandroid"
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
    }

    buildTypes {
        getByName("debug") {
            manifestPlaceholders["proxyForegroundServiceType"] = "dataSync"
            buildConfigField("String", "DECLARED_FOREGROUND_SERVICE_STRATEGY", "\"dataSync\"")
            buildConfigField("Boolean", "ENABLE_TEST_TELEMETRY_BUTTON", "false")
            buildConfigField("Boolean", "LIBXRAY_AAR_PACKAGED", "false")
        }

        getByName("release") {
            manifestPlaceholders["proxyForegroundServiceType"] = "dataSync"
            buildConfigField("String", "DECLARED_FOREGROUND_SERVICE_STRATEGY", "\"dataSync\"")
            buildConfigField("Boolean", "ENABLE_TEST_TELEMETRY_BUTTON", "false")
            buildConfigField("Boolean", "LIBXRAY_AAR_PACKAGED", "false")
        }

        create("sideload") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("debug")
            manifestPlaceholders["proxyForegroundServiceType"] = "specialUse"
            buildConfigField("String", "DECLARED_FOREGROUND_SERVICE_STRATEGY", "\"specialUse\"")
            buildConfigField("Boolean", "ENABLE_TEST_TELEMETRY_BUTTON", "false")
            buildConfigField("Boolean", "LIBXRAY_AAR_PACKAGED", "false")
        }

        create("privateSideload") {
            initWith(getByName("sideload"))
            matchingFallbacks += listOf("sideload", "debug")
            manifestPlaceholders["proxyForegroundServiceType"] = "specialUse"
            buildConfigField("String", "DECLARED_FOREGROUND_SERVICE_STRATEGY", "\"specialUse\"")
            buildConfigField("String", "BUILD_FLAVOR_NAME", "\"privateSideload\"")
            buildConfigField("Boolean", "ENABLE_TEST_TELEMETRY_BUTTON", "true")
            buildConfigField("Boolean", "LIBXRAY_AAR_PACKAGED", privateLibXrayAar.exists().toString())

            // This build type is an on-device diagnostic for the current physical
            // ARM64 test phone. Keeping only arm64-v8a prevents the large native
            // Cronet + libXray payload from packaging unused x86/x86_64/32-bit
            // binaries into a single APK. Normal debug/release/sideload builds
            // remain ABI-unrestricted.
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

val verifyPrivateLibXrayAar by tasks.registering {
    group = "verification"
    description = "Verify the pinned official libXray AAR required by privateSideload."

    doLast {
        check(privateLibXrayAar.isFile) {
            "Missing app/libs/libXray.aar for privateSideload. Run .\\tools\\build-libxray.ps1 first (pinned $pinnedLibXrayTag)."
        }
        val actual = sha256(privateLibXrayAar)
        check(actual.equals(pinnedLibXrayAarSha256, ignoreCase = true)) {
            "Unexpected libXray.aar SHA-256 for $pinnedLibXrayTag. Expected $pinnedLibXrayAarSha256, got $actual. Delete app/libs/libXray.aar and rerun .\\tools\\build-libxray.ps1 -Force."
        }
    }
}

tasks.matching { it.name == "prePrivateSideloadBuild" }.configureEach {
    dependsOn(verifyPrivateLibXrayAar)
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.android.material:material:1.12.0")

    // Private diagnostic only: package the native Chromium network stack so the
    // Huawei/mobile-network control does not depend on Google Play Services and
    // does not increase normal debug/release/sideload APKs.
    add("privateSideloadImplementation", "org.chromium.net:cronet-bundled:500.0.1")

    // libXray is deliberately local and pinned by tools/build-libxray.ps1 instead
    // of being fetched implicitly during Gradle configuration. This keeps normal
    // builds reproducible and lets us verify the exact official release AAR before
    // the private APK is assembled.
    if (privateLibXrayAar.exists()) {
        add("privateSideloadImplementation", files(privateLibXrayAar))
    }

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
