package com.flowseal.tgwsandroid.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ForegroundServiceManifestTest {
    @Test
    fun mainManifestIsPlaySafeAndUsesDataSyncPlaceholder() {
        val manifest = readRepoFile("app/src/main/AndroidManifest.xml")

        assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE_DATA_SYNC"))
        assertTrue(manifest.contains("android:foregroundServiceType=\"\${proxyForegroundServiceType}\""))
        assertFalse(manifest.contains("android.permission.FOREGROUND_SERVICE_SPECIAL_USE"))
        assertFalse(manifest.contains("PROPERTY_SPECIAL_USE_FGS_SUBTYPE"))
    }

    @Test
    fun debugManifestDoesNotContainSpecialUsePermissionOrProperty() {
        val manifest = readRepoFile("app/src/debug/AndroidManifest.xml")

        assertFalse(manifest.contains("android.permission.FOREGROUND_SERVICE_SPECIAL_USE"))
        assertFalse(manifest.contains("PROPERTY_SPECIAL_USE_FGS_SUBTYPE"))
        assertFalse(manifest.contains("specialUse"))
    }

    @Test
    fun releaseManifestDoesNotContainSpecialUsePermissionOrProperty() {
        val releaseManifest = File(repoRoot(), "app/src/release/AndroidManifest.xml")
        val manifest = if (releaseManifest.isFile) releaseManifest.readText() else ""

        assertFalse(manifest.contains("android.permission.FOREGROUND_SERVICE_SPECIAL_USE"))
        assertFalse(manifest.contains("PROPERTY_SPECIAL_USE_FGS_SUBTYPE"))
        assertFalse(manifest.contains("specialUse"))
    }

    @Test
    fun sideloadManifestContainsSpecialUsePermissionPropertyAndSubtype() {
        val manifest = readRepoFile("app/src/sideload/AndroidManifest.xml")

        assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE_SPECIAL_USE"))
        assertTrue(manifest.contains("android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"))
        assertTrue(manifest.contains(SPECIAL_USE_SUBTYPE_DESCRIPTION))
    }

    @Test
    fun gradleBuildTypesDeclareExpectedForegroundServiceStrategies() {
        val gradle = readRepoFile("app/build.gradle.kts")
        val debug = gradle.blockFrom("getByName(\"debug\")")
        val release = gradle.blockFrom("getByName(\"release\")")
        val sideload = gradle.blockFrom("create(\"sideload\")")

        assertTrue(debug.contains("manifestPlaceholders[\"proxyForegroundServiceType\"] = \"dataSync\""))
        assertTrue(debug.contains("buildConfigField(\"String\", \"DECLARED_FOREGROUND_SERVICE_STRATEGY\", \"\\\"dataSync\\\"\")"))
        assertFalse(debug.contains("specialUse"))
        assertTrue(release.contains("manifestPlaceholders[\"proxyForegroundServiceType\"] = \"dataSync\""))
        assertTrue(release.contains("buildConfigField(\"String\", \"DECLARED_FOREGROUND_SERVICE_STRATEGY\", \"\\\"dataSync\\\"\")"))
        assertFalse(release.contains("specialUse"))
        assertTrue(sideload.contains("manifestPlaceholders[\"proxyForegroundServiceType\"] = \"specialUse\""))
        assertTrue(sideload.contains("buildConfigField(\"String\", \"DECLARED_FOREGROUND_SERVICE_STRATEGY\", \"\\\"specialUse\\\"\")"))
    }

    @Test
    fun diagnosticsReportUsesBuildTypeSafeFlavorFieldForSideloadBuildType() {
        val gradle = readRepoFile("app/build.gradle.kts")
        val service = readRepoFile("app/src/main/java/com/flowseal/tgwsandroid/service/ProxyForegroundService.kt")

        assertTrue(gradle.contains("buildConfigField(\"String\", \"BUILD_FLAVOR_NAME\", \"\\\"none\\\"\")"))
        assertTrue(service.contains("buildType = BuildConfig.BUILD_TYPE"))
        assertTrue(service.contains("flavor = BuildConfig.BUILD_FLAVOR_NAME"))
        assertFalse(service.contains("BuildConfig.FLAVOR"))
    }

    private fun readRepoFile(relativePath: String): String = File(repoRoot(), relativePath).readText()

    private fun String.blockFrom(anchor: String): String {
        val start = indexOf(anchor)
        require(start >= 0) { "Missing Gradle block anchor: $anchor" }
        val nextBlock = listOf(
            indexOf("getByName(\"release\")", start + anchor.length),
            indexOf("create(\"sideload\")", start + anchor.length),
            indexOf("buildFeatures", start + anchor.length),
        ).filter { it >= 0 }.minOrNull() ?: length
        return substring(start, nextBlock)
    }

    private fun repoRoot(): File {
        val userDir = requireNotNull(System.getProperty("user.dir")) { "Missing user.dir system property" }
        return generateSequence(File(userDir)) { current ->
            current.parentFile
        }.first { candidate ->
            File(candidate, "app/build.gradle.kts").isFile
        }
    }

    companion object {
        private const val SPECIAL_USE_SUBTYPE_DESCRIPTION =
            "Local user-started Telegram proxy that maintains a visible foreground notification and " +
                "relays Telegram traffic through a local loopback proxy while the user keeps the proxy enabled"
    }
}
