plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

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
    }
}


dependencies {
    testImplementation("junit:junit:4.13.2")
}
