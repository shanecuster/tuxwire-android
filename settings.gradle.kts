// settings.gradle.kts: tells Gradle which projects make up the build and where
// to download plugins and libraries from.

// Where Gradle looks for *build plugins* (such as the Android Gradle Plugin).
pluginManagement {
    repositories {
        // Google's repository, restricted to the groups it actually hosts so
        // Gradle does not waste time asking it for everything.
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

// Lets Gradle download a matching Java (JDK) automatically if one is needed.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// Where Gradle looks for the app's *libraries*. FAIL_ON_PROJECT_REPOS means
// modules may not add their own repositories, so every download source is
// listed here, in one place.
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// The project's name, and the one module it contains (the `app/` folder).
rootProject.name = "tuxwire"
include(":app")
