pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()

        // tor-android/jtorctl are published by Guardian Project in its Git-backed
        // Maven repository. Restrict this repository to Guardian Project's group
        // so unrelated dependencies can never be resolved from it ahead of
        // Maven Central/Google.
        maven {
            url = uri("https://raw.githubusercontent.com/guardianproject/gpmaven/master")
            content {
                includeGroup("info.guardianproject")
            }
        }
    }
}

rootProject.name = "tg-ws-android"
include(":app")
