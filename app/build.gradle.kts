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
    }

    buildTypes {
        getByName("debug") {
            manifestPlaceholders["proxyForegroundServiceType"] = "dataSync"
            buildConfigField("String", "DECLARED_FOREGROUND_SERVICE_STRATEGY", "\"dataSync\"")
        }

        getByName("release") {
            manifestPlaceholders["proxyForegroundServiceType"] = "dataSync"
            buildConfigField("String", "DECLARED_FOREGROUND_SERVICE_STRATEGY", "\"dataSync\"")
        }

        create("sideload") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("debug")
            manifestPlaceholders["proxyForegroundServiceType"] = "specialUse"
            buildConfigField("String", "DECLARED_FOREGROUND_SERVICE_STRATEGY", "\"specialUse\"")
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

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
