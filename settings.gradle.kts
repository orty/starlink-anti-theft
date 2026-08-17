pluginManagement {
    repositories {
        // Content filters keep Google's Maven out of the resolution path for anything
        // that is not Android-specific. That lets `:core` resolve entirely from Maven
        // Central, so it builds in environments with no access to dl.google.com.
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
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "starlink-anti-theft"

include(":core")

// `-PskipAndroidApp=true` drops the Android module from the build entirely, which allows
// `:core` to be compiled and tested without the Android SDK installed. CI builds without
// the flag and therefore builds both modules.
if (providers.gradleProperty("skipAndroidApp").orNull != "true") {
    include(":app")
}
