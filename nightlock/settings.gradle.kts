pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "NightLock"

include(":app")

// `core` is its own build so the curfew and emergency rules can be compiled and tested
// without an Android SDK (`gradle -p core test`). Gradle substitutes the
// com.teamx.nightlock:core dependency in :app with this build automatically.
includeBuild("core")
